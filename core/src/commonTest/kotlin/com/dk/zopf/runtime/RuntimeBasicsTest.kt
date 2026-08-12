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
import com.dk.zopf.model.withDefaultsFrom
import com.dk.zopf.store.AppSettings
import java.nio.file.Paths
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
    fun `references are replaced with the output they name`() {
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

    @Test
    fun `the claude command streams both ways and pins the session id`() {
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
    fun `a schema is passed inline, since the flag refuses a path`() {
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
    fun `nothing unset is passed`() {
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
    fun `the approval hook arrives as --settings, with the hook events it explains`() {
        val command = claude(invocation.copy(settingsFile = Paths.get("/support/zopf-hooks-bash.json")))

        assertTrue(command.containsInOrder("--settings", "/support/zopf-hooks-bash.json"))
        assertTrue("--include-hook-events" in command)
    }

    @Test
    fun `a resolved executable path replaces the bare name`() {
        assertEquals("/opt/claude", ClaudeProvider.command(invocation, "/opt/claude").first())
        assertEquals("/opt/codex", CodexProvider.command(invocation, "/opt/codex").first())
    }

    @Test
    fun `codex takes its global flags before the subcommand, and the prompt after it`() {
        val command = codex()
        val exec = command.indexOf("exec")

        assertTrue(exec > 0, command.toString())
        assertTrue(command.indexOf("--ask-for-approval") < exec)
        assertTrue(command.indexOf("--sandbox") < exec)
        assertTrue(command.indexOf("--model") < exec)
        assertEquals(listOf("exec", "--json", "hi"), command.drop(exec))
    }

    @Test
    fun `codex is never left able to pause for an approval nobody can answer`() {
        assertTrue(codex().containsInOrder("--ask-for-approval", "never"))
        assertTrue(codex(AgentInvocation(prompt = "hi", cwd = Paths.get("/repo"))).containsInOrder("--ask-for-approval", "never"))
    }

    @Test
    fun `codex is passed nothing claude-shaped, and no sandbox it wasn't given`() {
        val command = codex(invocation.copy(sandbox = null, jsonSchema = "{}", settingsFile = Paths.get("/s.json")))

        assertFalse("--sandbox" in command)
        assertFalse("--permission-mode" in command)
        assertFalse("--allowedTools" in command)
        assertFalse("--plugin-dir" in command)
        assertFalse("--json-schema" in command)
        assertFalse("--settings" in command)
    }

    @Test
    fun `codex takes the prompt on the command line, claude on stdin`() {
        assertEquals(PromptChannel.ARGUMENT, CodexProvider.promptChannel)
        assertEquals(PromptChannel.STDIN, ClaudeProvider.promptChannel)

        assertTrue("hi" in codex())
        assertFalse("hi" in claude())
    }

    @Test
    fun `only the CLI that can resume one chooses the session id up front`() {
        assertNotNull(ClaudeProvider.newSessionId())
        assertNull(CodexProvider.newSessionId())
    }

    private fun List<String>.containsInOrder(vararg values: String): Boolean {
        val start = indexOf(values.first())
        return start >= 0 && subList(start, minOf(start + values.size, size)) == values.toList()
    }
}

class AgentCapabilitiesTest {
    @Test
    fun `every provider answers every question, and no two claim the same shape`() {
        val answers = AgentProviders.all.associate { it.id to it.capabilities }

        assertEquals(AgentProviderId.entries.toSet(), answers.keys)
        assertEquals(answers.size, answers.values.distinct().size, "two CLIs cannot be the same CLI")
    }

    @Test
    fun `what a codex node has no version of is what the console must stop offering`() {
        val codex = CodexProvider.capabilities

        assertFalse(codex.followUps)
        assertFalse(codex.inlineApproval)
        assertFalse(codex.resumeInTerminal)
        assertFalse(codex.reportsCostUsd)
        assertFalse(codex.skills)
        assertTrue(codex.sandbox)

        assertEquals(emptyList(), CodexProvider.terminalArgs("t-1"), "there is no exec session to resume")
    }

    @Test
    fun `a field the chosen CLI has no version of is named rather than silently dropped`() {
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

    @Test
    fun `the node wins over the workflow, which wins over the machine`() {
        val codexEverywhere = AppSettings(defaultProvider = AgentProviderId.CODEX)
        val workflowSaysClaude = workflow.copy(defaults = workflow.defaults.copy(provider = AgentProviderId.CLAUDE))

        assertEquals(
            AgentProviderId.CODEX,
            resolveProvider(node.copy(provider = AgentProviderId.CODEX), workflowSaysClaude, AppSettings()),
        )
        assertEquals(AgentProviderId.CLAUDE, resolveProvider(node, workflowSaysClaude, codexEverywhere))
        assertEquals(AgentProviderId.CODEX, resolveProvider(node, workflow, codexEverywhere))
    }

    @Test
    fun `absent everywhere means claude`() {
        assertEquals(AgentProviderId.CLAUDE, resolveProvider(node, workflow, AppSettings()))
    }

    @Test
    fun `the workspace wins over the machine, and the workflow wins over the workspace`() {
        val machineSaysClaude = AppSettings(defaultProvider = AgentProviderId.CLAUDE)
        val workspaceSaysCodex = NodeDefaults(provider = AgentProviderId.CODEX)

        assertEquals(
            AgentProviderId.CODEX,
            resolveProvider(node, workflow.withDefaultsFrom(workspaceSaysCodex), machineSaysClaude),
        )

        val workflowSaysClaude = workflow.copy(defaults = workflow.defaults.copy(provider = AgentProviderId.CLAUDE))
        assertEquals(
            AgentProviderId.CLAUDE,
            resolveProvider(node, workflowSaysClaude.withDefaultsFrom(workspaceSaysCodex), machineSaysClaude),
        )
    }
}

class ModelResolutionTest {
    private val node = WorkflowNode(id = "n", type = NodeType.AGENT)
    private val workflow = Workflow(name = "w")
    private val settings = AppSettings(defaultModel = "haiku")

    @Test
    fun `the node wins over the workflow, which wins over the machine`() {
        assertEquals("opus", resolveModel(node.copy(model = "opus"), workflow.withDefaultModel("sonnet"), settings))
        assertEquals("sonnet", resolveModel(node, workflow.withDefaultModel("sonnet"), settings))
        assertEquals("haiku", resolveModel(node, workflow, settings))
    }

    @Test
    fun `nobody naming one is not the same as naming a default`() {
        assertNull(resolveModel(node, workflow, AppSettings()))
    }

    @Test
    fun `the workspace wins over the machine, and the workflow wins over the workspace`() {
        val workspaceSaysSonnet = NodeDefaults(model = "sonnet")

        assertEquals("sonnet", resolveModel(node, workflow.withDefaultsFrom(workspaceSaysSonnet), settings))
        assertEquals(
            "opus",
            resolveModel(node, workflow.withDefaultModel("opus").withDefaultsFrom(workspaceSaysSonnet), settings),
        )
        assertEquals("haiku", resolveModel(node, workflow.withDefaultsFrom(NodeDefaults()), settings))
    }

    @Test
    fun `a model name belongs to the CLI it was written for, and never crosses to the other one`() {
        val codexNode = node.copy(provider = AgentProviderId.CODEX)

        assertNull(resolveModel(codexNode, workflow.withDefaultModel("sonnet"), settings))

        assertEquals("gpt-5-codex", resolveModel(codexNode.copy(model = "gpt-5-codex"), workflow, settings))
    }

    @Test
    fun `a workflow whose default CLI is codex hands its default model to codex, not to claude`() {
        val codexWorkflow = workflow.copy(defaults = workflow.defaults.copy(provider = AgentProviderId.CODEX))

        assertEquals("gpt-5-codex", resolveModel(node, codexWorkflow.withDefaultModel("gpt-5-codex"), settings))

        assertEquals(
            "haiku",
            resolveModel(node.copy(provider = AgentProviderId.CLAUDE), codexWorkflow.withDefaultModel("gpt-5-codex"), settings),
        )
    }

    private fun Workflow.withDefaultModel(model: String) = copy(defaults = defaults.copy(model = model))
}

class TerminalLauncherTest {
    @Test
    fun `the handoff script cds to the node's directory first`() {
        val script = TerminalLauncher.script(Paths.get("/Users/someone/dev/my repo"), listOf("-r", "abc-123"))
        assertTrue("cd '/Users/someone/dev/my repo'" in script, script)
        assertTrue("exec 'claude' '-r' 'abc-123'" in script, script)
    }

    @Test
    fun `with no arguments it starts a fresh conversation in the directory`() {
        val script = TerminalLauncher.script(Paths.get("/Users/someone/zopf/connectors/slack-post"))
        assertTrue("cd '/Users/someone/zopf/connectors/slack-post'" in script, script)
        assertTrue(script.trimEnd().endsWith("exec 'claude'"), script)
    }

    @Test
    fun `an opening prompt is one argument, however it is written`() {
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
    fun `a quote in a path cannot break out of the script`() {
        val script = TerminalLauncher.script(Paths.get("/tmp/it's here"), listOf("-r", "s"))
        assertTrue("""cd '/tmp/it'\''s here'""" in script, script)
    }
}

class FinderTest {
    @Test
    fun `reveal selects the item rather than opening it`() {
        assertEquals(
            listOf("open", "-R", "/w/workflows/ship.yaml"),
            Finder.command(Paths.get("/w/workflows/ship.yaml")),
        )
    }

    @Test
    fun `revealing something that has gone says which path`() {
        val missing = Paths.get("/nowhere/at/all/ship.yaml")
        val failure = Finder.reveal(missing).exceptionOrNull()
        assertContains(failure?.message.orEmpty(), missing.toString())
    }
}

class BranchesTest {
    @Test
    fun `an exit code compares as text, because that is all interpolation produces`() {
        assertTrue(Branches.evaluate("0 == 0").taken)
        assertFalse(Branches.evaluate("1 == 0").taken)
    }

    @Test
    fun `not-equals is checked before equals, so it isn't read as the tail of one`() {
        assertTrue(Branches.evaluate("1 != 0").taken)
        assertFalse(Branches.evaluate("ok != ok").taken)
    }

    @Test
    fun `quotes are optional and stripped either way`() {
        assertTrue(Branches.evaluate("\"ship it\" == \"ship it\"").taken)
        assertTrue(Branches.evaluate("'ship it' == ship it").taken)
    }

    @Test
    fun `with no operator, an empty or falsey value is false and anything else is true`() {
        assertFalse(Branches.evaluate("").taken)
        assertFalse(Branches.evaluate("  ").taken)
        assertFalse(Branches.evaluate("false").taken)
        assertFalse(Branches.evaluate("FALSE").taken)
        assertFalse(Branches.evaluate("0").taken)
        assertTrue(Branches.evaluate("true").taken)
        assertTrue(Branches.evaluate("anything at all").taken)
    }

    @Test
    fun `an unresolved reference compares as the literal it still is, rather than as nothing`() {
        assertFalse(Branches.evaluate("\${build.exitCode} == 0").taken)
    }

    @Test
    fun `the verdict explains itself, because a branch is the hardest thing to debug after the fact`() {
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
    fun `the keychain fills in what the environment doesn't have`() {
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
    fun `an empty value counts as not set`() {
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
    fun `the count is the step being run, so it agrees with the title beside it`() {
        val run = run("one", "two", "three", "four")
        run.nodes[0].status = RunStatus.RUNNING

        assertEquals("Running · 1/4 · one", run.summary())
    }

    @Test
    fun `a fan out counts both arms, and names the first of them`() {
        val run = run("a", "b", "c", "d")
        run.nodes[0].status = RunStatus.SUCCEEDED
        run.nodes[1].status = RunStatus.RUNNING
        run.nodes[2].status = RunStatus.RUNNING

        assertEquals("Running · 3/4 · b", run.summary())
    }
}
