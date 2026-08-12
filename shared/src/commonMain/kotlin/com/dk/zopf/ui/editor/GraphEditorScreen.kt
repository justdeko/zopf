package com.dk.zopf.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dk.kuiver.RelayoutPolicy
import com.dk.kuiver.SelectionMode
import com.dk.kuiver.model.layout.LayoutDirection
import com.dk.kuiver.renderer.KuiverInteractionCallbacks
import com.dk.kuiver.renderer.KuiverViewer
import com.dk.kuiver.renderer.KuiverViewerConfig
import com.dk.kuiver.ui.KuiverAnchor
import com.dk.kuiver.ui.LocalKuiverColors
import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.blurb
import com.dk.zopf.model.label
import com.dk.zopf.model.validate
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.store.Workspace
import com.dk.zopf.ui.LocalTitleBarInset
import com.dk.zopf.ui.LocalWindowDragArea
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.KuiverBridge
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.awt.Cursor
import kotlin.time.Duration.Companion.milliseconds

private val InspectorWidth = 340.dp
private val MinInspectorWidth = 260.dp
private val MaxInspectorWidth = 720.dp

private fun canvasConfig(nodeDragEnabled: Boolean) =
    KuiverViewerConfig(
        selectionMode = SelectionMode.NONE,
        nodeDragEnabled = nodeDragEnabled,
        hoverEnabled = true,
        relayoutPolicy = RelayoutPolicy.KEEP_MANUAL,
        fitToContent = false,
        contentPadding = FitPadding,
    )

private const val ReflowSettleMillis = 220L

@Composable
fun GraphEditorScreen(
    state: EditorState,
    workspace: Workspace?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onRunNode: (WorkflowNode) -> Unit = {},
    onRunWorkflow: () -> Unit = {},
    runningNodes: List<NodeRun> = emptyList(),
    onCommands: (EditorCommands?) -> Unit = {},
    canvas: EditorCanvas = rememberEditorCanvas(state.workflow),
) {
    val workflow = state.workflow
    val viewerState = canvas.viewer

    LaunchedEffect(workflow.nodes.map { it.id }, workflow.edges, canvas.direction) {
        viewerState.updateKuiver(workflow.toKuiver(canvas.direction))
    }

    LaunchedEffect(Unit) { viewerState.applyPositions(workflow) }

    LaunchedEffect(Unit) {
        snapshotFlow { viewerState.hasFittedInitially }.first { it }
        viewerState.fitGraph()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { viewerState.persistablePositions(state.workflow) }
            .collect { state.reportCanvasPositions(it) }
    }

    LaunchedEffect(canvas, state) {
        var settled = false
        snapshotFlow { canvas.autoDirection(state.workflow) }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { wanted ->

                if (settled) delay(ReflowSettleMillis.milliseconds)
                settled = true
                if (canvas.direction == wanted) return@collectLatest
                val before = viewerState.layoutedKuiver
                canvas.direction = wanted
                snapshotFlow { viewerState.layoutedKuiver }.first { it !== before }
                viewerState.fitGraph()
            }
    }

    var showSettings by remember { mutableStateOf(false) }
    var confirmingClose by remember { mutableStateOf(false) }
    var inspectorWidth by remember { mutableStateOf(InspectorWidth) }

    var inspectorVisible by remember { mutableStateOf(false) }
    var nodeDragEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        snapshotFlow { state.selectedNodeId }.first { it != null }
        inspectorVisible = true
    }

    fun save() = state.save(viewerState.persistablePositions(state.workflow))

    fun close() {
        if (state.isDirty) confirmingClose = true else onClose()
    }

    val publish by rememberUpdatedState(onCommands)

    DisposableEffect(state, viewerState) {
        publish(
            EditorCommands(
                save = { save() },
                close = { close() },
                openSettings = { showSettings = true },
                isDirty = { state.isDirty },
                isInspectorVisible = { inspectorVisible },
                toggleInspector = { inspectorVisible = !inspectorVisible },
                isNodeDragEnabled = { nodeDragEnabled },
                toggleNodeDrag = { nodeDragEnabled = !nodeDragEnabled },
                zoomIn = viewerState::zoomIn,
                zoomOut = viewerState::zoomOut,
                fit = viewerState::fitGraph,
                relayout = {
                    viewerState.clearManualPositions()
                    viewerState.relayout()
                },
            ),
        )
        onDispose { publish(null) }
    }

    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(state) { runCatching { editorFocus.requestFocus() } }

    Column(
        modifier
            .fillMaxSize()
            .focusRequester(editorFocus)
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> {
                        false
                    }

                    event.isMetaPressed && event.key == Key.S -> {
                        save()
                        true
                    }

                    event.key == Key.Escape && state.connectFrom != null -> {
                        state.cancelConnecting()
                        true
                    }

                    event.key == Key.Escape && !showSettings && !confirmingClose -> {
                        close()
                        true
                    }

                    else -> {
                        false
                    }
                }
            }.focusable(),
    ) {
        EditorTopBar(
            workflow = workflow,
            isDirty = state.isDirty,
            issues = state.issues,
            runnableNode =
                state.selectedNode?.takeIf {
                    it.type == NodeType.AGENT || it.type == NodeType.SHELL
                },
            onBack = { close() },
            inspectorVisible = inspectorVisible,
            onToggleInspector = { inspectorVisible = !inspectorVisible },
            onSettings = { showSettings = true },
            onRun = onRunNode,
            onRunWorkflow = onRunWorkflow,
            onSave = { save() },
        )
        HorizontalDivider()

        Row(Modifier.fillMaxSize()) {
            NodePalette(onAdd = { state.addNode(it) })
            VerticalDivider()

            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(state, canvas, nodeDragEnabled, runningNodes, onRunNode)
                if (workflow.nodes.isEmpty()) EmptyCanvasHint()
                state.connectFrom?.let { from ->
                    ConnectBanner(
                        fromTitle = workflow.node(from)?.displayTitle ?: from,
                        onCancel = { state.cancelConnecting() },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    )
                }
                CanvasControls(
                    onZoomIn = { viewerState.zoomIn() },
                    onZoomOut = { viewerState.zoomOut() },
                    onFit = { viewerState.fitGraph() },
                    onRelayout = {
                        viewerState.clearManualPositions()
                        viewerState.relayout()
                    },
                    nodeDragEnabled = nodeDragEnabled,
                    onToggleNodeDrag = { nodeDragEnabled = !nodeDragEnabled },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
            }

            AnimatedVisibility(
                visible = inspectorVisible,
                enter =
                    expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit =
                    shrinkHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                        fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            ) {
                Row(Modifier.fillMaxHeight()) {
                    InspectorSplitter(
                        onDrag = { delta ->
                            inspectorWidth =
                                (inspectorWidth - delta).coerceIn(MinInspectorWidth, MaxInspectorWidth)
                        },
                    )
                    Surface(
                        Modifier.width(inspectorWidth).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        val selected = state.selectedNode
                        if (selected == null) {
                            InspectorPlaceholder(state.issues)
                        } else {
                            NodeInspector(
                                state = state,
                                node = selected,
                                workspace = workspace,
                                onAddRepo = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        WorkflowSettingsDialog(state, workspace, onDismiss = { showSettings = false })
    }

    if (confirmingClose) {
        AlertDialog(
            onDismissRequest = { confirmingClose = false },
            title = { Text("Save ${workflow.name}?") },
            text = { Text("Some changes haven't been written to the YAML file yet.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClose = false
                    save()
                    onClose()
                }) { Text("Save and close") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmingClose = false }) { Text("Keep editing") }
                    TextButton(onClick = {
                        confirmingClose = false
                        state.revert()
                        onClose()
                    }) { Text("Discard") }
                }
            },
        )
    }
}

@Composable
private fun Canvas(
    state: EditorState,
    canvas: EditorCanvas,
    nodeDragEnabled: Boolean,
    runningNodes: List<NodeRun>,
    onRunNode: (WorkflowNode) -> Unit,
) {
    val workflow = state.workflow
    val viewerState = canvas.viewer
    val connectFrom = state.connectFrom

    val connectable =
        remember(connectFrom, workflow.nodes.map { it.id }, workflow.edges) {
            connectFrom?.let { workflow.connectableTargets(it) }.orEmpty()
        }
    val nodesWithIssues =
        remember(state.issues) {
            state.issues
                .filter { it.severity == WorkflowIssue.Severity.ERROR }
                .mapNotNull { it.nodeId }
                .toSet()
        }

    val sources = remember(workflow.edges) { workflow.edges.map { it.from }.toSet() }

    KuiverBridge {
        KuiverViewer(
            state = viewerState,
            modifier = Modifier.fillMaxSize(),
            config = remember(nodeDragEnabled) { canvasConfig(nodeDragEnabled) },
            callbacks =
                remember(state) {
                    KuiverInteractionCallbacks(
                        onNodeClick = { node ->
                            if (!state.completeConnection(node.id)) state.select(node.id)
                        },
                        onCanvasClick = {
                            state.cancelConnecting()
                            state.select(null)
                        },
                    )
                },
            nodeContent = { kuiverNode ->

                workflow.node(kuiverNode.id)?.let { node ->
                    val isConnectSource = node.id == connectFrom
                    val isConnectable = node.id in connectable

                    val dimmed = connectFrom != null && !isConnectable && !isConnectSource
                    Box(Modifier.alpha(if (dimmed) 0.35f else 1f)) {
                        ContextMenuArea(items = { nodeMenu(state, node, onRunNode) }) {
                            WorkflowNodeCard(
                                node = node,
                                isSelected = node.id == state.selectedNodeId,
                                hasIssue = node.id in nodesWithIssues,
                                isConnectSource = isConnectSource,
                                isConnectable = isConnectable,
                                connectMode = connectFrom != null,
                                onConnectClick = { state.startConnecting(node.id) },
                                runStatus = runningNodes.firstOrNull { it.nodeId == node.id }?.status,
                            )
                        }
                        EdgePort(node.id, canvas.direction, incoming = true, visible = false)
                        EdgePort(
                            node.id,
                            canvas.direction,
                            incoming = false,
                            visible = node.id in sources,
                        )
                    }
                }
            },
            edgeContent = { edge, from, to ->

                val model = workflow.edges.firstOrNull { it.from == edge.fromId && it.to == edge.toId }
                val failure = model?.on == EdgeTrigger.FAILURE
                val label = model?.let { it.condition?.toString() ?: "failed".takeIf { _ -> failure } }

                WorkflowEdgeContent(
                    from = from,
                    to = to,
                    direction = canvas.direction,
                    label = label,
                    color =
                        when {
                            failure -> MaterialTheme.colorScheme.error
                            model?.condition != null -> NodeType.BRANCH.colors().accent
                            else -> LocalKuiverColors.current.edge
                        },
                    dashed = failure,
                )
            },
        )
    }
}

private fun nodeMenu(
    state: EditorState,
    node: WorkflowNode,
    onRunNode: (WorkflowNode) -> Unit,
): List<ContextMenuItem> =
    buildList {
        add(ContextMenuItem("Edit ${node.displayTitle}") { state.select(node.id) })

        if (node.type == NodeType.AGENT || node.type == NodeType.SHELL) {
            add(ContextMenuItem("Run this node") { onRunNode(node) })
        }
        add(ContextMenuItem("Connect from here") { state.startConnecting(node.id) })
        add(ContextMenuItem("Duplicate") { state.duplicateNode(node.id) })
        add(ContextMenuItem("Delete") { state.removeNode(node.id) })
    }

@Composable
private fun BoxScope.EdgePort(
    nodeId: String,
    direction: LayoutDirection,
    incoming: Boolean,
    visible: Boolean,
) {
    val vertical = direction == LayoutDirection.VERTICAL
    val side =
        when {
            vertical && incoming -> Alignment.TopCenter
            vertical -> Alignment.BottomCenter
            incoming -> Alignment.CenterStart
            else -> Alignment.CenterEnd
        }
    val outward = if (incoming) -PortSize / 2 else PortSize / 2
    KuiverAnchor(
        modifier =
            Modifier
                .align(side)
                .offset(x = if (vertical) 0.dp else outward, y = if (vertical) outward else 0.dp)
                .size(PortSize),
        anchorId = if (incoming) direction.entryAnchor() else direction.exitAnchor(),
        nodeId = nodeId,
    ) {
        if (visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LocalKuiverColors.current.edge, CircleShape),
            )
        }
    }
}

private val PortSize = 7.dp

@Composable
private fun EditorTopBar(
    workflow: Workflow,
    isDirty: Boolean,
    issues: List<WorkflowIssue>,
    runnableNode: WorkflowNode?,
    onBack: () -> Unit,
    inspectorVisible: Boolean,
    onToggleInspector: () -> Unit,
    onSettings: () -> Unit,
    onRun: (WorkflowNode) -> Unit,
    onRunWorkflow: () -> Unit,
    onSave: () -> Unit,
) {
    val errors = issues.count { it.severity == WorkflowIssue.Severity.ERROR }

    Surface(tonalElevation = 2.dp) {
        LocalWindowDragArea.current {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 6.dp + LocalTitleBarInset.current,
                    bottom = 6.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(ZopfIcons.Back, contentDescription = "Back to workflows", Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        workflow.name + if (isDirty) " •" else "",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val summary =
                        buildString {
                            append(count(workflow.nodes.size, "node"))
                            append(" · ")
                            append(count(workflow.edges.size, "edge"))
                            if (errors > 0) append(" · $errors to fix")
                        }
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (errors > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                IconButton(onClick = onToggleInspector) {
                    Icon(
                        if (inspectorVisible) ZopfIcons.SidePanel else ZopfIcons.SidePanelHidden,
                        contentDescription = if (inspectorVisible) "Hide inspector" else "Show inspector",
                        Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(ZopfIcons.Tune, contentDescription = "Workflow settings", Modifier.size(18.dp))
                }
                runnableNode?.let { node ->
                    TextButton(onClick = { onRun(node) }) {
                        Icon(ZopfIcons.Play, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Run ${node.displayTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                TextButton(onClick = onRunWorkflow, enabled = workflow.nodes.isNotEmpty()) {
                    Icon(ZopfIcons.Play, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run workflow")
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onSave, enabled = isDirty) {
                    Icon(ZopfIcons.Check, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

private fun count(
    n: Int,
    noun: String,
) = if (n == 1) "1 $noun" else "$n ${noun}s"

@Composable
private fun NodePalette(onAdd: (NodeType) -> Unit) {
    Column(
        Modifier.width(64.dp).fillMaxHeight().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NodeType.entries.forEach { type ->
            IconButton(onClick = { onAdd(type) }) {
                Icon(
                    type.icon,
                    contentDescription = "Add a ${type.label} node. ${type.blurb}",
                    Modifier.size(20.dp),
                    tint = type.colors().accent,
                )
            }
            Text(
                type.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InspectorSplitter(onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    val interactions = remember { MutableInteractionSource() }
    val isHovered by interactions.collectIsHoveredAsState()
    val isDragging by interactions.collectIsDraggedAsState()
    val active = isHovered || isDragging

    Box(
        Modifier
            .width(8.dp)
            .fillMaxHeight()
            .semantics { contentDescription = "Resize inspector" }
            .pointerHoverIcon(ResizeCursor)
            .hoverable(interactions)
            .draggable(
                orientation = Orientation.Horizontal,
                interactionSource = interactions,
                state = rememberDraggableState { delta -> onDrag(with(density) { delta.toDp() }) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(
            thickness = if (active) 2.dp else 1.dp,
            color = if (active) MaterialTheme.colorScheme.primary else DividerDefaults.color,
        )
    }
}

private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

@Composable
private fun ConnectBanner(
    fromTitle: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ZopfIcons.Link, contentDescription = null, Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Connecting from $fromTitle. Click the next node.",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCancel) {
                Icon(ZopfIcons.Clear, contentDescription = "Cancel", Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun CanvasControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onRelayout: () -> Unit,
    nodeDragEnabled: Boolean,
    onToggleNodeDrag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        IconButton(onClick = onZoomOut) {
            Icon(ZopfIcons.ZoomOut, contentDescription = "Zoom out", Modifier.size(18.dp))
        }
        IconButton(onClick = onZoomIn) {
            Icon(ZopfIcons.ZoomIn, contentDescription = "Zoom in", Modifier.size(18.dp))
        }
        IconButton(onClick = onFit) {
            Icon(ZopfIcons.FitToScreen, contentDescription = "Fit to window", Modifier.size(18.dp))
        }
        IconButton(onClick = onRelayout) {
            Icon(ZopfIcons.Refresh, contentDescription = "Lay out again", Modifier.size(18.dp))
        }

        FilledIconToggleButton(checked = nodeDragEnabled, onCheckedChange = { onToggleNodeDrag() }) {
            Icon(
                ZopfIcons.OpenWith,
                contentDescription = if (nodeDragEnabled) "Stop moving nodes" else "Move nodes",
                Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyCanvasHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Add a node from the palette on the left.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InspectorPlaceholder(issues: List<WorkflowIssue>) {
    val workflowIssues = issues.filter { it.nodeId == null }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Select a node to edit it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (workflowIssues.isNotEmpty()) {
            Gap()
            SectionLabel("Needs attention")
            Gap(4)
            workflowIssues.forEach { issue ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Icon(ZopfIcons.Warning, contentDescription = null, Modifier.size(14.dp), tint = issue.tint())
                    Spacer(Modifier.width(8.dp))
                    Text(issue.message, style = MaterialTheme.typography.labelSmall, color = issue.tint())
                }
            }
        }
    }
}

@Preview
@Composable
private fun CanvasPreview() {
    val state = PreviewFixtures.editorState()
    state.select("plan")
    val canvas = rememberEditorCanvas(state.workflow)
    val runningNodes = listOf(PreviewFixtures.nodeRun(status = RunStatus.RUNNING, nodeId = "tests"))
    ZopfTheme {
        Surface {
            Box(Modifier.size(760.dp, 420.dp)) {
                Canvas(state, canvas, nodeDragEnabled = false, runningNodes = runningNodes, onRunNode = {})
            }
        }
    }
}

@Preview
@Composable
private fun EditorTopBarPreview() {
    val workflow = PreviewFixtures.workflow()
    ZopfTheme {
        Surface {
            EditorTopBar(
                workflow = workflow,
                isDirty = true,
                issues = workflow.validate(),
                runnableNode = workflow.node("plan"),
                onBack = {},
                inspectorVisible = true,
                onToggleInspector = {},
                onSettings = {},
                onRun = {},
                onRunWorkflow = {},
                onSave = {},
            )
        }
    }
}
