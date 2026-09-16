package com.dk.zopf.model

import com.dk.zopf.util.Strings

data class WorkflowIssue(
    val message: String,
    val nodeId: String? = null,
    val severity: Severity = Severity.ERROR,
) {
    enum class Severity { ERROR, WARNING }
}

fun Workflow.validate(
    repoExists: (RepoRef) -> Boolean = { true },
    fileExists: (String) -> Boolean = { true },
    connector: ((String) -> ConnectorManifest?)? = null,
    knownSkills: Set<String>? = null,
    promptText: (String) -> String? = { null },
    defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
    executableExists: (AgentProviderId) -> Boolean = { true },
): List<WorkflowIssue> {
    val issues = mutableListOf<WorkflowIssue>()
    val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
    val declaredRepos = repos.mapTo(mutableSetOf()) { it.id }

    if (isFromTheFuture) {
        issues += WorkflowIssue(Strings.Validation.FROM_THE_FUTURE)
    }

    issues += graphIssues()

    repos.filterNot(repoExists).forEach {
        issues += WorkflowIssue(Strings.Validation.repoMoved(it.id, it.path))
    }

    defaults.repo?.let { repo ->
        if (repo !in declaredRepos) issues += WorkflowIssue(Strings.Validation.defaultRepoUndeclared(repo))
    }

    for (node in nodes) {
        val where = node.displayTitle

        node.repo?.let { repo ->
            if (repo !in declaredRepos) {
                issues += WorkflowIssue(Strings.Validation.repoUndeclared(where, repo), node.id)
            }
        }
        node.alsoRead.filterNot { it in declaredRepos }.forEach {
            issues += WorkflowIssue(Strings.Validation.alsoReadUndeclared(where, it), node.id)
        }

        val ancestors = ancestorsOf(node.id)

        fun producedFields(upstream: WorkflowNode): List<String>? =
            when {
                upstream.type != NodeType.CONNECTOR -> upstream.outputFields()
                connector == null || upstream.connector.isBlank() -> null
                else -> connector(upstream.connector)?.let { upstream.outputFields(it) }
            }

        fun checkReferences(
            text: String,
            source: String? = null,
        ) {
            val located = Strings.Validation.inFile(source)
            val resolvable = mutableSetOf<String>()
            for (referenced in NodeRefs.referencedNodeIds(text)) {
                when (referenced) {
                    !in nodeIds -> {
                        issues +=
                            WorkflowIssue(
                                Strings.Validation.referencesUnknownNode(where, referenced, located),
                                node.id,
                            )
                    }

                    !in ancestors -> {
                        issues +=
                            WorkflowIssue(
                                Strings.Validation.referencesUnconnectedNode(where, referenced, located),
                                node.id,
                            )
                    }

                    else -> {
                        resolvable += referenced
                    }
                }
            }

            for ((referenced, field) in NodeRefs.references(text)) {
                if (referenced !in resolvable) continue
                val produced = producedFields(nodes.first { it.id == referenced }) ?: continue
                if (field !in produced) {
                    issues +=
                        WorkflowIssue(
                            Strings.Validation.referencesUnknownField(referenced, produced.joinToString(), where, field, located),
                            node.id,
                        )
                }
            }
        }
        node.interpolatedFields().forEach { checkReferences(it) }

        if (node.type == NodeType.AGENT && node.promptFile.isNotBlank()) {
            promptText(node.promptFile)?.let { checkReferences(it, node.promptFile) }
        }

        issues += node.emptinessIssue()
        issues += node.choiceIssues()
        issues += node.promptFileIssues(fileExists)
        issues += node.skillIssues(knownSkills)
        issues += node.schemaIssues()
        issues += node.providerIssues(providerFor(node, defaultProvider), executableExists)
        issues += branchIssues(node)
        issues += edgeTriggerIssues(node)
        issues += connectorIssues(node, connector)
    }

    if (nodes.size > 1) {
        val connected = edges.flatMapTo(mutableSetOf()) { listOf(it.from, it.to) }
        nodes.filterNot { it.id in connected }.forEach {
            issues +=
                WorkflowIssue(
                    Strings.Validation.orphanNode(it.displayTitle),
                    it.id,
                    WorkflowIssue.Severity.WARNING,
                )
        }
    }

    return issues
}

private fun Workflow.graphIssues(): List<WorkflowIssue> {
    val issues = mutableListOf<WorkflowIssue>()
    val known = nodes.mapTo(mutableSetOf()) { it.id }

    duplicateNodeIds().forEach { id ->
        issues +=
            WorkflowIssue(
                Strings.Validation.duplicateId(nodes.count { it.id == id }, id),
                id,
            )
    }

    edges.filter { it.from == it.to }.forEach { edge ->
        issues += WorkflowIssue(Strings.Validation.selfEdge(edge.from), edge.from)
    }

    edges
        .flatMap { edge ->
            listOfNotNull(
                edge.from.takeIf { it !in known },
                edge.to.takeIf { it !in known },
            )
        }.distinct()
        .forEach { missing ->
            issues +=
                WorkflowIssue(
                    Strings.Validation.edgeToMissingNode(missing),
                )
        }

    unreachableFromStart().takeIf { it.isNotEmpty() }?.let { stuck ->
        issues +=
            WorkflowIssue(
                Strings.Validation.cycle(stuck.joinToString()),
                stuck.first(),
            )
    }
    return issues
}

private fun WorkflowNode.emptinessIssue(): List<WorkflowIssue> {
    val missing =
        when (type) {
            NodeType.AGENT -> if (prompt.isBlank() && promptFile.isBlank()) Strings.Validation.NEEDS_PROMPT else null
            NodeType.SHELL -> if (command.isBlank()) Strings.Validation.NEEDS_COMMAND else null
            NodeType.CONNECTOR -> if (connector.isBlank()) Strings.Validation.NEEDS_CONNECTOR else null
            NodeType.BRANCH -> if (expression.isBlank()) Strings.Validation.NEEDS_EXPRESSION else null
            NodeType.INPUT -> if (prompt.isBlank()) Strings.Validation.NEEDS_QUESTION else null
            NodeType.GATE -> null
        }
    return missing?.let { listOf(WorkflowIssue(Strings.Validation.needs(displayTitle, it), id)) }.orEmpty()
}

private fun WorkflowNode.choiceIssues(): List<WorkflowIssue> {
    if (type != NodeType.INPUT || choices.isEmpty() || default.isBlank()) return emptyList()
    if (default in choices) return emptyList()
    return listOf(
        WorkflowIssue(
            Strings.Validation.defaultNotAChoice(displayTitle, choices.joinToString(), default),
            id,
            WorkflowIssue.Severity.WARNING,
        ),
    )
}

private fun WorkflowNode.promptFileIssues(fileExists: (String) -> Boolean): List<WorkflowIssue> {
    if (type != NodeType.AGENT || promptFile.isBlank()) return emptyList()

    val issues = mutableListOf<WorkflowIssue>()
    if (!fileExists(promptFile)) {
        issues += WorkflowIssue(Strings.Validation.promptFileMissing(displayTitle, promptFile), id)
    }

    if (prompt.isNotBlank()) {
        issues +=
            WorkflowIssue(
                Strings.Validation.inlinePromptIgnored(displayTitle, promptFile),
                id,
                WorkflowIssue.Severity.WARNING,
            )
    }
    return issues
}

private fun WorkflowNode.schemaIssues(): List<WorkflowIssue> {
    if (schema.isEmpty()) return emptyList()

    val issues = mutableListOf<WorkflowIssue>()
    if (type != NodeType.AGENT) {
        issues += WorkflowIssue(Strings.Validation.schemaOnNonAgent(displayTitle), id)
        return issues
    }

    schema.filter { it.name.isBlank() }.forEach {
        issues += WorkflowIssue(Strings.Validation.unnamedOutputField(displayTitle), id)
    }

    schema
        .map { it.name }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach {
            issues += WorkflowIssue(Strings.Validation.duplicateOutputField(displayTitle, it), id)
        }

    val builtIn = NodeType.AGENT.outputFields().toSet()
    schema.map { it.name }.filter { it in builtIn }.forEach {
        issues +=
            WorkflowIssue(
                Strings.Validation.outputFieldShadowsBuiltIn(displayTitle, it),
                id,
            )
    }

    schema.filter { it.name.isNotBlank() && !NodeRefs.isFieldName(it.name) }.forEach {
        issues +=
            WorkflowIssue(
                Strings.Validation.outputFieldUnreferenceable(displayTitle, it.name),
                id,
                WorkflowIssue.Severity.WARNING,
            )
    }

    return issues
}

private fun WorkflowNode.skillIssues(knownSkills: Set<String>?): List<WorkflowIssue> {
    if (knownSkills == null || type != NodeType.AGENT) return emptyList()
    return skills.filterNot { it in knownSkills }.map {
        WorkflowIssue(
            Strings.Validation.unknownSkill(displayTitle, it),
            id,
            WorkflowIssue.Severity.WARNING,
        )
    }
}

private fun WorkflowNode.providerIssues(
    provider: AgentProviderId,
    executableExists: (AgentProviderId) -> Boolean,
): List<WorkflowIssue> {
    if (type != NodeType.AGENT) {
        return listOfNotNull(
            "provider".takeIf { this.provider != null },
            "sandbox".takeIf { sandbox != null },
        ).map {
            WorkflowIssue(
                Strings.Validation.fieldDoesNothing(displayTitle, type.serialName, it),
                id,
                WorkflowIssue.Severity.WARNING,
            )
        }
    }

    val issues = mutableListOf<WorkflowIssue>()

    if (!executableExists(provider)) {
        issues +=
            WorkflowIssue(
                Strings.Validation.executableMissing(displayTitle, provider.cliValue),
                id,
                WorkflowIssue.Severity.WARNING,
            )
    }

    provider.ignoredFields(this).takeIf { it.isNotEmpty() }?.let {
        issues +=
            WorkflowIssue(
                Strings.Validation.providerIgnoresFields(displayTitle, provider.label, it.joinToString()),
                id,
                WorkflowIssue.Severity.WARNING,
            )
    }
    return issues
}

private fun connectorIssues(
    node: WorkflowNode,
    lookup: ((String) -> ConnectorManifest?)?,
): List<WorkflowIssue> {
    if (lookup == null || node.type != NodeType.CONNECTOR || node.connector.isBlank()) return emptyList()

    val manifest =
        lookup(node.connector)
            ?: return listOf(
                WorkflowIssue(
                    Strings.Validation.connectorMissing(node.displayTitle, node.connector),
                    node.id,
                ),
            )

    val issues = mutableListOf<WorkflowIssue>()
    manifest.inputs
        .filter { it.required && it.default.isBlank() && node.inputs[it.name].isNullOrBlank() }
        .forEach { issues += WorkflowIssue(Strings.Validation.connectorInputMissing(node.displayTitle, it.name), node.id) }

    node.inputs.keys
        .filter { key -> manifest.inputs.none { it.name == key } }
        .forEach {
            issues +=
                WorkflowIssue(
                    Strings.Validation.connectorInputUndeclared(node.displayTitle, it, manifest.name),
                    node.id,
                    WorkflowIssue.Severity.WARNING,
                )
        }
    return issues
}

private fun Workflow.edgeTriggerIssues(node: WorkflowNode): List<WorkflowIssue> {
    val issues = mutableListOf<WorkflowIssue>()

    if (!node.type.canFail()) {
        outgoingEdges(node.id).filter { it.on == EdgeTrigger.FAILURE }.forEach { edge ->
            issues +=
                WorkflowIssue(
                    Strings.Validation.failureEdgeOnInfallibleNode(node.displayTitle, node.type.name.lowercase(), edge.to),
                    node.id,
                    WorkflowIssue.Severity.WARNING,
                )
        }
    }

    if (node.type != NodeType.BRANCH) {
        outgoingEdges(node.id).filter { it.condition != null }.forEach { edge ->
            issues +=
                WorkflowIssue(
                    Strings.Validation.conditionOnNonBranch(node.displayTitle, edge.to),
                    node.id,
                )
        }
    }
    return issues
}

private fun Workflow.branchIssues(node: WorkflowNode): List<WorkflowIssue> {
    if (node.type != NodeType.BRANCH) return emptyList()
    val outgoing = outgoingEdges(node.id)
    val issues = mutableListOf<WorkflowIssue>()

    if (outgoing.any { it.condition == null }) {
        issues += WorkflowIssue(Strings.Validation.branchEdgeWithoutCondition(node.displayTitle), node.id)
    }
    outgoing
        .groupBy { it.condition }
        .filterKeys { it != null }
        .filterValues { it.size > 1 }
        .forEach { (condition, edges) ->
            issues +=
                WorkflowIssue(
                    Strings.Validation.duplicateBranchEdges(node.displayTitle, edges.size, "$condition"),
                    node.id,
                )
        }
    return issues
}
