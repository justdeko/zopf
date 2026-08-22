package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.validate
import com.dk.zopf.store.ConnectorStore
import com.dk.zopf.store.DiscoveredSkill
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.availableSkills
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

fun Workflow.issues(
    workspace: Workspace?,
    defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
): List<WorkflowIssue> {
    if (workspace == null) return validate(defaultProvider = defaultProvider)

    val connectors = ConnectorStore(workspace)
    return validate(
        repoExists = { workspace.resolvePath(it.path).exists() },
        fileExists = { workspace.resolvePath(it).isRegularFile() },
        connector = { name -> connectors.find(name)?.manifest },
        knownSkills = availableSkills(workspace, this).mapTo(mutableSetOf(), DiscoveredSkill::name),
        promptText = { raw -> runCatching { workspace.resolvePath(raw).readText() }.getOrNull() },
        defaultProvider = workspace.config.defaults.provider ?: defaultProvider,
        executableExists = AgentProviders::isInstalled,
    )
}

fun List<WorkflowIssue>.errors(): List<WorkflowIssue> = filter { it.severity == WorkflowIssue.Severity.ERROR }
