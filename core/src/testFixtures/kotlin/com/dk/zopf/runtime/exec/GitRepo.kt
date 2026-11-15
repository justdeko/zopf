package com.dk.zopf.runtime.exec

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

fun gitIn(
    dir: Path,
    vararg args: String,
): String {
    val identity = listOf("-c", "user.name=zopf", "-c", "user.email=zopf@example.invalid", "-c", "commit.gpgsign=false")
    val process =
        ProcessBuilder(listOf("git") + identity + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $output" }
    return output.trim()
}

fun gitRepo(
    dir: Path,
    files: List<String> = listOf("README.md"),
): Path {
    dir.createDirectories()
    gitIn(dir, "init", "-q", "-b", "main")
    files.forEach { dir.resolve(it).also { file -> file.parent.createDirectories() }.writeText("first\n") }
    gitIn(dir, "add", "-A")
    gitIn(dir, "commit", "-q", "-m", "first")
    return dir
}
