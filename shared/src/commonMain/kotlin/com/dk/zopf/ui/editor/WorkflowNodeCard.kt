package com.dk.zopf.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverNodeScope
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.LocalKuiverColors
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.label
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.runs.color
import com.dk.zopf.ui.theme.KuiverBridge
import com.dk.zopf.ui.theme.PathIcon
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors

private val CardWidth = 260.dp
private val BadgeSize = 15.dp
private const val PulseMillis = 850
private val SkippedAlpha = 0.5f
private val SelectedTint = 0.07f

val NodeType.icon: ImageVector
    get() =
        when (this) {
            NodeType.AGENT -> ZopfIcons.NodeAgent
            NodeType.SHELL -> ZopfIcons.NodeShell
            NodeType.CONNECTOR -> ZopfIcons.NodeConnector
            NodeType.GATE -> ZopfIcons.NodeGate
            NodeType.BRANCH -> ZopfIcons.NodeBranch
            NodeType.INPUT -> ZopfIcons.NodeInput
        }

@Composable
fun KuiverNodeScope.WorkflowNodeCard(
    node: WorkflowNode,
    isSelected: Boolean,
    hasIssue: Boolean,
    isConnectSource: Boolean,
    isConnectable: Boolean,
    isDropTarget: Boolean = false,
    connectMode: Boolean,
    onConnectClick: () -> Unit,
    runStatus: RunStatus? = null,
    provider: AgentProviderId? = null,
    model: String? = null,
) {
    val scheme = MaterialTheme.colorScheme

    val roles = node.type.colors()
    val accent = roles.accent

    val outline by animateColorAsState(
        when {
            isDropTarget -> scheme.primary
            isConnectSource -> scheme.primary
            connectMode && isConnectable -> scheme.tertiary
            hasIssue -> scheme.error
            isSelected -> scheme.primary
            runStatus != null && runStatus != RunStatus.QUEUED -> runStatus.color()
            isHovered -> accent
            else -> scheme.outlineVariant
        },
        label = "nodeOutline",
    )
    val outlineWidth by animateDpAsState(
        if (isDropTarget) {
            3.dp
        } else if (isSelected || isConnectSource || runStatus?.showsProgress == true) {
            2.dp
        } else {
            1.dp
        },
        label = "nodeOutlineWidth",
    )

    Surface(
        modifier = Modifier.width(CardWidth).alpha(if (runStatus == RunStatus.SKIPPED) SkippedAlpha else 1f),
        shape = RoundedCornerShape(14.dp),
        color =
            if (isSelected) {
                accent.copy(alpha = SelectedTint).compositeOver(scheme.secondaryContainer)
            } else {
                roles.surface
            },
        border = androidx.compose.foundation.BorderStroke(outlineWidth, outline),
        shadowElevation = if (isDragging) 8.dp else 1.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(roles.container)
                    .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PathIcon(
                    node.type.icon,
                    contentDescription = node.type.label,
                    size = 14.dp,
                    tint = if (hasIssue) scheme.error else roles.onContainer,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    node.headerLabel(provider, model),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasIssue) scheme.error else roles.onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (runStatus != null) {
                    StatusBadge(runStatus)
                    Spacer(Modifier.width(5.dp))
                }
                ConnectHandle(
                    isArmed = isConnectSource,
                    onClick = onConnectClick,
                    visible = !connectMode || isConnectSource,
                )
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    node.displayTitle,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = node.subtitle()
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (node.type.subtitleIsCode) FontFamily.Monospace else null,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: RunStatus) {
    val roles = status.colors()

    if (status.showsProgress) {
        LoadingIndicator(Modifier.size(BadgeSize), color = roles.accent)
        return
    }

    val glyph = status.glyph
    val filled = glyph != null || status == RunStatus.WAITING
    Box(
        Modifier
            .size(BadgeSize)
            .alpha(if (status == RunStatus.WAITING) pulse() else 1f)
            .background(if (filled) roles.accent else Color.Transparent, CircleShape)
            .border(if (filled) 0.dp else 2.dp, roles.accent, CircleShape)
            .semantics { contentDescription = status.label },
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            PathIcon(glyph, contentDescription = null, size = 9.dp, tint = roles.onAccent)
        }
    }
}

@Composable
private fun pulse(): Float {
    val transition = rememberInfiniteTransition(label = "waiting")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(PulseMillis), repeatMode = RepeatMode.Reverse),
        label = "waitingPulse",
    )
    return alpha
}

private val RunStatus.glyph: ImageVector?
    get() =
        when (this) {
            RunStatus.SUCCEEDED -> ZopfIcons.Check
            RunStatus.FAILED -> ZopfIcons.Warning
            RunStatus.STOPPED -> ZopfIcons.Stop
            RunStatus.DETACHED -> ZopfIcons.Terminal
            else -> null
        }

@Composable
private fun ConnectHandle(
    isArmed: Boolean,
    onClick: () -> Unit,
    visible: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(4.dp)
            .background(if (isArmed) scheme.primary else Color.Transparent, CircleShape)
            .border(1.dp, if (isArmed) scheme.primary else scheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PathIcon(
            ZopfIcons.Link,
            contentDescription = if (isArmed) "Cancel connection" else "Connect from here",
            size = 12.dp,
            tint = if (isArmed) scheme.onPrimary else scheme.onSurfaceVariant,
        )
    }
}

private fun WorkflowNode.headerLabel(
    provider: AgentProviderId?,
    model: String?,
): String =
    if (type == NodeType.AGENT && provider != null) {
        listOfNotNull(provider.label, model).joinToString(" · ")
    } else {
        type.label
    }

private val NodeType.subtitleIsCode: Boolean
    get() = this == NodeType.SHELL || this == NodeType.BRANCH

private fun WorkflowNode.subtitle(): String =
    when (type) {
        NodeType.AGENT -> {
            promptFile.ifBlank {
                prompt
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    .orEmpty()
            }
        }
        NodeType.SHELL ->
            command
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
        NodeType.CONNECTOR -> connector
        NodeType.BRANCH -> expression
        NodeType.GATE -> "Waits for approval"

        NodeType.INPUT ->
            prompt
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
    }

@Preview
@Composable
private fun WorkflowNodeCardPreview() {
    val workflow = PreviewFixtures.workflow()
    val canvas = rememberEditorCanvas(workflow)
    ZopfTheme {
        Surface {
            Box(Modifier.size(620.dp, 340.dp)) {
                KuiverBridge {
                    KuiverViewer(
                        state = canvas.viewer,
                        modifier = Modifier.fillMaxSize(),
                        config = KuiverViewerConfig(selectionMode = SelectionMode.NONE),
                        callbacks = KuiverInteractionCallbacks(),
                        nodeContent = { kuiverNode ->
                            workflow.node(kuiverNode.id)?.let { node ->
                                WorkflowNodeCard(
                                    node = node,
                                    isSelected = node.id == "plan",
                                    hasIssue = node.id == "notify",
                                    isConnectSource = node.id == "brief",
                                    isConnectable = node.id == "tests",
                                    connectMode = true,
                                    onConnectClick = {},
                                    runStatus = if (node.id == "route") RunStatus.RUNNING else null,
                                    provider = AgentProviderId.CLAUDE.takeIf { node.type == NodeType.AGENT },
                                    model = node.model,
                                )
                            }
                        },
                        edgeContent = { _, from, to ->
                            WorkflowEdgeContent(
                                from = from,
                                to = to,
                                direction = canvas.direction,
                                label = null,
                                color = LocalKuiverColors.current.edge,
                                dashed = false,
                            )
                        },
                    )
                }
            }
        }
    }
}
