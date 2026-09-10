package com.dk.zopf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.dk.zopf.model.NodeType
import com.dk.zopf.runtime.RunStatus

@Immutable
class ZopfColors internal constructor(
    private val nodes: Map<NodeType, NodeColorRoles>,
    private val statuses: Map<RunStatus, StatusColorRoles>,
    val graphEdge: Color,
    val graphSurface: Color,
) {
    fun node(type: NodeType): NodeColorRoles = nodes.getValue(type)

    fun status(status: RunStatus): StatusColorRoles = statuses.getValue(status)
}

@Immutable
data class NodeColorRoles(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val surface: Color,
)

@Immutable
data class StatusColorRoles(
    val accent: Color,
    val onAccent: Color,
)

@Immutable
data class NodeAccents(
    val agent: Color,
    val shell: Color,
    val connector: Color,
    val gate: Color,
    val branch: Color,
    val input: Color,
) {
    operator fun get(type: NodeType): Color =
        when (type) {
            NodeType.AGENT -> agent
            NodeType.SHELL -> shell
            NodeType.CONNECTOR -> connector
            NodeType.GATE -> gate
            NodeType.BRANCH -> branch
            NodeType.INPUT -> input
        }
}

@Immutable
data class StatusAccents(
    val queued: Color,
    val running: Color,
    val waiting: Color,
    val succeeded: Color,
    val failed: Color,
    val stopped: Color,
    val skipped: Color,
    val detached: Color,
) {
    operator fun get(status: RunStatus): Color =
        when (status) {
            RunStatus.QUEUED -> queued
            RunStatus.STARTING, RunStatus.RUNNING -> running
            RunStatus.WAITING -> waiting
            RunStatus.SUCCEEDED -> succeeded
            RunStatus.FAILED -> failed
            RunStatus.STOPPED -> stopped
            RunStatus.SKIPPED -> skipped
            RunStatus.DETACHED -> detached
        }
}

val LocalZopfColors =
    staticCompositionLocalOf<ZopfColors> {
        error("No ZopfColors — wrap the content in ZopfTheme")
    }

object ZopfTheme {
    val colors: ZopfColors
        @Composable @ReadOnlyComposable
        get() = LocalZopfColors.current
}

@Composable
@ReadOnlyComposable
fun NodeType.colors(): NodeColorRoles = LocalZopfColors.current.node(this)

@Composable
@ReadOnlyComposable
fun RunStatus.colors(): StatusColorRoles = LocalZopfColors.current.status(this)

internal fun zopfColors(
    scheme: ColorScheme,
    accents: NodeAccents,
    statuses: StatusAccents,
    dark: Boolean,
): ZopfColors {
    val header = if (dark) 0.26f else 0.18f
    val body = if (dark) 0.07f else 0.05f
    return ZopfColors(
        nodes =
            NodeType.entries.associateWith { type ->
                val accent = accents[type]
                NodeColorRoles(
                    accent = accent,
                    container = accent.over(scheme.surfaceContainer, header),
                    onContainer = accent,
                    surface = accent.over(scheme.surfaceContainer, body),
                )
            },
        statuses =
            RunStatus.entries.associateWith {
                StatusColorRoles(accent = statuses[it], onAccent = scheme.surface)
            },
        graphEdge = scheme.outlineVariant,
        graphSurface = scheme.surfaceContainerLow,
    )
}

private fun Color.over(
    surface: Color,
    amount: Float,
): Color = copy(alpha = amount).compositeOver(surface)
