package com.dk.zopf.runtime

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.PermissionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionBridgeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dirs = mutableListOf<Path>()
    private val bridges = mutableListOf<PermissionBridge>()

    @AfterTest
    fun cleanUp() {
        bridges.forEach { it.shutdown() }
        dirs.forEach { dir -> dir.toFile().deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("zopf-bridge").also { dirs.add(it) }

    private fun node(sessionId: String? = SESSION) =
        NodeRun(
            id = "run-1",
            workflowName = "demo",
            nodeId = "analyze",
            nodeTitle = "Analyze",
            nodeType = NodeType.AGENT,
            cwd = Paths.get("/tmp"),
        ).also {
            it.sessionId = sessionId
            it.status = RunStatus.RUNNING
        }

    private fun bridge(
        node: NodeRun?,
        dir: Path = tempDir(),
        deadlineSeconds: Long = 30,
        onRequest: (NodeRun) -> Unit = {},
    ) = PermissionBridge(
        nodeBySession = { id -> node?.takeIf { it.sessionId == id } },
        onRequest = onRequest,
        dir = dir,
        deadlineSeconds = deadlineSeconds,
    ).also { bridges.add(it) }

    private fun portOf(script: Path): Int = Regex("127\\.0\\.0\\.1:(\\d+)").find(script.readText())!!.groupValues[1].toInt()

    private fun scriptIn(dir: Path): Path = dir.resolve("zopf-approve.sh")

    private fun post(
        port: Int,
        body: String,
        token: String?,
    ): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port/permission").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        token?.let { connection.setRequestProperty("X-Zopf-Token", it) }
        connection.outputStream.use { out: OutputStream -> out.write(body.toByteArray()) }
        val status = connection.responseCode
        val text =
            (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.readText()
                .orEmpty()
        return status to text
    }

    private fun tokenIn(script: Path): String = Regex("X-Zopf-Token: (\\w+)").find(script.readText())!!.groupValues[1]

    private fun decisionIn(body: String): String =
        json
            .parseToJsonElement(body)
            .jsonObject["hookSpecificOutput"]!!
            .jsonObject["permissionDecision"]!!
            .jsonPrimitive.content

    private fun hookPayload(
        tool: String = "Bash",
        session: String = SESSION,
    ) = """
        {"session_id":"$session","hook_event_name":"PreToolUse","tool_name":"$tool",
         "tool_input":{"command":"./gradlew build"},"tool_use_id":"toolu_01"}
        """.trimIndent()

    @Test
    fun `the permission mode decides which tools are asked about`() {
        assertEquals(emptyList(), askableTools(PermissionMode.BYPASS_PERMISSIONS))
        assertEquals(emptyList(), askableTools(PermissionMode.DONT_ASK))

        assertEquals(listOf("Bash", "WebFetch"), askableTools(PermissionMode.ACCEPT_EDITS))
        assertEquals(ASKABLE_TOOLS, askableTools(null))
        assertEquals(ASKABLE_TOOLS, askableTools(PermissionMode.MANUAL))
    }

    @Test
    fun `reading and searching are never intercepted`() {
        listOf("Read", "Grep", "Glob").forEach {
            assertFalse(it in ASKABLE_TOOLS, "$it should never be asked about")
        }
    }

    @Test
    fun `stopping the runs lets the parked question go without ending inline approval`() {
        val dir = tempDir()
        val bridge = bridge(node(), dir)
        assertNotNull(bridge.settingsFile(ASKABLE_TOOLS))

        bridge.denyOutstanding()

        assertNotNull(
            bridge.settingsFile(ASKABLE_TOOLS),
            "Stop Everything must not switch inline approval off for the rest of the session",
        )
    }

    @Test
    fun `quitting does end it, and nothing is installed afterwards`() {
        val bridge = bridge(node(), tempDir())
        assertNotNull(bridge.settingsFile(ASKABLE_TOOLS))

        bridge.shutdown()

        assertNull(bridge.settingsFile(ASKABLE_TOOLS))
    }

    @Test
    fun `a mode that never asks gets no settings file at all`() {
        assertNull(bridge(node()).settingsFile(askableTools(PermissionMode.BYPASS_PERMISSIONS)))
    }

    @Test
    fun `each asked-about tool gets its own matcher`() {
        val dir = tempDir()
        val file = bridge(node(), dir).settingsFile(listOf("Bash", "Edit"))!!
        val hooks =
            json
                .parseToJsonElement(file.readText())
                .jsonObject["hooks"]!!
                .jsonObject["PreToolUse"]!!

        val matchers = json.parseToJsonElement(hooks.toString()).toString()
        assertTrue("\"Bash\"" in matchers && "\"Edit\"" in matchers)
        assertTrue(scriptIn(dir).toString() in matchers, "the hook has to point at the script")
    }

    @Test
    fun `an answered request comes back as the answer that was given`() {
        val node = node()
        val dir = tempDir()
        val bridge = bridge(node, dir, onRequest = { it.pendingPermission!!.decide(true) })
        bridge.settingsFile(ASKABLE_TOOLS)

        val (status, body) = post(portOf(scriptIn(dir)), hookPayload(), tokenIn(scriptIn(dir)))

        assertEquals(200, status)
        assertEquals("allow", decisionIn(body))

        assertEquals(RunStatus.RUNNING, node.status)
        assertNull(node.pendingPermission)
    }

    @Test
    fun `a denial is a denial`() {
        val node = node()
        val dir = tempDir()
        val bridge = bridge(node, dir, onRequest = { it.pendingPermission!!.decide(false) })
        bridge.settingsFile(ASKABLE_TOOLS)

        val (_, body) = post(portOf(scriptIn(dir)), hookPayload(), tokenIn(scriptIn(dir)))

        assertEquals("deny", decisionIn(body))
    }

    @Test
    fun `the request carries what the tool is about to do`() {
        val node = node()
        val dir = tempDir()
        var seen: PermissionRequest? = null
        val bridge =
            bridge(node, dir, onRequest = {
                seen = it.pendingPermission!!.request
                it.pendingPermission!!.decide(true)
            })
        bridge.settingsFile(ASKABLE_TOOLS)
        post(portOf(scriptIn(dir)), hookPayload(), tokenIn(scriptIn(dir)))

        assertEquals("Bash", seen?.toolName)

        assertEquals("./gradlew build", seen?.summary)
        assertEquals("toolu_01", seen?.toolUseId)
    }

    @Test
    fun `allowing a tool for the rest of the run stops it asking again`() {
        val node = node()
        val dir = tempDir()
        var asked = 0
        val bridge =
            bridge(node, dir, onRequest = {
                asked++
                it.autoAllowed.add(it.pendingPermission!!.request.toolName)
                it.pendingPermission!!.decide(true)
            })
        bridge.settingsFile(ASKABLE_TOOLS)
        val port = portOf(scriptIn(dir))
        val token = tokenIn(scriptIn(dir))

        val first = post(port, hookPayload(), token)
        val second = post(port, hookPayload(), token)

        assertEquals("allow", decisionIn(first.second))
        assertEquals("allow", decisionIn(second.second))
        assertEquals(1, asked, "the second call should not have reached a person")
    }

    @Test
    fun `another process on this machine cannot answer for you`() {
        val node = node()
        val dir = tempDir()
        bridge(node, dir, onRequest = { it.pendingPermission!!.decide(true) }).settingsFile(ASKABLE_TOOLS)

        val (status, _) = post(portOf(scriptIn(dir)), hookPayload(), token = "not-the-token")

        assertEquals(403, status)
    }

    @Test
    fun `a session nothing knows about is denied rather than allowed`() {
        val dir = tempDir()
        bridge(node = null, dir = dir).settingsFile(ASKABLE_TOOLS)

        val (_, body) = post(portOf(scriptIn(dir)), hookPayload(session = "who-is-this"), tokenIn(scriptIn(dir)))

        assertEquals("deny", decisionIn(body))
    }

    @Test
    fun `a request nobody answers is denied when the deadline passes`() {
        val node = node()
        val dir = tempDir()

        bridge(node, dir, deadlineSeconds = 1, onRequest = {}).settingsFile(ASKABLE_TOOLS)

        val (_, body) = post(portOf(scriptIn(dir)), hookPayload(), tokenIn(scriptIn(dir)))

        assertEquals("deny", decisionIn(body))
        assertTrue(
            node.entries.filterIsInstance<ConsoleEntry.Notice>().any { "Nobody answered" in it.text },
            "the console has to say why the tool didn't run",
        )
    }

    @Test
    fun `quitting lets every waiting child go with a no`() {
        val node = node()
        val dir = tempDir()

        val registered = CountDownLatch(1)
        val bridge = bridge(node, dir, deadlineSeconds = 60, onRequest = { registered.countDown() })
        bridge.settingsFile(ASKABLE_TOOLS)
        val port = portOf(scriptIn(dir))
        val token = tokenIn(scriptIn(dir))

        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<Pair<Int, String>> { post(port, hookPayload(), token) }
        assertTrue(registered.await(10, TimeUnit.SECONDS), "the request never reached the bridge")
        bridge.shutdown()

        val (_, body) = call.get(10, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertEquals("deny", decisionIn(body))
    }

    @Test
    fun `the hook script denies when zopf cannot be reached`() {
        val dir = tempDir()
        bridge(node(), dir).settingsFile(ASKABLE_TOOLS)
        val script = scriptIn(dir)

        val deadPort = ServerSocket(0).use { it.localPort }
        val rewritten = script.readText().replace(Regex("127\\.0\\.0\\.1:\\d+"), "127.0.0.1:$deadPort")
        Files.writeString(script, rewritten)

        val process =
            ProcessBuilder("/bin/sh", script.toString())
                .redirectErrorStream(false)
                .start()
        process.outputStream.use { it.write(hookPayload().toByteArray()) }
        val printed = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)

        assertEquals("deny", decisionIn(printed.trim()))
    }

    @Test
    fun `the script prints valid JSON, so a stray apostrophe cannot break the deny`() {
        val dir = tempDir()
        bridge(node(), dir).settingsFile(ASKABLE_TOOLS)
        val text = scriptIn(dir).readText()

        val fallback = Regex("printf '%s\\\\n' '(\\{.*})'").find(text)!!.groupValues[1]
        assertEquals("deny", decisionIn(fallback))
    }

    @Test
    fun `the script and settings are written where a workspace will never see them`() {
        val dir = tempDir()
        val file = bridge(node(), dir).settingsFile(ASKABLE_TOOLS)!!

        assertEquals(dir, file.parent)
        assertEquals(dir, scriptIn(dir).parent)
    }

    private companion object {
        const val SESSION = "11111111-2222-3333-4444-555555555555"
    }
}
