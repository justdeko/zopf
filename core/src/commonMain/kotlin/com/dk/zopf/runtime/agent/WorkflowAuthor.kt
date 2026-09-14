package com.dk.zopf.runtime.agent

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Sandbox
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.workflow.Prompts
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workflow.decodeWorkflow
import com.dk.zopf.store.workflow.slugify
import com.dk.zopf.store.workspace.ConnectorStore
import com.dk.zopf.store.workspace.SELF_REPO_ID
import com.dk.zopf.store.workspace.Workspace
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private const val DRAFT_TIMEOUT_SECONDS = 900

private const val GUIDE_DIR = "workflow-guide"

private val GUIDE_FILES = listOf("SKILL.md", "references/schema.md", "references/patterns.md")

private val READ_ONLY_TOOLS = listOf("Read", "Grep", "Glob")

private val FENCE = Regex("```(?:yaml|yml)?\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

object WorkflowAuthor {
    const val NODE_ID = "draft"

    const val WORKSPACE_REPO = "workspace"

    const val GUIDE_REPO = "guide"

    fun workflowFor(
        name: String,
        prompt: String,
        workspace: Workspace,
        guide: Path = unpackGuide(),
    ): Workflow =
        Workflow(
            name = "draft:${slugify(name)}",
            repos =
                listOf(
                    RepoRef(WORKSPACE_REPO, workspace.root.toString()),
                    RepoRef(GUIDE_REPO, guide.toString()),
                ),
            nodes =
                listOf(
                    WorkflowNode(
                        id = NODE_ID,
                        type = NodeType.AGENT,
                        title = "Drafting ${slugify(name)}",
                        repo = WORKSPACE_REPO,
                        alsoRead = listOf(GUIDE_REPO, SELF_REPO_ID),
                        sandbox = Sandbox.READ_ONLY,
                        allowedTools = READ_ONLY_TOOLS,
                        timeoutSeconds = DRAFT_TIMEOUT_SECONDS,
                        prompt = prompt,
                    ),
                ),
        )

    fun createPrompt(
        name: String,
        description: String,
        workspace: Workspace,
        guide: Path = unpackGuide(),
    ): String =
        Prompts.render(
            "workflow-author",
            "name" to slugify(name),
            "description" to description.trim(),
            "context" to contextOf(workspace),
            "workspace" to workspace.root.toString(),
            "guide" to guide.toString(),
        )

    fun repairPrompt(
        problems: List<String>,
        draft: String,
        guide: Path = unpackGuide(),
    ): String =
        Prompts.render(
            "workflow-repair",
            "problems" to problems.joinToString("\n") { "- $it" },
            "draft" to draft.trim(),
            "guide" to guide.toString(),
        )

    fun draftFrom(
        answer: String,
        name: String,
    ): Result<Workflow> {
        val yaml =
            yamlIn(answer)
                ?: return Result.failure(
                    IllegalStateException("Nothing in that answer was a workflow"),
                )
        return runCatching { decodeWorkflow(yaml).copy(name = slugify(name)) }
    }

    fun yamlIn(answer: String): String? {
        val blocks = FENCE.findAll(answer).map { it.groupValues[1] }.filter { "nodes:" in it }
        return blocks.maxByOrNull { it.length } ?: answer.takeIf { "nodes:" in it }
    }

    fun unpackGuide(root: Path = AppPaths.appSupport): Path {
        val dir = root.resolve(GUIDE_DIR)
        GUIDE_FILES.forEach { name ->
            val file = dir.resolve(name)
            file.parent.createDirectories()
            file.writeText(read(name))
        }
        return dir
    }

    private fun read(name: String): String {
        val path = "/zopf-workflows/$name"
        val stream =
            checkNotNull(WorkflowAuthor::class.java.getResourceAsStream(path)) {
                "$path isn't packaged — plugins/zopf/skills isn't on :core's resources"
            }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun contextOf(workspace: Workspace): String {
        val workflows = WorkflowStore(workspace).list().workflows
        val connectors = ConnectorStore(workspace).list().connectors
        val repos = workflows.flatMap { it.repos }.distinctBy { it.id }

        return buildList {
            add(
                when {
                    workflows.isEmpty() -> "This workspace has no workflows in it yet, so this one sets the house style."
                    else ->
                        "Workflows already here, worth reading for the house style: " +
                            workflows.joinToString { it.name } + "."
                },
            )
            if (connectors.isNotEmpty()) {
                add(
                    "Connectors it can call, by name: " +
                        connectors.joinToString { "${it.name} (${it.manifest.description.ifBlank { "no description" }})" } +
                        ". A connector node names one of those and nothing else.",
                )
            }
            if (repos.isNotEmpty()) {
                add("Repos the other workflows point at: " + repos.joinToString { "${it.id} → ${it.path}" } + ".")
            }
        }.joinToString("\n")
    }
}
