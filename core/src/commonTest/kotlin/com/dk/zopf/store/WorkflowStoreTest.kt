package com.dk.zopf.store

import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.PermissionMode
import com.dk.zopf.model.Position
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.SchemaField
import com.dk.zopf.model.SchemaFieldType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.validate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowStoreTest {
    private val tempDirs = mutableListOf<Path>()

    private fun newStore(): Pair<Workspace, WorkflowStore> {
        val dir = Files.createTempDirectory("zopf-store").also { tempDirs.add(it) }
        val workspace = Workspace.create(dir.resolve("ws"))
        return workspace to WorkflowStore(workspace)
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private val sample =
        Workflow(
            name = "refactor-api",
            description = "Analyze the diff, apply fixes, notify",
            repos = listOf(RepoRef("app", "~/dev/zopf"), RepoRef("kuiver", "~/dev/kuiver")),
            skills = listOf("~/.claude/skills/code-review"),
            defaults = NodeDefaults(model = "opus", permissionMode = PermissionMode.ACCEPT_EDITS),
            nodes =
                listOf(
                    WorkflowNode(
                        id = "analyze",
                        type = NodeType.AGENT,
                        title = "Analyze diff",
                        position = Position(40f, 120f),
                        repo = "app",
                        alsoRead = listOf("kuiver"),
                        allowedTools = listOf("Read", "Bash(git diff *)"),
                        skills = listOf("code-review"),
                        prompt = "Review the staged diff.\nList the risky changes.\n",
                    ),
                    WorkflowNode(
                        id = "fix",
                        type = NodeType.AGENT,
                        promptFile = "prompts/fix.md",
                    ),
                    WorkflowNode(id = "build", type = NodeType.SHELL, command = "./gradlew build", repo = "app"),
                    WorkflowNode(id = "ok", type = NodeType.BRANCH, expression = "\${build.exitCode} == 0"),
                    WorkflowNode(id = "approve", type = NodeType.GATE, title = "Ship it?"),
                    WorkflowNode(
                        id = "notify",
                        type = NodeType.CONNECTOR,
                        connector = "slack-post",
                        inputs = mapOf("channel" to "#eng", "text" to "\${analyze.result}"),
                    ),
                ),
            edges =
                listOf(
                    WorkflowEdge("analyze", "build"),
                    WorkflowEdge("build", "ok"),
                    WorkflowEdge("ok", "approve", condition = true),
                    WorkflowEdge("approve", "notify"),
                ),
        )

    @Test
    fun `a workflow round trips through yaml`() {
        val (_, store) = newStore()
        store.save(sample)
        assertEquals(sample, store.load("refactor-api"))
    }

    @Test
    fun `a multi-line prompt is written as a block scalar`() {
        val (_, store) = newStore()
        store.save(sample)
        val text = store.fileFor("refactor-api").readText()

        assertContains(text, "prompt: |")
        assertContains(text, "Review the staged diff.")
    }

    @Test
    fun `a schema field writes and reads back the same`() {
        val (_, store) = newStore()
        val node =
            WorkflowNode(
                "review",
                NodeType.AGENT,
                schema = listOf(SchemaField("severity"), SchemaField("count", SchemaFieldType.NUMBER)),
            )
        val workflow = Workflow(name = "shaped", nodes = listOf(node))

        store.save(workflow)
        val text = store.fileFor("shaped").readText()

        assertContains(text, "- name: severity\n")
        assertContains(text, "- name: count\n        type: number")
        assertFalse("required" in text, text)
        assertFalse("description" in text, text)

        assertEquals(workflow, store.load("shaped"))
    }

    @Test
    fun `defaults are omitted from the file`() {
        val (_, store) = newStore()
        store.save(Workflow(name = "bare"))
        val text = store.fileFor("bare").readText()
        assertEquals("name: bare", text.trim())
    }

    @Test
    fun `an unknown key is ignored`() {
        val (workspace, store) = newStore()
        workspace.workflowsDir.resolve("future.yaml").writeText(
            """
            name: "future"
            somethingAddedLater: true
            nodes:
              - id: "a"
                type: "claude"
                unknownNodeField: 3
            """.trimIndent(),
        )
        val loaded = store.load("future")
        assertNotNull(loaded)
        assertEquals(1, loaded.nodes.size)
        assertEquals(NodeType.AGENT, loaded.nodes.single().type)
    }

    @Test
    fun `a legacy claude node parses as an agent node`() {
        val (workspace, store) = newStore()
        workspace.workflowsDir.resolve("old.yaml").writeText(
            """
            name: old
            nodes:
              - id: review
                type: claude
                prompt: Look at the diff.
            """.trimIndent(),
        )

        val loaded = checkNotNull(store.load("old"))
        assertEquals(NodeType.AGENT, loaded.nodes.single().type)
        assertNull(loaded.nodes.single().provider, "the old spelling names no CLI, and the default is claude")

        store.save(loaded)
        assertContains(store.fileFor("old").readText(), "type: agent")
    }

    @Test
    fun `an unknown node type is refused`() {
        val (workspace, store) = newStore()
        workspace.workflowsDir.resolve("odd.yaml").writeText(
            """
            name: odd
            nodes:
              - id: a
                type: gemini
            """.trimIndent(),
        )

        val broken = store.list().broken.single()
        assertContains(broken.message, "gemini")
        assertContains(broken.message, "agent")
    }

    @Test
    fun `a broken file is listed as broken`() {
        val (workspace, store) = newStore()
        store.save(sample)
        workspace.workflowsDir.resolve("broken.yaml").writeText("nodes: [oh no\n")

        val listing = store.list()
        assertEquals(listOf("refactor-api"), listing.workflows.map { it.name })
        assertEquals(1, listing.broken.size)
        assertEquals(
            "broken.yaml",
            listing.broken
                .single()
                .file.fileName
                .toString(),
        )
    }

    @Test
    fun `the filename wins over the name field`() {
        val (workspace, store) = newStore()
        workspace.workflowsDir.resolve("renamed-in-finder.yaml").writeText("name: \"old-name\"\n")
        assertEquals("renamed-in-finder", store.load("renamed-in-finder")?.name)
    }

    @Test
    fun `create slugifies the name and refuses to clobber`() {
        val (_, store) = newStore()
        val created = store.create("My Cool Workflow!").getOrThrow()
        assertEquals("my-cool-workflow", created.name)
        assertTrue(store.create("My Cool Workflow!").isFailure)
        assertTrue(store.create("  ").isFailure)
    }

    @Test
    fun `rename moves the file and removes the old one`() {
        val (_, store) = newStore()
        store.save(sample)
        val renamed = store.rename(sample, "shipped").getOrThrow()

        assertEquals("shipped", renamed.name)
        assertNull(store.load("refactor-api"))
        assertEquals(sample.nodes.size, store.load("shipped")?.nodes?.size)
    }

    @Test
    fun `moving to another workspace absolutizes repo paths`() {
        val (source, sourceStore) = newStore()
        val (target, _) = newStore()
        val relative = sample.copy(repos = listOf(RepoRef("app", "sub/checkout")))
        sourceStore.save(relative)

        val moved = sourceStore.moveTo(relative, target).getOrThrow()

        assertEquals(source.root.resolve("sub/checkout").toString(), moved.repos.single().path)
        assertNull(sourceStore.load("refactor-api"))
        assertNotNull(WorkflowStore(target).load("refactor-api"))
    }
}

class UnknownKeyTest {
    @Test
    fun `an unknown node key is named with its node`() {
        val stray =
            unknownKeysIn(
                """
                name: w
                nodes:
                  - id: review
                    type: claude
                    promtFile: prompts/review.md
                """.trimIndent(),
            )

        assertEquals(listOf(UnknownKey("promtFile", "review")), stray)
    }

    @Test
    fun `an unknown top-level key is named`() {
        val stray = unknownKeysIn("name: w\nreposs: []\n")

        assertEquals(listOf(UnknownKey("reposs", "the workflow")), stray)
    }

    @Test
    fun `an unknown edge key is named`() {
        val stray =
            unknownKeysIn(
                """
                name: w
                edges:
                  - { from: a, to: b, wen: true }
                """.trimIndent(),
            )

        assertEquals(listOf(UnknownKey("wen", "edge 1")), stray)
    }

    @Test
    fun `a file zopf wrote reports nothing`() {
        val yaml =
            """
            name: w
            description: hi
            repos:
              - { id: app, path: ~/dev/app }
            defaults:
              model: opus
            nodes:
              - id: build
                type: shell
                command: ./gradlew test
                timeoutSeconds: 600
            edges:
              - { from: build, to: ship, on: failure, when: true }
            """.trimIndent()

        assertEquals(emptyList(), unknownKeysIn(yaml))
    }

    @Test
    fun `a file that is not a workflow reports nothing`() {
        assertTrue(unknownKeysIn("- just\n- a list\n").isEmpty())
        assertTrue(unknownKeysIn("{{{ not yaml").isEmpty())
    }
}

class TemplatesTest {
    @Test
    fun `every offered template is packaged`() {
        assertTrue(Templates.names.isNotEmpty(), "no templates declared")
        Templates.names.forEach { assertTrue(Templates.read(it).isNotBlank(), "$it read back blank") }
    }

    @Test
    fun `every template matches the editor's output`() {
        Templates.names.forEach { name ->
            val yaml = Templates.read(name)
            assertEquals(encodeWorkflow(decodeWorkflow(yaml)), yaml, name)
        }
    }

    @Test
    fun `every template validates in a bare workspace`() {
        Templates.names.forEach { name ->
            val errors =
                Templates
                    .workflow(name, name)
                    .validate(connector = { null })
                    .filter { it.severity == WorkflowIssue.Severity.ERROR }
            assertTrue(errors.isEmpty(), "$name: ${errors.joinToString { it.message }}")
        }
    }

    @Test
    fun `a template's label is its title cased name`() {
        assertEquals("Fix failing tests", Templates.label("fix-failing-tests"))
    }

    @Test
    fun `a template becomes a workflow under the given name`() {
        assertEquals("my-checks", Templates.workflow("fix-failing-tests", "my-checks").name)
    }
}
