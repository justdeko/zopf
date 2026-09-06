package com.dk.zopf.store

import com.dk.zopf.model.ConnectorManifest
import com.dk.zopf.model.ConnectorOutputField
import com.dk.zopf.model.ConnectorSecret
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectorStoreTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-connectors").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun workspace(): Workspace = Workspace.create(tempDir().resolve("ws"), "ws")

    private fun connector(
        root: Path,
        name: String,
        manifest: String = """{"description": "does $name"}""",
        script: String? = "#!/bin/sh\necho '{}'\n",
    ): Path {
        val dir = root.resolve(name).also { it.createDirectories() }
        dir.resolve(CONNECTOR_MANIFEST).writeText(manifest)
        script?.let { dir.resolve("run.sh").writeText(it) }
        return dir
    }

    @Test
    fun `connectors come from the workspace and the shared root`() {
        val workspace = workspace()
        val shared = tempDir()
        connector(workspace.connectorsDir, "local")
        connector(shared, "personal")

        val listing = ConnectorStore(workspace, shared).list()

        assertEquals(listOf("local", "personal"), listing.names)
        assertEquals(workspace.name, listing.find("local")?.source)
        assertEquals(SHARED_CONNECTOR_SOURCE, listing.find("personal")?.source)
    }

    @Test
    fun `a workspace connector shadows a shared one`() {
        val workspace = workspace()
        val shared = tempDir()
        connector(workspace.connectorsDir, "notify", manifest = """{"description": "the workspace one"}""")
        connector(shared, "notify", manifest = """{"description": "the shared one"}""")

        val store = ConnectorStore(workspace, shared)

        assertEquals(1, store.list().connectors.size)
        assertEquals("the workspace one", store.find("notify")?.manifest?.description)
    }

    @Test
    fun `the directory name wins over the manifest name`() {
        val shared = tempDir()
        connector(shared, "slack-post", manifest = """{"name": "something-else"}""")

        assertEquals("slack-post", ConnectorStore(null, shared).find("slack-post")?.name)
    }

    @Test
    fun `a connector with no script is broken`() {
        val shared = tempDir()
        connector(shared, "half-written", script = null)

        val listing = ConnectorStore(null, shared).list()

        assertTrue(listing.connectors.isEmpty())
        assertEquals("half-written", listing.broken.single().name)
        assertTrue(
            listing.broken
                .single()
                .message
                .contains("run.sh"),
        )
    }

    @Test
    fun `an unparseable manifest is listed as broken`() {
        val shared = tempDir()
        connector(shared, "mangled", manifest = "{ this is not json")

        val listing = ConnectorStore(null, shared).list()

        assertEquals("mangled", listing.broken.single().name)
        assertNull(ConnectorStore(null, shared).find("mangled"))
    }

    @Test
    fun `a directory without a manifest is not a connector`() {
        val shared = tempDir()
        shared.resolve("just-a-folder").createDirectories()

        assertTrue(ConnectorStore(null, shared).list().isEmpty)
    }

    @Test
    fun `unknown manifest keys are ignored`() {
        val shared = tempDir()
        connector(shared, "future", manifest = """{"description": "d", "retries": 3, "auth": {"kind": "oauth"}}""")

        assertEquals("d", ConnectorStore(null, shared).find("future")?.manifest?.description)
    }

    @Test
    fun `a secret can be a bare name or an object`() {
        val shared = tempDir()
        connector(
            shared,
            "mixed",
            manifest =
                """
                {"env": [
                  "HTTP_PROXY",
                  {"name": "SLACK_TOKEN", "description": "bot token", "keychain": "slack-post"},
                  {"name": "DEBUG", "required": false}
                ]}
                """.trimIndent(),
        )

        val env = assertNotNull(ConnectorStore(null, shared).find("mixed")).manifest.env

        assertEquals(listOf("HTTP_PROXY", "SLACK_TOKEN", "DEBUG"), env.map { it.name })
        assertEquals(ConnectorSecret("HTTP_PROXY"), env[0])
        assertEquals("slack-post", env[1].keychain)
        assertEquals("environment, or keychain \"slack-post\"", env[1].sourceLabel)
        assertTrue(env[0].required)
        assertFalse(env[2].required)
    }

    @Test
    fun `both forms of a secret and an output round trip`() {
        val manifest =
            ConnectorManifest(
                name = "round-trip",
                env =
                    listOf(
                        ConnectorSecret("HTTP_PROXY"),
                        ConnectorSecret("SLACK_TOKEN", description = "bot token", keychain = "slack-post"),
                    ),
                outputs =
                    listOf(
                        ConnectorOutputField("messageId"),
                        ConnectorOutputField("permalink", "link to the message"),
                    ),
            )

        val json = zopfJson.encodeToString(ConnectorManifest.serializer(), manifest)

        assertEquals(manifest, zopfJson.decodeFromString(ConnectorManifest.serializer(), json))
        assertTrue("\"HTTP_PROXY\"" in json, json)
        assertTrue("\"messageId\"" in json, json)
    }

    @Test
    fun `an output field can be a bare name or an object`() {
        val shared = tempDir()
        connector(
            shared,
            "poster",
            manifest =
                """
                {"outputs": [
                  "messageId",
                  {"name": "permalink", "description": "link to the message"}
                ]}
                """.trimIndent(),
        )

        val manifest = assertNotNull(ConnectorStore(null, shared).find("poster")).manifest

        assertEquals(listOf("messageId", "permalink"), manifest.outputs.map { it.name })
        assertEquals("", manifest.output("messageId")?.description)
        assertEquals("link to the message", manifest.output("permalink")?.description)
        assertNull(manifest.output("nothing-declares-this"))
    }

    @Test
    fun `create makes the directory in the workspace or shared root`() {
        val workspace = workspace()
        val shared = tempDir()
        val store = ConnectorStore(workspace, shared)

        val local = store.create("Slack Post").getOrThrow()
        val personal = store.create("pager duty", shared = true).getOrThrow()

        assertEquals(workspace.connectorsDir.resolve("slack-post"), local)
        assertEquals(shared.resolve("pager-duty"), personal)
    }

    @Test
    fun `create refuses to overwrite an existing connector`() {
        val workspace = workspace()
        connector(workspace.connectorsDir, "notify")

        val result = ConnectorStore(workspace, tempDir()).create("notify")

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun `the shared root is not searched twice`() {
        val workspace = Workspace.create(tempDir().resolve("zopf"), "zopf")
        connector(workspace.connectorsDir, "only-one")

        val store = ConnectorStore(workspace, workspace.connectorsDir)

        assertEquals(1, store.roots().size)
        assertEquals(listOf("only-one"), store.list().names)
    }
}
