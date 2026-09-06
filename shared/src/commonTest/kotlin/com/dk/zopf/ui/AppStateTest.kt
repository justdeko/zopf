package com.dk.zopf.ui

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.NodeExecution
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.RunRegistry
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.WorkspaceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkspaceSwitchTest {
    private val dirs = mutableListOf<Path>()
    private val apps = mutableListOf<AppState>()

    @AfterTest
    fun cleanup() {
        apps.forEach { it.shutdown() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("zopf-switch").also { dirs.add(it) }

    private fun app(): AppState = AppState(WorkspaceRegistry(tempDir().resolve("workspaces.json"))).also { apps.add(it) }

    private val workflow =
        Workflow(
            name = "demo",
            nodes = listOf(WorkflowNode(id = "build", type = NodeType.SHELL, command = "true")),
        )

    @Test
    fun `refreshing workspaces leaves an open editor alone`() {
        val app = app()
        app.addWorkspace(tempDir().resolve("one"))
        app.openEditor(workflow)
        assertNotNull(app.editing)

        app.refreshWorkspaces()

        assertNotNull(app.editing, "a refresh is not a move, and must not discard the graph")
    }

    @Test
    fun `picking the active workspace leaves an open editor alone`() {
        val app = app()
        val dir = tempDir().resolve("one")
        app.addWorkspace(dir)
        app.openEditor(workflow)
        val editing = assertNotNull(app.editing)

        app.selectWorkspace(assertNotNull(app.activeWorkspace).path)

        assertSame(editing, app.editing)
    }

    @Test
    fun `moving workspace closes the editor`() {
        val app = app()
        app.addWorkspace(tempDir().resolve("one"))
        val second = tempDir().resolve("two")
        app.addWorkspace(second)

        app.selectWorkspace(assertNotNull(app.workspaces.first { it.path.endsWith("one/.zopf") }).path)
        app.openEditor(workflow)
        assertNotNull(app.editing)

        app.selectWorkspace(assertNotNull(app.workspaces.first { it.path.endsWith("two/.zopf") }).path)

        assertNull(app.editing)
        assertEquals(
            second.fileName,
            app.activeWorkspace
                ?.path
                ?.parent
                ?.fileName,
        )
    }
}

class EditorRunPanelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private object Unused : NodeExecutor {
        override suspend fun execute(execution: NodeExecution): Unit = error("${execution.node.id} tried to start a process")
    }

    private fun registry() =
        RunRegistry(
            scope,
            LiveSettings(AppSettings()),
            executor = Unused,
            archiveRoot = Files.createTempDirectory("zopf-panel").also { dirs.add(it) },
        )

    private fun workflow(name: String) =
        Workflow(
            name = name,
            nodes = listOf(WorkflowNode("only", NodeType.GATE)),
        )

    private fun settling() =
        Workflow(
            name = "alpha",
            nodes = listOf(WorkflowNode("only", NodeType.BRANCH, expression = "true")),
        )

    @Test
    fun theEditorGetsNothingWhenTheSelectedRunIsAnotherWorkflows() {
        val runs = registry()
        runs.startWorkflow(null, workflow("alpha")).getOrThrow()
        val beta = runs.startWorkflow(null, workflow("beta")).getOrThrow()

        assertEquals(beta, runs.selectedRun)
        assertNull(runs.runForEditor("alpha"))
        assertEquals(beta, runs.runForEditor("beta"))
    }

    @Test
    fun theEditorLetsGoOfARunOnceItHasFinished() =
        runBlocking {
            val runs = registry()
            val alpha = runs.startWorkflow(null, settling()).getOrThrow()
            withTimeout(10.seconds) { alpha.job?.join() }

            assertEquals(RunStatus.SUCCEEDED, alpha.status)

            assertEquals(alpha, runs.selectedRun)
            assertNull(runs.runForEditor("alpha"))
        }

    @Test
    fun selectingAnotherRunElsewhereDoesNotLeakIntoTheEditor() {
        val runs = registry()
        val alpha = runs.startWorkflow(null, workflow("alpha")).getOrThrow()
        runs.startWorkflow(null, workflow("beta")).getOrThrow()

        runs.select(alpha)

        assertEquals(alpha, runs.runForEditor("alpha"))
        assertNull(runs.runForEditor("beta"))
    }
}

class RunGateTest {
    private val dirs = mutableListOf<Path>()
    private val apps = mutableListOf<AppState>()

    @AfterTest
    fun cleanup() {
        apps.forEach { it.shutdown() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("zopf-gate").also { dirs.add(it) }

    private fun app(): AppState =
        AppState(WorkspaceRegistry(tempDir().resolve("workspaces.json")))
            .also { apps.add(it) }
            .also { it.addWorkspace(tempDir().resolve("ws")) }

    private val broken =
        Workflow(
            name = "demo",
            nodes = listOf(WorkflowNode(id = "build", type = NodeType.SHELL, command = "true", repo = "app")),
        )

    @Test
    fun `the Run button refuses a workflow with errors`() {
        val app = app()

        app.runWorkflow(broken)

        assertEquals(0, app.runs.runs.size)
        assertEquals("demo can't run yet. build runs in \"app\", which isn't declared", app.message)
    }

    @Test
    fun `an open editor is judged on its own issues`() {
        val app = app()
        app.openEditor(broken)

        app.runWorkflow()

        assertEquals(0, app.runs.runs.size)
        assertNotNull(app.message)
    }

    @Test
    fun `the Run button refuses on a fresh snapshot`() {
        val app = app()
        val workspace = assertNotNull(assertNotNull(app.activeWorkspace).workspace)
        workspace.root.resolve("prompts").createDirectories()
        val prompt = workspace.root.resolve("prompts/review.md")
        prompt.writeText("Look at \${build.result}")

        app.openEditor(
            Workflow(
                name = "demo",
                nodes =
                    listOf(
                        WorkflowNode(id = "build", type = NodeType.SHELL, command = "true"),
                        WorkflowNode(id = "review", type = NodeType.AGENT, promptFile = "prompts/review.md"),
                    ),
                edges = listOf(WorkflowEdge("build", "review")),
            ),
        )
        prompt.writeText("Look at \${gone.result}")

        app.runWorkflow()

        assertEquals(0, app.runs.runs.size)
        val message = assertNotNull(app.message)
        assertTrue("gone" in message, "the gate read the copy the editor took when it opened: $message")
    }
}
