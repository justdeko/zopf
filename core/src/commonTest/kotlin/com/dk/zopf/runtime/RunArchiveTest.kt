package com.dk.zopf.runtime

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RunHistoryTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-history").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a finished run comes back with its verdict and output`() {
        val archiveRoot = tempDir()
        val original = runOnce(archiveRoot)

        val restored = RunHistory.list(archiveRoot).single()

        assertEquals(original.id, restored.id)
        assertEquals(RunStatus.SUCCEEDED, restored.status)
        assertEquals(listOf("first", "second"), restored.nodes.map { it.nodeId })
        assertTrue(restored.fromArchive)
        assertNotNull(restored.finishedAt)

        RunHistory.loadTranscript(restored)
        val output = restored.nodes.first { it.nodeId == "first" }.entries
        assertTrue(
            output.any { it is ConsoleEntry.Output && it.text == "hello" },
            "the transcript should hold what the node printed, got ${output.size} entries",
        )
    }

    @Test
    fun `replaying a transcript leaves the verdict alone`() {
        val archiveRoot = tempDir()
        runOnce(archiveRoot)

        val restored = RunHistory.list(archiveRoot).single()
        RunHistory.loadTranscript(restored)

        assertTrue(restored.nodes.all { it.status == RunStatus.SUCCEEDED }, restored.nodes.map { it.status }.toString())
        assertEquals(RunStatus.SUCCEEDED, restored.status)
    }

    @Test
    fun `loading twice does not repeat the transcript`() {
        val archiveRoot = tempDir()
        runOnce(archiveRoot)
        val restored = RunHistory.list(archiveRoot).single()

        RunHistory.loadTranscript(restored)
        val once = restored.nodes.sumOf { it.entries.size }
        RunHistory.loadTranscript(restored)

        assertEquals(once, restored.nodes.sumOf { it.entries.size })
    }

    @Test
    fun `deleting an archived run removes it from disk`() {
        val archiveRoot = tempDir()
        runOnce(archiveRoot)
        val restored = RunHistory.list(archiveRoot).single()

        RunArchive.delete(assertNotNull(restored.archiveDir))

        assertEquals(emptyList(), RunHistory.list(archiveRoot))
    }

    private fun runOnce(archiveRoot: Path): WorkflowRun {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        val engine = WorkflowEngine(scope, ProcessNodeExecutor(), archiveRoot)
        val workflow =
            Workflow(
                name = "history",
                nodes =
                    listOf(
                        WorkflowNode("first", NodeType.SHELL, command = "echo hello"),
                        WorkflowNode("second", NodeType.SHELL, command = "echo world"),
                    ),
                edges = listOf(WorkflowEdge("first", "second")),
            )
        return runBlocking {
            val run = engine.start(workspace, workflow).getOrThrow()
            withTimeout(30.seconds) { run.job?.join() }
            run
        }
    }
}

class SessionReconcilerTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-reconcile").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private val realOutput =
        """
        [
          {
            "pid": 89984,
            "cwd": "/Users/someone/dev/zopf",
            "kind": "interactive",
            "startedAt": 1785963845556,
            "sessionId": "e599a0c7-0073-4fdf-bac9-7339ba91bf16",
            "name": "zopf-cb",
            "status": "busy",
            "somethingAddedInAFutureRelease": true
          },
          {
            "pid": 91634,
            "cwd": "/Users/someone/dev/other",
            "kind": "interactive",
            "startedAt": 1785965384291,
            "sessionId": "2d7d9141-42cd-481a-910c-f7ba94251f84",
            "name": "cdg-15",
            "status": "idle"
          }
        ]
        """.trimIndent()

    @Test
    fun `the agent list parses and ignores unknown fields`() {
        val agents = SessionReconciler.parse(realOutput)

        assertEquals(2, agents.size)
        assertEquals("e599a0c7-0073-4fdf-bac9-7339ba91bf16", agents.first().sessionId)
        assertEquals("/Users/someone/dev/zopf", agents.first().cwd)
        assertEquals(89984, agents.first().pid)
    }

    @Test
    fun `an unparseable agent list does not fail the launch`() {
        assertTrue(SessionReconciler.parse("").isEmpty())
        assertTrue(SessionReconciler.parse("not json at all").isEmpty())
        assertTrue(SessionReconciler.parse("""{"error":"nope"}""").isEmpty())
    }

    @Test
    fun `a live session comes back as a detached run`() {
        val root = tempDir()
        write(root, unfinishedRecord(sessionId = "session-1"))

        val found = SessionReconciler.reconcile(root, listOf(LiveAgent(sessionId = "session-1")))

        assertEquals(1, found.orphans.size)
        assertEquals(0, found.closed)
        val run = found.orphans.single()
        assertEquals(RunStatus.DETACHED, run.status)
        val node = assertNotNull(run.node("analyze"))
        assertTrue(node.canTakeOver, "an orphan is only worth showing if it can be resumed")
        assertEquals("session-1", node.sessionId)
    }

    @Test
    fun `a dead session is closed out and stays closed`() {
        val root = tempDir()
        write(root, unfinishedRecord(sessionId = "session-1"))

        val first = SessionReconciler.reconcile(root, agents = emptyList())
        assertTrue(first.orphans.isEmpty())
        assertEquals(1, first.closed)

        val record = assertNotNull(RunArchive.all(root).single().read())
        assertEquals(RunStatus.STOPPED.name, record.status)
        assertEquals(RunStatus.STOPPED.name, record.nodes.single().status)
        assertNotNull(record.finishedAt)

        assertEquals(Reconciliation(), SessionReconciler.reconcile(root, agents = emptyList()))
    }

    @Test
    fun `a finished run is left alone`() {
        val root = tempDir()
        write(root, unfinishedRecord(sessionId = "session-1").copy(status = RunStatus.SUCCEEDED.name))

        assertEquals(Reconciliation(), SessionReconciler.reconcile(root, agents = emptyList()))
    }

    @Test
    fun `a live session zopf never started is not adopted`() {
        val root = tempDir()
        write(root, unfinishedRecord(sessionId = "ours"))

        val found = SessionReconciler.reconcile(root, listOf(LiveAgent(sessionId = "someone-elses")))

        assertTrue(found.orphans.isEmpty())
        assertEquals(1, found.closed)
    }

    private fun write(
        root: Path,
        record: RunRecord,
    ) {
        RunArchive.create(record.id, "ws-abcd1234", root).write(record)
    }

    private fun unfinishedRecord(sessionId: String) =
        RunRecord(
            id = "run-$sessionId",
            workflow = "refactor-api",
            workspace = "/Users/someone/zopf",
            startedAt = Instant.now().toString(),
            status = RunStatus.RUNNING.name,
            nodes =
                listOf(
                    NodeRunRecord(
                        nodeId = "analyze",
                        type = NodeType.AGENT,
                        status = RunStatus.RUNNING.name,
                        startedAt = Instant.now().toString(),
                        cwd = "/Users/someone/dev/zopf",
                        sessionId = sessionId,
                    ),
                ),
        )
}

class RunArchiveTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-archive").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `raw stream lines are mirrored verbatim`() {
        val archive = RunArchive.create("run-1", "ws-abcd1234", tempDir())
        val lines =
            listOf(
                """{"type":"system","subtype":"init"}""",
                """{"type":"whatever_comes_next"}""",
            )
        lines.forEach { archive.appendRaw("analyze", it) }
        archive.close()

        assertEquals(lines, archive.rawPath("analyze").readLines())
    }

    @Test
    fun `the record round trips`() {
        val archive = RunArchive.create("run-2", "ws-abcd1234", tempDir())
        val record =
            RunRecord(
                id = "run-2",
                workflow = "refactor-api",
                workspace = "/Users/someone/zopf",
                startedAt = "2026-08-05T21:06:10Z",
                finishedAt = "2026-08-05T21:06:14Z",
                status = "SUCCEEDED",
                nodes =
                    listOf(
                        NodeRunRecord(
                            nodeId = "analyze",
                            type = NodeType.AGENT,
                            status = "SUCCEEDED",
                            startedAt = "2026-08-05T21:06:10Z",
                            sessionId = "537e8539-45a8-4b4d-8675-fe918475e247",
                            command = listOf("claude", "-p"),
                            costUsd = 0.0712,
                        ),
                    ),
            )
        archive.write(record)

        assertEquals(record, RunArchive(archive.dir).read())
    }

    @Test
    fun `two workspaces of the same name get separate directories`() {
        val a = RunArchive.workspaceId(tempDir().resolve("alpha/.zopf").createDirectories())
        val b = RunArchive.workspaceId(tempDir().resolve("beta/.zopf").createDirectories())

        assertNotEquals(a, b)
        assertTrue(a.startsWith("zopf-"), a)
    }

    @Test
    fun `an unsafe node id still gets a file`() {
        val archive = RunArchive.create("run-3", "ws", tempDir())
        archive.appendRaw("../escape", "line")
        archive.close()

        assertEquals(archive.dir, archive.rawPath("../escape").parent)
    }
}
