package com.dk.zopf.runtime

import java.nio.file.Path
import kotlin.io.path.exists

object Finder {
    fun reveal(path: Path): Result<Unit> =
        runCatching {
            check(path.exists()) { "$path isn't there any more" }
            val process =
                ProcessBuilder(command(path))
                    .apply { environment().putAll(CommandLookup.childEnvironment()) }
                    .start()
            val exit = process.waitFor()
            check(exit == 0) {
                "Couldn't show $path in Finder: " +
                    process.errorStream
                        .bufferedReader()
                        .readText()
                        .trim()
            }
        }

    internal fun command(path: Path): List<String> = listOf("open", "-R", path.toString())
}
