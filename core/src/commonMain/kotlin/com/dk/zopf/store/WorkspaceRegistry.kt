package com.dk.zopf.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
private data class RegistryEntry(
    val path: String,
    val lastOpened: Long = 0L,
)

@Serializable
private data class RegistryFile(
    val workspaces: List<RegistryEntry> = emptyList(),
    val activePath: String? = null,
)

data class OpenWorkspace(
    val path: Path,
    val workspace: Workspace?,
    val lastOpened: Long,
) {
    val isOnline: Boolean get() = workspace != null
    val displayName: String get() = workspace?.name ?: path.fileName?.toString().orEmpty()
}

class WorkspaceRegistry(
    private val file: Path = AppPaths.workspacesFile,
) {
    private val _workspaces = MutableStateFlow<List<OpenWorkspace>>(emptyList())
    val workspaces: StateFlow<List<OpenWorkspace>> = _workspaces.asStateFlow()

    private val _active = MutableStateFlow<OpenWorkspace?>(null)
    val active: StateFlow<OpenWorkspace?> = _active.asStateFlow()

    fun load() {
        val stored = readFile()
        val entries =
            stored.workspaces.ifEmpty {
                val created = Workspace.create(AppPaths.defaultWorkspace)
                listOf(RegistryEntry(created.root.toString(), System.currentTimeMillis()))
            }

        _workspaces.value =
            entries
                .map { entry ->
                    val path = Paths.get(entry.path)
                    OpenWorkspace(path, Workspace.open(path), entry.lastOpened)
                }.sortedByDescending { it.lastOpened }

        _active.value = _workspaces.value.firstOrNull { it.path.toString() == stored.activePath }
            ?: _workspaces.value.firstOrNull { it.isOnline }
            ?: _workspaces.value.firstOrNull()

        persist()
    }

    fun add(
        dir: Path,
        createIfMissing: Boolean = true,
    ): OpenWorkspace? {
        val normalized = dir.toAbsolutePath().normalize()
        val workspace =
            Workspace.open(normalized)
                ?: if (createIfMissing) Workspace.create(normalized) else return null

        val entry = OpenWorkspace(workspace.root, workspace, System.currentTimeMillis())
        _workspaces.value =
            (_workspaces.value.filterNot { it.path == workspace.root } + entry)
                .sortedByDescending { it.lastOpened }
        _active.value = entry
        persist()
        return entry
    }

    fun remove(path: Path) {
        _workspaces.value = _workspaces.value.filterNot { it.path == path }
        if (_active.value?.path == path) {
            _active.value = _workspaces.value.firstOrNull { it.isOnline } ?: _workspaces.value.firstOrNull()
        }
        persist()
    }

    fun setActive(path: Path) {
        val next = _workspaces.value.firstOrNull { it.path == path } ?: return
        _workspaces.value =
            _workspaces.value
                .map { if (it.path == path) it.copy(lastOpened = System.currentTimeMillis()) else it }
                .sortedByDescending { it.lastOpened }
        _active.value = _workspaces.value.firstOrNull { it.path == path } ?: next
        persist()
    }

    fun refresh() {
        _workspaces.value = _workspaces.value.map { it.copy(workspace = Workspace.open(it.path)) }
        _active.value =
            _active.value?.let { current ->
                _workspaces.value.firstOrNull { it.path == current.path }
            }
    }

    private fun readFile(): RegistryFile =
        if (file.exists()) {
            runCatching { zopfJson.decodeFromString(RegistryFile.serializer(), file.readText()) }
                .getOrElse { RegistryFile() }
        } else {
            RegistryFile()
        }

    private fun persist() {
        val payload =
            RegistryFile(
                workspaces = _workspaces.value.map { RegistryEntry(it.path.toString(), it.lastOpened) },
                activePath = _active.value?.path?.toString(),
            )
        runCatching { file.writeTextAtomically(zopfJson.encodeToString(RegistryFile.serializer(), payload)) }
    }
}
