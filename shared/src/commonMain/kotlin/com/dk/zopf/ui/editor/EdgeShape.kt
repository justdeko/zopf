package com.dk.zopf.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.ui.ArrowDrawer
import com.dk.kuiver.ui.DefaultEdgeLabel
import com.dk.kuiver.ui.EdgeCanvas
import com.dk.kuiver.ui.EdgePath
import com.dk.kuiver.ui.KuiverDefaults
import com.dk.kuiver.ui.boundingRect

@Composable
internal fun WorkflowEdgeContent(
    from: Offset,
    to: Offset,
    direction: LayoutDirection,
    label: String?,
    color: Color,
    dashed: Boolean,
    arrowDrawer: ArrowDrawer = RoundArrowDrawer,
    width: Dp = EdgeStroke,
    dashPhase: Float = 0f,
) {
    val density = LocalDensity.current
    val curve = remember(from, to, direction, density) { flowCurve(from, to, direction, density) }
    val bounds =
        remember(curve, density, width) {
            curve.boundingRect(density = density, strokeWidth = maxOf(width, EdgeStroke), arrowSize = ArrowSize)
        }

    val edge = @Composable {
        EdgeCanvas(bounds) { drawFlowCurve(curve, color, dashed, arrowDrawer, width, dashPhase) }
    }

    val labelPosition =
        remember(curve, density, label) {
            if (label.isNullOrBlank()) {
                null
            } else {
                curve.calculateLabelPosition(0.5f, with(density) { MinLengthForLabel.toPx() })
            }
        }

    if (label == null || labelPosition == null) {
        edge()
        return
    }

    Box {
        edge()

        Box(
            Modifier
                .zIndex(1f)
                .wrapContentSize(unbounded = true)
                .graphicsLayer {
                    translationX = labelPosition.position.x - size.width / 2f
                    translationY = labelPosition.position.y - size.height / 2f
                },
        ) {
            DefaultEdgeLabel(label, style = KuiverDefaults.edgeLabelStyle())
        }
    }
}

internal fun flowCurve(
    from: Offset,
    to: Offset,
    direction: LayoutDirection,
    density: Density,
): EdgePath.Orthogonal {
    val vertical = direction == LayoutDirection.VERTICAL

    val reach = (if (vertical) to.y - from.y else to.x - from.x) * CurveFactor
    val control1 = if (vertical) Offset(from.x, from.y + reach) else Offset(from.x + reach, from.y)
    val control2 = if (vertical) Offset(to.x, to.y - reach) else Offset(to.x - reach, to.y)

    val heading = headingAt(to, control2, from)
    val endpoint = if (heading == null) to else to - heading * with(density) { ArrowSize.toPx() }

    return EdgePath.Orthogonal(
        from = from,
        to = to,
        controlPoint1 = control1,
        controlPoint2 = control2,
        pathEndpoint = endpoint,
        edgeLength = curveLength(from, control1, control2, to),
        curveFactor = CurveFactor,
    )
}

private fun headingAt(
    to: Offset,
    control2: Offset,
    from: Offset,
): Offset? {
    val tangent = to - control2
    val fallback = to - from
    val heading = if (tangent.getDistance() > 0f) tangent else fallback
    val length = heading.getDistance()
    return if (length > 0f) heading / length else null
}

internal fun DrawScope.drawFlowCurve(
    curve: EdgePath.Orthogonal,
    color: Color,
    dashed: Boolean,
    arrowDrawer: ArrowDrawer,
    width: Dp = EdgeStroke,
    dashPhase: Float = 0f,
) {
    val path =
        Path().apply {
            moveTo(curve.from.x, curve.from.y)
            cubicTo(
                curve.controlPoint1.x,
                curve.controlPoint1.y,
                curve.controlPoint2.x,
                curve.controlPoint2.y,
                curve.pathEndpoint.x,
                curve.pathEndpoint.y,
            )
        }

    drawPath(
        path = path,
        color = color.copy(alpha = color.alpha * LineAlpha),
        style =
            Stroke(
                width = width.toPx(),
                pathEffect =
                    if (dashed) {
                        PathEffect.dashPathEffect(
                            floatArrayOf(DashLength.toPx(), DashGap.toPx()),
                            phase = dashPhase,
                        )
                    } else {
                        null
                    },
                cap = StrokeCap.Round,
            ),
    )

    headingAt(curve.to, curve.controlPoint2, curve.from)?.let { heading ->
        arrowDrawer(curve.to, heading, color, ArrowSize.toPx())
    }
}

private fun curveLength(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
): Float {
    val polygon = (p1 - p0).getDistance() + (p2 - p1).getDistance() + (p3 - p2).getDistance()
    return ((p3 - p0).getDistance() + 2f * polygon) / 3f
}

private const val CurveFactor = 0.5f

private const val LineAlpha = 0.8f

private val DashLength = 10.dp
private val DashGap = 5.dp

internal val DashPeriod = DashLength + DashGap

private val MinLengthForLabel = 50.dp

internal val EdgeStroke = 2.dp
internal val ArrowSize = 11.dp

private val ArrowRadius = 1.5.dp
private const val ArrowSpread = 0.38f

internal val RoundArrowDrawer: ArrowDrawer = { tip, direction, color, arrowSize ->
    val radius = ArrowRadius.toPx()
    val length = arrowSize - 2 * radius

    val across = Offset(-direction.y, direction.x) * (length * ArrowSpread)
    val apex = tip - direction * radius
    val base = apex - direction * length

    val head =
        Path().apply {
            moveTo(apex.x, apex.y)
            lineTo(base.x + across.x, base.y + across.y)
            lineTo(base.x - across.x, base.y - across.y)
            close()
        }
    drawPath(head, color)
    drawPath(
        head,
        color,
        style = Stroke(radius * 2, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
