package com.dk.zopf.ui.editor

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.outgoingEdges

const val InAnchor = "left"
const val OutAnchor = "right"
const val TopAnchor = "top"
const val BottomAnchor = "bottom"

fun LayoutDirection.entryAnchor(): String = if (this == LayoutDirection.VERTICAL) TopAnchor else InAnchor

fun LayoutDirection.exitAnchor(): String = if (this == LayoutDirection.VERTICAL) BottomAnchor else OutAnchor

fun Workflow.toKuiver(direction: LayoutDirection = LayoutDirection.HORIZONTAL): Kuiver =
    Kuiver()
        .withNodes(nodes.map { KuiverNode(id = it.id) })
        .withEdges(
            edges.map {
                KuiverEdge(
                    fromId = it.from,
                    toId = it.to,
                    fromAnchor = direction.exitAnchor(),
                    toAnchor = direction.entryAnchor(),
                )
            },
        )

data class GraphShape(
    val depth: Int,
    val breadth: Int,
)

fun Workflow.graphShape(): GraphShape {
    if (nodes.isEmpty()) return GraphShape(depth = 0, breadth = 0)
    val levels = levels()
    return GraphShape(
        depth = levels.values.max(),
        breadth =
            levels.values
                .groupingBy { it }
                .eachCount()
                .values
                .max(),
    )
}

private fun Workflow.levels(): Map<String, Int> {
    val children = edges.groupBy({ it.from }, { it.to })
    val pending = nodes.associate { node -> node.id to edges.count { it.to == node.id } }.toMutableMap()
    val level = nodes.associate { it.id to 0 }.toMutableMap()
    val ready = ArrayDeque(pending.filterValues { it == 0 }.keys)

    while (ready.isNotEmpty()) {
        val id = ready.removeFirst()
        children[id]?.forEach { child ->
            if (child !in level) return@forEach
            level[child] = maxOf(level.getValue(child), level.getValue(id) + 1)
            val left = pending.getValue(child) - 1
            pending[child] = left
            if (left == 0) ready.addLast(child)
        }
    }

    val lastConnected = level.values.maxOrNull() ?: 0
    val connected = edges.flatMapTo(mutableSetOf()) { listOf(it.from, it.to) }
    nodes.forEach { if (it.id !in connected) level[it.id] = lastConnected + 1 }
    return level
}

fun Workflow.connect(
    from: String,
    to: String,
): Result<Workflow> {
    if (from == to) return Result.failure(IllegalArgumentException("A node can't feed itself"))
    val fromNode = node(from) ?: return Result.failure(IllegalArgumentException("No node called \"$from\""))
    if (node(to) == null) return Result.failure(IllegalArgumentException("No node called \"$to\""))
    if (hasEdge(from, to)) return Result.failure(IllegalStateException("Those are already connected"))
    if (toKuiver().wouldCreateCycle(from, to)) {
        return Result.failure(IllegalStateException("That would make a loop, and a workflow has to finish"))
    }

    val condition =
        if (fromNode.type == NodeType.BRANCH) {
            val taken = outgoingEdges(from).mapNotNull { it.condition }.toSet()
            if (true !in taken) {
                true
            } else if (false !in taken) {
                false
            } else {
                null
            }
        } else {
            null
        }
    return Result.success(copy(edges = edges + WorkflowEdge(from, to, condition)))
}

fun Workflow.disconnect(
    from: String,
    to: String,
): Workflow = copy(edges = edges.filterNot { it.from == from && it.to == to })

fun Workflow.setEdgeCondition(
    from: String,
    to: String,
    condition: Boolean?,
): Workflow =
    copy(
        edges = edges.map { if (it.from == from && it.to == to) it.copy(condition = condition) else it },
    )

fun Workflow.setEdgeTrigger(
    from: String,
    to: String,
    on: EdgeTrigger,
): Workflow =
    copy(
        edges = edges.map { if (it.from == from && it.to == to) it.copy(on = on) else it },
    )

fun Workflow.connectOrder(from: String): List<String> {
    val targets = connectableTargets(from)
    return nodes.map { it.id }.filter { it in targets }
}

fun Workflow.connectableTargets(from: String): Set<String> {
    val graph = toKuiver()
    return nodes
        .asSequence()
        .map { it.id }
        .filter { it != from && !hasEdge(from, it) && !graph.wouldCreateCycle(from, it) }
        .toSet()
}

fun KuiverViewerState.persistablePositions(workflow: Workflow): Map<String, Position>? {
    if (!hasFittedInitially) return null
    return workflow.nodes
        .mapNotNull { node ->
            manualPositions[node.id]?.let { node.id to it.toPosition() }
        }.toMap()
}

fun KuiverViewerState.applyPositions(workflow: Workflow) {
    workflow.nodes.forEach { node ->
        node.position?.let { moveNode(node.id, it.toDpOffset()) }
    }
}

fun KuiverViewerState.fitGraph() {
    centerGraph(animated = false)
    if (scale > 1f) updateTransform(scale = 1f, offset = offset)
}

fun Position.toDpOffset(): DpOffset = DpOffset(x.dp, y.dp)

fun DpOffset.toPosition(): Position = Position(x.value, y.value)
