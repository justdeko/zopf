package com.dk.zopf.runtime.run

import com.dk.zopf.model.NodeType
import com.dk.zopf.runtime.PendingQuestion
import com.dk.zopf.runtime.agent.PendingPermission
import com.dk.zopf.runtime.agent.PermissionRequest
import com.dk.zopf.util.tokens
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NodeRunStateTest {
    private val running =
        NodeRunState(
            status = RunStatus.RUNNING,
            sessionId = "s-1",
            model = "opus",
            costUsd = 0.25,
            tokens = 900,
        )

    @Test
    fun `an observation leaves out what it doesn't carry`() {
        val rows =
            listOf(
                "cost" to (running.observed(costUsd = 0.5) to running.copy(costUsd = 0.5)),
                "model" to (running.observed(model = "sonnet") to running.copy(model = "sonnet")),
                "session" to (running.observed(sessionId = "s-2") to running.copy(sessionId = "s-2")),
                "tokens" to (running.observed(tokens = 12) to running.copy(tokens = 12)),
                "nothing" to (running.observed() to running),
            )

        rows.forEach { (carried, states) ->
            assertEquals(states.second, states.first, carried)
        }
    }

    @Test
    fun `launching keeps the session it wasn't given`() {
        val launched = running.launched(sessionId = null, command = listOf("claude", "-p"))

        assertEquals("s-1", launched.sessionId)
        assertEquals("opus", launched.model)
        assertEquals(listOf("claude", "-p"), launched.command)
        assertEquals(RunStatus.RUNNING, launched.status)
    }

    @Test
    fun `a settled node forgets what it was waiting on`() {
        val asked = running.asking(question()).permissionAsked(permission())

        val rows =
            listOf(
                "finished" to asked.finished(RunStatus.SUCCEEDED, exitCode = 0, at = Instant.EPOCH),
                "restored" to asked.restored(RunStatus.STOPPED),
            )

        rows.forEach { (how, settled) ->
            assertNull(settled.pendingQuestion, how)
            assertNull(settled.pendingPermission, how)
        }
    }

    @Test
    fun `a finished node keeps the exit code it already had`() {
        val exited = running.copy(exitCode = 2)
        val finished = exited.finished(RunStatus.FAILED, exitCode = null, at = Instant.EPOCH)

        assertEquals(2, finished.exitCode)
        assertEquals(Instant.EPOCH, finished.finishedAt)
    }

    @Test
    fun `an answered permission resumes only a node that was waiting`() {
        val rows =
            listOf(
                RunStatus.WAITING to RunStatus.RUNNING,
                RunStatus.RUNNING to RunStatus.RUNNING,
                RunStatus.DETACHED to RunStatus.DETACHED,
                RunStatus.FAILED to RunStatus.FAILED,
            )

        rows.forEach { (before, after) ->
            val answered = running.copy(status = before).permissionAnswered()
            assertEquals(after, answered.status, "$before")
            assertNull(answered.pendingPermission, "$before")
        }
    }

    @Test
    fun `an answered question leaves the status alone`() {
        val asked = running.asking(question())
        assertEquals(RunStatus.WAITING, asked.status)

        val answered = asked.copy(status = RunStatus.RUNNING).answered()

        assertNull(answered.pendingQuestion)
        assertEquals(RunStatus.RUNNING, answered.status)
    }

    @Test
    fun `an archived node takes every field from the record`() {
        val archived =
            running.archived(
                status = RunStatus.SUCCEEDED,
                sessionId = null,
                model = null,
                costUsd = null,
                exitCode = 0,
                command = listOf("zsh", "-lc", "true"),
                finishedAt = Instant.EPOCH,
            )

        assertNull(archived.sessionId)
        assertNull(archived.model)
        assertNull(archived.costUsd)
        assertEquals(listOf("zsh", "-lc", "true"), archived.command)
    }

    private fun question(): PendingQuestion = PendingQuestion("Which one?", listOf("a", "b"), "a")

    private fun permission(): PendingPermission =
        PendingPermission(
            request =
                PermissionRequest(
                    toolName = "Bash",
                    summary = "rm -rf build",
                    toolUseId = "t-1",
                    sessionId = "s-1",
                ),
            answer = CompletableFuture(),
        )
}

class WorkflowRunStateTest {
    private fun run(): WorkflowRun =
        WorkflowRun(id = "r-1", workflowName = "pace", workspaceRoot = null, isInteractive = true)
            .apply { add(node()) }

    @Test
    fun `a node change is a new state for the run`() {
        val run = run()
        val before = run.state.value

        run.nodes.single().update { copy(status = RunStatus.RUNNING) }

        assertNotEquals(before, run.state.value)
        assertEquals(RunStatus.RUNNING, run.status)
    }

    @Test
    fun `a node that settles on the status it had leaves the run alone`() {
        val run = run()
        run.nodes.single().update { copy(status = RunStatus.RUNNING) }
        val before = run.state.value

        run.nodes.single().update { copy(status = RunStatus.RUNNING) }

        assertEquals(before, run.state.value)
    }

    @Test
    fun `a node and its run hold the same state`() {
        val run = run()
        val node = run.nodes.single()

        node.update { launched(sessionId = "s-1", command = listOf("zsh", "-lc", "true")) }
        node.update { observed(costUsd = 0.5) }
        node.update { finished(RunStatus.SUCCEEDED, exitCode = 0, at = Instant.EPOCH) }

        assertEquals(node.state.value, run.state.value.of(node))
    }

    private fun node(): NodeRun =
        NodeRun(
            id = "r-1:build",
            workflowName = "pace",
            nodeId = "build",
            nodeTitle = "Build",
            nodeType = NodeType.SHELL,
            cwd = Paths.get("/tmp"),
        )
}
