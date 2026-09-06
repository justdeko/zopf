package com.dk.zopf.cli

import com.dk.zopf.store.WORKSPACE_FILE
import com.dk.zopf.store.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkspaceDiscoveryTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-discovery").also { dirs.add(it) }

    @AfterTest
    fun cleanup() = dirs.forEach { it.toFile().deleteRecursively() }

    @Test
    fun `discovery finds the nearest workspace above`() {
        val root = tempDir()
        val created = Workspace.create(root)
        val deep = root.resolve("src/main/kotlin").also { it.createDirectories() }

        assertEquals(created.root, locateWorkspace(requested = null, from = deep).root)
    }

    @Test
    fun `discovery finds a dot-zopf inside a repo`() {
        val repo = tempDir()
        val workspace = repo.resolve(".zopf").also { it.createDirectories() }
        workspace.resolve(WORKSPACE_FILE).writeText("name: shipped\n")

        assertEquals(workspace, locateWorkspace(requested = null, from = repo.resolve("app")).root)
    }

    @Test
    fun `discovery prefers the nearest workspace`() {
        val outer = tempDir()
        Workspace.create(outer)
        val inner = outer.resolve("packages/api").also { it.createDirectories() }
        val nearest = Workspace.create(inner)

        assertEquals(nearest.root, locateWorkspace(requested = null, from = inner.resolve("src")).root)
    }

    @Test
    fun `--workspace overrides discovery`() {
        val here = tempDir()
        Workspace.create(here)
        val elsewhere = tempDir()
        val named = Workspace.create(elsewhere)

        assertEquals(named.root, locateWorkspace(requested = elsewhere.toString(), from = here).root)
    }

    @Test
    fun `--workspace on a non-workspace is refused`() {
        val plain = tempDir()

        val failure = assertFailsWith<UsageError> { locateWorkspace(requested = plain.toString(), from = plain) }

        assertContains(failure.message!!, ".zopf")
    }

    @Test
    fun `discovery finds nothing outside a workspace`() {
        assertNull(enclosingWorkspace(tempDir().resolve("a/b").also { it.createDirectories() }))
    }
}
