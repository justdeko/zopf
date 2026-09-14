package com.dk.zopf.store.workflow

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.zopfYaml
import com.dk.zopf.util.Strings
import kotlinx.serialization.descriptors.SerialDescriptor

data class UnknownKey(
    val key: String,
    val where: String,
)

fun unknownKeysIn(yaml: String): List<UnknownKey> {
    val root = runCatching { zopfYaml.parseToYamlNode(yaml) }.getOrNull() as? YamlMap ?: return emptyList()
    val found = mutableListOf<UnknownKey>()

    found += root.strayKeys(Workflow.serializer().descriptor, Strings.Validation.THE_WORKFLOW)

    root.list("nodes").forEachIndexed { index, node ->
        val name = (node as? YamlMap)?.text("id") ?: Strings.Validation.nodeAt(index)
        found += (node as? YamlMap)?.strayKeys(WorkflowNode.serializer().descriptor, name).orEmpty()
    }
    root.list("edges").forEachIndexed { index, edge ->
        found += (edge as? YamlMap)?.strayKeys(WorkflowEdge.serializer().descriptor, Strings.Validation.edgeAt(index)).orEmpty()
    }
    return found
}

private fun YamlMap.strayKeys(
    descriptor: SerialDescriptor,
    where: String,
): List<UnknownKey> {
    val known = (0 until descriptor.elementsCount).mapTo(mutableSetOf()) { descriptor.getElementName(it) }
    return entries.keys
        .map { it.content }
        .filterNot { it in known }
        .map { UnknownKey(it, where) }
}

private fun YamlMap.list(key: String): List<YamlNode> = (entries.entries.firstOrNull { it.key.content == key }?.value as? YamlList)?.items.orEmpty()

private fun YamlMap.text(key: String): String? = (entries.entries.firstOrNull { it.key.content == key }?.value as? YamlScalar)?.content
