package com.dk.zopf.runtime.exec

import com.dk.zopf.util.Strings
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

data class Worktree(
    val path: Path,
    val branch: String,
)

private const val GIT_TIMEOUT_MINUTES = 10L

private const val SHORT_RUN_ID = 8

object Worktrees {
    fun planned(
        root: Path,
        runId: String,
        nodeId: String,
    ): Worktree {
        val name = safe(nodeId)
        return Worktree(root.resolve(runId).resolve(name), branchFor(runId, name))
    }

    fun create(
        repo: Path,
        worktree: Worktree,
    ): Result<Unit> =
        runCatching {
            worktree.path.parent.createDirectories()
            val added = git(repo, "worktree", "add", "-b", worktree.branch, worktree.path.toString())
            check(added.ok) { Strings.RunErrors.worktreeFailed(repo, added.output) }
        }

    fun hasUncommittedChanges(repo: Path): Boolean = git(repo, "status", "--porcelain").output.isNotBlank()

    fun retire(
        runId: String,
        root: Path,
    ): List<Path> {
        val dir = root.resolve(runId)
        if (!dir.isDirectory()) return emptyList()
        val kept = dir.listDirectoryEntries().filterNot { retire(it, branchFor(runId, it.name), runId) }
        if (kept.isEmpty()) runCatching { dir.deleteIfExists() }
        return kept
    }

    fun dropUnchanged(worktree: Worktree): Boolean {
        val gitDir = gitDirOf(worktree.path) ?: return false
        if (hasUncommittedChanges(worktree.path)) return false
        if (!git(null, gitDir, "merge-base", "--is-ancestor", worktree.branch, "HEAD").ok) return false
        if (!remove(worktree.path, worktree.branch, gitDir)) return false
        runCatching { worktree.path.parent.deleteIfExists() }
        return true
    }

    private fun retire(
        path: Path,
        branch: String,
        runId: String,
    ): Boolean {
        if (!path.resolve(".git").exists()) {
            return runCatching { path.deleteIfExists() }.getOrDefault(false)
        }
        val gitDir = gitDirOf(path) ?: return false
        if (hasUncommittedChanges(path) && !commitLeftovers(path, Strings.Transcript.leftoverCommit(path.name, runId.take(SHORT_RUN_ID)))) return false
        return remove(path, branch, gitDir)
    }

    private fun commitLeftovers(
        path: Path,
        message: String,
    ): Boolean {
        if (!git(path, "add", "-A").ok) return false
        val commit = arrayOf("-c", "commit.gpgsign=false", "commit", "--no-verify", "-q", "-m", message)
        return git(path, *commit).ok || git(path, "-c", "user.name=zopf", "-c", "user.email=zopf@localhost", *commit).ok
    }

    private fun gitDirOf(path: Path): String? =
        git(path, "rev-parse", "--path-format=absolute", "--git-common-dir")
            .takeIf { it.ok }
            ?.let { "--git-dir=${it.output.trim()}" }

    private fun remove(
        path: Path,
        branch: String,
        gitDir: String,
    ): Boolean {
        if (!git(null, gitDir, "worktree", "remove", path.toString()).ok) return false
        git(null, gitDir, "branch", "-d", branch)
        return true
    }

    private fun branchFor(
        runId: String,
        name: String,
    ): String = "zopf/$name-${runId.take(SHORT_RUN_ID)}"

    private fun safe(nodeId: String): String =
        nodeId
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "node" }

    private class GitResult(
        val ok: Boolean,
        val output: String,
    )

    private fun git(
        dir: Path?,
        vararg args: String,
    ): GitResult =
        runCatching {
            val executable = CommandLookup.which("git")?.toString() ?: "git"
            val process =
                ProcessBuilder(listOf(executable) + args)
                    .apply { dir?.let { directory(it.toFile()) } }
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectErrorStream(true)
                    .apply {
                        environment().putAll(CommandLookup.childEnvironment())
                        environment()["GIT_TERMINAL_PROMPT"] = "0"
                    }.start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) process.destroyForcibly()
            GitResult(process.exitValue() == 0, output.trim())
        }.getOrElse { GitResult(false, it.message.orEmpty()) }
}
