package com.dk.zopf.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Position
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.blurb
import com.dk.zopf.model.modelOptions
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.showing
import com.dk.zopf.store.Workspace
import com.dk.zopf.ui.LocalTitleBarInset
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STEPS = 10

class GraphEditorScreenTest {
    private val twoNodes =
        Workflow(
            name = "w",
            nodes =
                listOf(
                    WorkflowNode("alpha", NodeType.AGENT, prompt = "do a thing"),
                    WorkflowNode("beta", NodeType.SHELL, command = "echo hi"),
                ),
        )

    private val oneNode = Workflow(name = "w", nodes = listOf(WorkflowNode("only", NodeType.GATE)))

    private val threeNodes =
        Workflow(
            name = "w",
            nodes =
                listOf(
                    WorkflowNode("alpha", NodeType.AGENT, prompt = "do a thing"),
                    WorkflowNode("beta", NodeType.SHELL, command = "echo hi"),
                    WorkflowNode("gamma", NodeType.SHELL, command = "echo bye"),
                ),
        )

    private val longChain =
        Workflow(
            name = "w",
            nodes = (1..7).map { WorkflowNode("step$it", NodeType.SHELL, command = "echo $it") },
            edges = (1..6).map { WorkflowEdge("step$it", "step${it + 1}") },
        )

    private fun Workflow.handPlaced() = copy(nodes = nodes.mapIndexed { i, node -> node.copy(position = Position(40f + i * 300f, 120f)) })

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.awaitLayout(canvas: EditorCanvas) =
        waitUntil(timeoutMillis = 10_000) {
            canvas.viewer.layoutedKuiver.nodes.values
                .all { it.dimensions != null }
        }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.centerOf(text: String): Offset = onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.dragBetween(
        from: Offset,
        to: Offset,
    ) = onRoot().performMouseInput {
        moveTo(from)
        press()
        repeat(STEPS) { step -> moveTo(lerp(from, to, (step + 1) / STEPS.toFloat())) }
        release()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.editor(
        workflow: Workflow = twoNodes,
        onSave: (Workflow) -> Unit = {},
        onClose: () -> Unit = {},
        onCommands: (EditorCommands?) -> Unit = {},
        runningNodes: List<NodeRun> = emptyList(),
        titleBarInset: Dp? = null,
    ): Pair<EditorState, EditorCanvas> {
        val state =
            EditorState(
                initial = workflow,
                workspace = null,
                onSave = onSave,
                executableExists = { true },
            )
        lateinit var canvas: EditorCanvas
        setContent {
            canvas = rememberEditorCanvas(state.workflow)
            ZopfTheme {
                val screen =
                    @Composable {
                        GraphEditorScreen(
                            state,
                            workspace = null,
                            onClose = onClose,
                            onCommands = onCommands,
                            canvas = canvas,
                            runningNodes = runningNodes,
                        )
                    }
                if (titleBarInset == null) {
                    screen()
                } else {
                    CompositionLocalProvider(LocalTitleBarInset provides titleBarInset) { screen() }
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { canvas.viewer.hasFittedInitially }
        waitForIdle()
        return state to canvas
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.showInspector() {
        onNodeWithContentDescription("Show inspector").performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the editor publishes its commands while it is up, and takes them back on the way out`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var published: EditorCommands? = null
            var open by mutableStateOf(true)
            val state = EditorState(initial = twoNodes, workspace = null, onSave = {})
            lateinit var canvas: EditorCanvas
            setContent {
                canvas = rememberEditorCanvas(state.workflow)
                ZopfTheme {
                    if (open) {
                        GraphEditorScreen(
                            state,
                            workspace = null,
                            onClose = {},
                            onCommands = { published = it },
                            canvas = canvas,
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) { canvas.viewer.hasFittedInitially }
            waitForIdle()

            assertNotNull(published, "nothing was published for the menu bar to drive")

            open = false
            waitForIdle()

            assertNull(published, "the commands outlived the editor that owned them")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the published commands drive the real canvas, and read back what it is showing`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var published: EditorCommands? = null
            editor(onCommands = { published = it })
            val commands = assertNotNull(published)

            assertTrue(!commands.isInspectorVisible(), "the editor opened with the inspector taking room")

            commands.toggleInspector()
            waitForIdle()

            onNodeWithText("Select a node to edit it.").assertIsDisplayed()
            assertTrue(commands.isInspectorVisible(), "the command missed the inspector it had just opened")

            onNodeWithContentDescription("Hide inspector").performClick()
            waitForIdle()
            assertTrue(!commands.isInspectorVisible(), "the command reported an inspector that had gone")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting the first node opens the inspector, and closing it afterwards sticks`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var published: EditorCommands? = null
            val (state, _) = editor(onCommands = { published = it })
            val commands = assertNotNull(published)

            state.select("alpha")
            waitForIdle()
            assertTrue(commands.isInspectorVisible(), "selecting a node left the inspector shut")

            commands.toggleInspector()
            waitForIdle()
            state.select("beta")
            waitForIdle()

            assertTrue(
                !commands.isInspectorVisible(),
                "the inspector reopened itself on a later selection, so it is not the user's toggle",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `isDirty follows the document, so Save in the menu greys out with the button`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var published: EditorCommands? = null
            val (state, _) = editor(onCommands = { published = it })
            val commands = assertNotNull(published)

            assertTrue(!commands.isDirty(), "a freshly opened workflow reported unsaved changes")

            state.addNode(NodeType.GATE)
            waitForIdle()

            assertTrue(commands.isDirty(), "an added node left Save greyed out in the menu")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `right-clicking a node opens its menu, and Duplicate lands a second node in the model`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, _) = editor()

            onNodeWithText("alpha").performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithText("Duplicate").assertIsDisplayed()
            onNodeWithText("Duplicate").performClick()
            waitForIdle()

            assertEquals(3, state.workflow.nodes.size, "Duplicate didn't add a node")

            assertEquals("alpha-2", state.selectedNodeId)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the node menu offers Run only for the types that mean something on their own`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            editor(Workflow(name = "w", nodes = listOf(WorkflowNode("ask", NodeType.GATE))))

            onNodeWithText("ask").performMouseInput { rightClick() }
            waitForIdle()

            onNodeWithText("Delete").assertIsDisplayed()
            onNodeWithText("Run this node").assertDoesNotExist()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape leaves a clean editor without anything having been clicked first`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var closed = 0
            editor(onClose = { closed++ })

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertEquals(1, closed, "Escape on a freshly opened editor should leave it")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape cancels connect mode instead of leaving, and only the first press`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var closed = 0
            val (state, _) = editor(onClose = { closed++ })

            state.startConnecting("alpha")
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertEquals(null, state.connectFrom, "the first Escape belongs to connect mode")
            assertEquals(0, closed, "and it must not also walk out of the editor")

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            assertEquals(1, closed, "the next one leaves")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape on a dirty editor asks instead of leaving`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var closed = 0
            val (state, _) = editor(onClose = { closed++ })

            state.addNode(NodeType.SHELL)
            waitForIdle()
            assertTrue(state.isDirty)

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertEquals(0, closed, "a dirty editor must stop to ask, not leave")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a graph smaller than the canvas opens at its own size, and fit never magnifies it`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (_, canvas) = editor(twoNodes)

            assertTrue(canvas.viewer.scale <= 1f, "the graph was magnified to fit: ${canvas.viewer.scale}")
            onNodeWithText("alpha").assertIsDisplayed()

            onNodeWithContentDescription("Fit to window").performClick()
            waitForIdle()
            assertTrue(canvas.viewer.scale <= 1f, "fit magnified the graph to fill the window")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `fitting a single node doesn't magnify it either`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (_, canvas) = editor(oneNode)

            onNodeWithContentDescription("Fit to window").performClick()
            waitForIdle()

            assertTrue(canvas.viewer.scale <= 1f, "fit magnified one node to fill the window")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `zooming in still goes past 100 percent, since the clamp is only on fitting`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (_, canvas) = editor(twoNodes)

            onNodeWithContentDescription("Zoom in").performClick()

            waitUntil(timeoutMillis = 5_000) { canvas.viewer.scale > 1f }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a chain too wide for the window is laid out downwards instead, and fits when it is`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (_, canvas) = editor(longChain)

            waitUntil(timeoutMillis = 10_000) { canvas.direction == LayoutDirection.VERTICAL }
            waitUntil(timeoutMillis = 10_000) { canvas.viewer.scale > 0.6f }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a graph with hand-placed nodes is never reflowed`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (_, canvas) = editor(longChain.handPlaced())

            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertEquals(LayoutDirection.HORIZONTAL, canvas.direction)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dragging a node is an edit, and saving writes that node's position and no others`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var written: Workflow? = null
            val (state, canvas) = editor(twoNodes, onSave = { written = it })
            awaitLayout(canvas)

            onNodeWithText("Save").assertIsNotEnabled()

            fun dragAlphaDown() =
                onNodeWithText("alpha").performMouseInput {
                    moveTo(center)
                    press()
                    repeat(6) { moveBy(Offset(0f, 20f)) }
                    release()
                }

            val alpha = centerOf("alpha")
            dragBetween(alpha, alpha + Offset(60f, 0f))
            waitForIdle()
            assertTrue(!state.isDirty, "a node moved with Move Nodes off")

            onNodeWithContentDescription("Move nodes").performClick()
            waitForIdle()

            dragAlphaDown()
            waitForIdle()

            assertTrue(state.isDirty, "a dragged node left Save greyed out")
            onNodeWithText("Save").assertIsEnabled()
            onNodeWithText("Save").performClick()
            waitForIdle()

            val saved = requireNotNull(written) { "Save wrote nothing" }
            assertTrue(saved.node("alpha")?.position != null, "the dragged node has no position")
            assertNull(saved.node("beta")?.position, "a node nobody touched was given a position")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dragging one node onto another connects the two`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            dragBetween(centerOf("alpha"), centerOf("beta"))
            waitForIdle()

            assertTrue(
                state.workflow.edges.any { it.from == "alpha" && it.to == "beta" },
                "dragging alpha onto beta left them unconnected",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a drag that ends back on its own node connects nothing and offers nothing`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            val alpha = centerOf("alpha")
            dragBetween(alpha, alpha + Offset(0f, 12f))
            waitForIdle()

            assertTrue(state.workflow.edges.isEmpty(), "a nudge inside one node made an edge")
            onNodeWithText(NodeType.SHELL.blurb).assertDoesNotExist()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dropping a drag on blank canvas makes the node it is dropped on`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(oneNode)
            awaitLayout(canvas)

            val only = centerOf("only")
            dragBetween(only, Offset(only.x + 420f, only.y + 260f))
            waitForIdle()

            onNodeWithText(NodeType.SHELL.blurb).performClick()
            waitForIdle()

            val made = state.workflow.nodes.single { it.id != "only" }
            assertEquals(NodeType.SHELL, made.type)
            assertTrue(
                state.workflow.edges.any { it.from == "only" && it.to == made.id },
                "the node made by the drop was not connected to the node it came from",
            )
            assertNotNull(made.position, "the node made by the drop was not placed where it landed")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with Move Nodes on, a drag moves instead of connecting`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            onNodeWithContentDescription("Move nodes").performClick()
            waitForIdle()

            dragBetween(centerOf("alpha"), centerOf("beta"))
            waitForIdle()

            assertTrue(state.workflow.edges.isEmpty(), "a drag in Move Nodes mode made an edge")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `C steps through the nodes a selected node could feed, and Enter connects to one`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(threeNodes)
            awaitLayout(canvas)

            state.select("alpha")
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.C) }
            waitForIdle()
            assertEquals("beta", state.connectCandidate, "C did not offer the first node alpha could feed")

            onRoot().performKeyInput { pressKey(Key.C) }
            waitForIdle()
            assertEquals("gamma", state.connectCandidate, "C did not step on to the next one")

            onRoot().performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            assertTrue(state.workflow.edges.any { it.from == "alpha" && it.to == "gamma" })
            assertNull(state.connectFrom, "connect mode was left armed after Enter")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `C wraps back round rather than running out of nodes`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(threeNodes)
            awaitLayout(canvas)

            state.select("alpha")
            waitForIdle()

            repeat(3) {
                onRoot().performKeyInput { pressKey(Key.C) }
                waitForIdle()
            }

            assertEquals("beta", state.connectCandidate, "stepping past the last node did not wrap")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Escape drops a connection armed from the keyboard`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(threeNodes)
            awaitLayout(canvas)

            state.select("alpha")
            waitForIdle()
            onRoot().performKeyInput { pressKey(Key.C) }
            waitForIdle()

            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertNull(state.connectFrom)
            assertNull(state.connectCandidate)
            assertTrue(state.workflow.edges.isEmpty())
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `C typed into an inspector field is text, not a connect shortcut`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(threeNodes)
            awaitLayout(canvas)

            state.select("alpha")
            waitForIdle()

            val field = onAllNodesWithText("alpha").filterToOne(hasSetTextAction())
            field.performClick()
            waitForIdle()
            field.performKeyInput { pressKey(Key.C) }
            waitForIdle()

            assertNull(state.connectFrom, "typing in a field armed a connection")
            assertNull(state.connectCandidate)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the source pane shows what saving would write, and typing valid YAML redraws the canvas`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            onNodeWithContentDescription("Edit the YAML").performClick()
            waitForIdle()

            val source = onAllNodesWithText("name: w", substring = true).filterToOne(hasSetTextAction())
            source.performTextReplacement(
                """
                name: w
                nodes:
                  - id: alpha
                    type: agent
                    prompt: do a thing
                  - id: beta
                    type: shell
                    command: echo hi
                edges:
                  - from: alpha
                    to: beta
                """.trimIndent(),
            )
            waitUntil(timeoutMillis = 5_000) { state.workflow.edges.isNotEmpty() }

            assertEquals(listOf("alpha" to "beta"), state.workflow.edges.map { it.from to it.to })
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `YAML that does not parse is reported and leaves the workflow alone`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            onNodeWithContentDescription("Edit the YAML").performClick()
            waitForIdle()

            onAllNodesWithText("name: w", substring = true)
                .filterToOne(hasSetTextAction())
                .performTextReplacement("nodes: [ this isn't yaml")

            waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    onNodeWithContentDescription("This YAML doesn't parse").assertIsDisplayed()
                }.isSuccess
            }

            assertEquals(2, state.workflow.nodes.size, "unparseable text emptied the workflow")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a rename in the source pane is refused rather than orphaning the file`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            onNodeWithContentDescription("Edit the YAML").performClick()
            waitForIdle()

            onAllNodesWithText("name: w", substring = true)
                .filterToOne(hasSetTextAction())
                .performTextReplacement("name: something-else\nnodes:\n  - id: alpha\n    type: gate\n")

            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithText("Rename on the Workflows screen", substring = true).assertIsDisplayed() }.isSuccess
            }

            assertEquals("w", state.workflow.name)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the canvas tools go away in the source pane, so nothing can edit behind the text`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (_, canvas) = editor(twoNodes)
            awaitLayout(canvas)

            onNodeWithContentDescription("Show inspector").performClick()
            onNodeWithContentDescription("Edit the YAML").performClick()
            waitForIdle()

            onNodeWithContentDescription("Add a Agent node. Run a headless agent session").assertDoesNotExist()
            onNodeWithContentDescription("Move nodes").assertDoesNotExist()

            onNodeWithContentDescription("Back to the canvas").performClick()
            waitForIdle()

            onNodeWithContentDescription("Add a Agent node. Run a headless agent session").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `laying out again hands every node back and saves a file with no positions`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            var written: Workflow? = null
            val (state, _) = editor(twoNodes.handPlaced(), onSave = { written = it })

            onNodeWithText("Save").assertIsNotEnabled()

            onNodeWithContentDescription("Lay out again").performClick()
            waitForIdle()

            assertTrue(state.isDirty, "throwing the layout away left Save greyed out")
            onNodeWithText("Save").performClick()
            waitForIdle()

            val saved = requireNotNull(written) { "Save wrote nothing" }
            assertTrue(saved.nodes.all { it.position == null }, "positions survived a re-layout")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dragging the splitter widens the inspector, and the far end of the drag is clamped`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            editor(twoNodes)
            showInspector()

            val splitter = onNodeWithContentDescription("Resize inspector")
            val atRest = splitter.getBoundsInRoot().left

            fun dragLeft(by: Int) {
                splitter.performMouseInput {
                    moveTo(center)
                    press()

                    repeat(by / 20) { moveBy(Offset(-20f, 0f)) }
                    release()
                }
                waitForIdle()
            }

            dragLeft(200)
            val widened = splitter.getBoundsInRoot().left
            assertTrue(widened < atRest, "dragging the splitter left didn't widen the inspector")

            dragLeft(2000)
            val clamped = splitter.getBoundsInRoot().left
            dragLeft(2000)
            assertEquals(clamped, splitter.getBoundsInRoot().left, "the inspector kept eating the canvas")
            onNodeWithContentDescription("Fit to window").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `hiding the inspector takes its splitter with it, and it comes back the width it was left`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            editor(twoNodes)
            showInspector()

            onNodeWithContentDescription("Resize inspector").performMouseInput {
                moveTo(center)
                press()
                repeat(6) { moveBy(Offset(-20f, 0f)) }
                release()
            }
            waitForIdle()
            val widened = onNodeWithContentDescription("Resize inspector").getBoundsInRoot().left

            onNodeWithContentDescription("Hide inspector").performClick()
            waitForIdle()

            onNodeWithText("Select a node to edit it.").assertDoesNotExist()
            onNodeWithContentDescription("Resize inspector").assertDoesNotExist()

            onNodeWithContentDescription("Show inspector").performClick()
            waitForIdle()

            onNodeWithText("Select a node to edit it.").assertIsDisplayed()
            assertEquals(
                widened,
                onNodeWithContentDescription("Resize inspector").getBoundsInRoot().left,
                "the inspector forgot how wide it had been dragged",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a node keeps its type icon while a run is painted on the graph`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val waiting =
                NodeRun(
                    id = "n1",
                    workflowName = "w",
                    nodeId = "only",
                    nodeTitle = "only",
                    nodeType = NodeType.GATE,
                    cwd = null,
                ).showing(status = RunStatus.WAITING)

            editor(oneNode, runningNodes = listOf(waiting))

            onNodeWithContentDescription("Gate").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the editor's back button clears the title bar, and is not padded when there isn't one`() {
        val inset = 28.dp

        fun backTop(titleBarInset: Dp?): Dp {
            var top = 0.dp
            runDesktopComposeUiTest(width = 1000, height = 700) {
                editor(workflow = oneNode, titleBarInset = titleBarInset)
                top = onNodeWithContentDescription("Back to workflows").getBoundsInRoot().top
            }
            return top
        }

        val padded = backTop(inset)
        assertTrue(padded >= inset, "Back is at $padded, under the close button — the top $inset must stay clear")

        val bare = backTop(null)
        assertTrue(bare < inset, "Back is at $bare, padded for a title bar that isn't there")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with nothing selected the inspector spends the room on what is wrong, and a click lands on the node`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val (state, _) =
                editor(
                    Workflow(
                        name = "w",
                        nodes =
                            listOf(
                                WorkflowNode("alpha", NodeType.AGENT, prompt = "do a thing"),
                                WorkflowNode("beta", NodeType.SHELL, command = ""),
                            ),
                        edges = listOf(WorkflowEdge("alpha", "beta")),
                    ).handPlaced(),
                )
            showInspector()

            assertNull(state.selectedNode)
            onNodeWithText("Not ready to run").assertIsDisplayed()
            onNodeWithText("beta needs a command").assertIsDisplayed()

            onNodeWithText("beta needs a command").performClick()
            waitForIdle()

            assertEquals("beta", state.selectedNode?.id)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a workflow with nothing wrong says so instead of leaving the inspector blank`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            editor(twoNodes.copy(edges = listOf(WorkflowEdge("alpha", "beta"))).handPlaced())
            showInspector()

            onNodeWithText("Ready to run").assertIsDisplayed()
            onNodeWithText("Nothing to fix.").assertIsDisplayed()
            onNodeWithText("1 agent").assertIsDisplayed()
        }
}

class NodeInspectorTest {
    private val tempDirs = mutableListOf<Path>()

    private fun workspaceWith(build: (Path) -> Unit = {}): Workspace {
        val root = Files.createTempDirectory("zopf-inspector").also { tempDirs.add(it) }
        return Workspace.create(root.resolve("ws")).also { build(it.root) }
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.modelField() = onAllNodesWithText("Model", substring = true).filterToOne(hasSetTextAction())

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.inspector(
        workflow: Workflow,
        workspace: Workspace,
    ): EditorState {
        val state = EditorState(initial = workflow, workspace = workspace, onSave = {})
        state.select(workflow.nodes.first().id)
        setContent {
            ZopfTheme {
                state.selectedNode?.let { NodeInspector(state, it, workspace, onAddRepo = {}) }
            }
        }
        waitForIdle()
        return state
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a node with a prompt file shows the file and can be switched back to an inline prompt`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val workspace =
                workspaceWith { root ->
                    root.resolve("prompts").createDirectories()
                    root.resolve("prompts/review.md").writeText("Review the staged diff.\n")
                }
            val state =
                inspector(
                    Workflow(
                        name = "w",
                        nodes =
                            listOf(
                                WorkflowNode("review", NodeType.AGENT, promptFile = "prompts/review.md"),
                            ),
                    ),
                    workspace,
                )

            onNodeWithText("Prompt file").assertIsDisplayed()

            onNodeWithText("Review the staged diff.", substring = true).assertIsDisplayed()

            onNodeWithText("Type it here instead").performClick()
            waitForIdle()

            assertEquals("", state.workflow.node("review")?.promptFile)

            onNodeWithText("Prompt").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an inline node offers the file instead, and says so in its own words`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            inspector(
                Workflow(name = "w", nodes = listOf(WorkflowNode("review", NodeType.AGENT, prompt = "hi"))),
                workspaceWith(),
            )

            onNodeWithText("Prompt").assertIsDisplayed()
            onNodeWithText("Read the prompt from a .md file…").assertIsDisplayed()
        }

    @Test
    fun `a picker opens in the directory the node runs in, and falls back downward`() {
        val workspace = workspaceWith()
        val repo = Files.createTempDirectory("zopf-repo").also { tempDirs.add(it) }
        val workflow =
            Workflow(
                name = "w",
                repos = listOf(RepoRef("app", repo.toString()), RepoRef("gone", "/nowhere/at/all")),
                nodes = listOf(WorkflowNode("review", NodeType.AGENT, prompt = "hi", repo = "app")),
            )
        val state = EditorState(initial = workflow, workspace = workspace, onSave = {})
        val node = workflow.nodes.single()

        assertEquals(repo, state.startIn(node, workspace))

        state.edit { it.copy(defaults = it.defaults.copy(repo = "app")) }
        assertEquals(repo, state.startIn(node.copy(repo = null), workspace))

        assertEquals(workspace.root, state.startIn(node.copy(repo = "gone"), workspace))

        val bare = EditorState(initial = Workflow(name = "w"), workspace = workspace, onSave = {})
        assertEquals(workspace.root, bare.startIn(null, workspace))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a model the list has never heard of can still be typed`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val state =
                inspector(
                    Workflow(
                        name = "w",
                        nodes = listOf(WorkflowNode("review", NodeType.AGENT, prompt = "hi", provider = AgentProviderId.CLAUDE)),
                    ),
                    workspaceWith(),
                )

            modelField().performTextReplacement("claude-sonnet-4-5-20250929")

            assertEquals("claude-sonnet-4-5-20250929", state.workflow.node("review")?.model)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `emptying the field is how a node goes back to the model it inherits`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val state =
                inspector(
                    Workflow(
                        name = "w",
                        defaults = NodeDefaults(model = "haiku"),
                        nodes = listOf(WorkflowNode("review", NodeType.AGENT, prompt = "hi", model = "opus")),
                    ),
                    workspaceWith(),
                )

            modelField().performTextReplacement("  ")
            waitForIdle()

            assertNull(state.workflow.node("review")?.model)
            onNodeWithText("Inherited (haiku)").assertIsDisplayed()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the models a CLI is known for are a suggestion, not the whole choice`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val state =
                inspector(
                    Workflow(
                        name = "w",
                        nodes = listOf(WorkflowNode("review", NodeType.AGENT, prompt = "hi", provider = AgentProviderId.CODEX)),
                    ),
                    workspaceWith(),
                )
            val suggested = AgentProviderId.CODEX.modelOptions.first()

            modelField().performTextReplacement(suggested.take(3))
            waitForIdle()

            onNodeWithText(suggested).performClick()

            assertEquals(suggested, state.workflow.node("review")?.model)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the skills on disk are offered as chips, and ticking one lands on the node`() =
        runDesktopComposeUiTest(width = 500, height = 900) {
            val workspace =
                workspaceWith { root ->
                    root.resolve("skills/code-review").createDirectories()
                    root.resolve("skills/code-review/SKILL.md").writeText("---\nname: code-review\n---\n")
                }
            val state =
                inspector(
                    Workflow(name = "w", nodes = listOf(WorkflowNode("review", NodeType.AGENT, prompt = "hi"))),
                    workspace,
                )

            assertEquals(listOf("code-review"), state.skills.map { it.name })

            onNodeWithText("code-review").performClick()
            waitForIdle()

            assertEquals(listOf("code-review"), state.workflow.node("review")?.skills)
        }
}
