package com.dk.zopf.model

data class AgentCapabilities(
    val followUps: Boolean,
    val inlineApproval: Boolean,
    val resumeInTerminal: Boolean,
    val reportsCostUsd: Boolean,
    val skills: Boolean,
    val toolPermissions: Boolean,
    val extraDirectories: Boolean,
    val outputSchema: Boolean,
    val sandbox: Boolean,
    val modelSelection: Boolean,
)

val AgentProviderId.capabilities: AgentCapabilities
    get() =
        when (this) {
            AgentProviderId.CLAUDE -> {
                AgentCapabilities(
                    followUps = true,
                    inlineApproval = true,
                    resumeInTerminal = true,
                    reportsCostUsd = true,
                    skills = true,
                    toolPermissions = true,
                    extraDirectories = true,
                    outputSchema = true,
                    sandbox = false,
                    modelSelection = true,
                )
            }

            AgentProviderId.CODEX -> {
                AgentCapabilities(
                    followUps = false,
                    inlineApproval = false,
                    resumeInTerminal = false,
                    reportsCostUsd = false,
                    skills = false,
                    toolPermissions = false,
                    extraDirectories = false,
                    outputSchema = false,
                    sandbox = true,
                    modelSelection = true,
                )
            }

            AgentProviderId.DSH -> {
                AgentCapabilities(
                    followUps = false,
                    inlineApproval = false,
                    resumeInTerminal = false,
                    reportsCostUsd = false,
                    skills = false,
                    toolPermissions = false,
                    extraDirectories = false,
                    outputSchema = false,
                    sandbox = false,
                    modelSelection = false,
                )
            }
        }

val AgentProviderId.modelOptions: List<String>
    get() =
        when (this) {
            AgentProviderId.CLAUDE -> listOf("opus", "sonnet", "haiku")
            AgentProviderId.CODEX -> emptyList()
            AgentProviderId.DSH -> emptyList()
        }

fun AgentProviderId.ignoredFields(node: WorkflowNode): List<String> =
    buildList {
        val can = capabilities
        if (!can.toolPermissions && node.permissionMode != null) add("permissionMode")
        if (!can.toolPermissions && node.allowedTools.isNotEmpty()) add("allowedTools")
        if (!can.skills && node.skills.isNotEmpty()) add("skills")
        if (!can.extraDirectories && node.alsoRead.isNotEmpty()) add("alsoRead")
        if (!can.outputSchema && node.schema.isNotEmpty()) add("schema")
        if (!can.sandbox && node.sandbox != null) add("sandbox")
        if (!can.modelSelection && node.model != null) add("model")
    }

fun Workflow.providerFor(
    node: WorkflowNode,
    fallback: AgentProviderId = AgentProviderId.CLAUDE,
): AgentProviderId = node.provider ?: defaults.provider ?: fallback

fun Workflow.inheritedModel(
    provider: AgentProviderId,
    machineProvider: AgentProviderId,
    machineModel: String?,
): String? =
    when {
        provider == (defaults.provider ?: machineProvider) && defaults.model != null -> defaults.model
        provider == machineProvider -> machineModel
        else -> null
    }
