package com.dk.zopf.runtime

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.CONNECTOR_MANIFEST
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isExecutable
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectorRunnerTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-connector").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun script(
        body: String,
        executable: Boolean = true,
    ): Path {
        val file = tempDir().resolve("run.sh")
        file.writeText("#!/bin/sh\n$body\n")
        if (executable) file.toFile().setExecutable(true)
        return file
    }

    private fun run(
        script: Path,
        inputs: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 30,
    ): Pair<Int, ConnectorOutput> =
        runBlocking {
            val session =
                ConnectorRunner().start(
                    ConnectorInvocation(script, script.parent, inputs, timeoutSeconds),
                )
            session.lines().toList()
            val exit = session.awaitExit()
            exit to session.output()
        }

    @Test
    fun `inputs arrive as one JSON object on stdin and the result comes back`() {
        val script = script("printf '{\"result\": %s}' \"\$(cat)\"")

        val (exit, output) = run(script, mapOf("channel" to "#eng", "text" to "hello"))

        assertEquals(0, exit)
        assertEquals("""{"channel":"#eng","text":"hello"}""", output.result)
        assertTrue(output.sawJson)
        assertNull(output.error)
    }

    @Test
    fun `a script that logs before its JSON is still understood`() {
        val script =
            script(
                """
                cat > /dev/null
                echo "connecting..."
                echo "done"
                echo '{"result": "posted"}'
                """.trimIndent(),
            )

        val (exit, output) = run(script)

        assertEquals(0, exit)
        assertEquals("posted", output.result)
    }

    @Test
    fun `an error object is reported even when the JSON parses`() {
        val script = script("""cat > /dev/null; echo '{"error": "401 unauthorized"}'; exit 1""")

        val (exit, output) = run(script)

        assertEquals(1, exit)
        assertEquals("401 unauthorized", output.error)
    }

    @Test
    fun `a script that prints no JSON still hands its output on, and says so`() {
        val script = script("""cat > /dev/null; echo "just some text"""")

        val (_, output) = run(script)

        assertEquals("just some text", output.result)
        assertFalse(output.sawJson)
    }

    @Test
    fun `stderr is kept apart from the result`() =
        runBlocking {
            val script = script("""cat > /dev/null; echo "warning" >&2; echo '{"result": "ok"}'""")
            val session = ConnectorRunner().start(ConnectorInvocation(script, script.parent, emptyMap()))

            val lines = session.lines().toList()
            session.awaitExit()

            assertEquals(setOf(ShellLine("warning", true), ShellLine("""{"result": "ok"}""", false)), lines.toSet())
            assertEquals("ok", session.output().result)
        }

    @Test
    fun `a script that was never chmod +x is made runnable`() {
        val script = script("""cat > /dev/null; echo '{"result": "ran anyway"}'""", executable = false)
        assertFalse(script.isExecutable())

        val (exit, output) = run(script)

        assertEquals(0, exit)
        assertEquals("ran anyway", output.result)
    }

    @Test
    fun `a connector that hangs is killed rather than parking the run`() {
        val script = script("sleep 30")

        val session =
            ConnectorRunner().start(
                ConnectorInvocation(script, script.parent, emptyMap(), timeoutSeconds = 1),
            )
        val exit = runBlocking { session.awaitExit() }

        assertEquals(CONNECTOR_TIMEOUT_EXIT, exit)
        assertTrue(session.timedOut)
    }

    @Test
    fun `the last JSON object on stdout wins`() {
        val output =
            ConnectorOutput.parse(
                listOf("""{"result": "first"}""", "chatter", """{"result": "second"}"""),
            )

        assertEquals("second", output.result)
    }

    @Test
    fun `JSON pretty-printed across several lines is still read`() {
        val output = ConnectorOutput.parse(listOf("{", """  "result": "spread out"""", "}"))

        assertEquals("spread out", output.result)
        assertTrue(output.sawJson)
    }

    @Test
    fun `every other key of the object comes back as its own field`() {
        val output =
            ConnectorOutput.parse(
                listOf("""{"result": "posted", "messageId": "1712.9", "permalink": "https://example.test/1"}"""),
            )

        assertEquals("posted", output.result)
        assertEquals(mapOf("messageId" to "1712.9", "permalink" to "https://example.test/1"), output.fields)
    }

    @Test
    fun `a structured field keeps its JSON form, and null is not a field`() {
        val output = ConnectorOutput.parse(listOf("""{"result": "ok", "ids": [1, 2], "cursor": null}"""))

        assertEquals("[1,2]", output.fields["ids"])
        assertFalse("cursor" in output.fields)
    }

    @Test
    fun `error is reported as an error rather than as a field`() {
        val output = ConnectorOutput.parse(listOf("""{"error": "401", "retryAfter": "30"}"""))

        assertEquals("401", output.error)
        assertEquals(mapOf("retryAfter" to "30"), output.fields)
    }

    @Test
    fun `an object with no result is the result, and still exposes its keys individually`() {
        val output = ConnectorOutput.parse(listOf("""{"id": 7, "url": "https://example.test"}"""))

        assertEquals("""{"id":7,"url":"https://example.test"}""", output.result)
        assertEquals(mapOf("id" to "7", "url" to "https://example.test"), output.fields)
        assertNull(output.error)
    }

    @Test
    fun `output with no JSON at all has no fields`() {
        assertEquals(emptyMap(), ConnectorOutput.parse(listOf("just some text")).fields)
    }

    @Test
    fun `a non-string result keeps its JSON form`() {
        assertEquals("""["a","b"]""", ConnectorOutput.parse(listOf("""{"result": ["a", "b"]}""")).result)
        assertEquals("42", ConnectorOutput.parse(listOf("""{"result": 42}""")).result)
    }

    @Test
    fun `a null error is not an error`() {
        val output = ConnectorOutput.parse(listOf("""{"result": "fine", "error": null}"""))

        assertNull(output.error)
        assertEquals("fine", output.result)
    }

    @Test
    fun `nothing on stdout is an empty result rather than a crash`() {
        val output = ConnectorOutput.parse(emptyList())

        assertEquals("", output.result)
        assertFalse(output.sawJson)
    }
}

class ConnectorNodeTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-connector-node").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a connector runs in its own directory with its inputs on stdin`() {
        val workspace = workspace()

        connector(
            workspace,
            "echoer",
            manifest = """{"inputs": [{"name": "text"}]}""",
            body = "INPUT=\$(cat)\n[ -f connector.json ] || exit 3\nprintf '{\"result\": %s}' \"\$INPUT\"",
        )

        val run =
            runWorkflow(
                workspace,
                Workflow(
                    name = "w",
                    nodes =
                        listOf(
                            WorkflowNode(id = "make", type = NodeType.SHELL, command = "echo hi"),
                            WorkflowNode(
                                id = "notify",
                                type = NodeType.CONNECTOR,
                                connector = "echoer",
                                inputs = mapOf("text" to "\${make.result}"),
                            ),
                        ),
                    edges = listOf(WorkflowEdge("make", "notify")),
                ),
            )

        val notify = assertNotNull(run.node("notify"))
        assertEquals(RunStatus.SUCCEEDED, notify.status)
        assertEquals(0, notify.exitCode)
        assertEquals(workspace.connectorsDir.resolve("echoer"), notify.cwd)

        assertEquals("""{"text":"hi"}""", notify.output().result)
    }

    @Test
    fun `what a connector returns is what the next node reads`() {
        val workspace = workspace()
        connector(workspace, "namer", body = "cat > /dev/null; echo '{\"result\": \"ada\"}'")

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("namer", caller = "who", reader = "echo hello \${who.result}"),
            )

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals("hello ada", run.node("read")?.output()?.result)
    }

    @Test
    fun `a connector's extra fields are addressable by the node after it, declared or not`() {
        val workspace = workspace()
        connector(
            workspace,
            "poster",
            manifest = """{"outputs": ["messageId"]}""",
            body = """cat > /dev/null; echo '{"result": "posted", "messageId": "1712.9", "cursor": "abc"}'""",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow(
                    "poster",
                    caller = "post",
                    reader = "echo \${post.result} in \${post.messageId} at \${post.cursor}",
                ),
            )

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals("posted in 1712.9 at abc", run.node("read")?.output()?.result)
    }

    @Test
    fun `zopf's own fields aren't shadowed by a connector returning the same name`() {
        val workspace = workspace()
        connector(workspace, "liar", body = """cat > /dev/null; echo '{"result": "ok", "exitCode": "99"}'""")

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("liar", caller = "call", reader = "echo \${call.exitCode}"),
            )

        assertEquals("0", run.node("read")?.output()?.result)
    }

    @Test
    fun `a declared output that didn't come back is said out loud`() {
        val workspace = workspace()
        connector(
            workspace,
            "forgetful",
            manifest = """{"outputs": ["messageId", "permalink"]}""",
            body = """cat > /dev/null; echo '{"result": "posted", "messageId": "1"}'""",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("forgetful"),
            )

        val post = assertNotNull(run.node("post"))

        val notice =
            assertNotNull(
                post.entries.filterIsInstance<ConsoleEntry.Notice>().firstOrNull { "permalink" in it.text },
            )
        assertTrue(notice.isWarning)
        assertFalse("messageId" in notice.text, notice.text)

        assertEquals(RunStatus.SUCCEEDED, post.status)
    }

    @Test
    fun `the manifest's defaults fill in the inputs a node doesn't set`() {
        val workspace = workspace()
        connector(
            workspace,
            "defaulter",
            manifest = """{"inputs": [{"name": "channel", "default": "#general"}]}""",
            body = "printf '{\"result\": %s}' \"\$(cat)\"",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("defaulter"),
            )

        assertEquals("""{"channel":"#general"}""", run.node("post")?.output()?.result)
    }

    @Test
    fun `a missing required input fails the node before anything is launched`() {
        val workspace = workspace()
        connector(
            workspace,
            "strict",
            manifest = """{"inputs": [{"name": "text", "required": true}]}""",
            body = "cat > /dev/null; echo '{\"result\": \"ran\"}'",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("strict"),
            )

        val post = assertNotNull(run.node("post"))
        assertEquals(RunStatus.FAILED, post.status)
        assertTrue(post.output().result.isEmpty())
        assertTrue(post.entries.any { it is ConsoleEntry.Notice && it.text.contains("needs text") })
    }

    @Test
    fun `a connector that reports an error fails the run and skips what follows`() {
        val workspace = workspace()
        connector(workspace, "flaky", body = "cat > /dev/null; echo '{\"error\": \"401\"}'; exit 1")

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("flaky", reader = "echo unreachable"),
            )

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(RunStatus.FAILED, run.node("post")?.status)
        assertEquals(RunStatus.SKIPPED, run.node("read")?.status)
    }

    @Test
    fun `a resolved secret reaches the script's environment, whichever place it came from`() {
        val workspace = workspace()
        connector(
            workspace,
            "tokened",
            manifest = """{"env": [{"name": "SLACK_TOKEN", "keychain": "slack-post"}]}""",
            body = "cat > /dev/null; printf '{\"result\": \"%s\"}' \"\$SLACK_TOKEN\"",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("tokened"),
                executor = executorWith(keychain = mapOf("slack-post" to "xoxb-from-keychain")),
            )

        val post = assertNotNull(run.node("post"))
        assertEquals(RunStatus.SUCCEEDED, post.status)
        assertEquals("xoxb-from-keychain", post.output().result)

        assertTrue(post.entries.any { it is ConsoleEntry.Notice && it.text == "Secrets: SLACK_TOKEN (keychain)" })
        assertTrue(post.entries.none { it is ConsoleEntry.Notice && "xoxb" in it.text })
    }

    @Test
    fun `a secret that can't be found anywhere stops the node before it launches`() {
        val workspace = workspace()
        connector(
            workspace,
            "needy",
            manifest = """{"env": [{"name": "SLACK_TOKEN", "keychain": "slack-post"}]}""",
            body = "cat > /dev/null; echo '{\"result\": \"ran\"}'",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("needy"),
                executor = executorWith(),
            )

        val post = assertNotNull(run.node("post"))
        assertEquals(RunStatus.FAILED, post.status)
        assertTrue(post.output().result.isEmpty())
        val notice =
            assertNotNull(
                post.entries.filterIsInstance<ConsoleEntry.Notice>().firstOrNull { "SLACK_TOKEN" in it.text },
            )

        assertTrue("keychain \"slack-post\"" in notice.text, notice.text)
    }

    @Test
    fun `an optional secret that isn't set doesn't stop anything`() {
        val workspace = workspace()
        connector(
            workspace,
            "relaxed",
            manifest = """{"env": [{"name": "HTTP_PROXY", "required": false}]}""",
            body = "cat > /dev/null; echo '{\"result\": \"fine\"}'",
        )

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("relaxed"),
                executor = executorWith(),
            )

        assertEquals(RunStatus.SUCCEEDED, run.node("post")?.status)
    }

    @Test
    fun `a connector nobody installed is refused before the run starts`() {
        val result =
            engine().start(
                workspace = workspace(),
                workflow = Workflow(name = "w"),
                only = WorkflowNode(id = "post", type = NodeType.CONNECTOR, connector = "not-installed-anywhere"),
            )

        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "not-installed-anywhere")
    }

    @Test
    fun `a connector that never finishes is given up on at the node's timeout rather than parking the run`() {
        val workspace = workspace()
        connector(workspace, "sleeper", body = "cat > /dev/null\nexec sleep 30")

        val run =
            runWorkflow(
                workspace,
                Workflow(
                    name = "w",
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "post",
                                type = NodeType.CONNECTOR,
                                connector = "sleeper",
                                timeoutSeconds = 1,
                            ),
                        ),
                ),
            )

        val post = assertNotNull(run.node("post"))
        assertEquals(RunStatus.FAILED, post.status)
        assertEquals(NODE_TIMEOUT_EXIT, post.exitCode)
        assertTrue(post.entries.any { it is ConsoleEntry.Notice && "Gave up after 1s" in it.text })
    }

    @Test
    fun `the raw output of a connector is archived like any other node`() {
        val workspace = workspace()
        val archiveRoot = tempDir()
        connector(workspace, "chatty", body = "cat > /dev/null; echo working; echo '{\"result\": \"done\"}'")

        val run =
            runWorkflow(
                workspace,
                connectorWorkflow("chatty"),
                archiveRoot = archiveRoot,
            )

        val archive = assertNotNull(RunArchive.all(archiveRoot).firstOrNull { it.read()?.id == run.id })
        val raw = Files.readAllLines(archive.rawPath("post"))
        assertEquals(listOf("working", """{"result": "done"}"""), raw)
    }

    private fun workspace(): Workspace = Workspace.create(tempDir().resolve("ws"))

    private fun connectorWorkflow(
        connector: String,
        caller: String = "post",
        reader: String? = null,
    ) = Workflow(
        name = "w",
        nodes =
            listOfNotNull(
                WorkflowNode(id = caller, type = NodeType.CONNECTOR, connector = connector),
                reader?.let { WorkflowNode(id = "read", type = NodeType.SHELL, command = it) },
            ),
        edges = reader?.let { listOf(WorkflowEdge(caller, "read")) }.orEmpty(),
    )

    private fun connector(
        workspace: Workspace,
        name: String,
        manifest: String = "{}",
        body: String,
    ) {
        val dir = workspace.connectorsDir.resolve(name).also { it.createDirectories() }
        dir.resolve(CONNECTOR_MANIFEST).writeText(manifest)
        dir.resolve("run.sh").apply {
            writeText("#!/bin/sh\n$body\n")
            toFile().setExecutable(true)
        }
    }

    private fun executorWith(
        environment: Map<String, String> = emptyMap(),
        keychain: Map<String, String> = emptyMap(),
    ) = ProcessNodeExecutor(
        secretResolver =
            SecretResolver(
                fromEnvironment = { environment[it] },
                fromKeychain = { keychain[it] },
            ),
    )

    private fun engine(
        archiveRoot: Path = tempDir(),
        executor: NodeExecutor = ProcessNodeExecutor(),
    ): WorkflowEngine {
        val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }
        return WorkflowEngine(scope, executor, archiveRoot)
    }

    private fun runWorkflow(
        workspace: Workspace,
        workflow: Workflow,
        archiveRoot: Path = tempDir(),
        executor: NodeExecutor = ProcessNodeExecutor(),
    ): WorkflowRun =
        runBlocking {
            val run = engine(archiveRoot, executor).start(workspace, workflow).getOrThrow()
            withTimeout(30.seconds) { run.job?.join() }
            run
        }
}
