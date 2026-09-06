package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.ConnectorSecret
import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.PermissionMode
import com.dk.zopf.model.Sandbox
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.ignoredFields
import com.dk.zopf.model.modelOptions
import com.dk.zopf.model.withDefaultsFrom
import com.dk.zopf.store.AppSettings
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterpolationTest {
    private val context =
        RunContext().apply {
            record("analyze", NodeOutput(result = "two findings", costUsd = 0.5, sessionId = "s-1"))
            record("build", NodeOutput(result = "", exitCode = 0))
        }

    @Test
    fun `a reference is replaced with its node's output`() {
        val interpolated = context.interpolate("Fix these:\n\${analyze.result}\nexit=\${build.exitCode}")
        assertEquals("Fix these:\ntwo findings\nexit=0", interpolated.text)
        assertTrue(interpolated.isComplete)
    }

    @Test
    fun `an unresolved reference is reported and left as written`() {
        val interpolated = context.interpolate("Review \${notyet.result} and \${analyze.nope}")
        assertEquals("Review \${notyet.result} and \${analyze.nope}", interpolated.text)
        assertEquals(listOf("notyet.result", "analyze.nope"), interpolated.unresolved)
        assertFalse(interpolated.isComplete)
    }

    @Test
    fun `text without references is untouched`() {
        assertEquals("100% of \$5", context.interpolate("100% of \$5").text)
    }
}

class AgentInvocationTest {
    private val invocation =
        AgentInvocation(
            prompt = "hi",
            cwd = Paths.get("/repo"),
            sessionId = "11111111-2222-3333-4444-555555555555",
            model = "opus",
            permissionMode = PermissionMode.ACCEPT_EDITS,
            sandbox = Sandbox.WORKSPACE_WRITE,
            allowedTools = listOf("Read", "Bash(git diff *)"),
            addDirs = listOf(Paths.get("/other")),
            pluginDirs = listOf(Paths.get("/skills/review"), Paths.get("/skills/triage")),
        )

    private fun claude(invocation: AgentInvocation = this.invocation) = ClaudeProvider.command(invocation, "claude")

    private fun codex(invocation: AgentInvocation = this.invocation) = CodexProvider.command(invocation, "codex")

    private fun dsh(invocation: AgentInvocation = this.invocation) = DshProvider.command(invocation, "dsh")

    @Test
    fun `the claude command streams and pins the session id`() {
        val command = claude()

        assertEquals(listOf("claude", "-p"), command.take(2))
        assertTrue(command.containsInOrder("--output-format", "stream-json"))

        assertTrue(command.containsInOrder("--input-format", "stream-json"))

        assertTrue("--verbose" in command)
        assertTrue("--include-partial-messages" in command)
        assertTrue(command.containsInOrder("--session-id", invocation.sessionId!!))
        assertTrue(command.containsInOrder("--model", "opus"))
        assertTrue(command.containsInOrder("--permission-mode", "acceptEdits"))
        assertTrue(command.containsInOrder("--allowedTools", "Read", "Bash(git diff *)"))
        assertTrue(command.containsInOrder("--add-dir", "/other"))

        assertFalse("--sandbox" in command)
    }

    @Test
    fun `claude takes a schema inline`() {
        val schema = """{"type":"object","properties":{"severity":{"type":"string"}}}"""

        assertTrue(claude(invocation.copy(jsonSchema = schema)).containsInOrder("--json-schema", schema))
        assertFalse("--json-schema" in claude())
    }

    @Test
    fun `each skill directory is its own --plugin-dir`() {
        val passed = claude().zipWithNext().filter { it.first == "--plugin-dir" }.map { it.second }

        assertEquals(listOf("/skills/review", "/skills/triage"), passed)
    }

    @Test
    fun `an unset field is not passed`() {
        val bare = claude(AgentInvocation(prompt = "hi", cwd = Paths.get("/repo")))
        assertFalse("--model" in bare)
        assertFalse("--permission-mode" in bare)
        assertFalse("--allowedTools" in bare)
        assertFalse("--add-dir" in bare)
        assertFalse("--plugin-dir" in bare)

        assertFalse("--settings" in bare)
        assertFalse("--include-hook-events" in bare)
    }

    @Test
    fun `the approval hook is passed as --settings`() {
        val command = claude(invocation.copy(settingsFile = Paths.get("/support/zopf-hooks-bash.json")))

        assertTrue(command.containsInOrder("--settings", "/support/zopf-hooks-bash.json"))
        assertTrue("--include-hook-events" in command)
    }

    @Test
    fun `a resolved executable path replaces the bare name`() {
        assertEquals("/opt/claude", ClaudeProvider.command(invocation, "/opt/claude").first())
        assertEquals("/opt/codex", CodexProvider.command(invocation, "/opt/codex").first())
        assertEquals("/opt/dsh", DshProvider.command(invocation, "/opt/dsh").first())
    }

    @Test
    fun `codex takes global flags before the subcommand`() {
        val command = codex()
        val exec = command.indexOf("exec")

        assertTrue(exec > 0, command.toString())
        assertTrue(command.indexOf("--ask-for-approval") < exec)
        assertTrue(command.indexOf("--sandbox") < exec)
        assertTrue(command.indexOf("--model") < exec)
        assertEquals(listOf("exec", "--json", "--skip-git-repo-check", "hi"), command.drop(exec))
    }

    @Test
    fun `codex takes a schema as a file`() {
        val schema = """{"type":"object","properties":{"severity":{"type":"string"}}}"""
        val command = codex(invocation.copy(jsonSchema = schema))
        val file = Paths.get(command[command.indexOf("--output-schema") + 1])

        assertTrue(command.indexOf("--output-schema") > command.indexOf("exec"))
        assertEquals(schema, file.readText())
        assertFalse("--output-schema" in codex())
    }

    @Test
    fun `codex skips the git repo check`() {
        assertTrue("--skip-git-repo-check" in codex())
        assertFalse("--skip-git-repo-check" in claude(), "claude never had the check to skip")
    }

    @Test
    fun `codex never pauses for an approval`() {
        assertTrue(codex().containsInOrder("--ask-for-approval", "never"))
        assertTrue(codex(AgentInvocation(prompt = "hi", cwd = Paths.get("/repo"))).containsInOrder("--ask-for-approval", "never"))
    }

    @Test
    fun `codex is passed no claude-only flags`() {
        val command = codex(invocation.copy(sandbox = null, jsonSchema = "{}", settingsFile = Paths.get("/s.json")))

        assertFalse("--sandbox" in command)
        assertFalse("--permission-mode" in command)
        assertFalse("--allowedTools" in command)
        assertFalse("--plugin-dir" in command)
        assertFalse("--json-schema" in command)
        assertFalse("--settings" in command)
    }

    @Test
    fun `codex takes the prompt on the command line`() {
        assertEquals(PromptChannel.ARGUMENT, CodexProvider.promptChannel)
        assertEquals(PromptChannel.STDIN, ClaudeProvider.promptChannel)

        assertTrue("hi" in codex())
        assertFalse("hi" in claude())
    }

    @Test
    fun `dsh takes only the profile and the prompt`() {
        val command = dsh()

        assertEquals(listOf("dsh", "--profile", "headless", "hi"), command)
        assertFalse("--model" in command)
        assertFalse("--sandbox" in command)
        assertFalse("--json" in command)
    }

    @Test
    fun `only claude accepts a session id up front`() {
        assertNotNull(ClaudeProvider.newSessionId())
        assertNull(CodexProvider.newSessionId())
        assertNull(DshProvider.newSessionId())
    }

    private fun List<String>.containsInOrder(vararg values: String): Boolean {
        val start = indexOf(values.first())
        return start >= 0 && subList(start, minOf(start + values.size, size)) == values.toList()
    }
}

class AgentCapabilitiesTest {
    @Test
    fun `every provider declares a distinct capability set`() {
        val answers = AgentProviders.all.associate { it.id to it.capabilities }

        assertEquals(AgentProviderId.entries.toSet(), answers.keys)
        assertEquals(answers.size, answers.values.distinct().size, "two CLIs cannot be the same CLI")
    }

    @Test
    fun `the console offers only what codex supports`() {
        val codex = CodexProvider.capabilities

        assertFalse(codex.followUps)
        assertFalse(codex.inlineApproval)
        assertFalse(codex.reportsCostUsd)
        assertFalse(codex.skills)
        assertFalse(codex.extraDirectories, "codex reads the whole disk already, and --add-dir grants writes")
        assertTrue(codex.sandbox)
        assertTrue(codex.outputSchema)
    }

    @Test
    fun `a codex node records its exec thread`() {
        assertTrue(CodexProvider.capabilities.resumeInTerminal)
        assertEquals(listOf("resume", "t-1"), CodexProvider.terminalArgs("t-1"))
        assertEquals(emptyList(), CodexProvider.terminalArgs(null))

        assertEquals(
            "exec 'codex' 'resume' 't-1'",
            TerminalLauncher.script(Paths.get("/tmp"), CodexProvider.terminalArgs("t-1"), CodexProvider.executable).lines().last { it.isNotBlank() },
        )
    }

    @Test
    fun `a provider that takes a model suggests models`() {
        assertContains(AgentProviderId.CLAUDE.modelOptions, "opus")
        assertTrue(AgentProviderId.CODEX.modelOptions.isNotEmpty())
        assertEquals(emptyList(), AgentProviderId.DSH.modelOptions)
    }

    @Test
    fun `a dsh node offers no model`() {
        val dsh = DshProvider.capabilities

        assertFalse(dsh.modelSelection)
        assertFalse(dsh.sandbox)
        assertFalse(dsh.followUps)
        assertFalse(dsh.reportsCostUsd)

        assertEquals(
            listOf("model"),
            AgentProviderId.DSH.ignoredFields(WorkflowNode(id = "n", type = NodeType.AGENT, model = "deepseek-chat")),
        )
        assertEquals(emptyList(), DshProvider.terminalArgs("s-1"), "the headless profile leaves nothing to reopen")
    }

    @Test
    fun `an unsupported field is named as ignored`() {
        val node =
            WorkflowNode(
                id = "review",
                type = NodeType.AGENT,
                provider = AgentProviderId.CODEX,
                permissionMode = PermissionMode.ACCEPT_EDITS,
                skills = listOf("zopf-workflows"),
                sandbox = Sandbox.READ_ONLY,
            )

        assertEquals(listOf("permissionMode", "skills"), AgentProviderId.CODEX.ignoredFields(node))
        assertEquals(listOf("sandbox"), AgentProviderId.CLAUDE.ignoredFields(node))
    }
}

class ProviderResolutionTest {
    private val node = WorkflowNode(id = "n", type = NodeType.AGENT)
    private val workflow = Workflow(name = "w")

    private fun Workflow.saying(provider: AgentProviderId?) = copy(defaults = defaults.copy(provider = provider))

    @Test
    fun `the nearest provider wins and absent means claude`() {
        val machineSaysCodex = AppSettings(defaultProvider = AgentProviderId.CODEX)

        listOf(
            Triple("node over workflow and machine", node.copy(provider = AgentProviderId.CLAUDE) to workflow.saying(AgentProviderId.CODEX), AgentProviderId.CLAUDE),
            Triple("workflow over machine", node to workflow.saying(AgentProviderId.CLAUDE), AgentProviderId.CLAUDE),
            Triple("machine when nobody names one", node to workflow, AgentProviderId.CODEX),
        ).forEach { (case, given, expected) ->
            val (n, w) = given
            assertEquals(expected, resolveProvider(n, w, machineSaysCodex), case)
        }

        assertEquals(AgentProviderId.CLAUDE, resolveProvider(node, workflow, AppSettings()), "absent everywhere")
    }

    @Test
    fun `a workflow provider wins over the workspace`() {
        val workspaceSaysCodex = NodeDefaults(provider = AgentProviderId.CODEX)
        val machineSaysClaude = AppSettings(defaultProvider = AgentProviderId.CLAUDE)

        assertEquals(
            AgentProviderId.CODEX,
            resolveProvider(node, workflow.withDefaultsFrom(workspaceSaysCodex), machineSaysClaude),
        )
        assertEquals(
            AgentProviderId.CLAUDE,
            resolveProvider(node, workflow.saying(AgentProviderId.CLAUDE).withDefaultsFrom(workspaceSaysCodex), machineSaysClaude),
        )
    }
}

class ModelResolutionTest {
    private val node = WorkflowNode(id = "n", type = NodeType.AGENT)
    private val workflow = Workflow(name = "w")
    private val settings = AppSettings(defaultModel = "haiku")

    private fun Workflow.withDefaultModel(model: String) = copy(defaults = defaults.copy(model = model))

    @Test
    fun `the nearest model wins and absent stays unset`() {
        val workspaceSaysSonnet = NodeDefaults(model = "sonnet")

        listOf(
            Triple("node over workflow", node.copy(model = "opus") to workflow.withDefaultModel("sonnet"), "opus"),
            Triple("workflow over machine", node to workflow.withDefaultModel("sonnet"), "sonnet"),
            Triple("machine when nobody names one", node to workflow, "haiku"),
            Triple("workspace over machine", node to workflow.withDefaultsFrom(workspaceSaysSonnet), "sonnet"),
            Triple("workflow over workspace", node to workflow.withDefaultModel("opus").withDefaultsFrom(workspaceSaysSonnet), "opus"),
            Triple("an empty workspace default falls through", node to workflow.withDefaultsFrom(NodeDefaults()), "haiku"),
        ).forEach { (case, given, expected) ->
            val (n, w) = given
            assertEquals(expected, resolveModel(n, w, settings), case)
        }

        assertNull(resolveModel(node, workflow, AppSettings()), "absent everywhere")
    }

    @Test
    fun `a model is not carried across providers`() {
        val codexNode = node.copy(provider = AgentProviderId.CODEX)
        val codexWorkflow = workflow.copy(defaults = workflow.defaults.copy(provider = AgentProviderId.CODEX))

        assertNull(resolveModel(codexNode, workflow.withDefaultModel("sonnet"), settings))
        assertEquals("gpt-5-codex", resolveModel(codexNode.copy(model = "gpt-5-codex"), workflow, settings))
        assertEquals("gpt-5-codex", resolveModel(node, codexWorkflow.withDefaultModel("gpt-5-codex"), settings))
        assertEquals(
            "haiku",
            resolveModel(node.copy(provider = AgentProviderId.CLAUDE), codexWorkflow.withDefaultModel("gpt-5-codex"), settings),
        )
    }
}

class TerminalLauncherTest {
    @Test
    fun `the handoff script cds to the node's directory`() {
        val script = TerminalLauncher.script(Paths.get("/Users/someone/dev/my repo"), listOf("-r", "abc-123"))
        assertTrue("cd '/Users/someone/dev/my repo'" in script, script)
        assertTrue("exec 'claude' '-r' 'abc-123'" in script, script)
    }

    @Test
    fun `with no arguments it starts a fresh session`() {
        val script = TerminalLauncher.script(Paths.get("/Users/someone/zopf/connectors/slack-post"))
        assertTrue("cd '/Users/someone/zopf/connectors/slack-post'" in script, script)
        assertTrue(script.trimEnd().endsWith("exec 'claude'"), script)
    }

    @Test
    fun `an opening prompt is passed as one argument`() {
        val script =
            TerminalLauncher.script(
                Paths.get("/tmp/c"),
                listOf("Write a connector.\nDon't invent what it does."),
            )
        assertTrue("-p" !in script, script)
        assertTrue("""exec 'claude' 'Write a connector.""" in script, script)
        assertTrue("""Don'\''t invent what it does.'""" in script, script)
    }

    @Test
    fun `a quote in a path is escaped`() {
        val script = TerminalLauncher.script(Paths.get("/tmp/it's here"), listOf("-r", "s"))
        assertTrue("""cd '/tmp/it'\''s here'""" in script, script)
    }
}

class FinderTest {
    @Test
    fun `reveal selects the item`() {
        assertEquals(
            listOf("open", "-R", "/w/workflows/ship.yaml"),
            Finder.command(Paths.get("/w/workflows/ship.yaml")),
        )
    }

    @Test
    fun `revealing a missing path names it`() {
        val missing = Paths.get("/nowhere/at/all/ship.yaml")
        val failure = Finder.reveal(missing).exceptionOrNull()
        assertContains(failure?.message.orEmpty(), missing.toString())
    }
}

class BranchesTest {
    @Test
    fun `an expression is taken or not on a text comparison`() {
        listOf(
            "0 == 0" to true,
            "1 == 0" to false,
            "1 != 0" to true,
            "ok != ok" to false,
            "\"ship it\" == \"ship it\"" to true,
            "'ship it' == ship it" to true,
            "\${build.exitCode} == 0" to false,
            "" to false,
            "  " to false,
            "false" to false,
            "FALSE" to false,
            "0" to false,
            "true" to true,
            "anything at all" to true,
        ).forEach { (expression, taken) ->
            assertEquals(taken, Branches.evaluate(expression).taken, expression)
        }
    }

    @Test
    fun `the operator is taken from the expression not the value`() {
        val verdict =
            Branches.evaluate("\${review.result} == ok") {
                it.replace("\${review.result}", "assertion failed: a != b")
            }

        assertFalse(verdict.taken)
        assertEquals("\"assertion failed: a != b\" == \"ok\" → false", verdict.explanation)
    }

    @Test
    fun `the verdict reports what was compared`() {
        assertEquals("\"0\" == \"0\" → true", Branches.evaluate("0 == 0").explanation)
    }
}

class SecretsTest {
    private fun resolver(
        environment: Map<String, String> = emptyMap(),
        keychain: Map<String, String> = emptyMap(),
    ) = SecretResolver(fromEnvironment = { environment[it] }, fromKeychain = { keychain[it] })

    @Test
    fun `the environment is preferred over the keychain`() {
        val secret = ConnectorSecret("SLACK_TOKEN", keychain = "slack-post")
        val resolved =
            resolver(
                environment = mapOf("SLACK_TOKEN" to "from-env"),
                keychain = mapOf("slack-post" to "from-keychain"),
            ).resolve(listOf(secret)).single()

        assertEquals("from-env", resolved.value)
        assertEquals(SecretSource.ENVIRONMENT, resolved.source)
    }

    @Test
    fun `the keychain fills in what the environment lacks`() {
        val secret = ConnectorSecret("SLACK_TOKEN", keychain = "slack-post")
        val resolved = resolver(keychain = mapOf("slack-post" to "from-keychain")).resolve(listOf(secret)).single()

        assertEquals("from-keychain", resolved.value)
        assertEquals(SecretSource.KEYCHAIN, resolved.source)
    }

    @Test
    fun `a secret with no keychain service is environment-only`() {
        val resolved =
            resolver(keychain = mapOf("SLACK_TOKEN" to "wrong-place"))
                .resolve(listOf(ConnectorSecret("SLACK_TOKEN")))
                .single()

        assertNull(resolved.value)
        assertEquals(SecretSource.MISSING, resolved.source)
    }

    @Test
    fun `an empty value counts as unset`() {
        val secret = ConnectorSecret("SLACK_TOKEN", keychain = "slack-post")
        val resolved =
            resolver(
                environment = mapOf("SLACK_TOKEN" to ""),
                keychain = mapOf("slack-post" to "real"),
            ).resolve(listOf(secret)).single()

        assertEquals("real", resolved.value)
    }

    @Test
    fun `a resolved secret never prints its value`() {
        val resolved =
            resolver(environment = mapOf("SLACK_TOKEN" to "xoxb-super-secret"))
                .resolve(listOf(ConnectorSecret("SLACK_TOKEN")))
                .single()

        assertEquals("SLACK_TOKEN (environment)", resolved.toString())
        assertTrue("xoxb" !in resolved.toString())
    }
}

class RunSummaryTest {
    private fun run(vararg titles: String): WorkflowRun =
        WorkflowRun(id = "r-1", workflowName = "pace", workspaceRoot = null, isInteractive = true).apply {
            titles.forEach { title ->
                nodes +=
                    NodeRun(
                        id = "r-1:$title",
                        workflowName = "pace",
                        nodeId = title,
                        nodeTitle = title,
                        nodeType = NodeType.SHELL,
                        cwd = null,
                    )
            }
        }

    @Test
    fun `the count is the step being run`() {
        val run = run("one", "two", "three", "four")
        run.nodes[0].status = RunStatus.RUNNING

        assertEquals("Running · 1/4 · one", run.summary())
    }

    @Test
    fun `a fan out counts both arms and names the first`() {
        val run = run("a", "b", "c", "d")
        run.nodes[0].status = RunStatus.SUCCEEDED
        run.nodes[1].status = RunStatus.RUNNING
        run.nodes[2].status = RunStatus.RUNNING

        assertEquals("Running · 3/4 · b", run.summary())
    }
}
