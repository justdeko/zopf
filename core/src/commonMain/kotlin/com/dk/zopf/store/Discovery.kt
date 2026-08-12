package com.dk.zopf.store

import com.dk.zopf.model.Workflow
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.useLines

data class DiscoveredSkill(
    val name: String,
    val path: Path,
    val source: String,
)

const val PERSONAL_SKILL_SOURCE = "personal"

const val SKILL_MANIFEST = "SKILL.md"

fun availableSkills(
    workspace: Workspace,
    workflow: Workflow,
): List<DiscoveredSkill> =
    (declaredSkills(workspace, workflow) + discoverSkills(workspace, workflow))
        .distinctBy(DiscoveredSkill::name)

fun skillMustBeNamed(dir: Path): Boolean =
    runCatching {
        dir.resolve(SKILL_MANIFEST).useLines { lines ->
            lines.take(FRONTMATTER_LINES).any {
                it.startsWith("disable-model-invocation:") && it.substringAfter(':').trim() == "true"
            }
        }
    }.getOrDefault(false)

private const val FRONTMATTER_LINES = 40

fun discoverSkills(
    workspace: Workspace,
    workflow: Workflow,
): List<DiscoveredSkill> {
    val roots =
        buildList {
            workflow.repos.forEach { repo ->
                add(workspace.resolvePath(repo.path).resolve(".claude/skills") to repo.id)
            }
            workspace.selfRepo?.let { add(it.resolve(".claude/skills") to SELF_REPO_ID) }
            add(workspace.skillsDir to workspace.name)
            add(homeDir().resolve(".claude/skills") to PERSONAL_SKILL_SOURCE)
        }

    return roots
        .flatMap { (root, source) -> skillsIn(root).map { DiscoveredSkill(it.name, it, source) } }
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase() }
}

fun declaredSkills(
    workspace: Workspace,
    workflow: Workflow,
): List<DiscoveredSkill> =
    workflow.skills.map { raw ->
        val path = workspace.resolvePath(raw)
        DiscoveredSkill(path.name, path, "workflow")
    }

private fun skillsIn(root: Path): List<Path> {
    if (!root.isDirectory()) return emptyList()
    return runCatching {
        root.listDirectoryEntries().filter { isSkillDir(it) }
    }.getOrDefault(emptyList())
}

fun isSkillDir(dir: Path): Boolean = dir.isDirectory() && dir.resolve(SKILL_MANIFEST).exists()

fun knownRepoPaths(workspace: Workspace): List<String> =
    WorkflowStore(workspace)
        .list()
        .workflows
        .flatMap { it.repos }
        .map { it.path }
        .distinct()
        .sorted()
