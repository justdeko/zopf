package com.dk.zopf.store

object Prompts {
    private val PLACEHOLDER = Regex("""\{\{([a-zA-Z]+)}}""")

    fun render(
        name: String,
        vararg values: Pair<String, String>,
    ): String =
        PLACEHOLDER.replace(read(name)) { match ->
            values.firstOrNull { it.first == match.groupValues[1] }?.second ?: match.value
        }

    fun read(name: String): String {
        val path = "/prompts/$name.md"
        val stream =
            checkNotNull(Prompts::class.java.getResourceAsStream(path)) {
                "$path isn't packaged — resources/prompts is missing a file the code asks for"
            }

        return stream.bufferedReader().use { it.readText() }.trimEnd()
    }
}
