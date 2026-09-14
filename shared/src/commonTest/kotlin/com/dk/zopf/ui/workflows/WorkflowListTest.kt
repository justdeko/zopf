package com.dk.zopf.ui.workflows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.store.workflow.Templates
import com.dk.zopf.store.workflow.WorkflowListing
import com.dk.zopf.store.workspace.OpenWorkspace
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.store.workspace.WorkspaceConfig
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
        onDescribe: (String, String) -> Unit = { _, _ -> },
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
                        onDescribe = onDescribe,
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
    fun `an empty workspace picks a template and takes its name`() {
        val created = mutableListOf<Pair<String, String?>>()
        runList(showing = WorkflowListing(emptyList(), emptyList()), onCreate = { name, t -> created += name to t }) {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            val wanted = Templates.names.last()
            onNodeWithText(Templates.label(wanted), useUnmergedTree = true).performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Create", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(listOf<Pair<String, String?>>(wanted to wanted), created)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `every template shows its graph and first line`() {
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
    fun `a typed name survives picking another template`() {
        val created = mutableListOf<Pair<String, String?>>()
        runList(showing = WorkflowListing(emptyList(), emptyList()), onCreate = { name, t -> created += name to t }) {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("Empty", useUnmergedTree = true).performScrollTo().performClick()
            waitForIdle()
            onNode(hasSetTextAction()).performTextReplacement("mine")
            onNodeWithText(Templates.label(Templates.names.first()), useUnmergedTree = true).performScrollTo().performClick()
            waitForIdle()
            onNodeWithText("Create", useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(listOf<Pair<String, String?>>("mine" to Templates.names.first()), created)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a workspace with workflows is offered no templates`() {
        runList {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("Empty", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("Describe it", useUnmergedTree = true).assertExists()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the description box is only there once it is asked for`() {
        runList {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("What should it do?", useUnmergedTree = true).assertDoesNotExist()

            onNodeWithText("Describe it", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("What should it do?", useUnmergedTree = true).assertExists()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `describing hands over the name and what was asked for`() {
        val described = mutableListOf<Pair<String, String>>()
        runList(onDescribe = { name, description -> described += name to description }) {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("Describe it", useUnmergedTree = true).performClick()
            waitForIdle()
            onAllNodes(hasSetTextAction())[0].performTextReplacement("lint-fix")
            onAllNodes(hasSetTextAction())[1].performTextReplacement("lint the repo then fix it")
            waitForIdle()
            onNodeWithText("Draft it", useUnmergedTree = true).performClick()
            waitForIdle()
        }

        assertEquals(listOf("lint-fix" to "lint the repo then fix it"), described)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `describing nothing can't be confirmed`() {
        runList {
            onNodeWithText("New workflow", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Describe it", useUnmergedTree = true).performClick()
            waitForIdle()

            onNodeWithText("Draft it").assertIsNotEnabled()
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
    fun `a gesture over the strip leaves the graph in place`() =
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

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.carded(
        workflow: Workflow,
        onClick: () -> Unit = {},
    ): Pair<SemanticsNodeInteraction, KuiverViewerState> {
        lateinit var viewerState: KuiverViewerState
        setContent {
            ZopfTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Card(onClick = onClick) {
                        viewerState = rememberMinimapViewerState(workflow)
                        WorkflowThumbnail(
                            workflow = workflow,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxSize(),
                            viewerState = viewerState,
                        )
                    }
                }
            }
        }
        waitUntil { viewerState.hasFittedInitially }
        waitForIdle()
        return onNodeWithContentDescription("${workflow.nodes.size} nodes") to viewerState
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a gesture over a thumbnail in a card leaves the graph where it was`() =
        runDesktopComposeUiTest(200, 200) {
            val (thumbnail, viewer) = carded(chain(listOf(NodeType.AGENT, NodeType.SHELL, NodeType.GATE)))
            val offset = viewer.offset
            val scale = viewer.scale

            thumbnail.performMouseInput {
                moveTo(center)
                press()
                moveBy(Offset(40f, 30f))
                release()
                scroll(4f)
            }
            waitForIdle()

            assertEquals(offset, viewer.offset, "the preview panned")
            assertEquals(scale, viewer.scale, "the preview zoomed")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a click on a thumbnail still reaches the card`() =
        runDesktopComposeUiTest(200, 200) {
            var clicks = 0
            val (thumbnail, _) = carded(chain(listOf(NodeType.AGENT, NodeType.SHELL)), onClick = { clicks += 1 })

            thumbnail.performClick()
            waitForIdle()

            assertEquals(1, clicks)
        }

    private fun Color.isNear(other: Color): Boolean = abs(red - other.red) < 0.02f && abs(green - other.green) < 0.02f && abs(blue - other.blue) < 0.02f
}
