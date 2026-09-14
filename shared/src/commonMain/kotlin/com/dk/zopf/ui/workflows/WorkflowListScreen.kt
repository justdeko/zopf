package com.dk.zopf.ui.workflows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.Workflow
import com.dk.zopf.store.workflow.BrokenWorkflow
import com.dk.zopf.store.workflow.Templates
import com.dk.zopf.store.workflow.WorkflowListing
import com.dk.zopf.store.workflow.WorkflowStore
import com.dk.zopf.store.workspace.OpenWorkspace
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.util.Strings
import java.nio.file.Path

private val RowHeight = 92.dp

private val TemplateGridWidth = 512.dp

private val TemplatePreviewHeight = 76.dp

private val IconSize = 18.dp

@Composable
fun WorkflowListScreen(
    workspace: OpenWorkspace?,
    listing: WorkflowListing,
    selected: Workflow?,
    onOpen: (Workflow) -> Unit,
    onRun: (Workflow) -> Unit,
    onCreate: (String, String?) -> Unit,
    onDescribe: (String, String) -> Unit,
    onRename: (Workflow, String) -> Unit,
    onDelete: (Workflow) -> Unit,
    onReveal: (Path) -> Unit,
    modifier: Modifier = Modifier,
    createRequested: Boolean = false,
    onCreateRequestHandled: () -> Unit = {},
) {
    val fileOf: (Workflow) -> Path? = { workflow ->
        workspace?.workspace?.let { WorkflowStore(it).fileFor(workflow.name) }
    }
    val isEmpty = listing.workflows.isEmpty() && listing.broken.isEmpty()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Workflow?>(null) }
    var deleting by remember { mutableStateOf<Workflow?>(null) }

    LaunchedEffect(createRequested) {
        if (!createRequested) return@LaunchedEffect
        if (workspace?.isOnline == true) creating = true
        onCreateRequestHandled()
    }

    Box(modifier.fillMaxSize()) {
        when {
            workspace == null -> EmptyMessage(Strings.Nav.NO_WORKSPACE, Strings.Workflows.OPEN_FROM_SWITCHER)

            !workspace.isOnline ->
                EmptyMessage(
                    title = Strings.Workflows.unavailableTitle(workspace.displayName),
                    detail = Strings.Workflows.unavailableBody(workspace.path),
                    isError = true,
                )

            isEmpty ->
                EmptyMessage(
                    title = Strings.Workflows.NONE_YET,
                    detail = Strings.Workflows.noneYetBody(workspace.workspace?.workflowsDir),
                )

            else ->
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listing.workflows, key = { it.name }) { workflow ->
                        WorkflowRow(
                            workflow = workflow,
                            isSelected = workflow.name == selected?.name,
                            onOpen = { onOpen(workflow) },
                            onRun = { onRun(workflow) },
                            onRename = { renaming = workflow },
                            onDelete = { deleting = workflow },
                            onReveal = { fileOf(workflow)?.let(onReveal) },
                        )
                    }
                    if (listing.broken.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                Strings.Workflows.UNREADABLE,
                                style = MaterialTheme.typography.labelLargeEmphasized,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        items(listing.broken, key = { it.file.toString() }) {
                            BrokenRow(it, onReveal = { onReveal(it.file) })
                        }
                    }
                }
        }

        if (workspace?.isOnline == true) {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                icon = { Icon(ZopfIcons.Add, contentDescription = null) },
                text = { Text(Strings.Workflows.NEW) },
            )
        }
    }

    if (creating) {
        NewWorkflowDialog(
            showTemplates = isEmpty,
            onDismiss = { creating = false },
            onCreate = { name, template ->
                creating = false
                onCreate(name, template)
            },
            onDescribe = { name, description ->
                creating = false
                onDescribe(name, description)
            },
        )
    }

    renaming?.let { workflow ->
        NameDialog(
            title = Strings.Workflows.RENAME_TITLE,
            initial = workflow.name,
            confirmLabel = Strings.Workflows.RENAME,
            onDismiss = { renaming = null },
            onConfirm = {
                renaming = null
                onRename(workflow, it)
            },
        )
    }

    deleting?.let { workflow ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(Strings.Workflows.deleteTitle(workflow.name)) },
            text = { Text(Strings.Workflows.DELETE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(workflow)
                }) { Text(Strings.Workflows.DELETE) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(Strings.Workflows.CANCEL) } },
        )
    }
}

@Composable
private fun WorkflowRow(
    workflow: Workflow,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReveal: () -> Unit,
) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(Strings.Workflows.OPEN) { onOpen() },
                ContextMenuItem(Strings.Workflows.RUN, enabled = workflow.nodes.isNotEmpty()) { onRun() },
                ContextMenuItem(Strings.Workflows.RENAME_MENU) { onRename() },
                ContextMenuItem(Strings.Workflows.SHOW_IN_FINDER) { onReveal() },
                ContextMenuItem(Strings.Workflows.DELETE_MENU) { onDelete() },
            )
        },
    ) {
        WorkflowCard(workflow, isSelected, onOpen, onRun, onRename, onDelete, onReveal)
    }
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onReveal: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (isSelected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(RowHeight).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkflowMinimap(workflow, onClick = onOpen)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(workflow.name, style = MaterialTheme.typography.titleMediumEmphasized)
                val summary =
                    workflow.description.firstParagraph().ifBlank {
                        Strings.Workflows.summary(
                            Strings.Words.count(workflow.nodes.size, Strings.Words.NODE),
                            Strings.Words.count(workflow.repos.size, Strings.Words.REPO),
                        )
                    }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRun, enabled = workflow.nodes.isNotEmpty()) {
                Icon(ZopfIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(Strings.Workflows.RUN)
            }
            IconButton(onClick = onReveal) {
                Icon(ZopfIcons.Folder, contentDescription = Strings.Workflows.SHOW_IN_FINDER, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRename) {
                Icon(ZopfIcons.Edit, contentDescription = Strings.Workflows.RENAME, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(ZopfIcons.Delete, contentDescription = Strings.Workflows.DELETE, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BrokenRow(
    broken: BrokenWorkflow,
    onReveal: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                ZopfIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    broken.file.fileName.toString(),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    broken.message
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .take(2)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onReveal) {
                Icon(
                    ZopfIcons.Folder,
                    contentDescription = Strings.Workflows.SHOW_IN_FINDER,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun NewWorkflowDialog(
    showTemplates: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String?) -> Unit,
    onDescribe: (String, String) -> Unit,
) {
    val templates = remember { Templates.names.map { it to Templates.workflow(it, it) } }
    var template by remember { mutableStateOf(Templates.names.first().takeIf { showTemplates }) }
    var name by remember { mutableStateOf(template.orEmpty()) }
    var typed by remember { mutableStateOf(false) }
    var describing by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.Workflows.NEW) },
        text = {
            Column(Modifier.width(TemplateGridWidth).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        typed = true
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.Workflows.NAME_LABEL) },
                    supportingText = { Text(Strings.Workflows.NAME_HINT) },
                )
                FilterChip(
                    selected = describing,
                    onClick = { describing = !describing },
                    label = { Text(Strings.Workflows.DESCRIBE_TAB) },
                    leadingIcon = { Icon(ZopfIcons.Edit, contentDescription = null, modifier = Modifier.size(IconSize)) },
                )
                AnimatedVisibility(describing) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text(Strings.Workflows.DESCRIPTION_LABEL) },
                        supportingText = { Text(Strings.Workflows.DESCRIPTION_HINT) },
                    )
                }
                if (showTemplates) {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        Strings.Workflows.TEMPLATE_HEADING,
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (templates + listOf(null)).chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { entry ->
                                    TemplateCard(
                                        title = entry?.let { Templates.label(it.first) } ?: Strings.Workflows.EMPTY_TEMPLATE,
                                        detail =
                                            entry?.second?.description?.firstParagraph()
                                                ?: Strings.Workflows.EMPTY_TEMPLATE_BLURB,
                                        workflow = entry?.second,
                                        isSelected = !describing && template == entry?.first,
                                        onClick = {
                                            template = entry?.first
                                            describing = false
                                            if (!typed) name = entry?.first.orEmpty()
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (describing) onDescribe(name, description) else onCreate(name, template) },
                enabled = name.isNotBlank() && (!describing || description.isNotBlank()),
            ) { Text(if (describing) Strings.Workflows.DRAFT else Strings.Workflows.CREATE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.Workflows.CANCEL) } },
    )
}

@Composable
private fun TemplateCard(
    title: String,
    detail: String,
    workflow: Workflow?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        border =
            if (isSelected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
    ) {
        Column(Modifier.padding(10.dp)) {
            val preview = Modifier.fillMaxWidth().height(TemplatePreviewHeight)
            if (workflow == null) {
                Box(
                    preview
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZopfTheme.colors.graphSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ZopfIcons.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                WorkflowThumbnail(workflow, RoundedCornerShape(8.dp), preview)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(Strings.Workflows.NAME_LABEL) },
                supportingText = { Text(Strings.Workflows.NAME_HINT) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.Workflows.CANCEL) } },
    )
}

@Composable
private fun EmptyMessage(
    title: String,
    detail: String,
    isError: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun String.firstParagraph(): String = trim().substringBefore('\n').trim()

@Preview
@Composable
private fun WorkflowCardPreview() {
    ZopfTheme {
        WorkflowCard(
            workflow = PreviewFixtures.workflow(),
            isSelected = false,
            onOpen = {},
            onRun = {},
            onRename = {},
            onDelete = {},
            onReveal = {},
        )
    }
}
