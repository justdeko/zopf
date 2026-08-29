package com.dk.zopf.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.blurb
import com.dk.zopf.model.label
import com.dk.zopf.ui.theme.PathIcon
import com.dk.zopf.ui.theme.colors
import kotlin.math.abs
import kotlin.math.roundToInt

data class ConnectDrag(
    val from: String,
    val start: DpOffset = DpOffset.Zero,
    val delta: DpOffset = DpOffset.Zero,
    val over: String? = null,
) {
    val tip: DpOffset get() = start + delta
}

data class ConnectDrop(
    val from: String,
    val at: DpOffset,
    val window: Offset,
)

internal fun KuiverViewerState.nodeAt(point: DpOffset): String? =
    layoutedKuiver.nodes.values
        .firstOrNull { node ->
            val size = node.dimensions ?: return@firstOrNull false
            abs((point.x - node.position.x).value) <= size.width.value / 2 &&
                abs((point.y - node.position.y).value) <= size.height.value / 2
        }?.id

internal fun KuiverViewerState.topLeftOf(nodeId: String): DpOffset? {
    val node = layoutedKuiver.nodes[nodeId] ?: return null
    val size = node.dimensions ?: return null
    return DpOffset(node.position.x - size.width / 2, node.position.y - size.height / 2)
}

@Composable
internal fun Modifier.connectDragSource(
    nodeId: String,
    state: EditorState,
    viewer: KuiverViewerState,
    enabled: Boolean,
    onDropOnCanvas: (ConnectDrop) -> Unit,
): Modifier {
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }

    return onGloballyPositioned { coordinates.value = it }
        .then(
            if (!enabled) {
                Modifier
            } else {
                Modifier.pointerInput(nodeId, state, viewer) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture

                        val past =
                            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture

                        val start = DpOffset(down.position.x.toDp(), down.position.y.toDp())
                        state.beginConnectDrag(nodeId, start)

                        var travelled = past.position - down.position

                        fun delta(): DpOffset = DpOffset(travelled.x.toDp(), travelled.y.toDp())

                        fun report() {
                            val origin = viewer.topLeftOf(nodeId) ?: return
                            val over = viewer.nodeAt(origin + start + delta())?.takeIf { it != nodeId }
                            state.moveConnectDrag(delta(), over)
                        }
                        report()

                        val completed =
                            drag(past.id) { change ->
                                travelled += change.positionChange()
                                change.consume()
                                report()
                            }

                        if (!completed) {
                            state.cancelConnectDrag()
                            return@awaitEachGesture
                        }

                        val origin = viewer.topLeftOf(nodeId)
                        val dropped = origin?.let { it + start + delta() }
                        when (dropped?.let(viewer::nodeAt)) {
                            null -> {
                                val window = coordinates.value?.localToWindow(down.position + travelled)
                                state.cancelConnectDrag()
                                if (window != null && dropped != null) {
                                    onDropOnCanvas(ConnectDrop(nodeId, dropped, window))
                                }
                            }

                            nodeId -> state.cancelConnectDrag()

                            else -> state.finishConnectDrag()
                        }
                    }
                }
            },
        )
}

internal fun Modifier.connectRubberBand(
    drag: ConnectDrag?,
    direction: LayoutDirection,
    color: Color,
): Modifier =
    drawWithContent {
        if (drag != null) {
            val from =
                if (direction == LayoutDirection.VERTICAL) {
                    Offset(size.width / 2f, size.height)
                } else {
                    Offset(size.width, size.height / 2f)
                }
            val to = Offset(drag.tip.x.toPx(), drag.tip.y.toPx())
            drawFlowCurve(flowCurve(from, to, direction, this), color, dashed = false, RoundArrowDrawer)
        }
        drawContent()
    }

@Composable
internal fun ConnectDropMenu(
    drop: ConnectDrop,
    fromTitle: String,
    onPick: (NodeType) -> Unit,
    onDismiss: () -> Unit,
) {
    val position =
        remember(drop.window) {
            AtWindowOffset(IntOffset(drop.window.x.roundToInt(), drop.window.y.roundToInt()))
        }

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    "New node after $fromTitle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                NodeType.entries.forEach { type ->
                    Row(
                        Modifier
                            .clickable { onPick(type) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PathIcon(type.icon, contentDescription = null, size = 15.dp, tint = type.colors().accent)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(type.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                type.blurb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private class AtWindowOffset(
    private val offset: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset =
        IntOffset(
            offset.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            offset.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
}
