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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dk.zopf.runtime.run.NodeRun
import com.dk.zopf.runtime.run.NodeRunState
import com.dk.zopf.runtime.run.RunRegistry
import com.dk.zopf.runtime.run.RunStatus
import com.dk.zopf.runtime.run.WorkflowRun
import com.dk.zopf.runtime.run.WorkflowRunState
import com.dk.zopf.ui.EmptyState
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.util.Strings
import com.dk.zopf.util.format
import java.time.Instant

private val RunListWidth = 280.dp
private val TimelineStart = 28.dp
private val TimelineSlot = 14.dp
private val TimelineGap = 6.dp
private val TimelineStroke = 1.5.dp

private fun Modifier.timeline(
    color: Color,
    isFirst: Boolean,
    isLast: Boolean,
): Modifier =
    drawBehind {
        val x = (TimelineStart + TimelineSlot / 2).toPx()
        val middle = size.height / 2
        val gap = TimelineGap.toPx()
        val stroke = TimelineStroke.toPx()
        if (!isFirst) drawLine(color, Offset(x, 0f), Offset(x, middle - gap), stroke)
        if (!isLast) drawLine(color, Offset(x, middle + gap), Offset(x, size.height), stroke)
    }

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
        EmptyState(Strings.Runs.EMPTY_TITLE, Strings.Runs.EMPTY, ZopfIcons.Play, modifier)
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
                                    add(ContextMenuItem(Strings.Actions.SHOW) { registry.select(run) })
                                    if (run.isElsewhere) return@buildList
                                    if (run.isActive) {
                                        add(ContextMenuItem(Strings.Actions.STOP) { registry.stop(run) })
                                        handover?.let { node ->
                                            add(
                                                ContextMenuItem(Strings.Actions.takeOverIn(registry.terminalApp)) {
                                                    registry.takeOver(node)
                                                },
                                            )
                                        }
                                    } else {
                                        add(ContextMenuItem(Strings.Runs.CLEAR) { registry.remove(run) })
                                        if (run.archiveDir != null) {
                                            add(
                                                ContextMenuItem(Strings.Runs.DELETE_FROM_DISK) {
                                                    registry
                                                        .forget(run)
                                                        .onFailure { onMessage(it.message ?: Strings.Runs.COULDNT_DELETE) }
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
                                        .onFailure { onMessage(it.message ?: Strings.Runs.COULDNT_RETRY) }
                                    Unit
                                }.takeIf {
                                    !selectedState.isActive && selectedState.of(node).status.isFinished && registry.canRetry(run)
                                }
                            ContextMenuArea(
                                items = {
                                    buildList {
                                        add(ContextMenuItem(Strings.Actions.SHOW) { registry.select(run, node) })
                                        retry?.let {
                                            add(ContextMenuItem(Strings.Runs.RETRY_FROM_NODE, it))
                                        }
                                        if (node.canTakeOver && node.status.isActive && !run.isElsewhere) {
                                            add(
                                                ContextMenuItem(Strings.Actions.takeOverIn(registry.terminalApp)) {
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
                                    isFirst = index == 0,
                                    isLast = index == selectedState.nodes.lastIndex,
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
            EmptyState(Strings.Runs.SELECT_ONE, Strings.Runs.SELECT_ONE_DETAIL, ZopfIcons.List)
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

    val elsewhere = run.isElsewhere(state)
    Box(Modifier.fillMaxWidth().background(background).clickable(onClick = onClick)) {
        RunRowContent(run, state, elsewhere, onStop, onRemove)
        if (state.isActive && !elsewhere) {
            ThinWavyBar(
                state.status.color(),
                Modifier.align(Alignment.BottomStart).padding(start = 30.dp, end = 16.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun RunRowContent(
    run: WorkflowRun,
    state: WorkflowRunState,
    elsewhere: Boolean,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
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
                "${state.summary()} · ${format(state.elapsed(run.startedAt, now))}".withStatusTint(state.status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            elsewhere -> Unit
            state.isActive ->
                IconButton(onClick = onStop) {
                    Icon(ZopfIcons.Stop, contentDescription = Strings.Runs.STOP_RUN, Modifier.size(12.dp))
                }

            else ->
                IconButton(onClick = onRemove) {
                    Icon(ZopfIcons.Clear, contentDescription = Strings.Runs.CLEAR_RUN, Modifier.size(14.dp))
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
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
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
            .timeline(line, isFirst, isLast)
            .padding(start = TimelineStart, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(TimelineSlot), contentAlignment = Alignment.Center) {
            StatusDot(state.status, size = 7)
        }
        Spacer(Modifier.width(6.dp))
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
            color = if (state.status == RunStatus.QUEUED) MaterialTheme.colorScheme.onSurfaceVariant else state.status.color(),
            maxLines = 1,
        )
        onRetry?.let {
            IconButton(onClick = it, modifier = Modifier.size(24.dp)) {
                Icon(
                    ZopfIcons.Refresh,
                    contentDescription = Strings.Runs.retryFrom(node.nodeTitle),
                    Modifier.size(12.dp),
                )
            }
        }
    }
}
