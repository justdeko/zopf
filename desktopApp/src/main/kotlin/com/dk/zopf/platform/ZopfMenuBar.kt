package com.dk.zopf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.dk.zopf.util.Strings

@Composable
fun FrameWindowScope.ZopfMenuBar(
    app: AppState,
    placement: WindowPlacement,
    onHideWindow: () -> Unit,
    onMinimize: () -> Unit,
    onZoom: () -> Unit,
) {
    MenuBar {
        Menu(Strings.MenuBar.FILE) {
            FileMenu(app, onHideWindow)
        }
        Menu(Strings.MenuBar.WORKFLOW) {
            WorkflowMenu(app)
        }
        Menu(Strings.MenuBar.VIEW) {
            ViewMenu(app)
        }
        Menu(Strings.MenuBar.WORKSPACE) {
            WorkspaceMenu(app)
        }
        Menu(Strings.MenuBar.WINDOW) {
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
        Strings.MenuBar.MINIMIZE,
        shortcut = KeyShortcut(Key.M, meta = true),
        enabled = placement != WindowPlacement.Fullscreen,
        onClick = onMinimize,
    )
    Item(
        Strings.MenuBar.ZOOM,
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
        Strings.MenuBar.NEW_WORKFLOW,
        shortcut = KeyShortcut(Key.N, meta = true),
        enabled = editor == null && workspace?.isOnline == true,
    ) {
        app.screen = Screen.WORKFLOWS
        app.dialogRequest = DialogRequest.NEW_WORKFLOW
    }
    Item(
        Strings.MenuBar.NEW_CONNECTOR,
        shortcut = KeyShortcut(Key.N, meta = true, shift = true),
        enabled = editor == null && workspace != null,
    ) {
        app.screen = Screen.CONNECTORS
        app.dialogRequest = DialogRequest.NEW_CONNECTOR
    }
    Separator()

    Item(
        Strings.Actions.SAVE,
        shortcut = KeyShortcut(Key.S, meta = true),
        enabled = editor?.isDirty() == true,
    ) { editor?.save?.invoke() }
    Item(
        Strings.Actions.SHOW_IN_FINDER,
        shortcut = KeyShortcut(Key.R, meta = true, alt = true),
        enabled = app.editing != null || app.selectedWorkflow != null,
    ) { app.revealCurrentWorkflow() }
    Separator()

    Item(Strings.MenuBar.CLOSE_WINDOW, shortcut = KeyShortcut(Key.W, meta = true)) { onHideWindow() }
}

@Composable
private fun MenuScope.WorkflowMenu(app: AppState) {
    val editing = app.editing
    val activity by app.runs.activity.collectAsState()
    val selectedRun =
        app.runs.state
            .collectAsState()
            .value.selected
    val selectedState = selectedRun?.state?.collectAsState()?.value

    Item(
        Strings.MenuBar.RUN_WORKFLOW,
        shortcut = KeyShortcut(Key.R, meta = true),
        enabled = app.runnableWorkflow != null,
    ) { app.runWorkflow() }

    val runnableNode =
        editing?.selectedNode?.takeIf {
            it.type == NodeType.AGENT || it.type == NodeType.SHELL
        }
    Item(
        Strings.MenuBar.RUN_SELECTED_NODE,
        shortcut = KeyShortcut(Key.R, meta = true, shift = true),
        enabled = runnableNode != null,
    ) { runnableNode?.let(app::runNode) }
    Separator()
    Item(
        Strings.MenuBar.STOP_RUN,
        shortcut = KeyShortcut(Key.Period, meta = true),
        enabled = selectedState?.isActive == true,
    ) { selectedRun?.let(app.runs::stop) }
    Item(
        Strings.MenuBar.STOP_EVERYTHING,
        shortcut = KeyShortcut(Key.Period, meta = true, alt = true),
        enabled = activity.active > 0,
    ) { app.runs.stopAll() }
    Separator()

    Menu(Strings.MenuBar.ADD_NODE, enabled = editing != null) {
        NodeType.entries.forEach { type ->
            Item(type.label) { editing?.addNode(type) }
        }
    }
    val node = editing?.selectedNodeId
    Item(
        Strings.MenuBar.DUPLICATE_NODE,
        shortcut = KeyShortcut(Key.D, meta = true),
        enabled = node != null,
    ) { node?.let { editing.duplicateNode(it) } }
    Item(
        Strings.MenuBar.CONNECT_FROM_NODE,
        shortcut = KeyShortcut(Key.L, meta = true),
        enabled = node != null,
    ) { node?.let { editing.startConnecting(it) } }

    Item(
        Strings.MenuBar.DELETE_NODE,
        shortcut = KeyShortcut(Key.Backspace, meta = true),
        enabled = node != null,
    ) { node?.let { editing.removeNode(it) } }
    Separator()
    Item(
        Strings.MenuBar.WORKFLOW_SETTINGS,
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
        Strings.MenuBar.INSPECTOR,
        checked = editor?.isInspectorVisible() == true,
        shortcut = KeyShortcut(Key.I, meta = true),
        enabled = editor != null,
    ) { editor?.toggleInspector?.invoke() }

    CheckboxItem(
        Strings.MenuBar.MOVE_NODES,
        checked = editor?.isNodeDragEnabled() == true,
        shortcut = KeyShortcut(Key.M, meta = true, shift = true),
        enabled = editor != null,
    ) { editor?.toggleNodeDrag?.invoke() }
    Separator()
    Item(Strings.MenuBar.ZOOM_IN, shortcut = KeyShortcut(Key.Equals, meta = true), enabled = editor != null) {
        editor?.zoomIn?.invoke()
    }
    Item(Strings.MenuBar.ZOOM_OUT, shortcut = KeyShortcut(Key.Minus, meta = true), enabled = editor != null) {
        editor?.zoomOut?.invoke()
    }
    Item(Strings.MenuBar.FIT_TO_WINDOW, shortcut = KeyShortcut(Key.Zero, meta = true), enabled = editor != null) {
        editor?.fit?.invoke()
    }

    Item(
        Strings.MenuBar.LAY_OUT_AGAIN,
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
            if (workspace.isOnline) workspace.displayName else Strings.MenuBar.workspaceUnavailable(workspace.displayName),
            selected = workspace.path == app.activeWorkspace?.path,
            enabled = editor == null,
        ) { app.selectWorkspace(workspace.path) }
    }
    if (app.workspaces.isNotEmpty()) Separator()
    Item(
        Strings.MenuBar.OPEN_WORKSPACE,
        shortcut = KeyShortcut(Key.O, meta = true, shift = true),
        enabled = editor == null,
    ) { app.openWorkspace() }

    Item(Strings.MenuBar.REFRESH_WORKSPACES) { app.refreshWorkspaces() }
    Item(Strings.MenuBar.REFRESH_CONNECTORS, enabled = app.activeWorkspace != null) { app.refreshConnectors() }
}
