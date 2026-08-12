package com.dk.zopf.runtime

import com.dk.zopf.model.NodeRefs
import java.util.concurrent.ConcurrentHashMap

data class NodeOutput(
    val result: String = "",
    val exitCode: Int? = null,
    val sessionId: String? = null,
    val costUsd: Double? = null,
    val extras: Map<String, String> = emptyMap(),
) {
    fun field(name: String): String? =
        when (name) {
            "result" -> result
            "exitCode" -> exitCode?.toString()
            "sessionId" -> sessionId
            "costUsd" -> costUsd?.toString()
            else -> extras[name]
        }
}

data class Interpolated(
    val text: String,
    val unresolved: List<String>,
) {
    val isComplete: Boolean get() = unresolved.isEmpty()
}

class RunContext(
    outputs: Map<String, NodeOutput> = emptyMap(),
) {
    private val outputs = ConcurrentHashMap(outputs)

    fun record(
        nodeId: String,
        output: NodeOutput,
    ) {
        outputs[nodeId] = output
    }

    operator fun get(nodeId: String): NodeOutput? = outputs[nodeId]

    fun interpolate(text: String): Interpolated {
        val unresolved = mutableListOf<String>()
        val result =
            NodeRefs.PATTERN.replace(text) { match ->
                val (nodeId, field) = match.destructured
                outputs[nodeId]?.field(field) ?: match.value.also { unresolved += "$nodeId.$field" }
            }
        return Interpolated(result, unresolved)
    }
}
