package com.dk.zopf.model

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
        issues += WorkflowIssue("This workflow needs workflow format v$version. This zopf reads v$WORKFLOW_VERSION, so update zopf")
    }

    issues += graphIssues()

    repos.filterNot(repoExists).forEach {
        issues += WorkflowIssue("Repo \"${it.id}\" isn't at ${it.path} any more")
    }

    defaults.repo?.let { repo ->
        if (repo !in declaredRepos) issues += WorkflowIssue("Default repo \"$repo\" isn't declared")
    }

    for (node in nodes) {
        val where = node.displayTitle

        node.repo?.let { repo ->
            if (repo !in declaredRepos) {
                issues += WorkflowIssue("$where runs in \"$repo\", which isn't declared", node.id)
            }
        }
        node.alsoRead.filterNot { it in declaredRepos }.forEach {
            issues += WorkflowIssue("$where also reads \"$it\", which isn't declared", node.id)
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
            val located = source?.let { " in $it" }.orEmpty()
            val resolvable = mutableSetOf<String>()
            for (referenced in NodeRefs.referencedNodeIds(text)) {
                when (referenced) {
                    !in nodeIds -> {
                        issues +=
                            WorkflowIssue(
                                "$where reads \${$referenced…}$located, which is no longer a node",
                                node.id,
                            )
                    }

                    !in ancestors -> {
                        issues +=
                            WorkflowIssue(
                                "$where reads \${$referenced…}$located, but nothing connects $referenced to it",
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
                            "$referenced produces ${produced.joinToString()}, so $where " +
                                "can't read \${$referenced.$field}$located",
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
                    "${it.displayTitle} isn't connected to anything",
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
                "There are ${nodes.count { it.id == id }} nodes called \"$id\". Ids have to be unique, " +
                    "since \${$id.result} can only mean one of them",
                id,
            )
    }

    edges.filter { it.from == it.to }.forEach { edge ->
        issues += WorkflowIssue("The edge on \"${edge.from}\" feeds itself, so it could never run", edge.from)
    }

    edges
        .flatMap { edge ->
            listOfNotNull(
                edge.from.takeIf { it !in known }?.let { it to edge.to },
                edge.to.takeIf { it !in known }?.let { it to edge.from },
            )
        }.distinct()
        .forEach { (missing, other) ->
            issues +=
                WorkflowIssue(
                    "An edge connects \"$missing\", which isn't a node in $name. The edge to \"$other\" " +
                        "is ignored, so the run takes a path nobody wrote",
                )
        }

    unreachableFromStart().takeIf { it.isNotEmpty() }?.let { stuck ->
        issues +=
            WorkflowIssue(
                "${stuck.joinToString()} can never run: the edges into them make a loop",
                stuck.first(),
            )
    }
    return issues
}

private fun WorkflowNode.emptinessIssue(): List<WorkflowIssue> {
    val missing =
        when (type) {
            NodeType.AGENT -> if (prompt.isBlank() && promptFile.isBlank()) "a prompt" else null
            NodeType.SHELL -> if (command.isBlank()) "a command" else null
            NodeType.CONNECTOR -> if (connector.isBlank()) "a connector" else null
            NodeType.BRANCH -> if (expression.isBlank()) "an expression" else null
            NodeType.INPUT -> if (prompt.isBlank()) "a question" else null
            NodeType.GATE -> null
        }
    return missing?.let { listOf(WorkflowIssue("$displayTitle needs $it", id)) }.orEmpty()
}

private fun WorkflowNode.choiceIssues(): List<WorkflowIssue> {
    if (type != NodeType.INPUT || choices.isEmpty() || default.isBlank()) return emptyList()
    if (default in choices) return emptyList()
    return listOf(
        WorkflowIssue(
            "$displayTitle offers ${choices.joinToString()}, so its default \"$default\" can't be picked",
            id,
            WorkflowIssue.Severity.WARNING,
        ),
    )
}

private fun WorkflowNode.promptFileIssues(fileExists: (String) -> Boolean): List<WorkflowIssue> {
    if (type != NodeType.AGENT || promptFile.isBlank()) return emptyList()

    val issues = mutableListOf<WorkflowIssue>()
    if (!fileExists(promptFile)) {
        issues += WorkflowIssue("$displayTitle reads its prompt from $promptFile, which isn't there", id)
    }

    if (prompt.isNotBlank()) {
        issues +=
            WorkflowIssue(
                "$displayTitle sends $promptFile, so its inline prompt is ignored",
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
        issues += WorkflowIssue("$displayTitle declares an output schema, which only an agent node can use", id)
        return issues
    }

    schema.filter { it.name.isBlank() }.forEach {
        issues += WorkflowIssue("$displayTitle declares an output field with no name", id)
    }

    schema
        .map { it.name }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach {
            issues += WorkflowIssue("$displayTitle declares the output field \"$it\" more than once", id)
        }

    val builtIn = NodeType.AGENT.outputFields().toSet()
    schema.map { it.name }.filter { it in builtIn }.forEach {
        issues +=
            WorkflowIssue(
                "$displayTitle declares an output field named \"$it\", which \${$id.$it} already means",
                id,
            )
    }

    schema.filter { it.name.isNotBlank() && !NodeRefs.isFieldName(it.name) }.forEach {
        issues +=
            WorkflowIssue(
                "$displayTitle declares the output field \"${it.name}\", which \${$id.…} can't name",
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
            "$displayTitle uses the \"$it\" skill, which isn't in this workflow's repos, the " +
                "workspace or ~/.claude/skills",
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
                "$displayTitle is a ${type.serialName} node, so its \"$it\" does nothing",
                id,
                WorkflowIssue.Severity.WARNING,
            )
        }
    }

    val issues = mutableListOf<WorkflowIssue>()

    if (!executableExists(provider)) {
        issues +=
            WorkflowIssue(
                "$displayTitle runs ${provider.cliValue}, which isn't on your PATH. " +
                    "The node will fail when it starts",
                id,
                WorkflowIssue.Severity.WARNING,
            )
    }

    provider.ignoredFields(this).takeIf { it.isNotEmpty() }?.let {
        issues +=
            WorkflowIssue(
                "$displayTitle runs ${provider.label}, which ignores ${it.joinToString()}",
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
                    "${node.displayTitle} calls \"${node.connector}\", which isn't a connector in this " +
                        "workspace or ~/.zopf/connectors",
                    node.id,
                ),
            )

    val issues = mutableListOf<WorkflowIssue>()
    manifest.inputs
        .filter { it.required && it.default.isBlank() && node.inputs[it.name].isNullOrBlank() }
        .forEach { issues += WorkflowIssue("${node.displayTitle} needs an input for \"${it.name}\"", node.id) }

    node.inputs.keys
        .filter { key -> manifest.inputs.none { it.name == key } }
        .forEach {
            issues +=
                WorkflowIssue(
                    "${node.displayTitle} sets \"$it\", which ${manifest.name} doesn't declare",
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
                    "${node.displayTitle} is a ${node.type.name.lowercase()}, which never fails, so the " +
                        "edge to ${edge.to} can never be taken",
                    node.id,
                    WorkflowIssue.Severity.WARNING,
                )
        }
    }

    if (node.type != NodeType.BRANCH) {
        outgoingEdges(node.id).filter { it.condition != null }.forEach { edge ->
            issues +=
                WorkflowIssue(
                    "${node.displayTitle} isn't a branch, so the \"when\" on its edge to ${edge.to} " +
                        "can never match and ${edge.to} never runs",
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
        issues += WorkflowIssue("${node.displayTitle} has an edge that is neither true nor false", node.id)
    }
    outgoing
        .groupBy { it.condition }
        .filterKeys { it != null }
        .filterValues { it.size > 1 }
        .forEach { (condition, edges) ->
            issues +=
                WorkflowIssue(
                    "${node.displayTitle} has ${edges.size} \"$condition\" edges; it can only take one",
                    node.id,
                )
        }
    return issues
}
