package com.dk.zopf.ui.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.runtime.ConsoleEntry
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.PendingPermission
import com.dk.zopf.runtime.PendingQuestion
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.format
import com.dk.zopf.runtime.spend
import com.dk.zopf.store.DEFAULT_TERMINAL_APP
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private const val CollapsedPromptLines = 12

@Composable
fun RunConsole(
    run: NodeRun,
    onStop: () -> Unit,
    onTakeOver: () -> Unit,
    onSend: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onApprove: (Boolean) -> Unit = {},
    onAnswer: (String?) -> Unit = {},
    onDecide: (allow: Boolean, forRestOfRun: Boolean) -> Unit = { _, _ -> },
    onClose: (() -> Unit)? = null,
    terminalApp: String = DEFAULT_TERMINAL_APP,
) {
    Column(modifier.fillMaxSize()) {
        RunHeader(run, onStop, onTakeOver, onClose, terminalApp)
        HorizontalDivider()
        Transcript(run, Modifier.weight(1f))
        when {
            run.isAwaitingPermission -> {
                HorizontalDivider()
                PermissionBar(run.pendingPermission, onDecide)
            }

            run.isAwaitingApproval -> {
                HorizontalDivider()
                GateBar(title = run.nodeTitle, onApprove = onApprove)
            }

            run.isAwaitingInput -> {
                HorizontalDivider()
                InputBar(run.pendingQuestion, onAnswer)
            }

            run.status == RunStatus.WAITING && run.canFollowUp -> {
                HorizontalDivider()
                FollowUpBar(onSend = onSend, onFinish = onFinish)
            }
        }
    }
}

@Composable
private fun RunHeader(
    run: NodeRun,
    onStop: () -> Unit,
    onTakeOver: () -> Unit,
    onClose: (() -> Unit)?,
    terminalApp: String,
) {
    var now by remember(run.id) { mutableStateOf(Instant.now()) }
    LaunchedEffect(run.id, run.status) {
        while (run.status.isActive) {
            delay(1.seconds)
            now = Instant.now()
        }
    }

    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(run.status)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    run.nodeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(run.workflowName)
                        append(" · ")
                        append(run.status.label)
                        append(" · ")
                        append(format(run.elapsed(now)))
                        spend(run.costUsd, run.tokens)?.let { append(" · $it") }
                        run.exitCode?.takeIf { it != 0 }?.let { append(" · exit $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (run.canTakeOver) {
                TextButton(onClick = onTakeOver) {
                    Icon(ZopfIcons.Terminal, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))

                    Text("Take over in $terminalApp")
                }
            }
            if (run.status.isActive) {
                TextButton(onClick = onStop) {
                    Icon(ZopfIcons.Stop, contentDescription = null, Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            }
            onClose?.let {
                IconButton(onClick = it) {
                    Icon(ZopfIcons.Clear, contentDescription = "Close console", Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Transcript(
    run: NodeRun,
    modifier: Modifier = Modifier,
) {
    val listState = remember(run.id) { LazyListState() }
    val scope = rememberCoroutineScope()

    fun jumpTo(index: Int) {
        scope.launch { listState.scrollToItem(index) }
    }

    val atBottom by remember(listState) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(run.entries.size) {
        if (atBottom && run.entries.isNotEmpty()) listState.scrollToItem(run.entries.lastIndex)
    }

    LaunchedEffect(run.id) {
        if (run.entries.isNotEmpty()) listState.scrollToItem(run.entries.lastIndex)
    }

    Box(modifier) {
        if (run.entries.isEmpty() && run.status.isActive) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LoadingIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Waiting for the first event…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Box
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                items(run.entries, key = { it.key }) { entry ->
                    when (entry) {
                        is ConsoleEntry.Message -> MessageRow(entry)
                        is ConsoleEntry.ToolCall -> ToolCallRow(entry)
                        is ConsoleEntry.Output -> OutputRow(entry)
                        is ConsoleEntry.Notice -> NoticeRow(entry)
                        is ConsoleEntry.Prompt -> PromptRow(entry)
                        is ConsoleEntry.Summary -> SummaryRow(entry)
                    }
                }
            }
        }
        ScrollControls(
            canScrollUp = listState.canScrollBackward,
            canScrollDown = listState.canScrollForward,
            onTop = { jumpTo(0) },
            onBottom = { jumpTo(run.entries.lastIndex.coerceAtLeast(0)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun ScrollControls(
    canScrollUp: Boolean,
    canScrollDown: Boolean,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canScrollUp && !canScrollDown) return

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Column {
            if (canScrollUp) {
                IconButton(onClick = onTop, modifier = Modifier.size(30.dp)) {
                    Icon(ZopfIcons.JumpToTop, contentDescription = "Jump to top", Modifier.size(16.dp))
                }
            }
            if (canScrollDown) {
                IconButton(onClick = onBottom, modifier = Modifier.size(30.dp)) {
                    Icon(ZopfIcons.JumpToBottom, contentDescription = "Jump to bottom", Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageRow(entry: ConsoleEntry.Message) {
    Text(
        entry.text,
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = if (entry.isThinking) FontStyle.Italic else FontStyle.Normal,
        color =
            if (entry.isThinking) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    )
}

@Composable
private fun ToolCallRow(entry: ConsoleEntry.ToolCall) {
    var expanded by remember(entry.key) { mutableStateOf(false) }
    val result = entry.result

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(enabled = result != null) { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (entry.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                if (entry.summary.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (result == null) {
                    LoadingIndicator(Modifier.size(16.dp))
                }
            }
            result?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (expanded) it else it.lineSequence().take(3).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OutputRow(entry: ConsoleEntry.Output) {
    Text(
        entry.text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun NoticeRow(entry: ConsoleEntry.Notice) {
    Text(
        entry.text,
        style = MaterialTheme.typography.labelSmall,
        color =
            if (entry.isWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

@Composable
private fun PromptRow(entry: ConsoleEntry.Prompt) {
    var expanded by remember(entry.key) { mutableStateOf(false) }
    var clipped by remember(entry.key) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else CollapsedPromptLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
            )
            if (clipped) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    Text(
                        if (expanded) "Show less" else "Show all",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(entry: ConsoleEntry.Summary) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color =
            if (entry.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            entry.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                buildString {
                    append(if (entry.isError) "Turn failed" else "Turn finished")
                    entry.durationMs?.let { append(" in ${format(Duration.ofMillis(it))}") }
                    spend(entry.costUsd, entry.tokens)?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FollowUpBar(
    onSend: (String) -> Unit,
    onFinish: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    fun send() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier =
                Modifier.weight(1f).onPreviewKeyEvent { event ->

                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                        send()
                        true
                    } else {
                        false
                    }
                },
            placeholder = { Text("Follow up in this session…") },
            textStyle = MaterialTheme.typography.bodyMedium,
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { send() }, enabled = text.isNotBlank()) {
            Icon(ZopfIcons.Send, contentDescription = "Send", Modifier.size(18.dp))
        }
        TextButton(onClick = onFinish) { Text("Finish") }
    }
}

@Composable
private fun PermissionBar(
    pending: PendingPermission?,
    onDecide: (Boolean, Boolean) -> Unit,
) {
    val request = pending?.request ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "${request.toolName} wants to run",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (request.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    request.summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDecide(false, false) }) { Text("Deny") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDecide(true, true) }) { Text("Allow for this run") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDecide(true, false) }) {
                    Icon(ZopfIcons.Check, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun GateBar(
    title: String,
    onApprove: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onApprove(false) }) { Text("Reject") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onApprove(true) }) {
                Icon(ZopfIcons.Check, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Approve")
            }
        }
    }
}

@Composable
private fun InputBar(
    question: PendingQuestion?,
    onAnswer: (String?) -> Unit,
) {
    if (question == null) return
    var text by remember(question) { mutableStateOf(question.initialAnswer) }

    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (question.choices.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.choices.forEach { choice ->
                        FilterChip(
                            selected = text == choice,
                            onClick = { text = choice },
                            label = { Text(choice) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (question.choices.isEmpty()) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier =
                            Modifier.weight(1f).onPreviewKeyEvent { event ->

                                val enter = event.key == Key.Enter && !event.isShiftPressed
                                if (event.type == KeyEventType.KeyDown && enter && text.isNotBlank()) {
                                    onAnswer(text.trim())
                                    true
                                } else {
                                    false
                                }
                            },
                        placeholder = { Text("Your answer…") },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onAnswer(null) }) { Text("Cancel run") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onAnswer(text.trim()) }, enabled = text.isNotBlank()) {
                    Icon(ZopfIcons.Send, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Answer")
                }
            }
        }
    }
}

@Composable
internal fun StatusDot(
    status: RunStatus,
    size: Int = 10,
) {
    val color = status.color()
    if (status.showsProgress) {
        LoadingIndicator(Modifier.size((size + 4).dp), color = color)
    } else {
        Box(
            Modifier
                .size(size.dp)
                .background(color, RoundedCornerShape(percent = 50)),
        )
    }
}

@Composable
internal fun RunStatus.color(): Color =
    when (this) {
        RunStatus.QUEUED -> MaterialTheme.colorScheme.outlineVariant
        RunStatus.STARTING, RunStatus.RUNNING -> MaterialTheme.colorScheme.primary
        RunStatus.WAITING -> MaterialTheme.colorScheme.tertiary
        RunStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
        RunStatus.FAILED -> MaterialTheme.colorScheme.error
        RunStatus.STOPPED, RunStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        RunStatus.DETACHED -> MaterialTheme.colorScheme.secondary
    }

@Preview
@Composable
private fun TranscriptPreview() {
    ZopfTheme {
        Surface {
            Box(Modifier.size(480.dp, 420.dp)) {
                Transcript(PreviewFixtures.nodeRun(status = RunStatus.RUNNING))
            }
        }
    }
}

@Preview
@Composable
private fun RunHeaderPreview() {
    ZopfTheme {
        RunHeader(
            run = PreviewFixtures.nodeRun(status = RunStatus.RUNNING),
            onStop = {},
            onTakeOver = {},
            onClose = {},
            terminalApp = DEFAULT_TERMINAL_APP,
        )
    }
}

@Preview
@Composable
private fun GateConsolePreview() {
    ZopfTheme {
        Surface {
            Box(Modifier.size(480.dp, 420.dp)) {
                RunConsole(
                    run = PreviewFixtures.gateRun(),
                    onStop = {},
                    onTakeOver = {},
                    onSend = {},
                    onFinish = {},
                )
            }
        }
    }
}

@Preview
@Composable
private fun InputBarPreview() {
    ZopfTheme {
        InputBar(question = PreviewFixtures.pendingQuestion(), onAnswer = {})
    }
}
