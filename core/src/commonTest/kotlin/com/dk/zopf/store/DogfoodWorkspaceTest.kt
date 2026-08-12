package com.dk.zopf.store

import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeRefs
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.canFail
import com.dk.zopf.model.validate
import com.dk.zopf.runtime.Branches
import com.dk.zopf.runtime.NodeOutput
import com.dk.zopf.runtime.RunContext
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DogfoodWorkspaceTest {
    private val workspace =
        requireNotNull(Workspace.open(Paths.get("..").toAbsolutePath().normalize())) {
            "No .zopf workspace at the repo root"
        }

    private fun connectorStore() = ConnectorStore(workspace, sharedRoot = workspace.root.resolve("no-shared-root"))

    private fun answerAs(
        field: String,
        answer: String,
    ) = if (field == "result") NodeOutput(result = answer) else NodeOutput(extras = mapOf(field to answer))

    @Test
    fun everyWorkflowParses() {
        val listing = WorkflowStore(workspace).list()
        assertEquals(emptyList(), listing.broken.map { "${it.file.fileName}: ${it.message}" })
        assertTrue(listing.workflows.isNotEmpty(), "no workflows in ${workspace.root}")
    }

    @Test
    fun everyWorkflowValidatesClean() {
        val connectors = connectorStore().list()
        assertEquals(emptyList(), connectors.broken.map { "${it.name}: ${it.message}" })

        val problems =
            WorkflowStore(workspace).list().workflows.flatMap { workflow ->
                workflow
                    .validate(
                        repoExists = { workspace.resolvePath(it.path).toFile().exists() },
                        fileExists = { workspace.resolvePath(it).isRegularFile() },
                        connector = { name -> connectors.find(name)?.manifest },
                        promptText = { raw ->
                            workspace.resolvePath(raw).takeIf { it.isRegularFile() }?.readText()
                        },
                    ).map { "${workflow.name}: [${it.severity}] ${it.message}" }
            }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun everyWorkflowSurvivesBeingSaved() {
        WorkflowStore(workspace).list().workflows.forEach { workflow ->
            val yaml = encodeYaml(Workflow.serializer(), workflow)
            assertEquals(workflow, zopfYaml.decodeFromString(Workflow.serializer(), yaml), workflow.name)
        }
    }

    @Test
    fun everyWorkflowIsAlreadyWrittenTheWayTheEditorWouldWriteIt() {
        val store = WorkflowStore(workspace)
        store.list().workflows.forEach { workflow ->
            assertEquals(
                encodeYaml(Workflow.serializer(), workflow),
                store.fileFor(workflow.name).readText(),
                workflow.name,
            )
        }
    }

    @Test
    fun everyBranchCanGoBothWays() {
        val cases =
            mapOf(
                "smoke" to "2.9.0 (Claude Code)",
                "release-cut" to "no",
            )

        val workflows = WorkflowStore(workspace).list().workflows.associateBy { it.name }
        val withBranches = workflows.values.filter { w -> w.nodes.any { it.type == NodeType.BRANCH } }
        assertEquals(
            cases.keys,
            withBranches.mapTo(mutableSetOf()) { it.name },
            "a workflow grew or lost a branch without this test being told what it compares",
        )

        cases.forEach { (name, whenFalse) ->
            val workflow = requireNotNull(workflows[name]) { "no $name workflow" }
            val branch = workflow.nodes.single { it.type == NodeType.BRANCH }
            val source = workflow.edges.single { it.to == branch.id }.from
            assertTrue("==" in branch.expression, "$name: this test only knows how to read an == branch")
            val whenTrue = branch.expression.substringAfter("==").trim()
            val field =
                requireNotNull(NodeRefs.PATTERN.find(branch.expression)) {
                    "$name: the branch compares nothing a node produced"
                }.groupValues[2]

            fun verdict(answer: String) =
                Branches
                    .evaluate(
                        RunContext(mapOf(source to answerAs(field, answer))).interpolate(branch.expression).text,
                    ).taken

            assertTrue(verdict(whenTrue), "$name: \"$whenTrue\" should take the true edge")
            assertFalse(verdict(whenFalse), "$name: \"$whenFalse\" should take the false edge")

            assertEquals(
                setOf(true, false),
                workflow.edges
                    .filter { it.from == branch.id }
                    .mapNotNull { it.condition }
                    .toSet(),
                "$name: the branch is missing an arm",
            )
        }
    }

    @Test
    fun theRecoveryEdgesHangOffNodesThatCanFail() {
        val workflows = WorkflowStore(workspace).list().workflows
        val recovery = workflows.flatMap { w -> w.edges.filter { it.on == EdgeTrigger.FAILURE }.map { w to it } }
        assertTrue(recovery.isNotEmpty(), "nothing here demonstrates on: failure any more")

        recovery.forEach { (workflow, edge) ->
            val source = requireNotNull(workflow.node(edge.from)) { "${workflow.name}: no node ${edge.from}" }
            assertTrue(
                source.type.canFail(),
                "${workflow.name}: ${edge.from} is a ${source.type} and never fails",
            )
        }
    }

    @Test
    fun everyConnectorScriptIsRunnable() {
        val connectors = connectorStore().list().connectors
        assertTrue(connectors.isNotEmpty(), "no connectors in ${workspace.root}")
        connectors.forEach {
            assertTrue(it.hasScript, "${it.name} has no ${it.manifest.run}")
            assertTrue(it.script.toFile().canExecute(), "${it.name}'s ${it.manifest.run} is not +x")
        }
    }
}
