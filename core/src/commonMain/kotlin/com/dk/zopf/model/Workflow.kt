package com.dk.zopf.model

import com.dk.zopf.store.BuildInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

val WORKFLOW_VERSION: Int get() = BuildInfo.workflowVersion

@Serializable
data class Workflow(
    val version: Int? = null,
    val name: String,
    val description: String = "",
    val repos: List<RepoRef> = emptyList(),
    val skills: List<String> = emptyList(),
    val defaults: NodeDefaults = NodeDefaults(),
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
) {
    val isFromTheFuture: Boolean get() = (version ?: WORKFLOW_VERSION) > WORKFLOW_VERSION

    fun node(id: String): WorkflowNode? = nodes.firstOrNull { it.id == id }

    fun hasEdge(
        from: String,
        to: String,
    ): Boolean = edges.any { it.from == from && it.to == to }
}

@Serializable
data class RepoRef(
    val id: String,
    val path: String,
)

@Serializable
data class NodeDefaults(
    val provider: AgentProviderId? = null,
    val model: String? = null,
    val permissionMode: PermissionMode? = null,
    val sandbox: Sandbox? = null,
    val repo: String? = null,
    val timeoutSeconds: Int? = null,
) {
    val isEmpty: Boolean get() = this == NodeDefaults()

    fun orElse(fallback: NodeDefaults): NodeDefaults =
        if (fallback.isEmpty) {
            this
        } else {
            NodeDefaults(
                provider = provider ?: fallback.provider,
                model = model ?: fallback.model,
                permissionMode = permissionMode ?: fallback.permissionMode,
                sandbox = sandbox ?: fallback.sandbox,
                repo = repo ?: fallback.repo,
                timeoutSeconds = timeoutSeconds ?: fallback.timeoutSeconds,
            )
        }
}

fun Workflow.withDefaultsFrom(workspace: NodeDefaults): Workflow = if (workspace.isEmpty) this else copy(defaults = defaults.orElse(workspace))

@Serializable
data class WorkflowNode(
    val id: String,
    val type: NodeType,
    val provider: AgentProviderId? = null,
    val title: String = "",
    val position: Position? = null,
    val repo: String? = null,
    val model: String? = null,
    val permissionMode: PermissionMode? = null,
    val sandbox: Sandbox? = null,
    val timeoutSeconds: Int? = null,
    val connector: String = "",
    val expression: String = "",
    val default: String = "",
    val choices: List<String> = emptyList(),
    val alsoRead: List<String> = emptyList(),
    val allowedTools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val schema: List<SchemaField> = emptyList(),
    val inputs: Map<String, String> = emptyMap(),
    val command: String = "",
    val promptFile: String = "",
    val prompt: String = "",
) {
    val displayTitle: String get() = title.ifBlank { id }
}

@Serializable(with = NodeTypeSerializer::class)
enum class NodeType {
    AGENT,
    SHELL,
    CONNECTOR,
    GATE,
    BRANCH,
    INPUT,

    ;

    val serialName: String get() = name.lowercase()
}

internal const val LEGACY_AGENT_TYPE = "claude"

internal object NodeTypeSerializer : KSerializer<NodeType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.dk.zopf.model.NodeType", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: NodeType,
    ) = encoder.encodeString(value.serialName)

    override fun deserialize(decoder: Decoder): NodeType {
        val raw = decoder.decodeString()
        if (raw == LEGACY_AGENT_TYPE) return NodeType.AGENT
        return NodeType.entries.firstOrNull { it.serialName == raw }
            ?: throw SerializationException(
                "\"$raw\" isn't a kind of node. It can be ${NodeType.entries.joinToString { it.serialName }}",
            )
    }
}

@Serializable
enum class AgentProviderId {
    @SerialName("claude")
    CLAUDE,

    @SerialName("codex")
    CODEX,

    ;

    val cliValue: String
        get() =
            when (this) {
                CLAUDE -> "claude"
                CODEX -> "codex"
            }

    val label: String
        get() =
            when (this) {
                CLAUDE -> "Claude Code"
                CODEX -> "codex"
            }
}

@Serializable
enum class Sandbox {
    @SerialName("read-only")
    READ_ONLY,

    @SerialName("workspace-write")
    WORKSPACE_WRITE,

    @SerialName("danger-full-access")
    DANGER_FULL_ACCESS,

    ;

    val cliValue: String
        get() =
            when (this) {
                READ_ONLY -> "read-only"
                WORKSPACE_WRITE -> "workspace-write"
                DANGER_FULL_ACCESS -> "danger-full-access"
            }
}

@Serializable
enum class PermissionMode {
    @SerialName("acceptEdits")
    ACCEPT_EDITS,

    @SerialName("auto")
    AUTO,

    @SerialName("bypassPermissions")
    BYPASS_PERMISSIONS,

    @SerialName("manual")
    MANUAL,

    @SerialName("dontAsk")
    DONT_ASK,

    @SerialName("plan")
    PLAN,

    ;

    val cliValue: String
        get() =
            when (this) {
                ACCEPT_EDITS -> "acceptEdits"
                AUTO -> "auto"
                BYPASS_PERMISSIONS -> "bypassPermissions"
                MANUAL -> "manual"
                DONT_ASK -> "dontAsk"
                PLAN -> "plan"
            }
}

@Serializable
data class Position(
    val x: Float,
    val y: Float,
)

@Serializable
enum class EdgeTrigger {
    @SerialName("success")
    SUCCESS,

    @SerialName("failure")
    FAILURE,
}

@Serializable
data class WorkflowEdge(
    val from: String,
    val to: String,
    @SerialName("when")
    val condition: Boolean? = null,
    val on: EdgeTrigger = EdgeTrigger.SUCCESS,
)
