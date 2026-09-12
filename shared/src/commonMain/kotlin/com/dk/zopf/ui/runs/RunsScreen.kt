package com.dk.zopf.ui.runs

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dk.zopf.runtime.run.NodeRun
import com.dk.zopf.runtime.run.NodeRunState
import com.dk.zopf.runtime.run.RunRegistry
import com.dk.zopf.runtime.run.RunStatus
import com.dk.zopf.runtime.run.WorkflowRun
import com.dk.zopf.runtime.run.WorkflowRunState
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.util.format
import java.time.Instant

private val RunListWidth = 280.dp

@Composable
fun RunsScreen(
    registry: RunRegistry,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
) {
    val open by registry.state.collectAsState()
    val runs = open.runs

    val selectedRun = open.selected
    val selectedState = selectedRun?.state?.collectAsState()?.value ?: WorkflowRunState()
    val shownNode = open.shownNode

    if (runs.isEmpty()) {
        Box(modifier.fillMaxSize().padding(48.dp)) {
            Text(
                "Open a workflow and press Run, or run one node from the editor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Row(modifier.fillMaxSize()) {
        Surface(
            Modifier.width(RunListWidth).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                runs.forEach { run ->
                    val isSelected = run.id == selectedRun?.id
                    item(key = run.id) {
                        ContextMenuArea(
                            items = {
                                val handover = run.nodes.firstOrNull { it.canTakeOver && it.status.isActive }
                                buildList {
                                    add(ContextMenuItem("Show") { registry.select(run) })
                                    if (run.isElsewhere) return@buildList
                                    if (run.isActive) {
                                        add(ContextMenuItem("Stop") { registry.stop(run) })
                                        handover?.let { node ->
                                            add(
                                                ContextMenuItem("Take over in ${registry.terminalApp}") {
                                                    registry.takeOver(node)
                                                },
                                            )
                                        }
                                    } else {
                                        add(ContextMenuItem("Clear") { registry.remove(run) })
                                        if (run.archiveDir != null) {
                                            add(
                                                ContextMenuItem("Delete from disk") {
                                                    registry
                                                        .forget(run)
                                                        .onFailure { onMessage(it.message ?: "Couldn't delete that run") }
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        ) {
                            RunRow(
                                run = run,
                                isSelected = isSelected,
                                onClick = { registry.select(run) },
                                onStop = { registry.stop(run) },
                                onRemove = { registry.remove(run) },
                            )
                        }
                    }

                    if (isSelected && selectedState.nodes.size > 1) {
                        items(selectedState.nodes.size, key = { selectedState.nodes[it].id }) { index ->
                            val node = selectedState.nodes[index]
                            val retry =
                                {
                                    registry
                                        .retry(run, node.nodeId)
                                        .onSuccess { retried -> registry.select(retried) }
                                        .onFailure { onMessage(it.message ?: "Couldn't retry that run") }
                                    Unit
                                }.takeIf {
                                    !selectedState.isActive && selectedState.of(node).status.isFinished && run.workflow != null
                                }
                            ContextMenuArea(
                                items = {
                                    buildList {
                                        add(ContextMenuItem("Show") { registry.select(run, node) })
                                        retry?.let {
                                            add(ContextMenuItem("Retry from this node", it))
                                        }
                                        if (node.canTakeOver && node.status.isActive && !run.isElsewhere) {
                                            add(
                                                ContextMenuItem("Take over in ${registry.terminalApp}") {
                                                    registry.takeOver(node)
                                                },
                                            )
                                        }
                                    }
                                },
                            ) {
                                NodeRow(
                                    node = node,
                                    state = selectedState.of(node),
                                    isSelected = node.id == shownNode?.id,
                                    onClick = { registry.select(run, node) },
                                    onRetry = retry,
                                )
                            }
                        }
                    }
                }
            }
        }
        VerticalDivider()

        val run = selectedRun
        val node = shownNode
        if (run == null || node == null) {
            Box(Modifier.fillMaxSize().padding(32.dp)) {
                Text(
                    "Select a run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            RunConsole(
                run = node,
                onStop = { registry.stop(run) },
                onTakeOver = { registry.takeOver(node) },
                isElsewhere = run.isElsewhere(selectedState),
                onSend = { registry.send(node, it) },
                onFinish = { registry.finishInput(node) },
                onApprove = { registry.approve(node, it) },
                onAnswer = { registry.answer(node, it) },
                onDecide = { allow, rest -> registry.decide(node, allow, rest) },
                modifier = Modifier.fillMaxSize(),
                terminalApp = registry.terminalApp,
            )
        }
    }
}

@Composable
private fun RunRow(
    run: WorkflowRun,
    isSelected: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    val state by run.state.collectAsState()
    val background =
        if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow

    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state.status, size = 8)
        Spacer(Modifier.width(10.dp))
        val now = Instant.now()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.workflowName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    format(run.startedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                "${state.summary()} · ${format(state.elapsed(run.startedAt, now))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            run.isElsewhere(state) -> Unit
            state.isActive ->
                IconButton(onClick = onStop) {
                    Icon(ZopfIcons.Stop, contentDescription = "Stop run", Modifier.size(12.dp))
                }

            else ->
                IconButton(onClick = onRemove) {
                    Icon(ZopfIcons.Clear, contentDescription = "Clear run", Modifier.size(14.dp))
                }
        }
    }
}

@Composable
private fun NodeRow(
    node: NodeRun,
    state: NodeRunState,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ).clickable(onClick = onClick)
            .padding(start = 34.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state.status, size = 6)
        Spacer(Modifier.width(10.dp))
        Text(
            node.nodeTitle,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (state.status == RunStatus.SKIPPED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            state.status.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        onRetry?.let {
            IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                Icon(
                    ZopfIcons.Refresh,
                    contentDescription = "Retry from ${node.nodeTitle}",
                    Modifier.size(12.dp),
                )
            }
        }
    }
}
