package com.dk.zopf.ui.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.layout.LayoutConfig
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.rememberKuiverViewerState
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.EdgeStyle
import com.dk.zopf.model.Workflow
import com.dk.zopf.ui.editor.toKuiver
import com.dk.zopf.ui.theme.KuiverBridge
import com.dk.zopf.ui.theme.ZopfTheme

private val MinimapWidth = 108.dp

private val CardCorner = 12.dp

private val DotSize = 40.dp

@Composable
fun rememberMinimapViewerState(workflow: Workflow): KuiverViewerState {
    val graph = remember(workflow.nodes.map { it.id }, workflow.edges) { workflow.toKuiver() }
    val state =
        rememberKuiverViewerState(
            initialKuiver = graph,
            layoutConfig =
                remember {
                    LayoutConfig.Hierarchical(
                        direction = LayoutDirection.HORIZONTAL,
                        levelSpacing = 0.dp,
                        nodeSpacing = 0.dp,
                    )
                },
        )

    LaunchedEffect(graph) { state.updateKuiver(graph) }
    return state
}

private val MinimapConfig =
    KuiverViewerConfig(
        selectionMode = SelectionMode.NONE,
        nodeDragEnabled = false,
        hoverEnabled = false,
        fitToContent = true,
        contentPadding = 0.92f,
        maxScale = 0.3f,
        minScale = 0.03f,
    )

@Composable
fun WorkflowMinimap(
    workflow: Workflow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewerState: KuiverViewerState = rememberMinimapViewerState(workflow),
) {
    val colors = ZopfTheme.colors

    Box(
        modifier
            .width(MinimapWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topStart = CardCorner, bottomStart = CardCorner))
            .background(colors.graphSurface)
            .semantics { contentDescription = "${workflow.nodes.size} nodes" },
    ) {
        KuiverBridge {
            KuiverViewer(
                state = viewerState,
                modifier = Modifier.fillMaxSize(),
                config = MinimapConfig,
                nodeContent = { node ->
                    workflow.node(node.id)?.let { Dot(colors.node(it.type).accent) }
                },
                edgeStyle = { edge -> EdgeStyle.styled(edge, baseColor = colors.graphEdge, strokeWidth = 6.dp) },
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(DotSize).background(color, CircleShape))
}
