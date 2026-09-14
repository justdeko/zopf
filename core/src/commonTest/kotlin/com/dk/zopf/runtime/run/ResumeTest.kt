package com.dk.zopf.runtime.run

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumeTest {
    private val root: Path = Files.createTempDirectory("zopf-resume")

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun node(
        id: String,
        status: String,
        type: NodeType = NodeType.SHELL,
        answer: String? = null,
    ) = NodeRunRecord(
        nodeId = id,
        type = type,
        status = status,
        startedAt = "2026-08-11T09:00:00Z",
        answer = answer,
    )

    private fun archived(
        id: String,
        nodes: List<NodeRunRecord>,
        workflow: String = "demo",
        status: String = "FAILED",
    ): RunRecord {
        val record =
            RunRecord(
                id = id,
                workflow = workflow,
                startedAt = "2026-08-11T09:00:00Z",
                finishedAt = "2026-08-11T09:05:00Z",
                status = status,
                nodes = nodes,
            )
        RunArchive.create(id, "ws", root).write(record)
        Thread.sleep(10)
        return record
    }

    private fun graph(
        nodes: List<String>,
        edges: List<Pair<String, String>> = emptyList(),
    ) = Workflow(
        name = "demo",
        nodes = nodes.map { WorkflowNode(id = it, type = NodeType.SHELL, command = "echo $it") },
        edges = edges.map { WorkflowEdge(it.first, it.second) },
    )

    @Test
    fun `last is the newest run`() {
        archived("older", listOf(node("a", "SUCCEEDED")))
        archived("newer", listOf(node("a", "SUCCEEDED")))

        assertEquals("newer", Resume.find(Resume.LATEST, root).getOrThrow().id)
    }

    @Test
    fun `an id is matched by its start`() {
        archived("4f6a21c8-lively", listOf(node("a", "SUCCEEDED")))

        assertEquals("4f6a21c8-lively", Resume.find("4f6a21c8", root).getOrThrow().id)
    }

    @Test
    fun `an ambiguous id names what it matched`() {
        archived("ab-one", listOf(node("a", "SUCCEEDED")))
        archived("ab-two", listOf(node("a", "SUCCEEDED")))

        val failure = Resume.find("ab", root).exceptionOrNull()

        assertContains(failure?.message.orEmpty(), "matches 2 runs")
        assertContains(failure?.message.orEmpty(), "ab-one")
    }

    @Test
    fun `an unknown id says where the ids come from`() {
        archived("known", listOf(node("a", "SUCCEEDED")))

        assertContains(
            Resume
                .find("nope", root)
                .exceptionOrNull()
                ?.message
                .orEmpty(),
            "zopf runs",
        )
    }

    @Test
    fun `an empty archive has nothing to resume`() {
        assertContains(
            Resume
                .find(Resume.LATEST, root)
                .exceptionOrNull()
                ?.message
                .orEmpty(),
            "No runs are archived yet",
        )
    }

    @Test
    fun `what succeeded carries over and the rest runs again`() {
        archived(
            "run",
            listOf(node("a", "SUCCEEDED"), node("b", "FAILED"), node("c", "SKIPPED")),
        )

        val point = Resume.pointFor(Resume.find("run", root).getOrThrow(), graph(listOf("a", "b", "c"), listOf("a" to "b", "b" to "c")))

        assertEquals(setOf("a"), point.carried.keys)
        assertEquals(listOf("b", "c"), point.redo)
    }

    @Test
    fun `a node that succeeded after a failed one runs again`() {
        archived(
            "run",
            listOf(node("a", "FAILED"), node("b", "SUCCEEDED")),
        )

        val point = Resume.pointFor(Resume.find("run", root).getOrThrow(), graph(listOf("a", "b"), listOf("a" to "b")))

        assertTrue(point.carried.isEmpty(), "b came after a, so it can't be trusted: ${point.carried.keys}")
    }

    @Test
    fun `a node added since the run takes everything after it with it`() {
        archived("run", listOf(node("a", "SUCCEEDED"), node("c", "SUCCEEDED")))

        val point =
            Resume.pointFor(
                Resume.find("run", root).getOrThrow(),
                graph(listOf("a", "b", "c"), listOf("a" to "b", "b" to "c")),
            )

        assertEquals(setOf("a"), point.carried.keys)
        assertEquals(listOf("b", "c"), point.redo)
    }

    @Test
    fun `a gate keeps the answer it was given`() {
        archived(
            "run",
            listOf(
                node("approve", "SUCCEEDED", type = NodeType.GATE, answer = "approved"),
                node("ship", "FAILED"),
            ),
        )

        val point =
            Resume.pointFor(
                Resume.find("run", root).getOrThrow(),
                Workflow(
                    name = "demo",
                    nodes =
                        listOf(
                            WorkflowNode(id = "approve", type = NodeType.GATE),
                            WorkflowNode(id = "ship", type = NodeType.SHELL, command = "echo ship"),
                        ),
                    edges = listOf(WorkflowEdge("approve", "ship")),
                ),
            )

        assertEquals("approved", point.carried["approve"]?.result)
    }

    @Test
    fun `a gate whose answer was never archived is asked again`() {
        archived(
            "run",
            listOf(
                node("approve", "SUCCEEDED", type = NodeType.GATE),
                node("ship", "FAILED"),
            ),
        )

        val point =
            Resume.pointFor(
                Resume.find("run", root).getOrThrow(),
                Workflow(
                    name = "demo",
                    nodes =
                        listOf(
                            WorkflowNode(id = "approve", type = NodeType.GATE),
                            WorkflowNode(id = "ship", type = NodeType.SHELL, command = "echo ship"),
                        ),
                    edges = listOf(WorkflowEdge("approve", "ship")),
                ),
            )

        assertTrue(point.carried.isEmpty(), "an answer zopf can't read back isn't one it can carry: ${point.carried}")
    }

    @Test
    fun `a run something else is still driving is left alone`() {
        val owner = ProcessBuilder("/bin/sleep", "30").start()
        try {
            RunArchive.create("live", "ws", root).write(
                RunRecord(
                    id = "live",
                    workflow = "demo",
                    startedAt = Instant.now().toString(),
                    status = "RUNNING",
                    pid = owner.pid(),
                    nodes = listOf(node("a", "RUNNING")),
                ),
            )

            val found = Resume.find("live", root)

            assertNull(found.getOrNull(), "a run with a live owner isn't ours to resume")
            assertContains(found.exceptionOrNull()?.message.orEmpty(), "still running somewhere else")
        } finally {
            owner.destroyForcibly()
        }
    }
}
