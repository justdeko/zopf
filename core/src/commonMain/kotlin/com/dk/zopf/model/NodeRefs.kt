package com.dk.zopf.model

object NodeRefs {
    private const val IDENT = "[A-Za-z0-9_-]+"

    val PATTERN = Regex("""\$\{($IDENT)\.($IDENT)}""")

    fun reference(
        nodeId: String,
        field: String,
    ): String = "\${$nodeId.$field}"

    fun referencedNodeIds(text: String): Set<String> = PATTERN.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1] }

    fun references(text: String): Set<Pair<String, String>> = PATTERN.findAll(text).mapTo(mutableSetOf()) { it.groupValues[1] to it.groupValues[2] }

    fun isFieldName(name: String): Boolean = Regex("^$IDENT$").matches(name)

    fun rename(
        text: String,
        from: String,
        to: String,
    ): String =
        PATTERN.replace(text) { match ->
            if (match.groupValues[1] == from) reference(to, match.groupValues[2]) else match.value
        }
}

internal fun WorkflowNode.interpolatedFields(): List<String> = listOf(prompt, command, expression) + inputs.values

fun NodeType.outputFields(): List<String> =
    when (this) {
        NodeType.AGENT -> listOf("result", "sessionId", "costUsd")
        NodeType.SHELL -> listOf("result", "exitCode")
        NodeType.CONNECTOR -> listOf("result", "exitCode")
        NodeType.GATE -> listOf("result")
        NodeType.BRANCH -> listOf("result")
        NodeType.INPUT -> listOf("result")
    }

fun WorkflowNode.outputFields(manifest: ConnectorManifest? = null): List<String> {
    val declared =
        when (type) {
            NodeType.CONNECTOR -> manifest?.outputs?.map { it.name }.orEmpty()
            NodeType.AGENT -> schema.map { it.name }.filter { it.isNotBlank() }
            else -> emptyList()
        }

    return (type.outputFields() + declared).distinct()
}
