package com.dk.zopf.runtime

object Browser {
    fun open(url: String): Result<Unit> =
        runCatching {
            check(url.startsWith("https://")) { "$url isn't an https link" }
            val process =
                ProcessBuilder(command(url))
                    .apply { environment().putAll(CommandLookup.childEnvironment()) }
                    .start()
            val exit = process.waitFor()
            check(exit == 0) {
                "Couldn't open $url: " +
                    process.errorStream
                        .bufferedReader()
                        .readText()
                        .trim()
            }
        }

    internal fun command(url: String): List<String> = listOf("open", url)
}
