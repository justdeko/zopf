package com.dk.zopf.runtime

import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ShellRunnerTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-shell").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `stdout and stderr come back tagged with the exit code`() =
        runBlocking {
            val session =
                ShellRunner().start(
                    ShellInvocation("echo out; echo problem >&2; exit 3", tempDir()),
                )
            val lines = session.lines().toList()

            assertEquals(setOf(ShellLine("out", false), ShellLine("problem", true)), lines.toSet())
            assertEquals(3, session.awaitExit())
        }

    @Test
    fun `the command runs in the directory it was given`() =
        runBlocking {
            val dir = tempDir().toRealPath()
            val session = ShellRunner().start(ShellInvocation("pwd", dir))
            val lines = session.lines().toList()

            assertEquals(dir.toString(), lines.single().text)
        }

    @Test
    fun `stopping a running command kills it`() =
        runBlocking {
            val session = ShellRunner().start(ShellInvocation("sleep 30", tempDir()))
            session.stop()

            assertTrue(session.awaitExit() != 0)
        }

    @Test
    fun `a command reading stdin gets end-of-file`() =
        runBlocking {
            val session = ShellRunner().start(ShellInvocation("read -r answer; echo got=\$answer", tempDir()))
            val lines = session.lines().toList()

            assertEquals("got=", lines.single { !it.isError }.text)
            assertEquals(0, session.awaitExit())
        }
}

class NodeTimeoutTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-timeout").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a node past its deadline is killed and fails`() {
        val run =
            runWorkflow(
                Workflow(
                    name = "w",
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "slow",
                                type = NodeType.SHELL,
                                command = "sleep 60",
                                timeoutSeconds = 1,
                            ),
                        ),
                ),
            )

        val slow = assertNotNull(run.node("slow"))
        assertEquals(RunStatus.FAILED, slow.status)
        assertEquals(NODE_TIMEOUT_EXIT, slow.exitCode)
        assertTrue(slow.entries.any { it is ConsoleEntry.Notice && "Gave up after 1s" in it.text })
        assertTrue(slow.elapsed().seconds < 30, "it waited ${slow.elapsed()}")
    }

    @Test
    fun `a node inside its deadline is untouched`() {
        val run =
            runWorkflow(
                Workflow(
                    name = "w",
                    nodes =
                        listOf(WorkflowNode(id = "quick", type = NodeType.SHELL, command = "echo hi", timeoutSeconds = 30)),
                ),
            )

        assertEquals(RunStatus.SUCCEEDED, run.node("quick")?.status)
        assertEquals("hi", run.node("quick")?.output()?.result)
    }

    @Test
    fun `a workflow deadline applies to a node naming none`() {
        val run =
            runWorkflow(
                Workflow(
                    name = "w",
                    defaults = NodeDefaults(timeoutSeconds = 1),
                    nodes = listOf(WorkflowNode(id = "slow", type = NodeType.SHELL, command = "sleep 60")),
                ),
            )

        assertEquals(RunStatus.FAILED, run.node("slow")?.status)
    }

    private fun runWorkflow(workflow: Workflow): WorkflowRun {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        val engine = WorkflowEngine(scope, ProcessNodeExecutor(), tempDir())
        return runBlocking {
            val run = engine.start(workspace, workflow).getOrThrow()
            withTimeout(60.seconds) { run.job?.join() }
            run
        }
    }
}

class NodeProgressTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-progress").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a node that prints nothing still reads as running`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        val engine = WorkflowEngine(scope, ProcessNodeExecutor(), tempDir())

        runBlocking {
            val run =
                engine
                    .start(
                        workspace,
                        Workflow(
                            name = "w",
                            nodes = listOf(WorkflowNode(id = "quiet", type = NodeType.SHELL, command = "sleep 1")),
                        ),
                    ).getOrThrow()
            val quiet = assertNotNull(run.node("quiet"))

            withTimeout(30.seconds) {
                while (quiet.status == RunStatus.QUEUED || quiet.status == RunStatus.STARTING) delay(10)
            }
            assertEquals(RunStatus.RUNNING, quiet.status)

            withTimeout(30.seconds) { run.job?.join() }
            assertEquals(RunStatus.SUCCEEDED, quiet.status)
        }
    }
}
