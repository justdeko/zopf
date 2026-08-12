package com.dk.zopf.store

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.WORKFLOW_VERSION
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
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

class WorkflowMigrationTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun newStore(): WorkflowStore {
        val dir = Files.createTempDirectory("zopf-migration").also { tempDirs.add(it) }
        return WorkflowStore(Workspace.create(dir.resolve("ws")))
    }

    private val renameCommand =
        WorkflowMigration(to = 2, summary = "a shell node's script became its command") { workflow ->
            workflow.eachNode { it.renaming("script", "command") }
        }

    private val describeEverything =
        WorkflowMigration(to = 3, summary = "a workflow says what it is for") { workflow ->
            workflow.with("description", "carried forward")
        }

    private val migrations = listOf(describeEverything, renameCommand)

    private fun migrate(
        yaml: String,
        current: Int = 3,
    ): Workflow =
        zopfYaml.decodeFromYamlNode(
            Workflow.serializer(),
            migrateWorkflow(zopfYaml.parseToYamlNode(yaml), migrations, current),
        )

    @Test
    fun `a workflow with no version is read by today's rules, not by the oldest ones`() {
        val workflow =
            migrate(
                """
                name: fresh
                nodes:
                  - id: build
                    type: shell
                    command: make
                """.trimIndent(),
            )

        assertEquals("", workflow.description, "no version means current, so no older step gets replayed over it")
        assertEquals("make", workflow.nodes.single().command)
    }

    @Test
    fun `a workflow pinned to an older version is carried forward one step at a time`() {
        val workflow =
            migrate(
                """
                version: 1
                name: old
                nodes:
                  - id: build
                    type: shell
                    script: make
                """.trimIndent(),
            )

        assertEquals("make", workflow.nodes.single().command, "v1 spelled it script, and the step to v2 renamed it")
        assertEquals("carried forward", workflow.description, "the step to v3 ran after it, not before")
    }

    @Test
    fun `only the steps between the version the file declares and this one run`() {
        val workflow =
            migrate(
                """
                version: 2
                name: newer
                nodes:
                  - id: build
                    type: shell
                    command: make
                """.trimIndent(),
            )

        assertEquals("make", workflow.nodes.single().command, "the rename already happened in this file")
        assertEquals("carried forward", workflow.description)
    }

    @Test
    fun `migrations run on the YAML, because parsing has already dropped a key the model no longer has`() {
        val parsedFirst =
            migrate(
                """
                version: 2
                name: strays
                nodes:
                  - id: build
                    type: shell
                    script: make
                """.trimIndent(),
                current = 2,
            )

        assertEquals("", parsedFirst.nodes.single().command, "nothing renamed script, and a stray key is ignored rather than kept")
    }

    @Test
    fun `a version that isn't a number is left for the parser to complain about`() {
        val failure = runCatching { migrate("version: banana\nname: nonsense\n") }.exceptionOrNull()

        assertNotNull(failure, "\"banana\" doesn't name a version, and inventing one would hide the typo")
    }

    @Test
    fun `a workflow that has been carried forward is saved without a version, because it is current again`() {
        val store = newStore()
        store.fileFor("old").writeText(
            """
            version: 0
            name: old
            nodes:
              - id: build
                type: shell
                command: make
            """.trimIndent(),
        )

        val loaded = store.load("old")!!
        assertNull(loaded.version, "it has been read as current, so it no longer says where it started")

        store.save(loaded)
        assertFalse(store.fileFor("old").readText().contains("version:"), "the editor writes current files, and current is unversioned")
    }

    @Test
    fun `a workflow from a newer zopf keeps its version and says so rather than running`() {
        val store = newStore()
        store.fileFor("future").writeText(
            """
            version: ${WORKFLOW_VERSION + 1}
            name: future
            nodes:
              - id: think
                type: agent
                prompt: hello
            """.trimIndent(),
        )

        val loaded = store.load("future")!!
        assertEquals(WORKFLOW_VERSION + 1, loaded.version)
        assertTrue(loaded.isFromTheFuture)
        assertEquals(NodeType.AGENT, loaded.nodes.single().type, "what this build does understand still loads")

        val issue = loaded.validate().first { it.severity == WorkflowIssue.Severity.ERROR }
        assertContains(issue.message, "needs workflow format v${WORKFLOW_VERSION + 1}")
        assertContains(issue.message, "update zopf")
    }

    @Test
    fun `a workflow written by this build is not from the future`() {
        assertFalse(Workflow(name = "now").isFromTheFuture)
        assertFalse(Workflow(name = "now", version = WORKFLOW_VERSION).isFromTheFuture)
    }
}
