package com.dk.zopf.runtime.exec

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorktreesTest {
    private val dirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-worktrees").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private val runId = "4f6a21c8-0000"
    private val repo = gitRepo(tempDir())
    private val root = tempDir()

    private fun checkedOut(nodeId: String = "fix"): Worktree = Worktrees.planned(root, runId, nodeId).also { Worktrees.create(repo, it).getOrThrow() }

    private fun branches(): List<String> = gitIn(repo, "branch", "--format=%(refname:short)").lines()

    @Test
    fun `a worktree is a checkout on its own branch`() {
        val worktree = checkedOut()

        assertEquals(root.resolve(runId).resolve("fix"), worktree.path)
        assertEquals("zopf/fix-4f6a21c8", worktree.branch)
        assertTrue(worktree.path.resolve("README.md").exists())
        assertEquals(worktree.branch, gitIn(worktree.path, "rev-parse", "--abbrev-ref", "HEAD"))
    }

    @Test
    fun `a node id is made safe for a path and a branch`() {
        val worktree = Worktrees.planned(root, runId, "fix it.now")

        assertEquals("fix-it-now", worktree.path.fileName.toString())
        assertEquals("zopf/fix-it-now-4f6a21c8", worktree.branch)
    }

    @Test
    fun `a second worktree for one node fails`() {
        checkedOut()

        val again = Worktrees.create(repo, Worktrees.planned(root, runId, "fix"))

        assertTrue(again.isFailure)
        assertTrue("git couldn't add a worktree" in again.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun `uncommitted changes are noticed`() {
        assertFalse(Worktrees.hasUncommittedChanges(repo))

        repo.resolve("README.md").writeText("edited\n")

        assertTrue(Worktrees.hasUncommittedChanges(repo))
    }

    @Test
    fun `retiring removes a clean worktree and its merged branch`() {
        val worktree = checkedOut()

        assertEquals(emptyList(), Worktrees.retire(runId, root))

        assertFalse(worktree.path.exists())
        assertFalse(root.resolve(runId).exists())
        assertEquals(listOf("main"), branches())
    }

    @Test
    fun `retiring saves uncommitted work to the branch`() {
        val worktree = checkedOut()
        worktree.path.resolve("notes.txt").writeText("work in progress\n")

        assertEquals(emptyList(), Worktrees.retire(runId, root))

        assertFalse(worktree.path.exists())
        assertEquals("work in progress", gitIn(repo, "show", "${worktree.branch}:notes.txt"))
        assertEquals("zopf: uncommitted work left by fix in run 4f6a21c8", gitIn(repo, "log", "-1", "--format=%s", worktree.branch))
    }

    @Test
    fun `retiring keeps a branch with unmerged commits`() {
        val worktree = checkedOut()
        worktree.path.resolve("notes.txt").writeText("done\n")
        gitIn(worktree.path, "add", "-A")
        gitIn(worktree.path, "commit", "-q", "-m", "work")

        assertEquals(emptyList(), Worktrees.retire(runId, root))

        assertFalse(worktree.path.exists())
        assertTrue(worktree.branch in branches())
    }

    @Test
    fun `retiring leaves other runs alone`() {
        val worktree = checkedOut()

        assertEquals(emptyList(), Worktrees.retire("another-run", root))

        assertTrue(worktree.path.exists())
    }

    @Test
    fun `only a worktree with nothing new is dropped early`() {
        val untouched = checkedOut("untouched")
        val edited = checkedOut("edited").also { it.path.resolve("notes.txt").writeText("draft\n") }
        val committed = checkedOut("committed")
        committed.path.resolve("notes.txt").writeText("done\n")
        gitIn(committed.path, "add", "-A")
        gitIn(committed.path, "commit", "-q", "-m", "work")
        val merged = checkedOut("merged")
        merged.path.resolve("more.txt").writeText("done\n")
        gitIn(merged.path, "add", "-A")
        gitIn(merged.path, "commit", "-q", "-m", "more")
        gitIn(repo, "merge", "-q", merged.branch)

        listOf(untouched to true, edited to false, committed to false, merged to true).forEach { (worktree, dropped) ->
            assertEquals(dropped, Worktrees.dropUnchanged(worktree), worktree.branch)
            assertEquals(!dropped, worktree.path.exists(), worktree.branch)
            assertEquals(!dropped, worktree.branch in branches(), worktree.branch)
        }
    }
}
