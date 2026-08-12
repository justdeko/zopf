package com.dk.zopf.model

private val NODE_ID = Regex("[A-Za-z0-9_-]+")

fun isValidNodeId(id: String): Boolean = NODE_ID.matches(id)

val NodeType.idStem: String get() = serialName

val NodeType.label: String
    get() =
        when (this) {
            NodeType.AGENT -> "Agent"
            NodeType.SHELL -> "Shell"
            NodeType.CONNECTOR -> "Connector"
            NodeType.GATE -> "Gate"
            NodeType.BRANCH -> "Branch"
            NodeType.INPUT -> "Input"
        }

fun NodeType.canFail(): Boolean = this !in setOf(NodeType.GATE, NodeType.BRANCH, NodeType.INPUT)

val NodeType.blurb: String
    get() =
        when (this) {
            NodeType.AGENT -> "Run a headless agent session"
            NodeType.SHELL -> "Run a command in your login shell"
            NodeType.CONNECTOR -> "Call a connector script"
            NodeType.GATE -> "Wait for you to approve"
            NodeType.BRANCH -> "Take one path or the other"
            NodeType.INPUT -> "Ask you for a value"
        }

fun uniqueNodeId(
    stem: String,
    taken: Set<String>,
): String {
    if (stem !in taken) return stem
    var suffix = 2
    while ("$stem-$suffix" in taken) suffix++
    return "$stem-$suffix"
}

fun Workflow.addNode(
    type: NodeType,
    position: Position? = null,
): Pair<Workflow, WorkflowNode> {
    val node =
        WorkflowNode(
            id = uniqueNodeId(type.idStem, nodes.mapTo(mutableSetOf()) { it.id }),
            type = type,
            position = position,
        )
    return copy(nodes = nodes + node) to node
}

private const val DUPLICATE_OFFSET = 36f

fun Workflow.duplicateNode(id: String): Pair<Workflow, WorkflowNode>? {
    val original = node(id) ?: return null
    val clone =
        original.copy(
            id = uniqueNodeId(original.id, nodes.mapTo(mutableSetOf()) { it.id }),
            title = if (original.title.isBlank()) "" else "${original.title} copy",
            position = original.position?.let { Position(it.x + DUPLICATE_OFFSET, it.y + DUPLICATE_OFFSET) },
        )
    return copy(nodes = nodes + clone) to clone
}

fun Workflow.replaceNode(node: WorkflowNode): Workflow = copy(nodes = nodes.map { if (it.id == node.id) node else it })

fun Workflow.removeNode(id: String): Workflow =
    copy(
        nodes = nodes.filterNot { it.id == id },
        edges = edges.filterNot { it.from == id || it.to == id },
    )

fun Workflow.renameNode(
    from: String,
    to: String,
): Result<Workflow> {
    if (from == to) return Result.success(this)
    if (!isValidNodeId(to)) {
        return Result.failure(IllegalArgumentException("Ids can use letters, digits, - and _ only"))
    }
    if (node(from) == null) return Result.failure(IllegalArgumentException("No node called \"$from\""))
    if (node(to) != null) return Result.failure(IllegalStateException("\"$to\" is already taken"))

    return Result.success(
        copy(
            nodes =
                nodes.map { node ->
                    val renamed = if (node.id == from) node.copy(id = to) else node
                    renamed.copy(
                        prompt = NodeRefs.rename(renamed.prompt, from, to),
                        command = NodeRefs.rename(renamed.command, from, to),
                        expression = NodeRefs.rename(renamed.expression, from, to),
                        inputs = renamed.inputs.mapValues { (_, v) -> NodeRefs.rename(v, from, to) },
                    )
                },
            edges =
                edges.map {
                    it.copy(
                        from = if (it.from == from) to else it.from,
                        to = if (it.to == from) to else it.to,
                    )
                },
        ),
    )
}

fun Workflow.withPositions(positions: Map<String, Position>): Workflow = copy(nodes = nodes.map { node -> node.copy(position = positions[node.id]) })

fun Workflow.positions(): Map<String, Position> = nodes.mapNotNull { node -> node.position?.let { node.id to it } }.toMap()

fun Workflow.addRepo(
    id: String,
    path: String,
): Result<Workflow> {
    val trimmed = id.trim()
    if (!isValidNodeId(trimmed)) {
        return Result.failure(IllegalArgumentException("Repo ids can use letters, digits, - and _ only"))
    }
    if (repos.any { it.id == trimmed }) {
        return Result.failure(IllegalStateException("\"$trimmed\" is already declared"))
    }
    return Result.success(copy(repos = repos + RepoRef(trimmed, path.trim())))
}

fun Workflow.removeRepo(id: String): Workflow =
    copy(
        repos = repos.filterNot { it.id == id },
        defaults = if (defaults.repo == id) defaults.copy(repo = null) else defaults,
    )

fun Workflow.ancestorsOf(id: String): Set<String> {
    val incoming = edges.groupBy({ it.to }, { it.from })
    val seen = mutableSetOf<String>()
    val pending = ArrayDeque(incoming[id].orEmpty())
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (seen.add(current)) pending.addAll(incoming[current].orEmpty())
    }
    return seen
}

fun Workflow.descendantsOf(id: String): Set<String> {
    val outgoing = edges.groupBy({ it.from }, { it.to })
    val seen = mutableSetOf<String>()
    val pending = ArrayDeque(outgoing[id].orEmpty())
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (seen.add(current)) pending.addAll(outgoing[current].orEmpty())
    }
    return seen
}

fun Workflow.outgoingEdges(id: String): List<WorkflowEdge> = edges.filter { it.from == id }

fun Workflow.incomingEdges(id: String): List<WorkflowEdge> = edges.filter { it.to == id }

fun Workflow.duplicateNodeIds(): List<String> =
    nodes
        .groupingBy { it.id }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .toList()

fun Workflow.runOrder(): List<List<String>> {
    val known = nodes.map { it.id }.distinct()
    val real = edges.filter { it.from in known && it.to in known && it.from != it.to }
    val incoming = real.groupingBy { it.to }.eachCount().toMutableMap()
    val outgoing = real.groupBy { it.from }

    val waves = mutableListOf<List<String>>()
    var wave = known.filter { incoming[it] == null }
    while (wave.isNotEmpty()) {
        waves += wave
        val next = mutableListOf<String>()
        wave.forEach { id ->
            outgoing[id].orEmpty().forEach { edge ->
                val left = incoming.getValue(edge.to) - 1
                incoming[edge.to] = left
                if (left == 0) next += edge.to
            }
        }
        wave = next.distinct()
    }
    return waves
}

fun Workflow.unreachableFromStart(): List<String> {
    val reachable = runOrder().flatten().toSet()
    return nodes.map { it.id }.distinct().filterNot { it in reachable }
}
