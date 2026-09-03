package com.dk.zopf.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.WindowPlacement
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.label
import com.dk.zopf.ui.AppState
import com.dk.zopf.ui.DialogRequest
import com.dk.zopf.ui.Screen

@Composable
fun FrameWindowScope.ZopfMenuBar(
    app: AppState,
    placement: WindowPlacement,
    onHideWindow: () -> Unit,
    onMinimize: () -> Unit,
    onZoom: () -> Unit,
) {
    MenuBar {
        Menu("File") {
            FileMenu(app, onHideWindow)
        }
        Menu("Workflow") {
            WorkflowMenu(app)
        }
        Menu("View") {
            ViewMenu(app)
        }
        Menu("Workspace") {
            WorkspaceMenu(app)
        }
        Menu("Window") {
            WindowMenu(placement, onMinimize, onZoom)
        }
    }
}

@Composable
private fun MenuScope.WindowMenu(
    placement: WindowPlacement,
    onMinimize: () -> Unit,
    onZoom: () -> Unit,
) {
    Item(
        "Minimize",
        shortcut = KeyShortcut(Key.M, meta = true),
        enabled = placement != WindowPlacement.Fullscreen,
        onClick = onMinimize,
    )
    Item(
        "Zoom",
        enabled = placement != WindowPlacement.Fullscreen,
        onClick = onZoom,
    )
}

@Composable
private fun MenuScope.FileMenu(
    app: AppState,
    onHideWindow: () -> Unit,
) {
    val editor = app.editorCommands
    val workspace = app.activeWorkspace

    Item(
        "New Workflow…",
        shortcut = KeyShortcut(Key.N, meta = true),
        enabled = editor == null && workspace?.isOnline == true,
    ) {
        app.screen = Screen.WORKFLOWS
        app.dialogRequest = DialogRequest.NEW_WORKFLOW
    }
    Item(
        "New Connector…",
        shortcut = KeyShortcut(Key.N, meta = true, shift = true),
        enabled = editor == null && workspace != null,
    ) {
        app.screen = Screen.CONNECTORS
        app.dialogRequest = DialogRequest.NEW_CONNECTOR
    }
    Separator()

    Item(
        "Save",
        shortcut = KeyShortcut(Key.S, meta = true),
        enabled = editor?.isDirty() == true,
    ) { editor?.save?.invoke() }
    Item(
        "Show in Finder",
        shortcut = KeyShortcut(Key.R, meta = true, alt = true),
        enabled = app.editing != null || app.selectedWorkflow != null,
    ) { app.revealCurrentWorkflow() }
    Separator()

    Item("Close Window", shortcut = KeyShortcut(Key.W, meta = true)) { onHideWindow() }
}

@Composable
private fun MenuScope.WorkflowMenu(app: AppState) {
    val editing = app.editing
    val selectedRun = app.runs.selectedRun

    Item(
        "Run Workflow",
        shortcut = KeyShortcut(Key.R, meta = true),
        enabled = app.runnableWorkflow != null,
    ) { app.runWorkflow() }

    val runnableNode =
        editing?.selectedNode?.takeIf {
            it.type == NodeType.AGENT || it.type == NodeType.SHELL
        }
    Item(
        "Run Selected Node",
        shortcut = KeyShortcut(Key.R, meta = true, shift = true),
        enabled = runnableNode != null,
    ) { runnableNode?.let(app::runNode) }
    Separator()
    Item(
        "Stop Run",
        shortcut = KeyShortcut(Key.Period, meta = true),
        enabled = selectedRun?.isActive == true,
    ) { selectedRun?.let(app.runs::stop) }
    Item(
        "Stop Everything",
        shortcut = KeyShortcut(Key.Period, meta = true, alt = true),
        enabled = app.runs.activeCount > 0,
    ) { app.runs.stopAll() }
    Separator()

    Menu("Add Node", enabled = editing != null) {
        NodeType.entries.forEach { type ->
            Item(type.label) { editing?.addNode(type) }
        }
    }
    val node = editing?.selectedNodeId
    Item(
        "Duplicate Node",
        shortcut = KeyShortcut(Key.D, meta = true),
        enabled = node != null,
    ) { node?.let { editing.duplicateNode(it) } }
    Item(
        "Connect From Node",
        shortcut = KeyShortcut(Key.L, meta = true),
        enabled = node != null,
    ) { node?.let { editing.startConnecting(it) } }

    Item(
        "Delete Node",
        shortcut = KeyShortcut(Key.Backspace, meta = true),
        enabled = node != null,
    ) { node?.let { editing.removeNode(it) } }
    Separator()
    Item(
        "Workflow Settings…",
        shortcut = KeyShortcut(Key.Comma, meta = true, alt = true),
        enabled = app.editorCommands != null,
    ) { app.editorCommands?.openSettings?.invoke() }
}

@Composable
private fun MenuScope.ViewMenu(app: AppState) {
    val editor = app.editorCommands

    Screen.entries.forEach { screen ->
        RadioButtonItem(
            screen.label,
            selected = app.screen == screen && app.editing == null,
            shortcut = screen.shortcut,
            enabled = app.editing == null,
        ) { app.screen = screen }
    }
    Separator()
    CheckboxItem(
        "Inspector",
        checked = editor?.isInspectorVisible() == true,
        shortcut = KeyShortcut(Key.I, meta = true),
        enabled = editor != null,
    ) { editor?.toggleInspector?.invoke() }

    CheckboxItem(
        "Move Nodes",
        checked = editor?.isNodeDragEnabled() == true,
        shortcut = KeyShortcut(Key.M, meta = true, shift = true),
        enabled = editor != null,
    ) { editor?.toggleNodeDrag?.invoke() }
    Separator()
    Item("Zoom In", shortcut = KeyShortcut(Key.Equals, meta = true), enabled = editor != null) {
        editor?.zoomIn?.invoke()
    }
    Item("Zoom Out", shortcut = KeyShortcut(Key.Minus, meta = true), enabled = editor != null) {
        editor?.zoomOut?.invoke()
    }
    Item("Fit to Window", shortcut = KeyShortcut(Key.Zero, meta = true), enabled = editor != null) {
        editor?.fit?.invoke()
    }

    Item(
        "Lay Out Again",
        shortcut = KeyShortcut(Key.Zero, meta = true, alt = true),
        enabled = editor != null,
    ) { editor?.relayout?.invoke() }
}

private val Screen.shortcut: KeyShortcut
    get() =
        when (this) {
            Screen.WORKFLOWS -> KeyShortcut(Key.One, meta = true)
            Screen.RUNS -> KeyShortcut(Key.Two, meta = true)
            Screen.CONNECTORS -> KeyShortcut(Key.Three, meta = true)
            Screen.SETTINGS -> KeyShortcut(Key.Comma, meta = true)
        }

@Composable
private fun MenuScope.WorkspaceMenu(app: AppState) {
    val editor = app.editorCommands

    app.workspaces.forEach { workspace ->
        RadioButtonItem(
            if (workspace.isOnline) workspace.displayName else "${workspace.displayName} (unavailable)",
            selected = workspace.path == app.activeWorkspace?.path,
            enabled = editor == null,
        ) { app.selectWorkspace(workspace.path) }
    }
    if (app.workspaces.isNotEmpty()) Separator()
    Item(
        "Open Workspace…",
        shortcut = KeyShortcut(Key.O, meta = true, shift = true),
        enabled = editor == null,
    ) { app.openWorkspace() }

    Item("Refresh Workspaces") { app.refreshWorkspaces() }
    Item("Refresh Connectors", enabled = app.activeWorkspace != null) { app.refreshConnectors() }
}
