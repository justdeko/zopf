package com.dk.zopf.ui.runs

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.runtime.ConsoleEntry
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.PendingPermission
import com.dk.zopf.runtime.PendingQuestion
import com.dk.zopf.runtime.PermissionRequest
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.showing
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunConsoleTest {
    private fun node(
        type: NodeType,
        nodeId: String,
        status: RunStatus = RunStatus.WAITING,
    ) = NodeRun(
        id = "run-1",
        workflowName = "demo",
        nodeId = nodeId,
        nodeTitle = nodeId.replaceFirstChar { it.uppercase() },
        nodeType = type,
        cwd = Paths.get("/tmp"),
    ).also { it.showing(status = status) }

    private fun asking(
        question: String = "Which branch should I review?",
        choices: List<String> = emptyList(),
        default: String = "",
    ) = node(NodeType.INPUT, "ask").also {
        it.showing(status = RunStatus.WAITING, question = PendingQuestion(question, choices, default))
    }

    private fun blocked(
        tool: String = "Bash",
        command: String = "./gradlew build",
    ): Pair<NodeRun, CompletableFuture<Boolean>> {
        val answer = CompletableFuture<Boolean>()
        val run =
            node(NodeType.AGENT, "analyze").also {
                it.showing(
                    status = RunStatus.WAITING,
                    permission = PendingPermission(PermissionRequest(tool, command, "toolu_01", "s1"), answer),
                )
            }
        return run to answer
    }

    private fun agent(provider: AgentProviderId) =
        NodeRun(
            id = "run-1",
            workflowName = "demo",
            nodeId = "analyze",
            nodeTitle = "Analyze",
            nodeType = NodeType.AGENT,
            cwd = Paths.get("/tmp"),
            provider = provider,
        ).also { it.showing(status = RunStatus.WAITING, sessionId = "s-1") }

    private fun printing(lines: Int) =
        node(NodeType.SHELL, "build", RunStatus.SUCCEEDED).also { run ->
            repeat(lines) { i -> run.entries.add(ConsoleEntry.Output(i.toLong(), "line $i", isError = false)) }
        }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.console(
        run: NodeRun,
        onAnswer: (String?) -> Unit = {},
        onDecide: (Boolean, Boolean) -> Unit = { _, _ -> },
    ) {
        setContent { ZopfTheme { RunConsole(run, {}, {}, {}, {}, onAnswer = onAnswer, onDecide = onDecide) } }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.isMissing(text: String) = runCatching { onNodeWithText(text).assertIsDisplayed() }.isFailure

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a free-text question is prefilled with the default, and sends whatever replaces it`() =
        runDesktopComposeUiTest {
            var answered: String? = null
            console(asking(default = "main"), onAnswer = { answered = it })

            onNodeWithText("main").assertIsDisplayed()
            onNodeWithText("Answer").performClick()
            assertEquals("main", answered)

            onNodeWithText("main").performTextReplacement("release-2")
            onNodeWithText("Answer").performClick()
            assertEquals("release-2", answered)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a question with choices offers them instead of a text field`() =
        runDesktopComposeUiTest {
            var answered: String? = null
            val node = asking(question = "Deploy where?", choices = listOf("staging", "prod", "skip"), default = "staging")
            console(node, onAnswer = { answered = it })

            onNodeWithText("prod").performClick()
            onNodeWithText("Answer").performClick()

            assertEquals("prod", answered)
            assertTrue(isMissing("Your answer…"), "a pick-one shouldn't also offer a free-text field")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `cancelling sends no answer at all`() =
        runDesktopComposeUiTest {
            var answered: String? = "untouched"
            var called = false
            console(asking(), onAnswer = {
                answered = it
                called = true
            })

            onNodeWithText("Cancel run").performClick()

            assertTrue(called)
            assertEquals(null, answered)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an empty answer can't be sent, and the follow-up bar does not appear in its place`() =
        runDesktopComposeUiTest {
            console(asking())

            onNodeWithText("Answer").assertIsNotEnabled()
            onNodeWithText("Cancel run").assertIsDisplayed()
            assertTrue(isMissing("Finish"), "the follow-up bar belongs to a claude session, not to a question")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the pending tool call is shown with what it would run, and takes the follow-up bar's place`() =
        runDesktopComposeUiTest {
            val (node, _) = blocked()
            console(node)

            onNodeWithText("Bash wants to run").assertIsDisplayed()
            onNodeWithText("./gradlew build").assertIsDisplayed()
            assertTrue(isMissing("Finish"), "the follow-up bar should not show while a tool call is pending")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Allow and Deny each answer the call that is holding the process open`() {
        fun clicking(button: String): Boolean? {
            var decided: Boolean? = null
            runDesktopComposeUiTest {
                val (node, answer) = blocked()
                console(node, onDecide = { allow, _ -> answer.complete(allow) })
                onNodeWithText(button).performClick()
                decided = answer.getNow(null)
            }
            return decided
        }

        assertEquals(true, clicking("Allow"))
        assertEquals(false, clicking("Deny"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `allowing for the whole run says so distinctly`() =
        runDesktopComposeUiTest {
            val (node, _) = blocked()
            var rest: Boolean? = null
            console(node, onDecide = { _, forRest -> rest = forRest })

            onNodeWithText("Allow for this run").performClick()

            assertEquals(true, rest, "the button has to be distinguishable from a one-off Allow")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a parked claude turn offers a follow-up`() =
        runDesktopComposeUiTest {
            console(agent(AgentProviderId.CLAUDE))
            onNodeWithText("Finish").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a codex node is offered the terminal, but not a follow-up it could not honour`() =
        runDesktopComposeUiTest {
            console(agent(AgentProviderId.CODEX))

            assertTrue(isMissing("Finish"), "codex reads no stdin, so there is nowhere to send a follow-up")
            onNodeWithText("Take over in Terminal").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a finished run opens at its tail, and the jumps go to either end`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            console(printing(200))

            onNodeWithText("line 199").assertIsDisplayed()
            assertTrue(isMissing("line 0"), "the transcript opened at the beginning")

            onNodeWithContentDescription("Jump to top").performClick()
            waitForIdle()
            onNodeWithText("line 0").assertIsDisplayed()

            onNodeWithContentDescription("Jump to bottom").performClick()
            waitForIdle()
            onNodeWithText("line 199").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a transcript that fits shows no controls at all`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            console(printing(3))

            listOf("Jump to top", "Jump to bottom").forEach {
                assertTrue(
                    runCatching { onNodeWithContentDescription(it).assertIsDisplayed() }.isFailure,
                    "a short transcript offered a jump that would do nothing",
                )
            }
        }
}
