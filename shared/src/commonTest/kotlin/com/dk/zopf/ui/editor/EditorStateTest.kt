package com.dk.zopf.ui.editor

import com.dk.zopf.model.ConnectorManifest
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.Connector
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
    fun `a canvas showing what the file already said is not an unsaved change`() {
        val state = positioned()

        state.reportCanvasPositions(mapOf("a" to Position(10f, 20f), "b" to Position(30f, 40f)))

        assertFalse(state.isDirty, "opening a workflow and looking at it counts as an edit")
    }

    @Test
    fun `dragging a node is an unsaved change, and saving writes where it was dropped`() {
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
    fun `laying out again drops the positions from the file`() {
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
    fun `a canvas that hasn't laid out yet can't make the file lose anything`() {
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
    fun `a connector that appears while the editor is open is picked up`() {
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
    fun `a manifest that gained a required input is validated against, not against the old one`() {
        var installed = listOf(connector("slack-post"))
        val state = calling({ installed })
        assertTrue(state.issuesFor("post").isEmpty())

        installed = listOf(connector("slack-post", "channel"))

        assertTrue(state.issuesFor("post").any { "needs an input for \"channel\"" in it.message })
    }

    @Test
    fun `a connector deleted underneath the editor stops being found`() {
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
    fun `a reference in a prompt file is validated like one written in the workflow`() {
        val state = reading("Summarise \${gone.result}")

        val issue = state.issues.single()

        assertEquals("review", issue.nodeId)
        assertTrue("gone" in issue.message)
        assertTrue("prompts/review.md" in issue.message, "the issue has to say where to look")
    }

    @Test
    fun `a prompt file whose references all resolve is clean`() {
        assertEquals(emptyList(), reading("Summarise \${build.result}").issues)
    }

    @Test
    fun `renaming a node says which prompt file it could not rewrite`() {
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

        state.refreshPromptFiles()
        assertTrue(state.issues.any { "build" in it.message })
    }

    @Test
    fun `renaming a node nothing in a prompt file mentions says nothing`() {
        val state = reading("Summarise \${build.result}")

        state.renameNode("review", "read")

        assertNull(state.message)
    }
}
