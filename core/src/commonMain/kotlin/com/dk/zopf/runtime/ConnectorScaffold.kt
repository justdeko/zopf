package com.dk.zopf.runtime

import com.dk.zopf.store.CONNECTOR_MANIFEST
import com.dk.zopf.store.Prompts

object ConnectorScaffold {
    fun createPrompt(name: String): String = Prompts.render("connector-create", "name" to name, "contract" to contract)

    fun fixPrompt(
        name: String,
        problem: String,
    ): String = Prompts.render("connector-fix", "name" to name, "problem" to problem.trim(), "contract" to contract)

    private val contract: String get() = Prompts.render("connector-contract", "manifest" to CONNECTOR_MANIFEST)
}
