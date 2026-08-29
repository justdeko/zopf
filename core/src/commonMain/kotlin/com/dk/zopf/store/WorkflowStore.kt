package com.dk.zopf.store

import com.dk.zopf.model.Workflow
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

data class BrokenWorkflow(
    val file: Path,
    val message: String,
)

data class WorkflowListing(
    val workflows: List<Workflow>,
    val broken: List<BrokenWorkflow>,
)

class WorkflowStore(
    private val workspace: Workspace,
) {
    fun list(): WorkflowListing {
        val dir = workspace.workflowsDir
        if (!dir.exists()) return WorkflowListing(emptyList(), emptyList())

        val files =
            dir
                .listDirectoryEntries()
                .filter { it.isRegularFile() && it.extension in YAML_EXTENSIONS }
                .sortedBy { it.nameWithoutExtension.lowercase() }

        val workflows = mutableListOf<Workflow>()
        val broken = mutableListOf<BrokenWorkflow>()
        for (file in files) {
            runCatching { decode(file) }
                .onSuccess { workflows += it }
                .onFailure { broken += BrokenWorkflow(file, it.message ?: it::class.simpleName.orEmpty()) }
        }
        return WorkflowListing(workflows, broken)
    }

    fun load(name: String): Workflow? {
        val file = fileFor(name)
        return if (file.exists()) decode(file) else null
    }

    fun save(workflow: Workflow) {
        workspace.workflowsDir.createDirectories()
        fileFor(workflow.name).writeTextAtomically(encodeWorkflow(workflow))
    }

    fun create(name: String): Result<Workflow> {
        val slug = slugify(name)
        if (slug.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        if (fileFor(slug).exists()) {
            return Result.failure(IllegalStateException("A workflow named \"$slug\" already exists"))
        }
        val workflow = Workflow(name = slug)
        save(workflow)
        return Result.success(workflow)
    }

    fun rename(
        workflow: Workflow,
        newName: String,
    ): Result<Workflow> {
        val slug = slugify(newName)
        if (slug.isBlank()) return Result.failure(IllegalArgumentException("Name cannot be empty"))
        if (slug == workflow.name) return Result.success(workflow)
        if (fileFor(slug).exists()) {
            return Result.failure(IllegalStateException("A workflow named \"$slug\" already exists"))
        }
        val renamed = workflow.copy(name = slug)
        save(renamed)
        fileFor(workflow.name).deleteIfExists()
        return Result.success(renamed)
    }

    fun delete(name: String) {
        fileFor(name).deleteIfExists()
    }

    fun moveTo(
        workflow: Workflow,
        target: Workspace,
    ): Result<Workflow> {
        val targetStore = WorkflowStore(target)
        if (targetStore.fileFor(workflow.name).exists()) {
            return Result.failure(IllegalStateException("\"${workflow.name}\" already exists in ${target.name}"))
        }
        val rebased =
            workflow.copy(
                repos = workflow.repos.map { it.copy(path = workspace.resolvePath(it.path).toString()) },
                skills = workflow.skills.map { workspace.resolvePath(it).toString() },
            )
        target.workflowsDir.createDirectories()
        targetStore.save(rebased)
        fileFor(workflow.name).deleteIfExists()
        return Result.success(rebased)
    }

    fun fileFor(name: String): Path = workspace.workflowsDir.resolve("${slugify(name)}.yaml")

    fun unknownKeys(name: String): List<UnknownKey> {
        val file = fileFor(name).takeIf { it.exists() } ?: return emptyList()
        return runCatching { unknownKeysIn(file.readText()) }.getOrDefault(emptyList())
    }

    private fun decode(file: Path): Workflow {
        val parsed = decodeWorkflow(file.readText())
        return if (parsed.name == file.nameWithoutExtension) parsed else parsed.copy(name = file.nameWithoutExtension)
    }

    private companion object {
        val YAML_EXTENSIONS = setOf("yaml", "yml")
    }
}

fun slugify(raw: String): String =
    raw
        .trim()
        .lowercase()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
