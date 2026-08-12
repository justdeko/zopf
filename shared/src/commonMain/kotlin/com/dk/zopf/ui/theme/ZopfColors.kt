package com.dk.zopf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.dk.zopf.model.NodeType

@Immutable
class ZopfColors internal constructor(
    private val nodes: Map<NodeType, NodeColorRoles>,
    val graphEdge: Color,
    val graphSurface: Color,
) {
    fun node(type: NodeType): NodeColorRoles = nodes.getValue(type)
}

@Immutable
data class NodeColorRoles(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
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

internal fun zopfColors(
    scheme: ColorScheme,
    accents: NodeAccents,
    dark: Boolean,
): ZopfColors {
    val tint = if (dark) 0.22f else 0.14f
    return ZopfColors(
        nodes =
            NodeType.entries.associateWith { type ->
                val accent = accents[type]
                NodeColorRoles(
                    accent = accent,
                    container = accent.copy(alpha = tint).compositeOver(scheme.surfaceContainer),
                    onContainer = accent,
                )
            },
        graphEdge = scheme.outlineVariant,
        graphSurface = scheme.surfaceContainerLow,
    )
}
