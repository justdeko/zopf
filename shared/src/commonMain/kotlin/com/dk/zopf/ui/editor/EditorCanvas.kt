package com.dk.zopf.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.zopf.model.Workflow

@Stable
class EditorCanvas internal constructor(
    val viewer: KuiverViewerState,
    private val directionState: MutableState<LayoutDirection>,
) {
    var direction: LayoutDirection
        get() = directionState.value
        internal set(value) {
            directionState.value = value
        }
}

@Composable
fun rememberEditorCanvas(workflow: Workflow): EditorCanvas {
    val direction = remember { mutableStateOf(LayoutDirection.HORIZONTAL) }
    val viewer =
        rememberKuiverViewerState(
            initialKuiver =
                remember(workflow.nodes.map { it.id }, workflow.edges) {
                    workflow.toKuiver(direction.value)
                },
            layoutConfig = LayoutConfig.Hierarchical(direction = direction.value),
        )
    return remember(viewer) { EditorCanvas(viewer, direction) }
}

internal fun EditorCanvas.autoDirection(workflow: Workflow): LayoutDirection? {
    if (viewer.manualPositions.isNotEmpty()) return null
    if (viewer.canvasWidth <= 0.dp || viewer.canvasHeight <= 0.dp) return null
    val measured =
        viewer.layoutedKuiver.nodes.values
            .mapNotNull { it.dimensions }
    if (measured.isEmpty()) return null
    return preferredDirection(
        canvas = DpSize(viewer.canvasWidth, viewer.canvasHeight),
        shape = workflow.graphShape(),
        node = DpSize(measured.maxOf { it.width }, measured.maxOf { it.height }),
    )
}

internal fun preferredDirection(
    canvas: DpSize,
    shape: GraphShape,
    node: DpSize,
): LayoutDirection {
    if (shape.breadth == 0 || canvas.width <= 0.dp || canvas.height <= 0.dp) {
        return LayoutDirection.HORIZONTAL
    }
    val alongFlow = shape.depth
    val acrossFlow = shape.breadth - 1

    val horizontal =
        DpSize(
            width = maxOf(MinLevelSpacing, node.width + LevelClearance) * alongFlow + node.width,
            height = maxOf(MinNodeSpacing, node.height + NodeClearance) * acrossFlow + node.height,
        )
    val vertical =
        DpSize(
            width = maxOf(MinNodeSpacing, node.width + NodeClearance) * acrossFlow + node.width,
            height = maxOf(MinLevelSpacing, node.height + LevelClearance) * alongFlow + node.height,
        )

    val fitsHorizontally = canvas.fitFor(horizontal)
    val fitsVertically = canvas.fitFor(vertical)
    val worthTurning =
        fitsHorizontally < TolerableShrink &&
            fitsVertically > fitsHorizontally * SwitchMargin
    return if (worthTurning) LayoutDirection.VERTICAL else LayoutDirection.HORIZONTAL
}

private fun DpSize.fitFor(extent: DpSize): Float =
    minOf(
        (width * FitPadding) / extent.width,
        (height * FitPadding) / extent.height,
    )

private val LevelClearance = 60.dp
private val NodeClearance = 40.dp
private val MinLevelSpacing = 150.dp
private val MinNodeSpacing = 100.dp

internal const val FitPadding = 0.92f

private const val TolerableShrink = 0.75f

private const val SwitchMargin = 1.15f
