package com.dk.zopf.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.renderer.KuiverNodeScope
import com.dk.kuiver.ui.ArrowDrawer
import com.dk.kuiver.ui.DefaultArrowDrawer
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.ui.theme.PathIcon
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Flags(
    override val isSelected: Boolean = false,
    override val isHovered: Boolean = false,
    override val isDragging: Boolean = false,
) : KuiverNodeScope

class CanvasPixelsTest {
    private companion object {
        const val EdgeStart = 20f
        const val EdgeEnd = 180f

        const val EdgeCross = 50f
    }

    private fun ImageBitmap.softEdgePixels(): Int {
        val pixels = toPixelMap()
        var soft = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = pixels[x, y].red
                if (v > 0.15f && v < 0.85f) soft++
            }
        }
        return soft
    }

    @OptIn(ExperimentalTestApi::class)
    private fun softEdgesUnder4xZoom(icon: @Composable () -> Unit): Int {
        var soft = -1
        runDesktopComposeUiTest(width = 200, height = 200) {
            setContent {
                Box(
                    Modifier.size(72.dp).testTag("zoomed").background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.graphicsLayer {
                            scaleX = 4f
                            scaleY = 4f
                        },
                    ) { icon() }
                }
            }
            waitForIdle()
            soft = onNodeWithTag("zoomed").captureToImage().softEdgePixels()
        }
        return soft
    }

    @Test
    fun `a node icon stays sharp when the canvas is zoomed in`() {
        val cached =
            softEdgesUnder4xZoom {
                Icon(ZopfIcons.NodeConnector, null, Modifier.size(18.dp), tint = Color.White)
            }
        val drawn =
            softEdgesUnder4xZoom {
                PathIcon(ZopfIcons.NodeConnector, null, size = 18.dp, tint = Color.White)
            }

        assertTrue(cached > 0, "the icon under test drew nothing")
        assertTrue(
            drawn * 2 < cached,
            "PathIcon is no sharper than a magnified bitmap: $drawn soft pixels vs $cached",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `hovering the connect handle highlights a circle, not the square around it`() =
        runDesktopComposeUiTest(width = 400, height = 200) {
            setContent {
                ZopfTheme(darkTheme = false) {
                    Box(Modifier.size(400.dp, 200.dp).background(Color.White)) {
                        with(Flags()) {
                            WorkflowNodeCard(
                                node = WorkflowNode("alpha", NodeType.AGENT, prompt = "hi"),
                                isSelected = false,
                                hasIssue = false,
                                isConnectSource = false,
                                isConnectable = true,
                                connectMode = false,
                                onConnectClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val before = captureRoot()

            onNodeWithContentDescription("Connect from here").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            val after = captureRoot()

            val changed = changedPixels(before, after)
            assertTrue(changed.isNotEmpty(), "hovering the handle changed nothing on screen")

            val minX = changed.minOf { it.first }
            val maxX = changed.maxOf { it.first }
            val minY = changed.minOf { it.second }
            val maxY = changed.maxOf { it.second }
            val corners = listOf(minX to minY, maxX to minY, minX to maxY, maxX to maxY)
            val square = corners.count { it in changed }

            assertEquals(
                0,
                square,
                "the hover highlight reaches into the corners of its box, so it is drawn square",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    private fun paintedEdge(
        from: Offset,
        to: Offset,
        direction: LayoutDirection,
        arrowDrawer: ArrowDrawer = RoundArrowDrawer,
    ): Set<Pair<Int, Int>> {
        val painted = mutableSetOf<Pair<Int, Int>>()
        runDesktopComposeUiTest(width = 220, height = 220) {
            setContent {
                Box(Modifier.size(220.dp, 220.dp).testTag("edge").background(Color.White)) {
                    WorkflowEdgeContent(
                        from = from,
                        to = to,
                        direction = direction,
                        label = null,
                        color = Color.Black,
                        dashed = false,
                        arrowDrawer = arrowDrawer,
                    )
                }
            }
            waitForIdle()
            val pixels = onNodeWithTag("edge").captureToImage().toPixelMap()
            for (x in 0 until pixels.width) {
                for (y in 0 until pixels.height) {
                    if (pixels[x, y].red < 0.5f) painted += x to y
                }
            }
        }
        return painted
    }

    @Test
    fun `the round arrow head reaches the endpoint and the default one stops short of it`() {
        val from = Offset(EdgeStart, EdgeCross)
        val to = Offset(EdgeEnd, EdgeCross)
        val round = paintedEdge(from, to, LayoutDirection.HORIZONTAL).maxOf { it.first }
        val default =
            paintedEdge(from, to, LayoutDirection.HORIZONTAL, DefaultArrowDrawer).maxOf { it.first }

        assertTrue(
            round >= EdgeEnd.toInt() - 2,
            "the round head stopped ${EdgeEnd.toInt() - round}px short of the endpoint",
        )
        assertTrue(
            default < round - 4,
            "the default head is no shorter than the round one ($default vs $round), so this " +
                "test can no longer tell them apart",
        )
    }

    @Test
    fun `a vertical edge arrives with its head pointing down`() {
        val to = Offset(EdgeCross + 80f, EdgeEnd)
        val painted = paintedEdge(Offset(EdgeCross, EdgeStart), to, LayoutDirection.VERTICAL)

        val reach = ArrowSize.value.toInt()
        val tip =
            painted.filter { (x, y) ->
                x in (to.x.toInt() - reach)..(to.x.toInt() + reach) &&
                    y in (to.y.toInt() - reach)..(to.y.toInt() + reach)
            }
        assertTrue(tip.isNotEmpty(), "nothing was drawn at the end of the edge")

        val across = tip.maxOf { it.first } - tip.minOf { it.first }
        val along = tip.maxOf { it.second } - tip.minOf { it.second }
        assertTrue(
            along > across,
            "what is drawn at the tip is ${across}px wide and ${along}px tall, so the head lies " +
                "across the edge rather than along it",
        )
        assertTrue(
            painted.maxOf { it.second } >= EdgeEnd.toInt() - 2,
            "the line stops ${EdgeEnd.toInt() - painted.maxOf { it.second }}px short of the endpoint",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.captureRoot(): ImageBitmap = onRoot().captureToImage()

    private fun changedPixels(
        before: ImageBitmap,
        after: ImageBitmap,
    ): Set<Pair<Int, Int>> {
        val a = before.toPixelMap()
        val b = after.toPixelMap()
        val changed = mutableSetOf<Pair<Int, Int>>()
        for (y in 0 until minOf(before.height, after.height)) {
            for (x in 0 until minOf(before.width, after.width)) {
                if (a[x, y] != b[x, y]) changed += x to y
            }
        }
        return changed
    }
}

class NodeCardTextTest {
    @OptIn(ExperimentalTestApi::class)
    private fun titleHeight(title: String): Dp {
        var height = 0.dp
        runDesktopComposeUiTest(600, 300) {
            setContent {
                ZopfTheme(darkTheme = false) {
                    Box(Modifier.size(600.dp, 300.dp)) {
                        with(Flags()) {
                            WorkflowNodeCard(
                                node = WorkflowNode("tests", NodeType.SHELL, title = title),
                                isSelected = false,
                                hasIssue = false,
                                isConnectSource = false,
                                isConnectable = true,
                                connectMode = false,
                                onConnectClick = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            height = onNodeWithText(title).getBoundsInRoot().height
        }
        return height
    }

    @Test
    fun `a command title fits the card on one line`() {
        val oneLine = titleHeight("tests")
        val command = titleHeight("./gradlew :shared:jvmTest")
        assertEquals(
            oneLine,
            command,
            "the card wrapped './gradlew :shared:jvmTest' ($command vs $oneLine for one line)",
        )
    }

    @Test
    fun `a title that is a sentence keeps two lines`() {
        val oneLine = titleHeight("tests")
        val sentence = titleHeight("Version matches — send the notification to the team channel?")
        assertTrue(
            sentence > oneLine,
            "a long title was clipped to one line ($sentence vs $oneLine)",
        )
    }
}

class EdgeShapeTest {
    private val density = Density(1f)
    private val arrow = ArrowSize.value

    @Test
    fun `a horizontal edge leaves and arrives along x`() {
        val from = Offset(20f, 50f)
        val to = Offset(180f, 90f)
        val curve = flowCurve(from, to, LayoutDirection.HORIZONTAL, density)

        assertEquals(from.y, curve.controlPoint1.y, "the line leaves at an angle")
        assertEquals(to.y, curve.controlPoint2.y, "the line arrives at an angle")

        assertEquals(to.y, curve.pathEndpoint.y, 0.01f)
        assertEquals(to.x - arrow, curve.pathEndpoint.x, 0.01f)
    }

    @Test
    fun `a vertical edge leaves and arrives along y`() {
        val from = Offset(100f, 20f)
        val to = Offset(140f, 220f)
        val curve = flowCurve(from, to, LayoutDirection.VERTICAL, density)

        assertEquals(from.x, curve.controlPoint1.x, "the line leaves sideways")
        assertEquals(to.x, curve.controlPoint2.x, "the line arrives sideways")

        assertEquals(to.x, curve.pathEndpoint.x, 0.01f)
        assertEquals(to.y - arrow, curve.pathEndpoint.y, 0.01f)
    }

    @Test
    fun `two anchors level on the flow axis still get a head`() {
        val from = Offset(50f, 20f)
        val to = Offset(50f, 180f)
        val curve = flowCurve(from, to, LayoutDirection.HORIZONTAL, density)

        assertTrue(
            curve.pathEndpoint != to,
            "the line runs all the way to the anchor, so there is no room for a head",
        )
        assertEquals(to.y - arrow, curve.pathEndpoint.y, 0.01f)
    }
}
