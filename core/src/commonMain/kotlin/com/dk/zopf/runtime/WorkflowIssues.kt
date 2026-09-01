package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.ConnectorManifest
import com.dk.zopf.model.RepoRef
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

class WorkflowLookups(
    val repoExists: (RepoRef) -> Boolean = { true },
    val fileExists: (String) -> Boolean = { true },
    val connector: ((String) -> ConnectorManifest?)? = null,
    val skills: List<DiscoveredSkill>? = null,
    val promptTexts: Map<String, String> = emptyMap(),
    val defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
    val executableExists: (AgentProviderId) -> Boolean = { true },
) {
    val skillNames: Set<String>? by lazy { skills?.mapTo(mutableSetOf(), DiscoveredSkill::name) }
}

fun workflowLookups(
    workspace: Workspace?,
    workflow: Workflow,
    defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
    executableExists: (AgentProviderId) -> Boolean = AgentProviders::isInstalled,
    connector: ((String) -> ConnectorManifest?)? = null,
): WorkflowLookups {
    if (workspace == null) {
        return WorkflowLookups(connector = connector, defaultProvider = defaultProvider)
    }

    val connectors = ConnectorStore(workspace)
    return WorkflowLookups(
        repoExists = { workspace.resolvePath(it.path).exists() },
        fileExists = { workspace.resolvePath(it).isRegularFile() },
        connector = connector ?: { name -> connectors.find(name)?.manifest },
        skills = availableSkills(workspace, workflow),
        promptTexts = workflow.readPromptFiles(workspace),
        defaultProvider = workspace.config.defaults.provider ?: defaultProvider,
        executableExists = executableExists,
    )
}

fun Workflow.promptFiles(): List<String> =
    nodes
        .map { it.promptFile }
        .filter { it.isNotBlank() }
        .distinct()

private fun Workflow.readPromptFiles(workspace: Workspace): Map<String, String> =
    promptFiles()
        .mapNotNull { raw -> runCatching { raw to workspace.resolvePath(raw).readText() }.getOrNull() }
        .toMap()

fun Workflow.issues(lookups: WorkflowLookups): List<WorkflowIssue> =
    validate(
        repoExists = lookups.repoExists,
        fileExists = lookups.fileExists,
        connector = lookups.connector,
        knownSkills = lookups.skillNames,
        promptText = lookups.promptTexts::get,
        defaultProvider = lookups.defaultProvider,
        executableExists = lookups.executableExists,
    )

fun Workflow.issues(
    workspace: Workspace?,
    defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
): List<WorkflowIssue> = issues(workflowLookups(workspace, this, defaultProvider))

fun List<WorkflowIssue>.errors(): List<WorkflowIssue> = filter { it.severity == WorkflowIssue.Severity.ERROR }
