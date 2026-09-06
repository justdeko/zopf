package com.dk.zopf.ui.editor

import com.dk.zopf.model.ConnectorManifest
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.exampleWorkflow
import com.dk.zopf.store.Connector
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.Workspace
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorStateTest {
    private val placed =
        Workflow(
            name = "w",
            nodes =
                listOf(
                    WorkflowNode("a", NodeType.AGENT, position = Position(10f, 20f)),
                    WorkflowNode("b", NodeType.AGENT, position = Position(30f, 40f)),
                ),
        )

    private fun positioned(onSave: (Workflow) -> Unit = {}) = EditorState(initial = placed, workspace = null, onSave = onSave)

    @Test
    fun `a canvas matching the file is not an unsaved change`() {
        val state = positioned()

        state.reportCanvasPositions(mapOf("a" to Position(10f, 20f), "b" to Position(30f, 40f)))

        assertFalse(state.isDirty, "opening a workflow and looking at it counts as an edit")
    }

    @Test
    fun `dragging a node is an unsaved change and saving writes it`() {
        var written: Workflow? = null
        val state = positioned(onSave = { written = it })

        val dragged = mapOf("a" to Position(99f, 99f), "b" to Position(30f, 40f))
        state.reportCanvasPositions(dragged)

        assertEquals(Position(10f, 20f), state.workflow.node("a")?.position)
        assertTrue(state.isDirty)

        state.save(dragged)

        assertEquals(Position(99f, 99f), written?.node("a")?.position)
        assertFalse(state.isDirty)
    }

    @Test
    fun `laying out again drops the positions`() {
        var written: Workflow? = null
        val state = positioned(onSave = { written = it })

        state.reportCanvasPositions(emptyMap())
        assertTrue(state.isDirty, "throwing the layout away is a change worth saving")

        state.save(emptyMap())

        assertTrue(written!!.nodes.all { it.position == null })
        assertNull(state.workflow.node("a")?.position)
        assertFalse(state.isDirty)
    }

    @Test
    fun `a canvas that has not laid out loses nothing`() {
        val state = positioned()

        state.reportCanvasPositions(null)

        assertFalse(state.isDirty)
    }

    private val calling =
        Workflow(
            name = "w",
            nodes = listOf(WorkflowNode("post", NodeType.CONNECTOR, connector = "slack-post")),
        )

    private fun connector(
        name: String,
        vararg inputs: String,
    ) = Connector(
        manifest =
            ConnectorManifest(
                name = name,
                inputs =
                    inputs.map {
                        com.dk.zopf.model
                            .ConnectorInput(it, required = true)
                    },
            ),
        dir = Paths.get("/tmp/$name"),
        source = "test",
    )

    private fun calling(
        connectors: () -> List<Connector>,
        onRefresh: () -> Unit = {},
    ) = EditorState(
        initial = calling,
        workspace = null,
        connectorsProvider = connectors,
        onRefreshConnectors = onRefresh,
        onSave = {},
    )

    @Test
    fun `a connector added while the editor is open is picked up`() {
        var installed = emptyList<Connector>()
        val state = calling({ installed })

        assertNull(state.connector("slack-post"))
        assertTrue(state.issuesFor("post").any { "isn't a connector" in it.message })

        installed = listOf(connector("slack-post"))

        assertEquals("slack-post", state.connector("slack-post")?.name)
        assertTrue(state.issuesFor("post").none { "isn't a connector" in it.message })

        assertNull(state.connector(""))
    }

    @Test
    fun `a changed manifest is validated against its new version`() {
        var installed = listOf(connector("slack-post"))
        val state = calling({ installed })
        assertTrue(state.issuesFor("post").isEmpty())

        installed = listOf(connector("slack-post", "channel"))

        assertTrue(state.issuesFor("post").any { "needs an input for \"channel\"" in it.message })
    }

    @Test
    fun `a deleted connector stops being found`() {
        var installed = listOf(connector("slack-post"))
        val state = calling({ installed })
        assertEquals("slack-post", state.connector("slack-post")?.name)

        installed = emptyList()

        assertNull(state.connector("slack-post"))
    }

    @Test
    fun `the editor can ask for a re-read`() {
        var reads = 0
        val state = calling({ emptyList() }, onRefresh = { reads++ })

        state.refreshConnectors()

        assertEquals(1, reads)
    }

    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-prompt-files").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun reading(
        promptFileText: String,
        connected: Boolean = true,
    ): EditorState {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        workspace.root.resolve("prompts").createDirectories()
        workspace.root.resolve("prompts/review.md").writeText(promptFileText)
        return editorIn(workspace, connected)
    }

    private fun editorIn(
        workspace: Workspace,
        connected: Boolean = true,
    ): EditorState {
        val workflow =
            Workflow(
                name = "w",
                nodes =
                    listOf(
                        WorkflowNode("build", NodeType.SHELL, command = "true"),
                        WorkflowNode("review", NodeType.AGENT, promptFile = "prompts/review.md"),
                    ),
                edges = if (connected) listOf(WorkflowEdge("build", "review")) else emptyList(),
            )
        return EditorState(
            initial = workflow,
            workspace = workspace,
            connectorsProvider = { emptyList() },
            onRefreshConnectors = {},
            executableExists = { true },
            onSave = {},
        )
    }

    @Test
    fun `a prompt file edited outside is validated as it reads`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        workspace.root.resolve("prompts").createDirectories()
        val prompt = workspace.root.resolve("prompts/review.md")
        prompt.writeText("Summarise \${build.result}")
        val state = editorIn(workspace)
        assertEquals(emptyList(), state.issues)

        prompt.writeText("Summarise \${gone.result}")
        state.checkFileOnDisk()

        assertTrue(state.issues.any { "gone" in it.message }, "still reading the copy it took when it opened")
    }

    @Test
    fun `a reference in a prompt file is validated like any other`() {
        val state = reading("Summarise \${gone.result}")

        val issue = state.issues.single()

        assertEquals("review", issue.nodeId)
        assertTrue("gone" in issue.message)
        assertTrue("prompts/review.md" in issue.message, "the issue has to say where to look")
    }

    @Test
    fun `a prompt file whose references resolve is clean`() {
        assertEquals(emptyList(), reading("Summarise \${build.result}").issues)
    }

    @Test
    fun `renaming a node names the prompt file it could not rewrite`() {
        val state = reading("Summarise \${build.result}")

        state.renameNode("build", "compile")

        assertEquals(
            "compile",
            state.workflow.nodes
                .first()
                .id,
        )
        val message = state.message
        assertTrue(message != null && "prompts/review.md" in message, "said nothing about the file: $message")
        assertTrue("build" in message, "the message has to name the reference that is now stale")

        state.refreshLookups()
        assertTrue(state.issues.any { "build" in it.message })
    }

    @Test
    fun `renaming a node no prompt file mentions reports nothing`() {
        val state = reading("Summarise \${build.result}")

        state.renameNode("review", "read")

        assertNull(state.message)
    }
}

class EditorSourceTest {
    private fun editing(workflow: Workflow) = EditorState(initial = workflow, workspace = null, onSave = {})

    @Test
    fun `reading the source and writing it back changes nothing`() {
        val state = editing(exampleWorkflow)
        val before = state.sourceText

        state.applySource(before).getOrThrow()

        assertEquals(before, state.sourceText)
        assertEquals(exampleWorkflow, state.workflow)
    }

    @Test
    fun `the source carries the canvas positions`() {
        val state = editing(exampleWorkflow)
        val placed = mapOf(exampleWorkflow.nodes.first().id to Position(40f, 90f))
        state.reportCanvasPositions(placed)

        assertTrue(state.sourceText.contains("x: 40"), "the source left out a position the canvas was holding")

        state.applySource(state.sourceText).getOrThrow()

        assertEquals(
            Position(40f, 90f),
            state.workflow.nodes
                .first()
                .position,
        )
    }

    @Test
    fun `an id removed from the source clears the selection`() {
        val state = editing(Workflow(name = "w", nodes = listOf(WorkflowNode("gone", NodeType.GATE))))
        state.select("gone")

        state.applySource("name: w\nnodes:\n  - id: other\n    type: gate\n").getOrThrow()

        assertNull(state.selectedNodeId, "the inspector was left pointing at a node that no longer exists")
    }

    @Test
    fun `text that is not a workflow is refused`() {
        val state = editing(exampleWorkflow)

        val failed = state.applySource("nodes: [ this isn't yaml")

        assertTrue(failed.isFailure)
        assertEquals(exampleWorkflow, state.workflow)
    }
}

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
    fun `an outside edit applies when nothing is unsaved`() {
        val (state, store, _) = open()

        store.rewrite("echo from somewhere else")
        state.checkFileOnDisk()

        assertEquals("echo from somewhere else", state.workflow.node("alpha")?.command)
        assertNull(state.changedOnDisk, "a clean editor should not have to ask")
        assertTrue(!state.isDirty, "adopting the file on disk left the editor looking unsaved")
    }

    @Test
    fun `an outside edit is offered when there are unsaved changes`() {
        val (state, store, _) = open()
        state.updateNode(state.workflow.node("alpha")!!.copy(command = "echo mine"))

        store.rewrite("echo theirs")
        state.checkFileOnDisk()

        assertNotNull(state.changedOnDisk, "an outside edit went unreported")
        assertEquals("echo mine", state.workflow.node("alpha")?.command, "unsaved work was overwritten")
    }

    @Test
    fun `taking the outside edit discards the unsaved one`() {
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
    fun `keeping the unsaved edit leaves the file alone`() {
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
    fun `the editor's own save is not an outside edit`() {
        val (state, _, _) = open()
        state.updateNode(state.workflow.node("alpha")!!.copy(command = "echo mine"))

        state.save(null)
        state.checkFileOnDisk()

        assertNull(state.changedOnDisk, "saving reported the editor's own write back to it")
        assertEquals("echo mine", state.workflow.node("alpha")?.command)
    }

    @Test
    fun `a file that stops parsing is left alone`() {
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
