package com.dk.zopf.store

import com.dk.zopf.model.Workflow
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillExamplesTest {
    private val skills: Path = Paths.get("../plugins/zopf/skills").toAbsolutePath().normalize()

    @Test
    fun everyWorkflowInTheSkillDocsIsWrittenTheWayTheEditorWouldWriteIt() {
        val examples = workflowExamples()
        assertTrue(examples.isNotEmpty(), "no yaml workflow examples under $skills")

        examples.forEach { (where, yaml) ->
            val workflow = zopfYaml.decodeFromString(Workflow.serializer(), yaml)
            assertEquals(encodeYaml(Workflow.serializer(), workflow), yaml, where)
        }
    }

    private fun workflowExamples(): List<Pair<String, String>> =
        skills
            .walk()
            .filter { it.isRegularFile() && it.extension == "md" }
            .sorted()
            .flatMap { file ->
                fencedYaml(file.readText())
                    .filter { it.startsWith("name:") && "\nnodes:" in it }
                    .mapIndexed { index, yaml -> "${skills.relativize(file)} block ${index + 1}" to yaml }
            }.toList()

    private fun fencedYaml(markdown: String): List<String> =
        Regex("```yaml\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(markdown)
            .map { it.groupValues[1] }
            .toList()
}
