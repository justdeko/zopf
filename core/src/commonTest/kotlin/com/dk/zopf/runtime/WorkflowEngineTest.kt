package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.WORKFLOW_VERSION
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.DEFAULT_CONCURRENCY
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.WorkspaceConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WorkflowEngineTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-engine").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a diamond runs both sides and joins once`() {
        val executor = FakeExecutor()
        val workflow =
            workflow(
                nodes = listOf(claude("analyze"), claude("left"), claude("right"), claude("merge")),
                edges = listOf("analyze" to "left", "analyze" to "right", "left" to "merge", "right" to "merge"),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(listOf("analyze", "left", "right", "merge").sorted(), executor.started.sorted())
        assertEquals(1, executor.started.count { it == "merge" })

        assertTrue(executor.started.indexOf("merge") > executor.started.indexOf("left"))
        assertTrue(executor.started.indexOf("merge") > executor.started.indexOf("right"))
    }

    @Test
    fun `a failure skips everything downstream`() {
        val executor = FakeExecutor(fail = setOf("analyze"))
        val workflow =
            workflow(
                nodes = listOf(claude("analyze"), claude("fix"), claude("verify")),
                edges = listOf("analyze" to "fix", "fix" to "verify"),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(listOf("analyze"), executor.started)
        assertEquals(RunStatus.SKIPPED, run.node("fix")?.status)
        assertEquals(RunStatus.SKIPPED, run.node("verify")?.status)
    }

    @Test
    fun `an on-failure edge runs only when its source fails`() {
        val workflow =
            wired(
                nodes = listOf(shell("tests"), claude("diagnose"), claude("ship")),
                edges =
                    listOf(
                        WorkflowEdge("tests", "diagnose", on = EdgeTrigger.FAILURE),
                        WorkflowEdge("tests", "ship"),
                    ),
            )

        val failed = FakeExecutor(fail = setOf("tests"))
        val afterFailure = runToCompletion(workflow, failed)
        assertTrue("diagnose" in failed.started, "the recovery path should have run")
        assertEquals(RunStatus.SKIPPED, afterFailure.node("ship")?.status)

        val passed = FakeExecutor()
        val afterSuccess = runToCompletion(workflow, passed)
        assertEquals(RunStatus.SUCCEEDED, afterSuccess.status)
        assertEquals(RunStatus.SKIPPED, afterSuccess.node("diagnose")?.status)
        assertTrue("ship" in passed.started)
    }

    @Test
    fun `a failed success edge skips a node with an unfired failure edge`() {
        val executor = FakeExecutor(fail = setOf("build"))
        val workflow =
            wired(
                nodes = listOf(shell("build"), shell("lint"), claude("report")),
                edges =
                    listOf(
                        WorkflowEdge("build", "report"),
                        WorkflowEdge("lint", "report", on = EdgeTrigger.FAILURE),
                    ),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.SKIPPED, run.node("report")?.status)
    }

    @Test
    fun `an on-failure edge runs when another predecessor also failed`() {
        val executor = FakeExecutor(fail = setOf("build", "lint"))
        val workflow =
            wired(
                nodes = listOf(shell("build"), shell("lint"), claude("report")),
                edges =
                    listOf(
                        WorkflowEdge("build", "report"),
                        WorkflowEdge("lint", "report", on = EdgeTrigger.FAILURE),
                    ),
            )

        runToCompletion(workflow, executor)

        assertTrue("report" in executor.started, "the recovery path should have run")
    }

    @Test
    fun `a handled failure settles the run`() {
        val executor = FakeExecutor(fail = setOf("tests"))
        val workflow =
            wired(
                nodes = listOf(shell("tests"), claude("diagnose")),
                edges = listOf(WorkflowEdge("tests", "diagnose", on = EdgeTrigger.FAILURE)),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(RunStatus.FAILED, run.node("tests")?.status)
    }

    @Test
    fun `a rescue arm that fails fails the run`() {
        val executor = FakeExecutor(fail = setOf("tests", "diagnose"))
        val workflow =
            wired(
                nodes = listOf(shell("tests"), claude("diagnose")),
                edges = listOf(WorkflowEdge("tests", "diagnose", on = EdgeTrigger.FAILURE)),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun `an on-failure edge out of another node leaves a failure unhandled`() {
        val executor = FakeExecutor(fail = setOf("build"))
        val workflow =
            wired(
                nodes = listOf(shell("build"), shell("lint"), claude("report")),
                edges =
                    listOf(
                        WorkflowEdge("build", "report"),
                        WorkflowEdge("lint", "report", on = EdgeTrigger.FAILURE),
                    ),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun `stopping a run does not fire an on-failure edge`() =
        runBlocking {
            val executor = FakeExecutor(work = { delay(10.seconds) })
            val workflow =
                wired(
                    nodes = listOf(shell("tests"), claude("diagnose")),
                    edges = listOf(WorkflowEdge("tests", "diagnose", on = EdgeTrigger.FAILURE)),
                )

            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()
            awaitStatus(assertNotNull(run.node("tests")), RunStatus.RUNNING)
            engine.stop(run)
            withTimeout(10.seconds) { run.job?.join() }

            assertEquals(listOf("tests"), executor.started)
            assertEquals(RunStatus.STOPPED, run.node("tests")?.status)
        }

    @Test
    fun `a branch runs one side and skips the other`() {
        val executor = FakeExecutor()
        val workflow =
            wired(
                nodes =
                    listOf(
                        shell("build"),
                        WorkflowNode(id = "ok", type = NodeType.BRANCH, expression = "\${build.result} == green"),
                        claude("ship"),
                        claude("report"),
                    ),
                edges =
                    listOf(
                        WorkflowEdge("build", "ok"),
                        WorkflowEdge("ok", "ship", condition = true),
                        WorkflowEdge("ok", "report", condition = false),
                    ),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(listOf("build", "ship"), executor.started)
        assertEquals(RunStatus.SKIPPED, run.node("report")?.status)
        assertEquals("true", run.node("ok")?.output()?.result)
    }

    @Test
    fun `a node after both sides of a branch runs on the taken side`() {
        val executor = FakeExecutor()
        val workflow =
            wired(
                nodes =
                    listOf(
                        shell("build"),
                        WorkflowNode(id = "ok", type = NodeType.BRANCH, expression = "\${build.result} == green"),
                        claude("ship"),
                        claude("report"),
                        claude("notify"),
                    ),
                edges =
                    listOf(
                        WorkflowEdge("build", "ok"),
                        WorkflowEdge("ship", "notify"),
                        WorkflowEdge("report", "notify"),
                        WorkflowEdge("ok", "ship", condition = true),
                        WorkflowEdge("ok", "report", condition = false),
                    ),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertContains(executor.started, "notify")
        assertEquals(RunStatus.SKIPPED, run.node("report")?.status)
    }

    @Test
    fun `a node that finishes instantly does not strand the chain`() {
        val workflow =
            workflow(
                nodes = listOf(claude("a"), claude("b"), claude("c"), claude("d")),
                edges = listOf("a" to "b", "b" to "c", "c" to "d"),
            )
        val archiveRoot = tempDir()

        repeat(100) { attempt ->
            val executor = FakeExecutor()
            val run = runToCompletion(workflow, executor, archiveRoot = archiveRoot)
            assertEquals(listOf("a", "b", "c", "d"), executor.started, "attempt $attempt stopped early")
            assertEquals(RunStatus.SUCCEEDED, run.status, "attempt $attempt")
        }
    }

    @Test
    fun `a cycle fails the nodes in it`() {
        val executor = FakeExecutor()
        val workflow =
            workflow(
                nodes = listOf(claude("start"), claude("a"), claude("b")),
                edges = listOf("start" to "a", "a" to "b", "b" to "a"),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(listOf("start"), executor.started)
        assertEquals(RunStatus.FAILED, run.node("a")?.status)
        assertEquals(RunStatus.FAILED, run.node("b")?.status)
    }

    @Test
    fun `a prompt gets the output it references`() {
        val executor = FakeExecutor(output = { "$it-output" })
        val workflow =
            workflow(
                nodes =
                    listOf(
                        claude("analyze"),
                        claude("fix").copy(prompt = "Apply these:\n\${analyze.result}"),
                    ),
                edges = listOf("analyze" to "fix"),
            )

        runToCompletion(workflow, executor)

        assertEquals("Apply these:\nanalyze-output", executor.prompts["fix"])
    }

    @Test
    fun `approving a gate continues the run`() =
        runBlocking {
            val executor = FakeExecutor()
            val workflow =
                workflow(
                    nodes = listOf(claude("analyze"), gate(), claude("ship")),
                    edges = listOf("analyze" to "approve", "approve" to "ship"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val gate = assertNotNull(run.node("approve"))
            awaitStatus(gate, RunStatus.WAITING)
            assertEquals(RunStatus.WAITING, run.status)
            assertEquals(listOf("analyze"), executor.started)

            engine.resolveGate(gate, approved = true)
            run.job?.join()

            assertEquals(RunStatus.SUCCEEDED, run.status)
            assertEquals(listOf("analyze", "ship"), executor.started)
            assertEquals("approved", gate.output().result)
        }

    @Test
    fun `a gate shows its upstream output`() =
        runBlocking {
            val executor = FakeExecutor(output = { "$it-output" })
            val workflow =
                workflow(
                    nodes =
                        listOf(
                            claude("review"),
                            gate().copy(prompt = "Apply these?\n\${review.result}"),
                            claude("fix"),
                        ),
                    edges = listOf("review" to "approve", "approve" to "fix"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val gate = assertNotNull(run.node("approve"))
            awaitStatus(gate, RunStatus.WAITING)

            val shown = gate.entries.filterIsInstance<ConsoleEntry.Prompt>().single()
            assertEquals("Apply these?\nreview-output", shown.text)
            assertTrue(gate.entries.filterIsInstance<ConsoleEntry.Notice>().isEmpty())

            engine.resolveGate(gate, approved = true)
            run.job?.join()
            assertEquals(RunStatus.SUCCEEDED, run.status)
        }

    @Test
    fun `a gate with no prompt falls back to its title`() =
        runBlocking {
            val executor = FakeExecutor()
            val workflow = workflow(nodes = listOf(gate()), edges = emptyList())
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val gate = assertNotNull(run.node("approve"))
            awaitStatus(gate, RunStatus.WAITING)
            assertTrue(gate.entries.filterIsInstance<ConsoleEntry.Prompt>().isEmpty())
            assertEquals(
                "Waiting for you",
                gate.entries
                    .filterIsInstance<ConsoleEntry.Notice>()
                    .single()
                    .text,
            )

            engine.resolveGate(gate, approved = true)
            run.job?.join()
            assertEquals(RunStatus.SUCCEEDED, run.status)
        }

    @Test
    fun `rejecting a gate stops the run without failing`() =
        runBlocking {
            val executor = FakeExecutor()
            val workflow =
                workflow(
                    nodes = listOf(gate(), claude("ship")),
                    edges = listOf("approve" to "ship"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val gate = assertNotNull(run.node("approve"))
            awaitStatus(gate, RunStatus.WAITING)
            engine.resolveGate(gate, approved = false)
            run.job?.join()

            assertEquals(RunStatus.STOPPED, run.status)
            assertTrue(executor.started.isEmpty())
            assertEquals(RunStatus.SKIPPED, run.node("ship")?.status)
        }

    @Test
    fun `an input node parks the run and its answer reaches downstream`() =
        runBlocking {
            val executor = FakeExecutor()
            val workflow =
                workflow(
                    nodes =
                        listOf(
                            input(question = "Which branch?"),
                            claude("review").copy(prompt = "review \${ask.result}"),
                        ),
                    edges = listOf("ask" to "review"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val ask = assertNotNull(run.node("ask"))
            awaitStatus(ask, RunStatus.WAITING)
            assertEquals(RunStatus.WAITING, run.status)
            assertTrue(executor.started.isEmpty())

            engine.resolveInput(ask, "release-2")
            run.job?.join()

            assertEquals(RunStatus.SUCCEEDED, run.status)
            assertEquals("release-2", ask.output().result)
            assertEquals("review release-2", executor.prompts["review"])
        }

    @Test
    fun `an input question is interpolated`() =
        runBlocking {
            val executor = FakeExecutor(output = { "3 files changed" })
            val workflow =
                workflow(
                    nodes = listOf(claude("analyze"), input(question = "Ship \${analyze.result}?")),
                    edges = listOf("analyze" to "ask"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val ask = assertNotNull(run.node("ask"))
            awaitStatus(ask, RunStatus.WAITING)

            assertEquals("Ship 3 files changed?", assertNotNull(ask.pendingQuestion).question)

            engine.resolveInput(ask, "yes")
            run.job?.join()
            assertEquals(RunStatus.SUCCEEDED, run.status)
        }

    @Test
    fun `cancelling an input stops the run without failing`() =
        runBlocking {
            val executor = FakeExecutor()
            val workflow =
                workflow(
                    nodes = listOf(input(question = "Which branch?"), claude("review")),
                    edges = listOf("ask" to "review"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val ask = assertNotNull(run.node("ask"))
            awaitStatus(ask, RunStatus.WAITING)
            engine.resolveInput(ask, null)
            run.job?.join()

            assertEquals(RunStatus.STOPPED, run.status)
            assertTrue(executor.started.isEmpty())
            assertEquals(RunStatus.SKIPPED, run.node("review")?.status)
        }

    @Test
    fun `a parked question is announced`() =
        runBlocking {
            val (engine, run, waiting) = watchingWaits(input(question = "Which branch?"))

            val ask = assertNotNull(run.node("ask"))
            awaitStatus(ask, RunStatus.WAITING)
            assertEquals(listOf("ask"), waiting)

            engine.resolveInput(ask, "main")
            run.job?.join()
            assertEquals(listOf("ask"), waiting)
        }

    @Test
    fun `a parked gate is announced`() =
        runBlocking {
            val (engine, run, waiting) = watchingWaits(gate())

            val approve = assertNotNull(run.node("approve"))
            awaitStatus(approve, RunStatus.WAITING)
            assertEquals(listOf("approve"), waiting)

            engine.resolveGate(approve, approved = true)
            run.job?.join()
            assertEquals(listOf("approve"), waiting)
        }

    @Test
    fun `every archived line reaches a watcher`() {
        val executor = FakeExecutor(work = { }, output = { "green" })
        val seen = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val workflow = workflow(nodes = listOf(shell("build")), edges = emptyList())

        val run =
            runBlocking {
                val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
                val archiveRoot = tempDir()
                val engine =
                    WorkflowEngine(
                        scope = scope,
                        executor = executor,
                        archiveRoot = archiveRoot,
                        settings = LiveSettings(AppSettings()),
                        onRaw = { nodeId, line -> seen.add(nodeId to line) },
                    )
                val started = engine.start(workspace(), workflow).getOrThrow()
                withTimeout(10.seconds) { started.job?.join() }
                started
            }

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(listOf("build" to "green"), seen)
    }

    @Test
    fun `no more than the configured processes run at once`() {
        val executor = FakeExecutor(work = { delay(50.milliseconds) })
        val workflow =
            workflow(
                nodes = (1..6).map { claude("n$it") },
                edges = emptyList(),
            )

        runToCompletion(workflow, executor, concurrency = 2)

        assertEquals(6, executor.started.size)
        assertTrue(executor.peak.get() <= 2, "peak concurrency was ${executor.peak.get()}")
    }

    @Test
    fun `stopping a run ends the node in flight and skips the rest`() =
        runBlocking {
            val executor = FakeExecutor(work = { delay(10.seconds) })
            val workflow =
                workflow(
                    nodes = listOf(claude("slow"), claude("next")),
                    edges = listOf("slow" to "next"),
                )
            val engine = engine(executor)
            val run = engine.start(workspace(), workflow).getOrThrow()

            val slow = assertNotNull(run.node("slow"))
            awaitStatus(slow, RunStatus.RUNNING)
            engine.stop(run)
            run.job?.join()

            assertEquals(RunStatus.STOPPED, run.status)
            assertEquals(RunStatus.STOPPED, slow.status)
            assertEquals(true, run.node("next")?.status?.isFinished)
        }

    @Test
    fun `running a single node ignores the rest of the graph`() {
        val executor = FakeExecutor()
        val workflow =
            workflow(
                nodes = listOf(claude("analyze"), claude("fix")),
                edges = listOf("analyze" to "fix"),
            )

        val run = runToCompletion(workflow, executor, only = workflow.node("fix"))

        assertEquals(listOf("fix"), executor.started)
        assertEquals(1, run.nodes.size)
        assertTrue(run.isInteractive)
    }

    @Test
    fun `a workspace default reaches a workflow naming no provider`() {
        val run =
            runBlocking {
                val started =
                    engine(FakeExecutor())
                        .start(
                            workspace(NodeDefaults(provider = AgentProviderId.CODEX)),
                            workflow(nodes = listOf(claude("analyze")), edges = emptyList()),
                        ).getOrThrow()
                withTimeout(10.seconds) { started.job?.join() }
                started
            }

        assertEquals(AgentProviderId.CODEX, run.node("analyze")?.provider)
    }

    @Test
    fun `a workflow naming its own provider ignores the workspace`() {
        val onCodex =
            wired(listOf(claude("analyze")), emptyList()).let {
                it.copy(defaults = it.defaults.copy(provider = AgentProviderId.CODEX))
            }

        val run =
            runBlocking {
                val started =
                    engine(FakeExecutor())
                        .start(workspace(NodeDefaults(provider = AgentProviderId.CLAUDE)), onCodex)
                        .getOrThrow()
                withTimeout(10.seconds) { started.job?.join() }
                started
            }

        assertEquals(AgentProviderId.CODEX, run.node("analyze")?.provider)
    }

    @Test
    fun `a graph-only node type is refused on its own`() {
        val result =
            engine(FakeExecutor()).start(
                workspace = workspace(),
                workflow = Workflow(name = "w", nodes = listOf(gate())),
                only = gate(),
            )
        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "run the workflow instead")
    }

    @Test
    fun `a workflow from a newer zopf is refused`() {
        val result =
            engine(FakeExecutor()).start(
                workspace = workspace(),
                workflow =
                    Workflow(
                        version = WORKFLOW_VERSION + 1,
                        name = "w",
                        nodes = listOf(WorkflowNode(id = "go", type = NodeType.SHELL, command = "true")),
                    ),
            )
        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "so update zopf to run it")
    }

    @Test
    fun `an undeclared repo stops a single-node run`() {
        val result =
            engine(FakeExecutor()).start(
                workspace = workspace(),
                workflow = Workflow(name = "w"),
                only = WorkflowNode(id = "fix", type = NodeType.AGENT, repo = "app", prompt = "go"),
            )
        assertTrue(result.isFailure)
        assertEquals("Repo \"app\" isn't declared in w", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a moved repo fails the node`() {
        val result =
            engine(FakeExecutor()).start(
                workspace = workspace(),
                workflow = Workflow(name = "w", repos = listOf(RepoRef("app", "/nowhere/at/all"))),
                only = WorkflowNode(id = "fix", type = NodeType.AGENT, repo = "app", prompt = "go"),
            )
        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "/nowhere/at/all")
    }

    @Test
    fun `a broken repo on an untaken path does not stop the run`() {
        val executor = FakeExecutor()
        val workflow =
            Workflow(
                name = "w",
                repos = listOf(RepoRef("gone", "/nowhere/at/all")),
                nodes =
                    listOf(
                        shell("build"),
                        WorkflowNode(id = "ok", type = NodeType.BRANCH, expression = "true"),
                        claude("ship"),
                        claude("rescue").copy(repo = "gone"),
                    ),
                edges =
                    listOf(
                        WorkflowEdge("build", "ok"),
                        WorkflowEdge("ok", "ship", condition = true),
                        WorkflowEdge("ok", "rescue", condition = false),
                    ),
            )

        val run = runToCompletion(workflow, executor)

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(RunStatus.SKIPPED, run.node("rescue")?.status)
    }

    @Test
    fun `an empty workflow is refused`() {
        val result = engine(FakeExecutor()).start(workspace(), Workflow(name = "empty"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `the archive records every node including skipped ones`() {
        val executor = FakeExecutor(fail = setOf("analyze"))
        val archiveRoot = tempDir()
        val workflow =
            workflow(
                nodes = listOf(claude("analyze"), claude("fix")),
                edges = listOf("analyze" to "fix"),
            )

        val run = runToCompletion(workflow, executor, archiveRoot = archiveRoot)

        val record = assertNotNull(RunArchive.all(archiveRoot).firstOrNull { it.read()?.id == run.id }?.read())
        assertEquals(RunStatus.FAILED.name, record.status)
        assertEquals(2, record.nodes.size)
        assertEquals(RunStatus.SKIPPED.name, record.nodes.first { it.nodeId == "fix" }.status)
        assertNotNull(record.finishedAt)
    }

    private fun engine(
        executor: NodeExecutor,
        concurrency: Int = DEFAULT_CONCURRENCY,
        archiveRoot: Path = tempDir(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): WorkflowEngine {
        val scope = CoroutineScope(Job() + dispatcher).also { scopes.add(it) }
        val settings = LiveSettings(AppSettings(concurrency = concurrency))
        return WorkflowEngine(scope, executor, archiveRoot, settings)
    }

    private fun runToCompletion(
        workflow: Workflow,
        executor: FakeExecutor,
        concurrency: Int = DEFAULT_CONCURRENCY,
        archiveRoot: Path = tempDir(),
        only: WorkflowNode? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): WorkflowRun =
        runBlocking {
            val engine = engine(executor, concurrency, archiveRoot, dispatcher)
            val run = engine.start(workspace(), workflow, only).getOrThrow()
            withTimeout(10.seconds) { run.job?.join() }
            run
        }

    private fun watchingWaits(node: WorkflowNode): Triple<WorkflowEngine, WorkflowRun, List<String>> {
        val waiting = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        val engine =
            WorkflowEngine(
                scope = scope,
                executor = FakeExecutor(),
                archiveRoot = tempDir(),
                settings = LiveSettings(AppSettings()),
                onWaiting = { waiting.add(it.nodeId) },
            )
        val run = engine.start(workspace(), workflow(nodes = listOf(node), edges = emptyList())).getOrThrow()
        return Triple(engine, run, waiting)
    }

    private suspend fun awaitStatus(
        node: NodeRun,
        status: RunStatus,
    ) {
        withTimeout(5.seconds) {
            while (node.status != status) delay(5.milliseconds)
        }
    }

    private fun workspace(): Workspace = Workspace.create(tempDir().resolve("ws"))

    private fun workspace(defaults: NodeDefaults): Workspace = Workspace(Workspace.create(tempDir().resolve("ws")).root, WorkspaceConfig(defaults = defaults))

    private fun workflow(
        nodes: List<WorkflowNode>,
        edges: List<Pair<String, String>>,
    ) = wired(nodes, edges.map { WorkflowEdge(it.first, it.second) })

    private fun wired(
        nodes: List<WorkflowNode>,
        edges: List<WorkflowEdge>,
    ) = Workflow(name = "test", nodes = nodes, edges = edges)

    private fun claude(id: String) = WorkflowNode(id = id, type = NodeType.AGENT, prompt = "do $id")

    private fun shell(id: String) = WorkflowNode(id = id, type = NodeType.SHELL, command = "echo $id")

    private fun gate() = WorkflowNode(id = "approve", type = NodeType.GATE)

    private fun input(question: String) = WorkflowNode(id = "ask", type = NodeType.INPUT, prompt = question)
}

private class FakeExecutor(
    private val fail: Set<String> = emptySet(),
    private val work: suspend () -> Unit = {},
    private val output: (String) -> String = { "green" },
) : NodeExecutor {
    val started: List<String> = Collections.synchronizedList(mutableListOf<String>())
    val prompts = ConcurrentHashMap<String, String>()

    private val running = AtomicInteger()
    val peak = AtomicInteger()

    override suspend fun execute(execution: NodeExecution) {
        val id = execution.node.id
        (started as MutableList<String>).add(id)
        prompts[id] = execution.outputs.interpolate(execution.node.prompt).text

        peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            execution.run.status = RunStatus.RUNNING
            work()
        } finally {
            running.decrementAndGet()
        }

        val text = output(id)
        execution.archive.appendRaw(id, text)
        execution.run.produce(text)
        if (id in fail) {
            execution.run.finish(RunStatus.FAILED, 1)
        } else {
            execution.run.finish(RunStatus.SUCCEEDED, 0)
        }
    }
}
