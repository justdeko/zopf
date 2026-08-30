package com.dk.zopf.store

import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.PermissionMode
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.addNode
import com.dk.zopf.model.addRepo
import com.dk.zopf.model.exampleWorkflow
import com.dk.zopf.model.renameNode
import com.dk.zopf.model.replaceNode
import com.dk.zopf.model.withPositions
import com.dk.zopf.ui.editor.connect
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorRoundTripTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun newStore(): Pair<Workspace, WorkflowStore> {
        val dir = Files.createTempDirectory("zopf-editor").also { tempDirs.add(it) }
        val workspace = Workspace.create(dir.resolve("ws"))
        return workspace to WorkflowStore(workspace)
    }

    private fun drawExample(): Workflow {
        var w = Workflow(name = "refactor-api", description = "Analyze the diff, apply fixes, notify")
        w = w.addRepo("app", "~/dev/zopf").getOrThrow()
        w = w.addRepo("kuiver", "~/dev/kuiver").getOrThrow()
        w =
            w.copy(
                skills = listOf("~/.claude/skills/code-review"),
                defaults = NodeDefaults(model = "opus", permissionMode = PermissionMode.ACCEPT_EDITS),
            )

        val (withAnalyze, analyze) = w.addNode(NodeType.AGENT)
        w = withAnalyze.renameNode(analyze.id, "analyze").getOrThrow()
        val (withFix, fix) = w.addNode(NodeType.AGENT)
        w = withFix.renameNode(fix.id, "fix").getOrThrow()
        val (withBuild, build) = w.addNode(NodeType.SHELL)
        w = withBuild.renameNode(build.id, "build").getOrThrow()
        val (withOk, ok) = w.addNode(NodeType.BRANCH)
        w = withOk.renameNode(ok.id, "ok").getOrThrow()
        val (withApprove, approve) = w.addNode(NodeType.GATE)
        w = withApprove.renameNode(approve.id, "approve").getOrThrow()
        val (withNotify, notify) = w.addNode(NodeType.CONNECTOR)
        w = withNotify.renameNode(notify.id, "notify").getOrThrow()

        w =
            w.replaceNode(
                w.node("analyze")!!.copy(
                    title = "Analyze diff",
                    repo = "app",
                    alsoRead = listOf("kuiver"),
                    allowedTools = listOf("Read", "Bash(git diff *)"),
                    skills = listOf("code-review"),
                    prompt = "Review the staged diff and list the risky changes.\n",
                ),
            )
        w =
            w.replaceNode(
                w.node("fix")!!.copy(prompt = "Apply the fixes for these findings:\n\${analyze.result}\n"),
            )
        w = w.replaceNode(w.node("build")!!.copy(command = "./gradlew :desktopApp:run", repo = "app"))
        w = w.replaceNode(w.node("ok")!!.copy(expression = "\${build.exitCode} == 0"))
        w = w.replaceNode(w.node("approve")!!.copy(title = "Ship it?"))
        w =
            w.replaceNode(
                w.node("notify")!!.copy(
                    connector = "slack-post",
                    inputs = mapOf("channel" to "#eng", "text" to "\${analyze.result}"),
                ),
            )

        w = w.connect("analyze", "fix").getOrThrow()
        w = w.connect("fix", "build").getOrThrow()
        w = w.connect("build", "ok").getOrThrow()
        w = w.connect("ok", "approve").getOrThrow()
        w = w.connect("approve", "notify").getOrThrow()

        return w.withPositions(mapOf("analyze" to Position(40f, 120f)))
    }

    @Test
    fun `the example workflow drawn in the editor matches the one in the design`() {
        assertEquals(exampleWorkflow, drawExample())
    }

    @Test
    fun `it saves as YAML a person would be willing to read`() {
        val (_, store) = newStore()
        store.save(drawExample())

        val yaml = store.fileFor("refactor-api").readText()

        assertContains(yaml, "prompt: |")
        assertTrue("\\n" !in yaml, "prompts were escaped onto one line:\n$yaml")

        assertContains(yaml, "when: true")

        assertTrue("connector: \"\"" !in yaml, "empty fields were written out:\n$yaml")
        assertTrue("alsoRead: []" !in yaml, "empty lists were written out:\n$yaml")

        assertContains(yaml, "x: 40.0")
    }

    @Test
    fun `saving and reopening gives back the same graph`() {
        val (_, store) = newStore()
        val drawn = drawExample()
        store.save(drawn)

        assertEquals(drawn, store.load("refactor-api"))
    }

    @Test
    fun `an input node keeps its choices and its default`() {
        val (_, store) = newStore()
        val w =
            Workflow(
                name = "release",
                nodes =
                    listOf(
                        WorkflowNode(
                            id = "ask",
                            type = NodeType.INPUT,
                            prompt = "Deploy where?",
                            choices = listOf("staging", "prod", "no"),
                            default = "staging",
                        ),
                    ),
            )
        store.save(w)

        val reopened = assertNotNull(store.load("release")).nodes.single()
        assertEquals(listOf("staging", "prod", "no"), reopened.choices)
        assertEquals("staging", reopened.default)
        assertEquals("Deploy where?", reopened.prompt)
    }

    @Test
    fun `a gate keeps the prompt that says what is being approved`() {
        val (_, store) = newStore()
        store.save(
            Workflow(
                name = "release",
                nodes =
                    listOf(
                        WorkflowNode("review", NodeType.AGENT, prompt = "Look for bugs."),
                        WorkflowNode(
                            id = "approve",
                            type = NodeType.GATE,
                            title = "Apply these fixes?",
                            prompt = "\${review.result}",
                        ),
                    ),
            ),
        )

        val reopened = assertNotNull(store.load("release")).node("approve")
        assertEquals("\${review.result}", assertNotNull(reopened).prompt)
    }

    @Test
    fun `a gate with nothing to show writes no prompt at all`() {
        val (_, store) = newStore()
        store.save(
            Workflow(
                name = "ship",
                nodes = listOf(WorkflowNode("approve", NodeType.GATE, title = "Ship it?")),
            ),
        )

        val yaml = store.fileFor("ship").readText()
        assertTrue("prompt" !in yaml, "an empty prompt was written out:\n$yaml")
    }

    @Test
    fun `a free-text input writes neither of the fields it never set`() {
        val (_, store) = newStore()
        store.save(
            Workflow(
                name = "review",
                nodes = listOf(WorkflowNode("ask", NodeType.INPUT, prompt = "Which branch?")),
            ),
        )

        val yaml = store.fileFor("review").readText()
        assertTrue("choices" !in yaml, "an empty choice list was written out:\n$yaml")
        assertTrue("default" !in yaml, "an empty default was written out:\n$yaml")
    }

    @Test
    fun `creating from a template writes that template's graph under the new name`() {
        val (_, store) = newStore()
        val created = store.create("my-checks", "fix-failing-tests").getOrThrow()

        assertEquals("my-checks", created.name)
        assertEquals(Templates.workflow("fix-failing-tests", "my-checks"), store.load("my-checks"))
        assertTrue(created.nodes.isNotEmpty(), "a template landed with no nodes")
    }

    @Test
    fun `creating without a template still starts on an empty canvas`() {
        val (_, store) = newStore()

        assertEquals(emptyList(), store.create("blank").getOrThrow().nodes)
    }

    @Test
    fun `a workflow saved with no positions reopens without inventing any`() {
        val (_, store) = newStore()
        val w = Workflow(name = "plain").addNode(NodeType.GATE).first
        store.save(w)

        assertEquals(
            null,
            store
                .load("plain")
                ?.nodes
                ?.single()
                ?.position,
        )
    }
}
