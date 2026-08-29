package com.dk.zopf.ui.editor

import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.exampleWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSourceTest {
    private fun editing(workflow: Workflow) = EditorState(initial = workflow, workspace = null, onSave = {})

    @Test
    fun `reading the source and writing it straight back changes nothing`() {
        val state = editing(exampleWorkflow)
        val before = state.sourceText

        state.applySource(before).getOrThrow()

        assertEquals(before, state.sourceText)
        assertEquals(exampleWorkflow, state.workflow)
    }

    @Test
    fun `the source carries the positions the canvas is holding, so what you read is what saving writes`() {
        val state = editing(exampleWorkflow)
        val placed = mapOf(exampleWorkflow.nodes.first().id to Position(40f, 90f))
        state.reportCanvasPositions(placed)

        assertTrue(state.sourceText.contains("x: 40"), "the source left out a position the canvas was holding")

        state.applySource(state.sourceText).getOrThrow()

        assertEquals(
            Position(40f, 90f),
            state.workflow.nodes
                .first()
                .position,
        )
    }

    @Test
    fun `an id that vanishes from the source takes the selection with it`() {
        val state = editing(Workflow(name = "w", nodes = listOf(WorkflowNode("gone", NodeType.GATE))))
        state.select("gone")

        state.applySource("name: w\nnodes:\n  - id: other\n    type: gate\n").getOrThrow()

        assertNull(state.selectedNodeId, "the inspector was left pointing at a node that no longer exists")
    }

    @Test
    fun `text that is not a workflow is refused without touching what is being edited`() {
        val state = editing(exampleWorkflow)

        val failed = state.applySource("nodes: [ this isn't yaml")

        assertTrue(failed.isFailure)
        assertEquals(exampleWorkflow, state.workflow)
    }
}
