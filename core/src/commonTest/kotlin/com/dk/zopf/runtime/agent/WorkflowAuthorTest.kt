package com.dk.zopf.runtime.agent

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Sandbox
import com.dk.zopf.model.Workflow
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workspace.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowAuthorTest {
    private val dir: Path = Files.createTempDirectory("zopf-author")

    private val workspace = Workspace.create(dir.resolve("ws")).also { it.ensureDirectories() }

    private val guide = WorkflowAuthor.unpackGuide(dir)

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the draft node runs in the workspace and may only read`() {
        val workflow = WorkflowAuthor.workflowFor("Lint Fix", "draft it", workspace, guide)
        val node = workflow.nodes.single()

        assertEquals(NodeType.AGENT, node.type)
        assertEquals(Sandbox.READ_ONLY, node.sandbox)
        assertEquals(workspace.root, workspace.resolveRepo(workflow, node.repo))
        assertTrue(node.allowedTools.none { it == "Write" || it == "Edit" }, node.allowedTools.toString())
    }

    @Test
    fun `the draft node is allowed at the guide and the repo around the workspace`() {
        val workflow = WorkflowAuthor.workflowFor("lint-fix", "draft it", workspace, guide)
        val node = workflow.nodes.single()

        assertEquals(guide, workspace.resolveRepo(workflow, WorkflowAuthor.GUIDE_REPO))
        assertEquals(listOf(WorkflowAuthor.GUIDE_REPO, "self"), node.alsoRead)
    }

    @Test
    fun `the guide is unpacked where the prompt says it is`() {
        listOf("SKILL.md", "references/schema.md", "references/patterns.md").forEach {
            assertTrue(guide.resolve(it).exists(), "$it isn't packaged")
        }
        assertContains(guide.resolve("SKILL.md").readText(), "zopf workflow")
    }

    @Test
    fun `the prompt names the guide, the workspace and what is already in it`() {
        WorkflowStore(workspace).save(Workflow(name = "verify", repos = listOf(RepoRef("app", "~/dev/app"))))

        val prompt = WorkflowAuthor.createPrompt("lint-fix", "run the linter", workspace, guide)

        assertContains(prompt, "lint-fix")
        assertContains(prompt, "run the linter")
        assertContains(prompt, guide.toString())
        assertContains(prompt, workspace.root.toString())
        assertContains(prompt, "verify")
        assertContains(prompt, "app → ~/dev/app")
    }

    @Test
    fun `an empty workspace says so rather than listing nothing`() {
        assertContains(WorkflowAuthor.createPrompt("first", "do a thing", workspace, guide), "no workflows in it yet")
    }

    @Test
    fun `the repair prompt carries the problems and the draft`() {
        val prompt = WorkflowAuthor.repairPrompt(listOf("tests: no command"), "name: demo\nnodes: []", guide)

        assertContains(prompt, "- tests: no command")
        assertContains(prompt, "name: demo")
    }

    @Test
    fun `the workflow is read out of whatever the answer is shaped like`() {
        val body = "name: demo\nnodes:\n  - id: build\n    type: shell\n    command: make\n"
        listOf(
            "plain fence" to "here you go\n\n```\n$body```\n",
            "yaml fence" to "```yaml\n$body```",
            "yml fence" to "```yml\n$body```",
            "words after it" to "```yaml\n$body```\n\nRun it with zopf run demo.",
            "no fence at all" to body,
            "an example first" to "```\nzopf run demo\n```\n\n```yaml\n$body```",
        ).forEach { (shape, answer) ->
            assertEquals(
                "make",
                WorkflowAuthor
                    .draftFrom(answer, "demo")
                    .getOrThrow()
                    .nodes
                    .single()
                    .command,
                shape,
            )
        }
    }

    @Test
    fun `an answer with no workflow in it says what to do`() {
        assertNull(WorkflowAuthor.yamlIn("I'd rather not."))

        val failure = WorkflowAuthor.draftFrom("I'd rather not.", "demo").exceptionOrNull()

        assertContains(failure?.message.orEmpty(), "Nothing in that answer was a workflow")
    }

    @Test
    fun `the draft is named after the file it will be written to`() {
        val drafted = WorkflowAuthor.draftFrom("```yaml\nname: whatever\nnodes: []\n```", "Lint Fix").getOrThrow()

        assertEquals("lint-fix", drafted.name)
    }

    @Test
    fun `a draft that isn't a workflow at all fails with the parse error`() {
        val failure = WorkflowAuthor.draftFrom("```yaml\nnodes: [\n```", "demo").exceptionOrNull()

        assertTrue(failure != null, "\"nodes: [\" isn't yaml")
    }
}
