package com.dk.zopf.store

import com.dk.zopf.model.Workflow

object Templates {
    val names: List<String> = listOf("fix-failing-tests", "review-and-fix", "release-notes")

    fun label(name: String): String = name.replace('-', ' ').replaceFirstChar { it.uppercase() }

    fun read(name: String): String {
        val path = "/templates/$name.yaml"
        val stream =
            checkNotNull(Templates::class.java.getResourceAsStream(path)) {
                "$path isn't packaged — resources/templates is missing a file the code asks for"
            }

        return stream.bufferedReader().use { it.readText() }
    }

    fun workflow(
        name: String,
        slug: String,
    ): Workflow = decodeWorkflow(read(name)).copy(name = slug)
}
