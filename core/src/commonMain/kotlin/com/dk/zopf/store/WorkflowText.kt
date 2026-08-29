package com.dk.zopf.store

import com.dk.zopf.model.Workflow

fun encodeWorkflow(workflow: Workflow): String = encodeYaml(Workflow.serializer(), workflow)

fun decodeWorkflow(text: String): Workflow {
    val root = migrateWorkflow(zopfYaml.parseToYamlNode(text))
    val parsed = zopfYaml.decodeFromYamlNode(Workflow.serializer(), root)
    return if (parsed.isFromTheFuture) parsed else parsed.copy(version = null)
}
