package com.dk.zopf.ui.editor

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.ui.AnchorSide
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowGraphTest {
    private val chain =
        Workflow(
            name = "w",
            nodes =
                listOf(
                    WorkflowNode("analyze", NodeType.AGENT),
                    WorkflowNode("fix", NodeType.AGENT),
                    WorkflowNode("build", NodeType.SHELL),
                ),
            edges = listOf(WorkflowEdge("analyze", "fix"), WorkflowEdge("fix", "build")),
        )

    @Test
    fun `the kuiver graph carries the ids and edges and nothing else`() {
        val graph = chain.toKuiver()

        assertEquals(setOf("analyze", "fix", "build"), graph.nodes.keys)
        assertEquals(
            setOf("analyze" to "fix", "fix" to "build"),
            graph.edges.map { it.fromId to it.toId }.toSet(),
        )
    }

    @Test
    fun `every edge leaves the right of a card and arrives at the left`() {
        val graph = chain.toKuiver()

        assertTrue(graph.edges.all { it.fromAnchor == OutAnchor && it.toAnchor == InAnchor })
        assertEquals(AnchorSide.RIGHT, AnchorSide.fromAnchorId(OutAnchor))
        assertEquals(AnchorSide.LEFT, AnchorSide.fromAnchorId(InAnchor))
    }

    @Test
    fun `a vertical layout names the top and bottom of a card instead`() {
        val graph = chain.toKuiver(LayoutDirection.VERTICAL)

        assertTrue(graph.edges.all { it.fromAnchor == BottomAnchor && it.toAnchor == TopAnchor })
        assertEquals(AnchorSide.BOTTOM, AnchorSide.fromAnchorId(BottomAnchor))
        assertEquals(AnchorSide.TOP, AnchorSide.fromAnchorId(TopAnchor))
    }

    @Test
    fun `the shape of a graph is how deep it runs and how wide its widest level is`() {
        assertEquals(GraphShape(depth = 2, breadth = 1), chain.graphShape())

        val diamond =
            Workflow(
                name = "w",
                nodes = listOf("a", "b", "c", "d").map { WorkflowNode(it, NodeType.SHELL) },
                edges =
                    listOf(
                        WorkflowEdge("a", "b"),
                        WorkflowEdge("a", "c"),
                        WorkflowEdge("b", "d"),
                        WorkflowEdge("c", "d"),
                    ),
            )
        assertEquals(GraphShape(depth = 2, breadth = 2), diamond.graphShape())

        val skewed = diamond.copy(edges = diamond.edges + WorkflowEdge("b", "c"))
        assertEquals(GraphShape(depth = 3, breadth = 1), skewed.graphShape())

        assertEquals(GraphShape(depth = 0, breadth = 0), Workflow(name = "empty").graphShape())
    }

    @Test
    fun `an unconnected node lands on a level of its own`() {
        val stray = chain.copy(nodes = chain.nodes + WorkflowNode("notes", NodeType.GATE))

        assertEquals(GraphShape(depth = 3, breadth = 1), stray.graphShape())
    }

    @Test
    fun `an edge that would close a loop is refused`() {
        val result = chain.connect("build", "analyze")

        assertTrue(result.isFailure)

        assertEquals(2, chain.edges.size)
    }

    @Test
    fun `self edges and duplicates are refused`() {
        assertTrue(chain.connect("analyze", "analyze").isFailure)
        assertTrue(chain.connect("analyze", "fix").isFailure)
        assertTrue(chain.connect("nope", "fix").isFailure)
    }

    @Test
    fun `a legal edge lands with no condition, including one that shortcuts across the chain`() {
        val updated = chain.connect("analyze", "build").getOrThrow()

        assertTrue(updated.hasEdge("analyze", "build"))
        assertEquals(3, updated.edges.size)
        assertEquals(null, updated.edges.first { it.from == "analyze" && it.to == "build" }.condition)
    }

    @Test
    fun `a branch labels its first two edges true then false, and stops guessing after that`() {
        val w =
            Workflow(
                name = "w",
                nodes =
                    listOf(
                        WorkflowNode("ok", NodeType.BRANCH),
                        WorkflowNode("ship", NodeType.AGENT),
                        WorkflowNode("stop", NodeType.AGENT),
                        WorkflowNode("log", NodeType.SHELL),
                    ),
            )

        val first = w.connect("ok", "ship").getOrThrow()
        val second = first.connect("ok", "stop").getOrThrow()
        val third = second.connect("ok", "log").getOrThrow()

        assertEquals(true, third.edges.first { it.to == "ship" }.condition)
        assertEquals(false, third.edges.first { it.to == "stop" }.condition)
        assertEquals(null, third.edges.first { it.to == "log" }.condition)
    }

    @Test
    fun `connectable targets exclude the source, existing edges and anything upstream`() {
        assertEquals(emptySet(), chain.connectableTargets("build"))
        assertEquals(setOf("build"), chain.connectableTargets("analyze"))
    }

    @Test
    fun `setting and clearing an edge condition touches only that edge`() {
        val labelled = chain.setEdgeCondition("analyze", "fix", true)

        assertEquals(true, labelled.edges.first { it.to == "fix" }.condition)
        assertEquals(null, labelled.edges.first { it.to == "build" }.condition)
        assertEquals(
            null,
            labelled
                .setEdgeCondition("analyze", "fix", null)
                .edges
                .first { it.to == "fix" }
                .condition,
        )
    }

    @Test
    fun `disconnecting leaves the nodes in place`() {
        val updated = chain.disconnect("analyze", "fix")

        assertFalse(updated.hasEdge("analyze", "fix"))
        assertEquals(3, updated.nodes.size)
    }

    @Test
    fun `positions survive the trip through kuiver's dp offsets`() {
        val position = Position(40.5f, 120.25f)

        assertEquals(position, position.toDpOffset().toPosition())
    }
}

class EditorCanvasTest {
    private val card = DpSize(260.dp, 90.dp)
    private val landscape = DpSize(1400.dp, 860.dp)
    private val portrait = DpSize(760.dp, 1100.dp)

    private fun direction(
        canvas: DpSize,
        depth: Int,
        breadth: Int,
    ) = preferredDirection(canvas, GraphShape(depth, breadth), card)

    @Test
    fun `a graph that fits, or nearly fits, is never turned`() {
        assertEquals(LayoutDirection.HORIZONTAL, direction(landscape, depth = 0, breadth = 1))
        assertEquals(LayoutDirection.HORIZONTAL, direction(landscape, depth = 2, breadth = 2))

        assertEquals(LayoutDirection.HORIZONTAL, direction(landscape, depth = 3, breadth = 1))

        assertEquals(LayoutDirection.HORIZONTAL, direction(landscape, depth = 4, breadth = 1))
    }

    @Test
    fun `a long chain in a landscape window is turned on its side rather than shrunk`() {
        assertEquals(LayoutDirection.VERTICAL, direction(landscape, depth = 8, breadth = 1))
    }

    @Test
    fun `a portrait window turns a graph that a landscape one would have left alone`() {
        assertEquals(LayoutDirection.HORIZONTAL, direction(landscape, depth = 4, breadth = 1))
        assertEquals(LayoutDirection.VERTICAL, direction(portrait, depth = 4, breadth = 1))
    }

    @Test
    fun `a wide fan stays horizontal even in a portrait window`() {
        assertEquals(LayoutDirection.HORIZONTAL, direction(portrait, depth = 1, breadth = 8))
    }

    @Test
    fun `an empty graph has no shape to pick a direction from`() {
        assertEquals(LayoutDirection.HORIZONTAL, direction(landscape, depth = 0, breadth = 0))
        assertEquals(LayoutDirection.HORIZONTAL, direction(DpSize(0.dp, 0.dp), depth = 6, breadth = 1))
    }
}
