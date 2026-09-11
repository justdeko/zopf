package com.dk.zopf.store.workflow

import com.dk.zopf.model.Workflow
import com.dk.zopf.store.encodeYaml
import com.dk.zopf.store.zopfYaml

fun encodeWorkflow(workflow: Workflow): String = encodeYaml(Workflow.serializer(), workflow)

fun decodeWorkflow(text: String): Workflow {
    val root = migrateWorkflow(zopfYaml.parseToYamlNode(text))
    val parsed = zopfYaml.decodeFromYamlNode(Workflow.serializer(), root)
    return if (parsed.isFromTheFuture) parsed else parsed.copy(version = null)
}
