package com.dk.zopf.store

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import kotlinx.serialization.Serializable
import java.io.BufferedWriter
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

@Serializable
data class RunRecord(
    val id: String,
    val workflow: String,
    val workspace: String? = null,
    val startedAt: String,
    val finishedAt: String? = null,
    val status: String,
    val nodes: List<NodeRunRecord> = emptyList(),
)

@Serializable
data class NodeRunRecord(
    val nodeId: String,
    val type: NodeType,
    val provider: AgentProviderId? = null,
    val status: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val cwd: String? = null,
    val sessionId: String? = null,
    val command: List<String> = emptyList(),
    val exitCode: Int? = null,
    val costUsd: Double? = null,
)

class RunArchive(
    val dir: Path,
    private val onRaw: (String, String) -> Unit = { _, _ -> },
) {
    private val writers = mutableMapOf<String, BufferedWriter>()

    init {
        dir.createDirectories()
    }

    fun appendRaw(
        nodeId: String,
        line: String,
    ) {
        onRaw(nodeId, line)
        synchronized(writers) {
            val writer =
                writers.getOrPut(nodeId) {
                    dir.resolve("${sanitize(nodeId)}.jsonl").bufferedWriter()
                }
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    fun write(record: RunRecord) {
        dir.resolve(RUN_FILE).writeTextAtomically(zopfJson.encodeToString(RunRecord.serializer(), record))
    }

    fun read(): RunRecord? =
        runCatching {
            zopfJson.decodeFromString(RunRecord.serializer(), dir.resolve(RUN_FILE).readText())
        }.getOrNull()

    fun rawPath(nodeId: String): Path = dir.resolve("${sanitize(nodeId)}.jsonl")

    fun close() {
        synchronized(writers) {
            writers.values.forEach { runCatching { it.close() } }
            writers.clear()
        }
    }

    companion object {
        const val RUN_FILE = "run.json"

        fun create(
            runId: String,
            workspaceId: String,
            root: Path = AppPaths.runsDir,
            onRaw: (String, String) -> Unit = { _, _ -> },
        ): RunArchive = RunArchive(root.resolve(workspaceId).resolve(runId), onRaw)

        fun all(root: Path = AppPaths.runsDir): List<RunArchive> {
            if (!root.isDirectory()) return emptyList()
            return root
                .listDirectoryEntries()
                .filter { it.isDirectory() }
                .flatMap { workspace -> workspace.listDirectoryEntries().filter { it.isDirectory() } }
                .filter { it.resolve(RUN_FILE).exists() }
                .sortedByDescending { it.getLastModifiedTime() }
                .map { RunArchive(it) }
        }

        fun prune(
            root: Path = AppPaths.runsDir,
            keep: Int,
            olderThan: Duration? = null,
        ): List<Path> {
            val cutoff = olderThan?.let { Instant.now().minus(it) }
            return all(root)
                .drop(keep)
                .map { it.dir }
                .filter { dir ->
                    cutoff == null || dir.getLastModifiedTime().toInstant().isBefore(cutoff)
                }
        }

        fun delete(dir: Path) {
            dir.toFile().deleteRecursively()
            runCatching { dir.parent?.takeIf { it.listDirectoryEntries().isEmpty() }?.deleteExisting() }
        }

        fun workspaceId(root: Path?): String {
            if (root == null) return "unsaved"
            val absolute = root.toAbsolutePath().normalize()
            val name =
                absolute.fileName
                    ?.toString()
                    .orEmpty()
                    .ifBlank { "workspace" }
            return "${sanitize(name)}-${shortHash(absolute.toString())}"
        }

        private fun shortHash(value: String): String =
            MessageDigest
                .getInstance("SHA-1")
                .digest(value.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }

        private fun sanitize(value: String): String {
            val safe =
                value
                    .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
                    .joinToString("")
                    .trim('-')
                    .ifBlank { "node" }
            return if (safe == value) safe else "$safe-${shortHash(value)}"
        }
    }
}
