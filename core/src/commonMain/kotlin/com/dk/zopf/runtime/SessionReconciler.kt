package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
data class LiveAgent(
    val sessionId: String? = null,
    val pid: Int? = null,
    val cwd: String? = null,
    val kind: String? = null,
    val name: String? = null,
    val status: String? = null,
    val startedAt: Long? = null,
)

data class Reconciliation(
    val orphans: List<WorkflowRun> = emptyList(),
    val closed: Int = 0,
)

object SessionReconciler {
    private val json = Json { ignoreUnknownKeys = true }

    private const val SCAN_LIMIT = 200

    fun reconcile(
        root: Path = AppPaths.runsDir,
        agents: List<LiveAgent> = liveAgents(),
    ): Reconciliation {
        val alive = agents.mapNotNullTo(mutableSetOf()) { it.sessionId }
        val orphans = mutableListOf<WorkflowRun>()
        var closed = 0

        for (archive in RunArchive.all(root).take(SCAN_LIMIT)) {
            val record = archive.read() ?: continue
            if (!record.isUnfinished()) continue

            if (record.nodes.any { it.resumable() && it.sessionId in alive }) {
                orphans += WorkflowRun.restored(record)
            } else {
                archive.write(record.closedOut())
                closed++
            }
        }
        return Reconciliation(orphans, closed)
    }

    private fun NodeRunRecord.resumable(): Boolean = type == NodeType.AGENT && (provider ?: AgentProviderId.CLAUDE) == AgentProviderId.CLAUDE

    fun liveAgents(executable: String = ClaudeProvider.executable): List<LiveAgent> =
        runCatching {
            val resolved = CommandLookup.which(executable) ?: return emptyList()
            val process =
                ProcessBuilder(resolved.toString(), "agents", "--json")
                    .apply { environment().putAll(CommandLookup.childEnvironment()) }
                    .redirectErrorStream(false)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy()
                return emptyList()
            }
            parse(out)
        }.getOrDefault(emptyList())

    internal fun parse(text: String): List<LiveAgent> =
        runCatching {
            json.decodeFromString(ListSerializer(LiveAgent.serializer()), text.trim())
        }.getOrDefault(emptyList())

    private fun RunRecord.isUnfinished(): Boolean = runCatching { RunStatus.valueOf(status).isActive }.getOrDefault(false)

    private fun RunRecord.closedOut(): RunRecord {
        val now = Instant.now().toString()
        return copy(
            status = RunStatus.STOPPED.name,
            finishedAt = finishedAt ?: now,
            nodes =
                nodes.map { node ->
                    val wasActive = runCatching { RunStatus.valueOf(node.status).isActive }.getOrDefault(false)
                    if (wasActive) {
                        node.copy(status = RunStatus.STOPPED.name, finishedAt = node.finishedAt ?: now)
                    } else {
                        node
                    }
                },
        )
    }
}
