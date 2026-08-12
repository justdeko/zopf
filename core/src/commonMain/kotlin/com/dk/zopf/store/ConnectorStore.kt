package com.dk.zopf.store

import com.dk.zopf.model.ConnectorManifest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

const val CONNECTOR_MANIFEST = "connector.json"

const val SHARED_CONNECTOR_SOURCE = "shared"

data class Connector(
    val manifest: ConnectorManifest,
    val dir: Path,
    val source: String,
) {
    val name: String get() = manifest.name
    val script: Path get() = dir.resolve(manifest.run)
    val hasScript: Boolean get() = script.exists()
}

data class BrokenConnector(
    val dir: Path,
    val message: String,
) {
    val name: String get() = dir.name
}

data class ConnectorListing(
    val connectors: List<Connector> = emptyList(),
    val broken: List<BrokenConnector> = emptyList(),
) {
    fun find(name: String): Connector? = connectors.firstOrNull { it.name == name }

    val names: List<String> get() = connectors.map { it.name }

    val isEmpty: Boolean get() = connectors.isEmpty() && broken.isEmpty()
}

class ConnectorStore(
    private val workspace: Workspace?,
    private val sharedRoot: Path = defaultSharedRoot,
) {
    fun roots(): List<Pair<Path, String>> {
        val workspaceDir = workspace?.connectorsDir
        return buildList {
            workspaceDir?.let { add(it to (workspace.name)) }

            if (workspaceDir?.normalize() != sharedRoot.normalize()) add(sharedRoot to SHARED_CONNECTOR_SOURCE)
        }
    }

    fun list(): ConnectorListing {
        val connectors = mutableListOf<Connector>()
        val broken = mutableListOf<BrokenConnector>()

        for ((root, source) in roots()) {
            for (dir in connectorDirs(root)) {
                load(dir, source)
                    .onSuccess { connectors += it }
                    .onFailure { broken += BrokenConnector(dir, it.message ?: it::class.simpleName.orEmpty()) }
            }
        }
        return ConnectorListing(
            connectors = connectors.distinctBy { it.name }.sortedBy { it.name.lowercase() },
            broken = broken.distinctBy { it.dir.name },
        )
    }

    fun find(name: String): Connector? {
        if (name.isBlank()) return null
        return roots()
            .asSequence()
            .map { (root, source) -> root.resolve(name) to source }
            .filter { (dir, _) -> dir.isDirectory() }
            .mapNotNull { (dir, source) -> load(dir, source).getOrNull() }
            .firstOrNull { it.name == name }
    }

    fun create(
        name: String,
        shared: Boolean = false,
    ): Result<Path> {
        val slug = slugify(name)
        if (slug.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty"))

        val root = if (shared) sharedRoot else workspace?.connectorsDir ?: sharedRoot
        val dir = root.resolve(slug)
        if (dir.resolve(CONNECTOR_MANIFEST).exists()) {
            return Result.failure(IllegalStateException("A connector named \"$slug\" already exists"))
        }
        return runCatching { dir.createDirectories() }
    }

    fun delete(connector: Connector) {
        connector.dir.toFile().deleteRecursively()
    }

    fun delete(broken: BrokenConnector) {
        broken.dir.toFile().deleteRecursively()
    }

    private fun connectorDirs(root: Path): List<Path> {
        if (!root.isDirectory()) return emptyList()
        return runCatching {
            root
                .listDirectoryEntries()
                .filter { it.isDirectory() && it.resolve(CONNECTOR_MANIFEST).exists() }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    companion object {
        val defaultSharedRoot: Path get() = AppPaths.defaultWorkspace.resolve("connectors")

        fun load(
            dir: Path,
            source: String = SHARED_CONNECTOR_SOURCE,
        ): Result<Connector> =
            runCatching {
                val file = dir.resolve(CONNECTOR_MANIFEST)
                if (!file.exists()) error("No $CONNECTOR_MANIFEST in ${dir.name}")

                val parsed = zopfJson.decodeFromString(ConnectorManifest.serializer(), file.readText())
                val manifest = if (parsed.name == dir.name) parsed else parsed.copy(name = dir.name)
                val connector = Connector(manifest, dir, source)

                if (!connector.hasScript) error("${manifest.run} is missing")
                connector
            }
    }
}
