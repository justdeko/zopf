package com.dk.zopf.ui.editor

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDiskWatchTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private val onDisk =
        Workflow(
            name = "watched",
            nodes = listOf(WorkflowNode("alpha", NodeType.SHELL, command = "echo one")),
        )

    private fun open(): Triple<EditorState, WorkflowStore, Workspace> {
        val dir = Files.createTempDirectory("zopf-watch").also { tempDirs.add(it) }
        val workspace = Workspace.create(dir.resolve("ws"))
        val store = WorkflowStore(workspace)
        store.save(onDisk)
        val state =
            EditorState(
                initial = store.load("watched")!!,
                workspace = workspace,
                onSave = store::save,
            )
        return Triple(state, store, workspace)
    }

    private fun WorkflowStore.rewrite(command: String) {
        save(onDisk.copy(nodes = listOf(WorkflowNode("alpha", NodeType.SHELL, command = command))))
    }

    @Test
    fun `an outside edit lands straight in the editor when nothing is unsaved`() {
        val (state, store, _) = open()

        store.rewrite("echo from somewhere else")
        state.checkFileOnDisk()

        assertEquals("echo from somewhere else", state.workflow.node("alpha")?.command)
        assertNull(state.changedOnDisk, "a clean editor should not have to ask")
        assertTrue(!state.isDirty, "adopting the file on disk left the editor looking unsaved")
    }

    @Test
    fun `an outside edit is offered rather than applied when there are unsaved changes`() {
        val (state, store, _) = open()
        state.updateNode(state.workflow.node("alpha")!!.copy(command = "echo mine"))

        store.rewrite("echo theirs")
        state.checkFileOnDisk()

        assertNotNull(state.changedOnDisk, "an outside edit went unreported")
        assertEquals("echo mine", state.workflow.node("alpha")?.command, "unsaved work was overwritten")
    }

    @Test
    fun `taking the outside edit throws away the unsaved one`() {
        val (state, store, _) = open()
        state.updateNode(state.workflow.node("alpha")!!.copy(command = "echo mine"))
        store.rewrite("echo theirs")
        state.checkFileOnDisk()

        state.adoptChangeOnDisk()

        assertEquals("echo theirs", state.workflow.node("alpha")?.command)
        assertNull(state.changedOnDisk)
        assertTrue(!state.isDirty)
    }

    @Test
    fun `keeping the unsaved edit stops the asking without touching the file`() {
        val (state, store, _) = open()
        state.updateNode(state.workflow.node("alpha")!!.copy(command = "echo mine"))
        store.rewrite("echo theirs")
        state.checkFileOnDisk()

        state.keepMineOverChangeOnDisk()
        state.checkFileOnDisk()

        assertNull(state.changedOnDisk, "the same outside edit was reported twice")
        assertEquals("echo mine", state.workflow.node("alpha")?.command)
        assertEquals("echo theirs", store.load("watched")?.node("alpha")?.command)
    }

    @Test
    fun `the editor's own save is not mistaken for an outside edit`() {
        val (state, _, _) = open()
        state.updateNode(state.workflow.node("alpha")!!.copy(command = "echo mine"))

        state.save(null)
        state.checkFileOnDisk()

        assertNull(state.changedOnDisk, "saving reported the editor's own write back to it")
        assertEquals("echo mine", state.workflow.node("alpha")?.command)
    }

    @Test
    fun `a file that stops parsing is left alone rather than adopted`() {
        val (state, _, workspace) = open()
        workspace.workflowsDir
            .resolve("watched.yaml")
            .toFile()
            .writeText("nodes: [ this isn't yaml")

        state.checkFileOnDisk()

        assertEquals("echo one", state.workflow.node("alpha")?.command)
        assertNull(state.changedOnDisk)
    }
}
