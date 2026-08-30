package com.dk.zopf.store

import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesTest {
    @Test
    fun `every template the new workflow dialog offers is packaged`() {
        assertTrue(Templates.names.isNotEmpty(), "no templates declared")
        Templates.names.forEach { assertTrue(Templates.read(it).isNotBlank(), "$it read back blank") }
    }

    @Test
    fun `every template is written the way the editor would write it`() {
        Templates.names.forEach { name ->
            val yaml = Templates.read(name)
            assertEquals(encodeWorkflow(decodeWorkflow(yaml)), yaml, name)
        }
    }

    @Test
    fun `every template validates in a workspace that has no repos and no connectors`() {
        Templates.names.forEach { name ->
            val errors =
                Templates
                    .workflow(name, name)
                    .validate(connector = { null })
                    .filter { it.severity == WorkflowIssue.Severity.ERROR }
            assertTrue(errors.isEmpty(), "$name: ${errors.joinToString { it.message }}")
        }
    }

    @Test
    fun `a template's label is what the grid shows above its graph`() {
        assertEquals("Fix failing tests", Templates.label("fix-failing-tests"))
    }

    @Test
    fun `a template becomes a workflow under the name the dialog was given`() {
        assertEquals("my-checks", Templates.workflow("fix-failing-tests", "my-checks").name)
    }
}
