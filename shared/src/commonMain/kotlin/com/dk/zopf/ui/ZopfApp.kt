package com.dk.zopf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dk.zopf.ui.connectors.ConnectorsScreen
import com.dk.zopf.ui.editor.GraphEditorScreen
import com.dk.zopf.ui.runs.RunConsole
import com.dk.zopf.ui.runs.RunsScreen
import com.dk.zopf.ui.settings.SettingsScreen
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.isDark
import com.dk.zopf.ui.workflows.WorkflowListScreen
import com.dk.zopf.ui.workspace.WorkspaceSwitcher

private val RunPanelHeight = 300.dp

private fun subtitleFor(state: AppState): String =
    when (state.screen) {
        Screen.WORKFLOWS -> {
            val listing = state.listing
            when {
                state.activeWorkspace == null -> {
                    "No workspace open"
                }

                listing.broken.isEmpty() -> {
                    count(listing.workflows.size, "workflow")
                }

                else -> {
                    count(listing.workflows.size, "workflow") +
                        " · ${count(listing.broken.size, "file")} that can't be read"
                }
            }
        }

        Screen.RUNS -> {
            val active = state.runs.activeCount
            val total = state.runs.runs.size
            when {
                total == 0 -> "Nothing has run yet"
                active == 0 -> "${count(total, "run")}, none active"
                else -> "$active of ${count(total, "run")} active"
            }
        }

        Screen.CONNECTORS -> {
            val listing = state.connectors
            when {
                state.activeWorkspace == null -> {
                    "No workspace open"
                }

                listing.broken.isEmpty() -> {
                    count(listing.connectors.size, "connector")
                }

                else -> {
                    count(listing.connectors.size, "connector") +
                        " · ${count(listing.broken.size, "broken")}"
                }
            }
        }

        Screen.SETTINGS -> {
            "Applies to every run on this machine, in any workspace"
        }
    }

@Composable
private fun TopBar(state: AppState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.zIndex(1f),
    ) {
        LocalWindowDragArea.current {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.screen.label,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                    Text(
                        subtitleFor(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(16.dp))
                WorkspaceSwitcher(
                    workspaces = state.workspaces,
                    active = state.activeWorkspace,
                    onSelect = state::selectWorkspace,
                    onAdd = state::addWorkspace,
                    onForget = state::forgetWorkspace,
                    onRefresh = state::refreshWorkspaces,
                )
            }
        }
    }
}

private fun count(
    n: Int,
    noun: String,
) = if (n == 1) "1 $noun" else "$n ${noun}s"

@Composable
fun ZopfApp(state: AppState = remember { AppState() }) {
    LaunchedEffect(Unit) { state.start() }

    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            state.message = null
        }
    }

    val editor = state.editing
    LaunchedEffect(editor?.message) {
        editor?.message?.let {
            snackbar.showSnackbar(it)
            editor.message = null
        }
    }

    ZopfTheme(
        darkTheme =
            state.settings.current.theme
                .isDark(),
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->

            if (editor != null) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    val workflowRun = state.runs.runForEditor(editor.workflow.name)
                    GraphEditorScreen(
                        state = editor,
                        workspace = state.activeWorkspace?.workspace,
                        onClose = state::closeEditor,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onRunNode = state::runNode,
                        onRunWorkflow = { state.runWorkflow() },
                        runningNodes = workflowRun?.nodes.orEmpty(),
                        onCommands = { state.editorCommands = it },
                    )

                    val node = workflowRun?.let { state.runs.selectedNode }
                    if (state.showRunPanel && workflowRun != null && node != null) {
                        HorizontalDivider()
                        RunConsole(
                            run = node,
                            onStop = { state.runs.stop(workflowRun) },
                            onTakeOver = { state.runs.takeOver(node) },
                            onSend = { state.runs.send(node, it) },
                            onFinish = { state.runs.finishInput(node) },
                            onApprove = { state.runs.approve(node, it) },
                            onAnswer = { state.runs.answer(node, it) },
                            onDecide = { allow, rest -> state.runs.decide(node, allow, rest) },
                            onClose = { state.showRunPanel = false },
                            modifier = Modifier.fillMaxWidth().height(RunPanelHeight),
                            terminalApp = state.runs.terminalApp,
                        )
                    }
                }
                return@Scaffold
            }

            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.height(LocalTitleBarInset.current))
                    NavigationRailItem(
                        selected = state.screen == Screen.WORKFLOWS,
                        onClick = { state.screen = Screen.WORKFLOWS },
                        icon = { Icon(ZopfIcons.Workflow, contentDescription = null) },
                        label = { Text(Screen.WORKFLOWS.label) },
                    )
                    NavigationRailItem(
                        selected = state.screen == Screen.RUNS,
                        onClick = { state.screen = Screen.RUNS },
                        icon = {
                            val active = state.runs.activeCount
                            BadgedBox(
                                badge = { if (active > 0) Badge { Text("$active") } },
                            ) {
                                Icon(ZopfIcons.Play, contentDescription = null)
                            }
                        },
                        label = { Text(Screen.RUNS.label) },
                    )
                    NavigationRailItem(
                        selected = state.screen == Screen.CONNECTORS,
                        onClick = { state.screen = Screen.CONNECTORS },
                        icon = { Icon(ZopfIcons.List, contentDescription = null) },
                        label = { Text(Screen.CONNECTORS.label) },
                    )

                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = state.screen == Screen.SETTINGS,
                        onClick = { state.screen = Screen.SETTINGS },
                        icon = { Icon(ZopfIcons.Tune, contentDescription = null) },
                        label = { Text(Screen.SETTINGS.label) },
                    )
                    Spacer(Modifier.height(12.dp))
                }

                VerticalDivider()

                Column(Modifier.fillMaxSize()) {
                    TopBar(state)
                    HorizontalDivider()
                    when (state.screen) {
                        Screen.WORKFLOWS -> {
                            WorkflowListScreen(
                                workspace = state.activeWorkspace,
                                listing = state.listing,
                                selected = state.selectedWorkflow,
                                onOpen = state::openEditor,
                                onRun = { state.runWorkflow(it) },
                                onCreate = state::createWorkflow,
                                onRename = state::renameWorkflow,
                                onDelete = state::deleteWorkflow,
                                onReveal = state::reveal,
                                createRequested = state.dialogRequest == DialogRequest.NEW_WORKFLOW,
                                onCreateRequestHandled = { state.dialogRequest = null },
                            )
                        }

                        Screen.RUNS -> {
                            RunsScreen(state.runs, onMessage = { state.message = it })
                        }

                        Screen.CONNECTORS -> {
                            ConnectorsScreen(
                                workspace = state.activeWorkspace,
                                listing = state.connectors,
                                onCreate = state::createConnector,
                                onChange = state::changeConnector,
                                onFixBroken = state::fixConnector,
                                onDelete = state::deleteConnector,
                                onReveal = state::reveal,
                                onRefresh = state::refreshConnectors,
                                createRequested = state.dialogRequest == DialogRequest.NEW_CONNECTOR,
                                onCreateRequestHandled = { state.dialogRequest = null },
                            )
                        }

                        Screen.SETTINGS -> {
                            SettingsScreen(
                                settings = state.settings.current,
                                onChange = state::updateSettings,
                                update = state.update,
                                onOpenRelease = state::openRelease,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ZopfAppPreview() {
    ZopfApp()
}
