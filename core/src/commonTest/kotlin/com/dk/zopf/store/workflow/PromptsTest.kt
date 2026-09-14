package com.dk.zopf.store.workflow

import com.dk.zopf.runtime.agent.SelectedSkill
import com.dk.zopf.runtime.agent.SkillPlan
import com.dk.zopf.runtime.agent.WorkflowAuthor
import com.dk.zopf.runtime.exec.ConnectorScaffold
import com.dk.zopf.store.workspace.Workspace
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptsTest {
    private val dir = Files.createTempDirectory("zopf-prompts")

    private val workspace = Workspace.create(dir.resolve("ws"))

    private val guide = WorkflowAuthor.unpackGuide(dir)

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun everyPrompt() =
        listOf(
            "connector-create" to ConnectorScaffold.createPrompt("slack-post"),
            "connector-fix" to ConnectorScaffold.fixPrompt("slack-post", "not JSON at offset 4"),
            "workflow-author" to WorkflowAuthor.createPrompt("lint-fix", "run the linter", workspace, guide),
            "workflow-repair" to WorkflowAuthor.repairPrompt(listOf("tests: no command"), "name: demo", guide),
            "skill-use-one" to useSentenceVia(listOf("code-review")),
            "skill-use-many" to useSentenceVia(listOf("a", "b", "c")),
        )

    @Test
    fun `every prompt is packaged and fully interpolated`() {
        everyPrompt().forEach { (name, rendered) ->
            assertTrue(rendered.isNotBlank(), "$name rendered blank")
            assertFalse(
                Regex("""\{\{[a-zA-Z]+}}""").containsMatchIn(rendered),
                "$name still has an unfilled placeholder:\n$rendered",
            )
        }
    }

    @Test
    fun `the contract keeps its interpolation syntax`() {
        val contract = Prompts.read("connector-contract")
        assertTrue("\${node.result}" in contract, contract)
        assertTrue("\${node.<key>}" in contract, contract)
    }

    @Test
    fun `the connector prompts carry the contract`() {
        val create = ConnectorScaffold.createPrompt("slack-post")

        listOf("slack-post", "connector.json", "stdin", "\"result\"", "\"error\"", "\${node.result}")
            .forEach { assertTrue(it in create, "the create prompt never says $it:\n$create") }
        assertTrue("Ask me what it should do" in create, create)

        val fix = ConnectorScaffold.fixPrompt("slack-post", "Unexpected JSON token at offset 12")

        listOf("Unexpected JSON token at offset 12", "connector.json", "re-test")
            .forEach { assertTrue(it in fix, "the fix prompt never says $it:\n$fix") }
    }

    @Test
    fun `an unsupplied hole is left standing`() {
        val rendered = Prompts.render("connector-create", "name" to "slack-post")
        assertTrue("{{contract}}" in rendered, "a missing value should be visible, not invisible")
        assertTrue("slack-post" in rendered)
    }

    @Test
    fun `a trailing newline is not part of the prompt`() {
        val one = Prompts.render("skill-use-one", "skill" to "code-review")
        assertEquals("Use the code-review skill.", one)
    }

    @Test
    fun `a missing prompt fails loudly`() {
        val thrown = runCatching { Prompts.read("no-such-prompt") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "got $thrown")
        assertTrue("prompts/no-such-prompt.md" in thrown.message.orEmpty(), thrown.message.orEmpty())
    }

    private fun useSentenceVia(names: List<String>): String =
        SkillPlan(
            selected =
                names.map {
                    SelectedSkill(
                        name = it,
                        dir = Paths.get("/tmp/skills/$it"),
                        source = "test",
                        ambient = false,
                        mustBeNamed = false,
                    )
                },
            unresolved = emptyList(),
        ).withInvocation("Go.")
}
