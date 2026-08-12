package com.dk.zopf.store

object BuildInfo {
    val version: String by lazy { resource("/zopf-version.txt") ?: "unknown" }

    val workflowVersion: Int by lazy { resource("/zopf-workflow-version.txt")?.toIntOrNull() ?: 1 }

    private fun resource(path: String): String? =
        BuildInfo::class.java
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText().trim() }
            ?.takeIf { it.isNotBlank() }
}
