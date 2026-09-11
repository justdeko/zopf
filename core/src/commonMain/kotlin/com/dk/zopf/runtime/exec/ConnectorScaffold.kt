package com.dk.zopf.runtime.exec

import com.dk.zopf.store.workflow.Prompts
import com.dk.zopf.store.workspace.CONNECTOR_MANIFEST

object ConnectorScaffold {
    fun createPrompt(name: String): String = Prompts.render("connector-create", "name" to name, "contract" to contract)

    fun fixPrompt(
        name: String,
        problem: String,
    ): String = Prompts.render("connector-fix", "name" to name, "problem" to problem.trim(), "contract" to contract)

    private val contract: String get() = Prompts.render("connector-contract", "manifest" to CONNECTOR_MANIFEST)
}
