package com.dk.zopf.ui.workflows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.OpenWorkspace
import com.dk.zopf.store.Templates
import com.dk.zopf.store.WorkflowListing
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.WorkspaceConfig
import com.dk.zopf.ui.theme.ZopfColors
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowListInteractionTest {
    private fun workflow(index: Int) =
        Workflow(
            name = "wf-%02d".format(index),
            nodes =
                listOf(
                    WorkflowNode("a", NodeType.AGENT),
                    WorkflowNode("b", NodeType.SHELL),
                ),
            edges = listOf(WorkflowEdge("a", "b")),
        )

    private val listing = WorkflowListing((0 until 12).map(::workflow), broken = emptyList())

    @OptIn(ExperimentalTestApi::class)
    private fun runList(
        onOpen: (Workflow) -> Unit = {},
        showing: WorkflowListing = listing,
        onCreate: (String, String?) -> Unit = { _, _ -> },
        body: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(900, 760) {
        val root = Files.createTempDirectory("ws")
        val ws = OpenWorkspace(root, Workspace(root, WorkspaceConfig(name = "demo")), 0L)
        setContent {
            ZopfTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WorkflowListScreen(
                        workspace = ws,
                        listing = showing,
                        selected = null,
                        onOpen = onOpen,
                        onRun = {},
                        onCreate = onCreate,
                        onRename = { _, _ -> },
                        onDelete = {},
                        onReveal = {},
                    )
                }
            }
        }
        waitForIdle()
        body()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a scroll over a minimap scrolls the list`() =
        runList {
            val before = onNodeWithText("wf-00").fetchSemanticsNode().boundsInRoot.top

            onAllNodesWithContentDescription("2 nodes")[0].performMouseInput { scroll(3f) }
            waitForIdle()

            val after = onNodeWithText("wf-00").fetchSemanticsNode().boundsInRoot.top
            assertTrue(after < before, "the list didn't scroll: $before -> $after")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking a minimap opens its workflow`() {
        val opened = mutableListOf<String>()
        runList(onOpen = { opened += it.name }) {
            onAllNodesWithContentDescription("2 nodes")[1].performClick()
            waitForIdle()
        }

        assertEquals(listOf("wf-01"), opened)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an empty workspace picks a template out of the grid and is named after it`() {
        val created = mutableListOf<Pair<String, String?>>()
        runList(showing = WorkflowListing(emptyList(), emptyList()), onCreate = { name, t -> created += name to t }) {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            val wanted = Templates.names.last()
            onNodeWithText(Templates.label(wanted), useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Create", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(listOf<Pair<String, String?>>(wanted to wanted), created)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `every template in the grid shows its graph and its first line`() {
        runList(showing = WorkflowListing(emptyList(), emptyList())) {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            Templates.names.forEach { name ->
                val template = Templates.workflow(name, name)
                onNodeWithText(Templates.label(name), useUnmergedTree = true).assertExists()
                onNodeWithText(template.description.substringBefore('\n'), useUnmergedTree = true).assertExists()
                onAllNodesWithContentDescription("${template.nodes.size} nodes")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
                    .let { assertTrue(it, "no thumbnail was drawn for $name") }
            }
            onNodeWithText("Empty", useUnmergedTree = true).assertExists()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a name typed by hand survives picking another template`() {
        val created = mutableListOf<Pair<String, String?>>()
        runList(showing = WorkflowListing(emptyList(), emptyList()), onCreate = { name, t -> created += name to t }) {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            onNode(hasSetTextAction()).performTextReplacement("mine")
            onNodeWithText("Empty", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Create", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(listOf<Pair<String, String?>>("mine" to null), created)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a workspace that already has workflows is only asked for a name`() {
        runList {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("Empty", useUnmergedTree = true).assertDoesNotExist()
        }
    }
}

class WorkflowMinimapTest {
    private fun chain(types: List<NodeType>): Workflow {
        val ids = List(types.size) { ('a' + it).toString() }
        return Workflow(
            name = "w",
            nodes = types.mapIndexed { i, type -> WorkflowNode(ids[i], type) },
            edges = ids.zipWithNext { from, to -> WorkflowEdge(from, to) },
        )
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.minimap(
        workflow: Workflow,
        boxed: Boolean = false,
    ): Pair<SemanticsNodeInteraction, ZopfColors> {
        lateinit var palette: ZopfColors
        lateinit var viewerState: KuiverViewerState
        setContent {
            ZopfTheme {
                palette = ZopfTheme.colors
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(if (boxed) Modifier.height(92.dp) else Modifier) {
                        viewerState = rememberMinimapViewerState(workflow)
                        WorkflowMinimap(workflow, onClick = {}, viewerState = viewerState)
                    }
                }
            }
        }
        waitUntil { viewerState.hasFittedInitially }
        waitForIdle()
        return onNodeWithContentDescription("${workflow.nodes.size} nodes") to palette
    }

    private fun SemanticsNodeInteraction.pixels(): List<Color> {
        val map = captureToImage().toPixelMap()
        return buildList {
            for (y in 0 until map.height) for (x in 0 until map.width) add(map[x, y])
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `every node type reaches the canvas in its own colour`() =
        runDesktopComposeUiTest(200, 200) {
            val (strip, palette) = minimap(chain(NodeType.entries))

            val drawn = strip.pixels()
            NodeType.entries.forEach { type ->
                val wanted = palette.node(type).accent
                assertTrue(drawn.any { it.isNear(wanted) }, "no ${type.name} dot was drawn")
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `gestures over the strip leave the graph where it was`() =
        runDesktopComposeUiTest(200, 200) {
            val (strip, _) = minimap(chain(listOf(NodeType.AGENT, NodeType.SHELL, NodeType.GATE)), boxed = true)
            val before = strip.pixels()

            strip.performMouseInput { scroll(4f) }
            waitForIdle()
            strip.performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(40f, 30f))
                release()
            }
            waitForIdle()

            val moved = before.indices.count { before[it] != strip.pixels()[it] }
            assertEquals(0, moved, "the thumbnail was dragged out of place")
        }

    private fun Color.isNear(other: Color): Boolean = abs(red - other.red) < 0.02f && abs(green - other.green) < 0.02f && abs(blue - other.blue) < 0.02f
}
