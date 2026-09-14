package com.dk.zopf.ui

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.NodeExecution
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.exec.ShellLine
import com.dk.zopf.runtime.run.RunRegistry
import com.dk.zopf.runtime.run.RunStatus
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.workspace.WorkspaceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun app(): AppState = AppState(WorkspaceRegistry(tempDir().resolve("workspaces.json")), archiveRoot = tempDir()).also { apps.add(it) }

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
    private val apps = mutableListOf<AppState>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        apps.forEach { it.shutdown() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("zopf-panel-app").also { dirs.add(it) }

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

    private fun RunRegistry.editorRunState(workflowName: String) = runForEditor(workflowName)?.state?.value

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
    fun `the editor keeps a run after it has finished`() =
        runBlocking {
            val runs = registry()
            val alpha = runs.startWorkflow(null, settling()).getOrThrow()
            withTimeout(10.seconds) { alpha.job?.join() }

            assertEquals(RunStatus.SUCCEEDED, alpha.status)
            assertEquals(alpha, runs.runForEditor("alpha"))
        }

    @Test
    fun `reopening the editor hides a panel left from before`() {
        val app = AppState(WorkspaceRegistry(tempDir().resolve("workspaces.json")), archiveRoot = tempDir()).also { apps.add(it) }
        app.addWorkspace(tempDir().resolve("ws"))
        app.showRunPanel = true

        app.openEditor(workflow("alpha"))

        assertFalse(app.showRunPanel)
    }

    @Test
    fun `the canvas keeps painting a run while the panel is shut`() {
        val runs = registry()
        runs.startWorkflow(null, workflow("alpha")).getOrThrow()

        assertNotNull(runs.editorRunState("alpha").onCanvas(panelOpen = false))
    }

    @Test
    fun `the canvas drops a finished run once the panel is shut`() =
        runBlocking {
            val runs = registry()
            val alpha = runs.startWorkflow(null, settling()).getOrThrow()
            withTimeout(10.seconds) { alpha.job?.join() }

            assertNotNull(runs.editorRunState("alpha").onCanvas(panelOpen = true))
            assertNull(runs.editorRunState("alpha").onCanvas(panelOpen = false))
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

    private fun app(archiveRoot: Path = tempDir()): AppState =
        AppState(WorkspaceRegistry(tempDir().resolve("workspaces.json")), archiveRoot = archiveRoot)
            .also { apps.add(it) }
            .also { it.addWorkspace(tempDir().resolve("ws")) }

    private val broken =
        Workflow(
            name = "demo",
            nodes = listOf(WorkflowNode(id = "build", type = NodeType.SHELL, command = "true", repo = "app")),
        )

    @Test
    fun `a run is archived under the root the app was given`() =
        runBlocking {
            val root = tempDir()
            val app = app(root)

            app.runWorkflow(Workflow(name = "demo", nodes = listOf(WorkflowNode("build", NodeType.SHELL, command = "true"))))
            val run = app.runs.runs.single()
            withTimeout(10.seconds) { run.job?.join() }

            assertEquals(1, RunArchive.all(root).size, "the run was archived somewhere other than the root the app was given")
        }

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
    fun `the Run button says nothing when the editor shows the run`() {
        val app = app()
        val gate = Workflow(name = "demo", nodes = listOf(WorkflowNode(id = "look", type = NodeType.GATE)))
        app.openEditor(gate)
        app.message = null

        app.runWorkflow()

        assertEquals(1, app.runs.runs.size)
        assertNull(app.message)
    }

    @Test
    fun `the Run button names a run the editor is not showing`() {
        val app = app()
        val gate = Workflow(name = "demo", nodes = listOf(WorkflowNode(id = "look", type = NodeType.GATE)))
        app.message = null

        app.runWorkflow(gate)

        assertEquals(1, app.runs.runs.size)
        assertEquals("Running demo", app.message)
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

class PrunedRunsMessageTest {
    @Test
    fun `show prune message only when it clears more than threshold`() {
        listOf(
            Triple(1, 50, false),
            Triple(3, 50, false),
            Triple(50, 50, false),
            Triple(51, 50, true),
            Triple(400, 50, true),
            Triple(2, 1, true),
        ).forEach { (gone, keep, expected) ->
            assertEquals(expected, prunedRunsMessage(gone, keep) != null, "$gone gone, $keep kept")
        }
    }

    @Test
    fun `the pruning message names the count and the limit`() {
        assertEquals(
            "Deleted 400 archived runs, keeping the last 50. See Settings › History.",
            prunedRunsMessage(400, 50),
        )
    }
}

class ConnectorAgentTest {
    private fun installed(vararg ids: AgentProviderId): (AgentProviderId) -> Boolean = { it in ids }

    @Test
    fun `a connector opens in the installed cli nearest the default`() {
        listOf(
            Triple(AgentProviderId.CLAUDE, installed(AgentProviderId.CLAUDE, AgentProviderId.CODEX), "claude"),
            Triple(AgentProviderId.CODEX, installed(AgentProviderId.CLAUDE, AgentProviderId.CODEX), "codex"),
            Triple(AgentProviderId.CODEX, installed(AgentProviderId.CLAUDE), "claude"),
            Triple(AgentProviderId.DSH, installed(AgentProviderId.CLAUDE, AgentProviderId.DSH), "claude"),
            Triple(AgentProviderId.DSH, installed(AgentProviderId.CODEX), "codex"),
            Triple(AgentProviderId.DSH, installed(AgentProviderId.DSH), "claude"),
            Triple(AgentProviderId.CODEX, installed(), "codex"),
            Triple(AgentProviderId.CLAUDE, installed(), "claude"),
        ).forEach { (default, isInstalled, expected) ->
            assertEquals(expected, authoringAgent(default, isInstalled).executable, "default ${default.cliValue}")
        }
    }
}

class WorkflowDraftTest {
    private val dirs = mutableListOf<Path>()
    private val apps = mutableListOf<AppState>()

    @AfterTest
    fun cleanup() {
        apps.forEach { it.shutdown() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("zopf-draft").also { dirs.add(it) }

    private class Answering(
        private val answers: List<String>,
    ) : NodeExecutor {
        val prompts: MutableList<String> = mutableListOf()

        override suspend fun execute(execution: NodeExecution) {
            execution.run.consume(ShellLine(answers[minOf(prompts.size, answers.lastIndex)], isError = false))
            prompts += execution.node.prompt
        }
    }

    private fun app(executor: NodeExecutor): AppState =
        AppState(WorkspaceRegistry(tempDir().resolve("workspaces.json")), archiveRoot = tempDir(), executor = executor)
            .also { apps.add(it) }
            .also { it.addWorkspace(tempDir().resolve("ws")) }

    private fun draft(
        vararg repo: String,
        id: String = "build",
    ) = """
        ```yaml
        name: whatever
        nodes:
          - id: $id
            type: shell
            command: make
        ${repo.joinToString("") { "    repo: $it\n" }}```
        """.trimIndent()

    private fun AppState.awaitDraft() =
        runBlocking {
            withTimeout(20.seconds) {
                while (drafting != null) delay(20)
            }
        }

    @Test
    fun `a described workflow is written and opened for review`() {
        val app = app(Answering(listOf(draft())))

        app.describeWorkflow("Lint Fix", "lint it and fix what it says")
        app.awaitDraft()

        assertEquals(listOf("lint-fix"), app.listing.workflows.map { it.name })
        assertEquals("lint-fix", app.editing?.workflow?.name)
        assertEquals("Wrote lint-fix, look it over before you run it", app.message)
        assertEquals(Screen.RUNS, app.screen, "the draft is a run, and you watch it happen")
    }

    @Test
    fun `the draft prompt carries what was asked for`() {
        val executor = Answering(listOf(draft()))
        val app = app(executor)

        app.describeWorkflow("lint-fix", "lint it and fix what it says")
        app.awaitDraft()

        assertEquals(1, executor.prompts.size)
        assertTrue("lint it and fix what it says" in executor.prompts.single(), executor.prompts.single())
    }

    @Test
    fun `a draft that wouldn't run is handed back its errors once`() {
        val executor = Answering(listOf(draft("app"), draft()))
        val app = app(executor)

        app.describeWorkflow("lint-fix", "lint it")
        app.awaitDraft()

        assertEquals(2, executor.prompts.size)
        assertTrue("isn't declared" in executor.prompts.last(), executor.prompts.last())
        assertEquals(listOf("lint-fix"), app.listing.workflows.map { it.name })
    }

    @Test
    fun `a draft that stays broken is still written, with what to fix`() {
        val app = app(Answering(listOf(draft("app"))))

        app.describeWorkflow("lint-fix", "lint it")
        app.awaitDraft()

        assertEquals(listOf("lint-fix"), app.listing.workflows.map { it.name })
        assertTrue(app.message.orEmpty().startsWith("Wrote lint-fix, but it won't run yet:"), app.message.orEmpty())
    }

    @Test
    fun `an answer with no workflow in it writes nothing`() {
        val app = app(Answering(listOf("I'd rather not.")))

        app.describeWorkflow("lint-fix", "lint it")
        app.awaitDraft()

        assertEquals(emptyList(), app.listing.workflows)
        assertNull(app.editing)
        assertTrue("didn't come back as a workflow" in app.message.orEmpty(), app.message.orEmpty())
    }

    @Test
    fun `a name already taken is refused before anything starts`() {
        val executor = Answering(listOf(draft()))
        val app = app(executor)
        app.describeWorkflow("lint-fix", "lint it")
        app.awaitDraft()

        app.describeWorkflow("lint-fix", "lint it again")

        assertEquals("A workflow named \"lint-fix\" already exists", app.message)
        assertEquals(1, executor.prompts.size)
    }
}
