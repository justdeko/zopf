package com.dk.zopf.store

import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.Workflow
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

const val WORKSPACE_DIR = ".zopf"

const val WORKSPACE_FILE = "zopf.yaml"

const val SELF_REPO_ID = "self"

const val WORKSPACE_VERSION = 1

@Serializable
data class WorkspaceConfig(
    val name: String = "",
    val version: Int? = null,
    val defaults: NodeDefaults = NodeDefaults(),
) {
    val isFromTheFuture: Boolean get() = (version ?: WORKSPACE_VERSION) > WORKSPACE_VERSION
}

class Workspace(
    val root: Path,
    val config: WorkspaceConfig,
) {
    val name: String get() = config.name.ifBlank { defaultName(root) }

    val workflowsDir: Path get() = root.resolve("workflows")
    val connectorsDir: Path get() = root.resolve("connectors")
    val skillsDir: Path get() = root.resolve("skills")

    val selfRepo: Path? by lazy { findEnclosingGitRepo(root) }

    fun resolvePath(raw: String): Path = resolvePathAgainst(raw, root)

    fun resolveRepo(
        workflow: Workflow,
        repoId: String?,
    ): Path? {
        val id = repoId ?: workflow.defaults.repo ?: return null
        if (id == SELF_REPO_ID) {
            val declared = workflow.repos.firstOrNull { it.id == SELF_REPO_ID }
            return declared?.let { resolvePath(it.path) } ?: selfRepo
        }
        val ref = workflow.repos.firstOrNull { it.id == id } ?: return null
        return resolvePath(ref.path)
    }

    fun availableRepoIds(workflow: Workflow): List<String> {
        val declared = workflow.repos.map { it.id }
        return if (selfRepo != null && SELF_REPO_ID !in declared) declared + SELF_REPO_ID else declared
    }

    fun ensureDirectories() {
        workflowsDir.createDirectories()
        connectorsDir.createDirectories()
    }

    companion object {
        fun isWorkspace(dir: Path): Boolean = dir.isDirectory() && dir.name == WORKSPACE_DIR

        fun open(dir: Path): Workspace? {
            val root =
                when {
                    isWorkspace(dir) -> dir
                    isWorkspace(dir.resolve(WORKSPACE_DIR)) -> dir.resolve(WORKSPACE_DIR)
                    else -> return null
                }
            val config =
                runCatching {
                    zopfYaml.decodeFromString(
                        WorkspaceConfig.serializer(),
                        root.resolve(WORKSPACE_FILE).readText(),
                    )
                }.getOrElse { WorkspaceConfig() }
            return Workspace(root.toAbsolutePath().normalize(), config)
        }

        fun create(
            root: Path,
            name: String = "",
        ): Workspace {
            open(root)?.let { return it.also { existing -> existing.ensureDirectories() } }

            val target = if (root.name == WORKSPACE_DIR) root else root.resolve(WORKSPACE_DIR)
            target.createDirectories()

            val file = target.resolve(WORKSPACE_FILE)
            if (name.isNotBlank() && !file.exists()) {
                val config = WorkspaceConfig(name = name.trim(), version = WORKSPACE_VERSION)
                file.writeTextAtomically(encodeYaml(WorkspaceConfig.serializer(), config))
            }
            return open(target)!!.also { it.ensureDirectories() }
        }

        private fun defaultName(root: Path): String = root.parent?.takeIf { it != homeDir() }?.name ?: "zopf"

        private fun findEnclosingGitRepo(from: Path): Path? {
            var current: Path? = from.toAbsolutePath().normalize()
            while (current != null) {
                if (Files.exists(current.resolve(".git"))) return current
                current = current.parent
            }
            return null
        }
    }
}

internal fun homeDir(): Path = Paths.get(System.getProperty("user.home"))

fun resolvePathAgainst(
    raw: String,
    base: Path,
): Path {
    val trimmed = raw.trim()
    return when {
        trimmed == "~" -> homeDir()
        trimmed.startsWith("~/") -> homeDir().resolve(trimmed.removePrefix("~/"))
        else -> {
            val p = Paths.get(trimmed)
            if (p.isAbsolute) p.normalize() else base.resolve(p).normalize()
        }
    }
}
