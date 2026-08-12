package com.dk.zopf.store

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.dk.zopf.model.WORKFLOW_VERSION

internal class WorkflowMigration(
    val to: Int,
    val summary: String,
    val apply: (YamlMap) -> YamlMap,
)

internal val workflowMigrations: List<WorkflowMigration> = emptyList()

internal fun migrateWorkflow(
    root: YamlNode,
    migrations: List<WorkflowMigration> = workflowMigrations,
    current: Int = WORKFLOW_VERSION,
): YamlNode {
    if (root !is YamlMap) return root
    val declared = root.int("version") ?: return root
    if (declared >= current) return root

    return migrations
        .filter { it.to in (declared + 1)..current }
        .sortedBy { it.to }
        .fold(root) { workflow, migration -> migration.apply(workflow) }
        .without("version")
}

internal fun YamlMap.with(
    key: String,
    value: YamlNode,
): YamlMap {
    val name = entryFor(key)?.key ?: YamlScalar(key, path.withMapElementKey(key, path.endLocation))
    return copy(entries = entries.filterKeys { it.content != key } + (name to value))
}

internal fun YamlMap.with(
    key: String,
    value: String,
): YamlMap = with(key, YamlScalar(value, path.withMapElementKey(key, path.endLocation)))

internal fun YamlMap.without(key: String): YamlMap = copy(entries = entries.filterKeys { it.content != key })

internal fun YamlMap.renaming(
    from: String,
    to: String,
): YamlMap = entryFor(from)?.let { without(from).with(to, it.value) } ?: this

internal fun YamlMap.eachNode(transform: (YamlMap) -> YamlMap): YamlMap {
    val nodes = entryFor("nodes")?.value as? YamlList ?: return this
    return with("nodes", nodes.copy(items = nodes.items.map { if (it is YamlMap) transform(it) else it }))
}

private fun YamlMap.entryFor(key: String): Map.Entry<YamlScalar, YamlNode>? = entries.entries.firstOrNull { it.key.content == key }

private fun YamlMap.int(key: String): Int? = (entryFor(key)?.value as? YamlScalar)?.content?.toIntOrNull()
