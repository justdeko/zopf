package com.dk.zopf.ui.editor

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.util.calculatePositionBounds
import com.dk.zopf.runtime.run.RunStatus
import kotlin.math.abs

internal const val CameraStiffness = 70f

internal val CameraScaleSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = CameraStiffness, visibilityThreshold = 0.001f)

internal val CameraOffsetSpec =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = CameraStiffness,
        visibilityThreshold = DpOffset.VisibilityThreshold,
    )

private const val RevealMargin = 0.08f

internal fun KuiverViewerState.isInView(nodeId: String): Boolean {
    val node = layoutedKuiver.nodes[nodeId] ?: return true
    val size = node.dimensions ?: return true
    if (canvasWidth <= 0.dp || canvasHeight <= 0.dp) return true
    val origin = layoutedKuiver.nodes.values.calculatePositionBounds()
    val centerX = (node.position.x - origin.centerX) * scale + offset.x
    val centerY = (node.position.y - origin.centerY) * scale + offset.y
    val roomX = canvasWidth * (0.5f - RevealMargin) - size.width * scale / 2
    val roomY = canvasHeight * (0.5f - RevealMargin) - size.height * scale / 2
    return abs(centerX.value) <= roomX.value && abs(centerY.value) <= roomY.value
}

internal fun KuiverViewerState.reveal(nodeId: String) {
    if (isInView(nodeId)) return
    val node = layoutedKuiver.nodes[nodeId] ?: return
    val origin = layoutedKuiver.nodes.values.calculatePositionBounds()
    updateTransform(
        scale = scale,
        offset = DpOffset(-(node.position.x - origin.centerX) * scale, -(node.position.y - origin.centerY) * scale),
        animated = true,
    )
}

internal fun Map<String, RunStatus>.followed(previous: Map<String, RunStatus>): String? = entries.firstOrNull { (id, status) -> status.isLive && previous[id]?.isLive != true }?.key

private val RunStatus.isLive: Boolean
    get() = this == RunStatus.STARTING || this == RunStatus.RUNNING || this == RunStatus.WAITING

internal fun Modifier.onUserCameraMove(onMove: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            var travelled = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Scroll -> {
                        onMove()
                    }

                    PointerEventType.Press -> {
                        travelled = 0f
                    }

                    PointerEventType.Move -> {
                        event.changes.firstOrNull { it.pressed }?.let { change ->
                            travelled += (change.position - change.previousPosition).getDistance()
                            if (travelled > viewConfiguration.touchSlop) onMove()
                        }
                    }
                }
            }
        }
    }
