package com.dk.zopf.runtime

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.NotifyLevel
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunRegistryTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-registry").also { dirs.add(it) }

    private val spawned = mutableListOf<Process>()

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        spawned.forEach { it.destroyForcibly() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a retry runs the failed node and its dependents`() {
        val executor = RecordingExecutor(fail = setOf("fix"))
        val registry = registry(executor)
        val chain = workflow(listOf(node("analyze"), node("fix"), node("build")), listOf("analyze" to "fix", "fix" to "build"))

        val first = registry.runToCompletion(chain)
        assertEquals(RunStatus.FAILED, first.status)
        assertEquals(RunStatus.SKIPPED, first.node("build")!!.status)

        executor.fail = emptySet()
        val second = registry.retryToCompletion(first, "fix")

        assertEquals(RunStatus.SUCCEEDED, second.status)

        assertEquals(listOf("analyze", "fix", "fix", "build"), executor.started)
        assertEquals(RunStatus.SUCCEEDED, second.node("analyze")!!.status)
    }

    @Test
    fun `a carried node's output reaches its reader`() {
        val executor = RecordingExecutor(fail = setOf("fix"))
        val registry = registry(executor)
        val chain =
            workflow(
                listOf(node("analyze"), node("fix", prompt = "apply \${analyze.result}")),
                listOf("analyze" to "fix"),
            )

        val first = registry.runToCompletion(chain)
        executor.fail = emptySet()
        registry.retryToCompletion(first, "fix")

        assertEquals("apply analyze-output", executor.prompts["fix"])
    }

    @Test
    fun `a carried branch keeps the side it took`() {
        val executor = RecordingExecutor(fail = setOf("ship"), output = { if (it == "build") "green" else "$it-output" })
        val registry = registry(executor)
        val branching =
            Workflow(
                name = "test",
                nodes =
                    listOf(
                        node("build"),
                        WorkflowNode(id = "ok", type = NodeType.BRANCH, expression = "\${build.result} == green"),
                        node("ship"),
                        node("report"),
                    ),
                edges =
                    listOf(
                        WorkflowEdge("build", "ok"),
                        WorkflowEdge("ok", "ship", condition = true),
                        WorkflowEdge("ok", "report", condition = false),
                    ),
            )

        val first = registry.runToCompletion(branching)
        assertEquals(RunStatus.SKIPPED, first.node("report")!!.status)

        executor.fail = emptySet()
        val second = registry.retryToCompletion(first, "ship")

        assertEquals(RunStatus.SUCCEEDED, second.status)
        assertEquals(RunStatus.SUCCEEDED, second.node("ship")!!.status)
        assertEquals(RunStatus.SKIPPED, second.node("report")!!.status)
        assertEquals(listOf("build", "ship", "ship"), executor.started)
    }

    @Test
    fun `retrying a succeeded node redoes everything after it`() {
        val executor = RecordingExecutor()
        val registry = registry(executor)
        val chain = workflow(listOf(node("analyze"), node("fix"), node("build")), listOf("analyze" to "fix", "fix" to "build"))

        val first = registry.runToCompletion(chain)
        assertEquals(RunStatus.SUCCEEDED, first.status)

        val second = registry.retryToCompletion(first, "fix")

        assertEquals(listOf("analyze", "fix", "build", "fix", "build"), executor.started)
        assertEquals(RunStatus.SUCCEEDED, second.node("analyze")!!.status)
    }

    @Test
    fun `the retried run is left unchanged`() {
        val executor = RecordingExecutor(fail = setOf("fix"))
        val registry = registry(executor)
        val chain = workflow(listOf(node("analyze"), node("fix")), listOf("analyze" to "fix"))

        val first = registry.runToCompletion(chain)
        executor.fail = emptySet()
        val second = registry.retryToCompletion(first, "fix")

        assertEquals(2, registry.runs.size)
        assertTrue(second.id != first.id)
        assertEquals(RunStatus.FAILED, first.status)
        assertEquals(RunStatus.FAILED, first.node("fix")!!.status)
    }

    @Test
    fun `a run restored from the archive cannot be retried`() {
        val registry = registry(RecordingExecutor())
        val restored =
            WorkflowRun.restored(
                RunRecord(id = "old", workflow = "test", startedAt = "2026-08-07T00:00:00Z", status = "FAILED"),
                Restore.SETTLED,
            )

        val failure = registry.retry(restored, "fix").exceptionOrNull()

        assertTrue(failure != null && "quit" in failure.message.orEmpty(), "unhelpful: ${failure?.message}")
    }

    @Test
    fun `a running run cannot be retried`() {
        val registry = registry(RecordingExecutor(work = { delay(10.seconds) }))
        val chain = workflow(listOf(node("analyze")), emptyList())
        val run = registry.startWorkflow(workspace(), chain).getOrThrow()

        val failure = registry.retry(run, "analyze").exceptionOrNull()

        assertTrue(failure != null && "still running" in failure.message.orEmpty())
        registry.stopAll()
        runBlocking { withTimeout(10.seconds) { run.job?.join() } }
    }

    @Test
    fun `the configured concurrency applies to the next run`() {
        val settings = LiveSettings(AppSettings(concurrency = 1))
        val executor = RecordingExecutor(work = { delay(50.milliseconds) })
        val registry = registry(executor, settings)

        registry.runToCompletion(freeNodes())
        assertEquals(1, executor.peak.get())

        settings.update { it.copy(concurrency = 3) }
        executor.peak.set(0)
        registry.runToCompletion(freeNodes())

        assertEquals(3, executor.peak.get())
    }

    @Test
    fun `the terminal is taken from the settings`() {
        assertEquals("iTerm", registry(RecordingExecutor(), LiveSettings(AppSettings(terminalApp = "iTerm"))).terminalApp)

        val settings = LiveSettings(AppSettings())
        val registry = registry(RecordingExecutor(), settings)
        assertEquals("Terminal", registry.terminalApp)

        settings.update { it.copy(terminalApp = "Ghostty") }
        assertEquals("Ghostty", registry.terminalApp)
    }

    private fun registry(
        executor: NodeExecutor,
        settings: LiveSettings = LiveSettings(),
        notifier: Notifier = SilentNotifier,
        archiveRoot: Path = tempDir(),
    ): RunRegistry {
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        return RunRegistry(scope, settings, executor, archiveRoot, notifier)
    }

    private fun RunRegistry.runToCompletion(workflow: Workflow): WorkflowRun =
        runBlocking {
            val run = startWorkflow(workspace(), workflow).getOrThrow()
            withTimeout(10.seconds) { run.job?.join() }
            run
        }

    private fun RunRegistry.retryToCompletion(
        run: WorkflowRun,
        nodeId: String,
    ): WorkflowRun =
        runBlocking {
            val retried = retry(run, nodeId).getOrThrow()
            withTimeout(10.seconds) { retried.job?.join() }
            retried
        }

    private fun workspace(): Workspace = Workspace.create(tempDir().resolve("ws"))

    private fun freeNodes() =
        Workflow(
            name = "w",
            nodes = (1..4).map { WorkflowNode(id = "n$it", type = NodeType.SHELL, command = "true") },
        )

    private fun workflow(
        nodes: List<WorkflowNode>,
        edges: List<Pair<String, String>>,
    ) = Workflow(name = "test", nodes = nodes, edges = edges.map { WorkflowEdge(it.first, it.second) })

    private fun node(
        id: String,
        prompt: String = "do $id",
    ) = WorkflowNode(id = id, type = NodeType.AGENT, prompt = prompt)

    @Test
    fun `a parked gate posts a notification`() {
        val notifier = RecordingNotifier()
        val registry = registry(RecordingExecutor(), notifier = notifier)
        val gated =
            workflow(
                listOf(node("analyze"), WorkflowNode(id = "approve", type = NodeType.GATE, prompt = "Ship it?")),
                listOf("analyze" to "approve"),
            )

        val run = runBlocking { registry.startWorkflow(workspace(), gated).getOrThrow() }
        val gate = runBlocking { awaitWaiting(run, "approve") }
        runBlocking { awaitAsked(notifier) }

        val asked = notifier.asked.single()
        assertEquals("Ship it?", asked.body)
        assertTrue(asked.title.contains("needs approval"), asked.title)
        assertEquals(listOf("approve", "reject"), asked.actions.map { it.id })

        registry.approve(gate, true)
        runBlocking { withTimeout(10.seconds) { run.job?.join() } }
        assertEquals(1, notifier.cancelled.size)
    }

    @Test
    fun `answering a gate withdraws its notification`() {
        val notifier = RecordingNotifier()
        val registry = registry(RecordingExecutor(), notifier = notifier)
        val gated = workflow(listOf(WorkflowNode(id = "approve", type = NodeType.GATE, prompt = "Ship it?")), emptyList())

        val run = runBlocking { registry.startWorkflow(workspace(), gated).getOrThrow() }
        val gate = runBlocking { awaitWaiting(run, "approve") }
        runBlocking { awaitAsked(notifier) }
        assertEquals(1, notifier.asked.size)

        registry.approve(gate, true)
        runBlocking { withTimeout(10.seconds) { run.job?.join() } }
        assertEquals(listOf("node-${gate.id}"), notifier.cancelled)
    }

    @Test
    fun `nothing is posted while the window is focused`() {
        val notifier = RecordingNotifier()
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        val registry =
            RunRegistry(
                scope,
                LiveSettings(),
                RecordingExecutor(),
                tempDir(),
                notifier,
                isForeground = { true },
            )

        val run = registry.runToCompletion(workflow(listOf(node("analyze")), emptyList()))
        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertTrue(notifier.posted.isEmpty(), notifier.posted.toString())
    }

    @Test
    fun `a succeeded run posts nothing at the important level`() {
        val notifier = RecordingNotifier()
        val settings = LiveSettings(AppSettings(notify = NotifyLevel.IMPORTANT))
        val registry = registry(RecordingExecutor(), settings, notifier)

        registry.runToCompletion(workflow(listOf(node("analyze")), emptyList()))
        assertTrue(notifier.posted.isEmpty(), notifier.posted.toString())

        val failing = registry(RecordingExecutor(fail = setOf("analyze")), settings, notifier)
        failing.runToCompletion(workflow(listOf(node("analyze")), emptyList()))
        assertEquals(1, notifier.posted.size)
        assertTrue(notifier.posted.single().isFailure)
    }

    @Test
    fun `a run still going elsewhere is not announced`() {
        val notifier = RecordingNotifier()
        val root = tempDir()
        val registry = registry(RecordingExecutor(), notifier = notifier, archiveRoot = root)
        elsewhere(root, RunStatus.RUNNING)

        registry.readArchive()

        val watched = registry.runs.single()
        assertEquals(RunStatus.RUNNING, watched.status)
        assertTrue(watched.isElsewhere)
        assertTrue(notifier.posted.isEmpty(), notifier.posted.toString())
    }

    @Test
    fun `a run that finishes elsewhere is announced once`() {
        val notifier = RecordingNotifier()
        val root = tempDir()
        val registry = registry(RecordingExecutor(), notifier = notifier, archiveRoot = root)
        elsewhere(root, RunStatus.RUNNING)
        registry.readArchive()

        elsewhere(root, RunStatus.SUCCEEDED)
        registry.readArchive()
        registry.readArchive()

        val watched = registry.runs.single()
        assertEquals(RunStatus.SUCCEEDED, watched.status)
        assertEquals(1, notifier.posted.size, notifier.posted.toString())
        assertTrue(
            notifier.posted
                .single()
                .title
                .contains("done"),
            notifier.posted.single().title,
        )
    }

    @Test
    fun `a run whose owner is killed is announced as stopped`() {
        val notifier = RecordingNotifier()
        val root = tempDir()
        val registry = registry(RecordingExecutor(), notifier = notifier, archiveRoot = root)
        val owner = elsewhere(root, RunStatus.RUNNING)
        registry.readArchive()

        owner.destroyForcibly().waitFor()
        registry.readArchive()

        assertEquals(RunStatus.STOPPED, registry.runs.single().status)
        assertEquals(1, notifier.posted.size, notifier.posted.toString())
    }

    private fun elsewhere(
        root: Path,
        status: RunStatus,
    ): Process {
        val owner = ProcessBuilder("/bin/sleep", "60").start().also { spawned.add(it) }
        RunArchive.create("run-elsewhere", "ws-abcd1234", root).write(
            RunRecord(
                id = "run-elsewhere",
                workflow = "release-cut",
                startedAt = Instant.now().toString(),
                finishedAt = Instant.now().toString().takeIf { status.isFinished },
                status = status.name,
                pid = owner.pid(),
                nodes =
                    listOf(
                        NodeRunRecord(
                            nodeId = "checks",
                            type = NodeType.SHELL,
                            status = status.name,
                            startedAt = Instant.now().toString(),
                        ),
                    ),
            ),
        )
        return owner
    }

    private suspend fun awaitAsked(notifier: RecordingNotifier) {
        withTimeout(10.seconds) {
            while (notifier.asked.isEmpty()) delay(20.milliseconds)
        }
    }

    private suspend fun awaitWaiting(
        run: WorkflowRun,
        nodeId: String,
    ): NodeRun =
        withTimeout(10.seconds) {
            var found = run.nodes.firstOrNull { it.nodeId == nodeId }
            while (found == null || found.status != RunStatus.WAITING) {
                delay(20.milliseconds)
                found = run.nodes.firstOrNull { it.nodeId == nodeId }
            }
            found
        }
}

private class RecordingNotifier : Notifier {
    val posted: MutableList<RunNotification> = Collections.synchronizedList(mutableListOf())
    val asked: MutableList<RunNotification> = Collections.synchronizedList(mutableListOf())
    val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun post(notification: RunNotification) {
        posted.add(notification)
    }

    override fun ask(
        notification: RunNotification,
        timeoutSeconds: Long,
        onAnswer: (String) -> Unit,
    ): NotificationHandle {
        asked.add(notification)
        return object : NotificationHandle {
            override fun cancel() {
                cancelled.add(notification.key)
            }
        }
    }

    override fun withdraw(key: String) {
        cancelled.add(key)
    }
}

private class RecordingExecutor(
    var fail: Set<String> = emptySet(),
    private val work: suspend () -> Unit = {},
    private val output: (String) -> String = { "$it-output" },
) : NodeExecutor {
    val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val prompts: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    private val running = AtomicInteger()
    val peak = AtomicInteger()

    override suspend fun execute(execution: NodeExecution) {
        val id = execution.node.id
        started.add(id)
        prompts[id] = execution.outputs.interpolate(execution.node.prompt).text

        peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            execution.run.status = RunStatus.RUNNING
            work()
        } finally {
            running.decrementAndGet()
        }

        execution.run.produce(output(id))
        if (id in fail) execution.run.finish(RunStatus.FAILED, 1) else execution.run.finish(RunStatus.SUCCEEDED, 0)
    }
}
