package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.Workspace
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeEventsTest {
    private val events: List<AgentEvent> by lazy { ClaudeEvents.parseAll(fixture()) }

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/claude-stream.jsonl")) {
            "claude-stream.jsonl is missing from the test resources"
        }.bufferedReader().readText()

    @Test
    fun `every line of a real stream parses`() {
        assertEquals(29, events.size)
        assertTrue(events.none { it is AgentEvent.NonJson }, "a line failed to parse as JSON")

        val unknown = events.filterIsInstance<AgentEvent.Unknown>().map { it.type }
        assertTrue(unknown.isEmpty(), "unhandled event types: $unknown")
    }

    @Test
    fun `the first event describes the session`() {
        val init = events.first() as AgentEvent.SystemInit
        assertEquals("claude-opus-5", init.model)
        assertEquals("acceptEdits", init.permissionMode)
        assertEquals("2.1.222", init.version)
        assertTrue("Bash" in init.tools)
        assertNotNull(init.sessionId)
    }

    @Test
    fun `assistant text arrives as deltas and then as a complete block`() {
        val deltas = events.filterIsInstance<AgentEvent.TextDelta>()
        assertTrue(deltas.isNotEmpty())
        assertFalse(deltas.any { it.isThinking })

        val firstMessage = events.filterIsInstance<AgentEvent.AssistantMessage>().first()

        assertEquals("Hi there, running that now.", firstMessage.text)
        assertEquals(firstMessage.text, deltas.take(2).joinToString("") { it.text })

        assertEquals(4, deltas.size)
    }

    @Test
    fun `a tool call carries what is worth showing, and its result comes back keyed to it`() {
        val call =
            events
                .filterIsInstance<AgentEvent.AssistantMessage>()
                .flatMap { it.blocks }
                .filterIsInstance<ContentBlock.ToolUse>()
                .single()

        assertEquals("Bash", call.name)
        assertEquals("echo hello", call.summary)

        val result =
            events
                .filterIsInstance<AgentEvent.UserMessage>()
                .flatMap { it.blocks }
                .filterIsInstance<ContentBlock.ToolResult>()
                .single()

        assertEquals(call.id, result.toolUseId)
        assertEquals("hello", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun `the result event carries the answer, the cost and the duration`() {
        val result = events.last() as AgentEvent.Result
        assertEquals("success", result.subtype)
        assertFalse(result.isError)
        assertEquals("It printed `hello`.", result.text)
        assertEquals(0.071205, result.costUsd)
        assertEquals(4296L, result.durationMs)
        assertEquals(2, result.numTurns)
    }

    @Test
    fun `an event type this version has never seen survives as Unknown`() {
        val event =
            ClaudeEvents.parse(
                """{"type":"something_new_in_2_2","session_id":"abc","payload":{"n":1}}""",
            )
        val unknown = assertIs<AgentEvent.Unknown>(event)
        assertEquals("something_new_in_2_2", unknown.type)
        assertEquals("abc", unknown.sessionId)
        assertTrue("payload" in unknown.raw)
    }

    @Test
    fun `a line that is not JSON is kept rather than dropped`() {
        val event = ClaudeEvents.parse("Error: something went wrong before the stream started")
        assertIs<AgentEvent.NonJson>(event)
        assertNull(ClaudeEvents.parse("   "))
    }

    @Test
    fun `a denied tool call is named rather than folded into a generic failure`() {
        val line =
            """
            {"type":"result","subtype":"success","is_error":false,
             "session_id":"ab90d64b-6140-4a49-87fb-14cc94ae25f4",
             "permission_denials":[{"tool_name":"Bash","tool_use_id":"toolu_01M3h6Ty",
               "tool_input":{"command":"echo zopf-probe-token","description":"Echo a probe token"}}],
             "result":"denied","total_cost_usd":0.0782905}
            """.trimIndent().replace("\n", "")

        val result = ClaudeEvents.parse(line) as AgentEvent.Result

        assertEquals(1, result.permissionDenials.size)
        val denial = result.permissionDenials.single()
        assertEquals("Bash", denial.toolName)
        assertEquals("toolu_01M3h6Ty", denial.toolUseId)

        assertEquals("echo zopf-probe-token", denial.summary)
    }

    @Test
    fun `a turn that denied nothing has an empty list rather than a surprise`() {
        val line = """{"type":"result","subtype":"success","is_error":false,"result":"fine"}"""
        assertEquals(emptyList(), (ClaudeEvents.parse(line) as AgentEvent.Result).permissionDenials)
    }

    @Test
    fun `a hook lifecycle event says which hook, since it carries no status`() {
        val line =
            """
            {"type":"system","subtype":"hook_started","hook_name":"PreToolUse:Bash",
             "session_id":"abc"}
            """.trimIndent().replace("\n", "")

        val notice = ClaudeEvents.parse(line) as AgentEvent.Notice

        assertEquals("hook_started", notice.kind)
        assertEquals("PreToolUse:Bash", notice.detail)
    }

    @Test
    fun `a malformed event does not take the stream down`() {
        val event = ClaudeEvents.parse("""{"type":"result","is_error":"yes","total_cost_usd":"free"}""")
        val result = assertIs<AgentEvent.Result>(event)
        assertFalse(result.isError)
        assertNull(result.costUsd)
    }
}

class CodexEventsTest {
    private val events: List<AgentEvent> by lazy {
        CodexEvents.parseAll(
            checkNotNull(javaClass.getResourceAsStream("/codex-stream.jsonl")) {
                "codex-stream.jsonl is missing from the test resources"
            }.bufferedReader().readText(),
        )
    }

    @Test
    fun `every line of the stream parses into something the console already knows`() {
        assertEquals(7, events.size)
        assertTrue(events.none { it is AgentEvent.NonJson }, "a line failed to parse as JSON")

        val unknown = events.filterIsInstance<AgentEvent.Unknown>().map { it.type }
        assertTrue(unknown.isEmpty(), "unhandled event types: $unknown")
    }

    @Test
    fun `the thread id is the session id, and it arrives before any output`() {
        val init = assertIs<AgentEvent.SystemInit>(events.first())
        assertEquals("0199a4c2-6f11-7a3e-9d64-2f0b1c8ee551", init.sessionId)
    }

    @Test
    fun `reasoning is thinking and an agent message is the answer`() {
        val blocks = events.filterIsInstance<AgentEvent.AssistantMessage>().flatMap { it.blocks }

        assertTrue(
            blocks
                .filterIsInstance<ContentBlock.Thinking>()
                .single()
                .text
                .startsWith("The command printed"),
        )
        assertEquals("It printed `hello`.", blocks.filterIsInstance<ContentBlock.Text>().single().text)
    }

    @Test
    fun `a command execution is a tool call, and its output comes back keyed to it`() {
        val call =
            events
                .filterIsInstance<AgentEvent.AssistantMessage>()
                .flatMap { it.blocks }
                .filterIsInstance<ContentBlock.ToolUse>()
                .single()

        assertEquals("CommandExecution", call.name)
        assertEquals("echo hello", call.summary)

        val result =
            events
                .filterIsInstance<AgentEvent.UserMessage>()
                .flatMap { it.blocks }
                .filterIsInstance<ContentBlock.ToolResult>()
                .single()

        assertEquals(call.id, result.toolUseId)
        assertEquals("hello", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun `the turn ends in tokens, and never in a dollar figure it was not given`() {
        val result = assertIs<AgentEvent.Result>(events.last())

        assertFalse(result.isError)
        assertNull(result.costUsd)
        assertEquals(2451 + 183, result.tokens)
    }

    @Test
    fun `a failed turn and a bare error are both a failed node`() {
        val failed = assertIs<AgentEvent.Result>(CodexEvents.parse("""{"type":"turn.failed","error":{"message":"model stream ended"}}"""))
        assertTrue(failed.isError)
        assertEquals("model stream ended", failed.text)

        val error = CodexEvents.parse("""{"type":"error","message":"sandbox denied the write"}""")
        assertTrue(assertIs<AgentEvent.Result>(error).isError)
    }

    @Test
    fun `an item that names its type the older way still parses`() {
        val event = CodexEvents.parse("""{"type":"item.completed","item":{"id":"i","item_type":"agent_message","text":"done"}}""")
        assertEquals("done", assertIs<AgentEvent.AssistantMessage>(event).text)
    }

    @Test
    fun `an error the CLI reports mid-turn is a warning rather than the end of the node`() {
        val event = CodexEvents.parse("""{"type":"item.completed","item":{"id":"i","type":"error","message":"Model metadata not found."}}""")
        val notice = assertIs<AgentEvent.Notice>(event)

        assertEquals("error", notice.kind)
        assertEquals("Model metadata not found.", notice.detail)
    }

    @Test
    fun `an error message that is itself JSON is unwrapped to the sentence inside it`() {
        val line = """{"type":"turn.failed","error":{"message":"{\"type\":\"error\",\"status\":400,\"error\":{\"message\":\"That model is not supported on this account.\"}}"}}"""

        assertEquals("That model is not supported on this account.", assertIs<AgentEvent.Result>(CodexEvents.parse(line)).text)
    }

    @Test
    fun `an item type this version has never seen survives rather than throwing`() {
        val event = CodexEvents.parse("""{"type":"item.completed","item":{"id":"i","item_type":"invented_later"}}""")
        assertIs<AgentEvent.Unknown>(event)

        assertIs<AgentEvent.NonJson>(CodexEvents.parse("Error: codex is not signed in"))
        assertNull(CodexEvents.parse("   "))
    }
}

class NodeRunTest {
    private fun run() =
        NodeRun(
            id = "run-1",
            workflowName = "demo",
            nodeId = "analyze",
            nodeTitle = "Analyze",
            nodeType = NodeType.AGENT,
            cwd = Paths.get("/tmp"),
            provider = AgentProviderId.CLAUDE,
        )

    private fun replayFixture(name: String = "/claude-stream.jsonl"): NodeRun {
        val text = checkNotNull(javaClass.getResourceAsStream(name)).bufferedReader().readText()
        return run().apply { ClaudeEvents.parseAll(text).forEach(::consume) }
    }

    @Test
    fun `a schema'd turn answers in fields, and the whole object is still the result`() {
        val output = replayFixture("/claude-structured-stream.jsonl").output()

        assertEquals("low", output.field("severity"))
        assertTrue(output.field("rationale").orEmpty().startsWith("A 2px centring offset"))

        assertTrue(output.result.startsWith("""{"severity":"low""""), output.result)
    }

    @Test
    fun `a turn with no schema declares no fields`() {
        assertEquals(emptyMap(), replayFixture().output().extras)
    }

    @Test
    fun `a real stream becomes a readable transcript`() {
        val run = replayFixture()

        val messages = run.entries.filterIsInstance<ConsoleEntry.Message>()
        assertEquals(
            listOf("Hi there, running that now.", "It printed `hello`."),
            messages.map { it.text },
        )
        assertTrue(messages.none { it.isStreaming }, "a row was left mid-stream")

        val tool = run.entries.filterIsInstance<ConsoleEntry.ToolCall>().single()
        assertEquals("Bash", tool.name)
        assertEquals("echo hello", tool.summary)

        assertEquals("hello", tool.result)
        assertFalse(tool.isError)

        val summary = run.entries.filterIsInstance<ConsoleEntry.Summary>().single()
        assertEquals("It printed `hello`.", summary.text)
        assertEquals(0.071205, summary.costUsd)
    }

    @Test
    fun `the session details are lifted out of the stream`() {
        val run = replayFixture()
        assertNotNull(run.sessionId)
        assertEquals("claude-opus-5", run.model)
        assertEquals(0.071205, run.costUsd)
        assertTrue(run.canTakeOver, "a claude run with a session id can be handed to a terminal")

        assertEquals(RunStatus.WAITING, run.status)
        assertEquals("It printed `hello`.", run.output().result)
    }

    @Test
    fun `a codex turn ends the node rather than parking it for a follow-up`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/codex-stream.jsonl")).bufferedReader().readText()
        val run =
            NodeRun("r", "demo", "analyze", "Analyze", NodeType.AGENT, Paths.get("/tmp"), provider = AgentProviderId.CODEX)
                .apply { CodexEvents.parseAll(text).forEach(::consume) }

        assertEquals(RunStatus.RUNNING, run.status)
        assertFalse(run.canFollowUp)
        assertTrue(run.canTakeOver, "the thread id codex reported is enough to reopen it in a terminal")

        assertEquals("It printed `hello`.", run.output().result)
        assertNull(run.costUsd)
        assertEquals(2634, run.tokens)
    }

    @Test
    fun `a codex answer that fills a schema becomes fields, and stays the result`() {
        val line = """{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"{\"severity\":\"low\",\"count\":2}"}}"""
        val run =
            NodeRun("r", "demo", "ask", "Ask", NodeType.AGENT, Paths.get("/tmp"), provider = AgentProviderId.CODEX)
                .apply {
                    CodexEvents.parseAll(line).forEach(::consume)
                    takeFieldsFromJsonResult()
                }

        val output = run.output()

        assertEquals("low", output.field("severity"))
        assertEquals("2", output.field("count"))
        assertEquals("""{"severity":"low","count":2}""", output.result)
    }

    @Test
    fun `an answer in prose is left as prose, however the node was asked`() {
        val run =
            NodeRun("r", "demo", "ask", "Ask", NodeType.AGENT, Paths.get("/tmp"), provider = AgentProviderId.CODEX)
                .apply {
                    CodexEvents
                        .parseAll("""{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"Nothing looked wrong."}}""")
                        .forEach(::consume)
                    takeFieldsFromJsonResult()
                }

        assertEquals(emptyMap(), run.output().extras)
        assertEquals("Nothing looked wrong.", run.output().result)
    }

    @Test
    fun `a codex failure that arrives as both an error and a failed turn is reported once`() {
        val stream =
            """
            {"type":"error","message":"That model is not supported on this account."}
            {"type":"turn.failed","error":{"message":"That model is not supported on this account."}}
            """.trimIndent()
        val run =
            NodeRun("r", "demo", "ask", "Ask", NodeType.AGENT, Paths.get("/tmp"), provider = AgentProviderId.CODEX)
                .apply { CodexEvents.parseAll(stream).forEach(::consume) }

        val summary = run.entries.filterIsInstance<ConsoleEntry.Summary>().single()

        assertEquals("That model is not supported on this account.", summary.text)
        assertTrue(summary.isError)
        assertEquals(RunStatus.FAILED, run.status)
    }

    @Test
    fun `an error codex reports mid-turn shows as a warning and leaves the node running`() {
        val run =
            NodeRun("r", "demo", "ask", "Ask", NodeType.AGENT, Paths.get("/tmp"), provider = AgentProviderId.CODEX)
                .apply {
                    CodexEvents
                        .parseAll("""{"type":"item.completed","item":{"id":"i","type":"error","message":"Model metadata not found."}}""")
                        .forEach(::consume)
                }

        val notice = run.entries.filterIsInstance<ConsoleEntry.Notice>().single()

        assertEquals("Model metadata not found.", notice.text)
        assertTrue(notice.isWarning)
        assertEquals(RunStatus.RUNNING, run.status)
    }

    @Test
    fun `a dsh run has no stream, so its stdout is the answer and the whole answer`() {
        val text = "Both tests pass.\n\nThe flake was the clock, not the parser.\n"
        val run =
            NodeRun("r", "demo", "analyze", "Analyze", NodeType.AGENT, Paths.get("/tmp"), provider = AgentProviderId.DSH)
                .apply {
                    DshEvents.parseAll(text).forEach(::consume)
                    finish(RunStatus.SUCCEEDED, 0)
                }

        assertFalse(run.canFollowUp)
        assertFalse(run.canTakeOver)
        assertNull(run.sessionId)

        assertEquals(text.trim(), run.output().result, "the blank line between paragraphs survives")
        assertEquals(1, run.entries.filterIsInstance<ConsoleEntry.Message>().size)
    }

    @Test
    fun `deltas accumulate into one row until the block is complete`() {
        val run = run()
        run.consume(AgentEvent.TextDelta("s1", "Hel", isThinking = false))
        run.consume(AgentEvent.TextDelta("s1", "lo", isThinking = false))

        val streaming = run.entries.filterIsInstance<ConsoleEntry.Message>().single()
        assertEquals("Hello", streaming.text)
        assertTrue(streaming.isStreaming)

        run.consume(
            AgentEvent.AssistantMessage(
                sessionId = "s1",
                model = "opus",
                blocks = listOf(ContentBlock.Text("Hello, world")),
                raw = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
        )

        assertEquals(1, run.entries.size)
        assertEquals("Hello, world", streaming.text)
        assertFalse(streaming.isStreaming)
    }

    @Test
    fun `a watcher is handed each entry once, and a message only once it stops growing`() {
        val settled = mutableListOf<ConsoleEntry>()
        val run = run()
        run.onEntrySettled = { settled.add(it) }

        run.consume(AgentEvent.TextDelta("s1", "Hel", isThinking = false))
        run.consume(AgentEvent.TextDelta("s1", "lo", isThinking = false))
        assertTrue(settled.isEmpty())

        run.consume(
            AgentEvent.AssistantMessage(
                sessionId = "s1",
                model = "opus",
                blocks = listOf(ContentBlock.Text("Hello, world")),
                raw = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
        )
        run.notice("and a notice")

        assertEquals(listOf("Hello, world", "and a notice"), settled.map(::describe))
    }

    @Test
    fun `a watcher sees the whole of a real stream, in order, with nothing repeated`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/claude-stream.jsonl")).bufferedReader().readText()
        val settled = mutableListOf<ConsoleEntry>()
        val run = run().apply { onEntrySettled = { settled.add(it) } }

        ClaudeEvents.parseAll(text).forEach(run::consume)

        assertEquals(run.entries.toList(), settled)
    }

    private fun describe(entry: ConsoleEntry): String =
        when (entry) {
            is ConsoleEntry.Message -> entry.text
            is ConsoleEntry.Notice -> entry.text
            else -> entry::class.simpleName.orEmpty()
        }

    @Test
    fun `shell output keeps stdout and stderr apart`() {
        val run = NodeRun("r", "demo", "build", "Build", NodeType.SHELL, Paths.get("/tmp"))
        run.consume(ShellLine("compiling", isError = false))
        run.consume(ShellLine("warning: deprecated", isError = true))

        val output = run.entries.filterIsInstance<ConsoleEntry.Output>()
        assertEquals(listOf(false, true), output.map { it.isError })

        assertEquals("compiling", run.output().result)
    }

    @Test
    fun `an unknown event is ignored without disturbing the transcript`() {
        val run = run()
        run.consume(ClaudeEvents.parse("""{"type":"invented_later","session_id":"s1"}""")!!)
        assertTrue(run.entries.isEmpty())
        assertEquals("s1", run.sessionId)
    }

    private fun pending(tool: String = "Bash") =
        PendingPermission(
            PermissionRequest(tool, "./gradlew build", "toolu_01", "s1"),
            java.util.concurrent.CompletableFuture(),
        )

    @Test
    fun `a node blocked on a tool call is waiting for you, and still active`() {
        val run = run().apply { status = RunStatus.RUNNING }
        run.beginPermission(pending())

        assertEquals(RunStatus.WAITING, run.status)

        assertTrue(run.status.isActive)
        assertTrue(run.isAwaitingPermission)
    }

    @Test
    fun `answering puts the node back to work`() {
        val run = run().apply { status = RunStatus.RUNNING }
        run.beginPermission(pending())
        run.endPermission(allowed = true, request = pending().request)

        assertEquals(RunStatus.RUNNING, run.status)
        assertFalse(run.isAwaitingPermission)

        run.consume(ClaudeEvents.parse("""{"type":"assistant","session_id":"s1"}""")!!)
        assertEquals(RunStatus.RUNNING, run.status)
    }

    @Test
    fun `a finished node cannot be left holding a question nobody can answer`() {
        val run = run()
        run.beginPermission(pending())
        run.finish(RunStatus.STOPPED)

        assertFalse(run.isAwaitingPermission)
    }

    @Test
    fun `a denial from the result event is spelled out`() {
        val run = run()
        run.consume(
            ClaudeEvents.parse(
                """{"type":"result","subtype":"success","is_error":false,"result":"ok",
                   "permission_denials":[{"tool_name":"Bash","tool_use_id":"t1",
                     "tool_input":{"command":"rm -rf build"}}]}""".replace("\n", ""),
            )!!,
        )

        val notices = run.entries.filterIsInstance<ConsoleEntry.Notice>()
        assertTrue(
            notices.any { it.isWarning && "Bash" in it.text && "rm -rf build" in it.text },
            "a turn that ended short has to say what it wasn't allowed to do",
        )
    }
}

class AgentPromptTest {
    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-prompt").also { tempDirs.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun node(
        prompt: String = "",
        promptFile: String = "",
    ) = WorkflowNode(id = "review", type = NodeType.AGENT, prompt = prompt, promptFile = promptFile)

    @Test
    fun `without a prompt file the node's own prompt is sent`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        assertEquals("inline", agentPromptText(node(prompt = "inline"), workspace, workspace.root).getOrThrow())
    }

    @Test
    fun `a relative prompt file resolves against the workspace root`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        workspace.root.resolve("prompts").createDirectories()
        workspace.root.resolve("prompts/review.md").writeText("Review the diff.\n")

        val text =
            agentPromptText(
                node(prompt = "ignored", promptFile = "prompts/review.md"),
                workspace,
                workspace.root,
            )

        assertEquals("Review the diff.\n", text.getOrThrow())
    }

    @Test
    fun `an absolute prompt file is read as written`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        val file = tempDir().resolve("elsewhere.md").also { it.writeText("From elsewhere.") }

        assertEquals(
            "From elsewhere.",
            agentPromptText(node(promptFile = file.toString()), workspace, workspace.root).getOrThrow(),
        )
    }

    @Test
    fun `without a workspace a relative path resolves against the node's own directory`() {
        val dir = tempDir()
        dir.resolve("brief.md").writeText("Write the connector.")

        assertEquals(
            "Write the connector.",
            agentPromptText(node(promptFile = "brief.md"), workspace = null, cwd = dir).getOrThrow(),
        )
    }

    @Test
    fun `a prompt file that isn't there fails, naming the path it looked for`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))

        val failure =
            agentPromptText(node(promptFile = "prompts/gone.md"), workspace, workspace.root)
                .exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(workspace.root.resolve("prompts/gone.md").toString() in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a directory is not a prompt file`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        workspace.root.resolve("prompts").createDirectories()

        assertTrue(agentPromptText(node(promptFile = "prompts"), workspace, workspace.root).isFailure)
    }
}
