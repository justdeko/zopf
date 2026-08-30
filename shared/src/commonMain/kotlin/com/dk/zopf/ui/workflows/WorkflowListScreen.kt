package com.dk.zopf.ui.workflows

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
import com.dk.zopf.store.BrokenWorkflow
import com.dk.zopf.store.OpenWorkspace
import com.dk.zopf.store.Templates
import com.dk.zopf.store.WorkflowListing
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Path

private val RowHeight = 92.dp

private val TemplateGridWidth = 512.dp

private val TemplatePreviewHeight = 76.dp

@Composable
fun WorkflowListScreen(
    workspace: OpenWorkspace?,
    listing: WorkflowListing,
    selected: Workflow?,
    onOpen: (Workflow) -> Unit,
    onRun: (Workflow) -> Unit,
    onCreate: (String, String?) -> Unit,
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
            workspace == null -> EmptyMessage("No workspace open", "Open one from the switcher above.")

            !workspace.isOnline ->
                EmptyMessage(
                    title = "${workspace.displayName} is unavailable",
                    detail =
                        "${workspace.path} isn't reachable. Reconnect the drive or check out the " +
                            "branch that has it, then refresh.",
                    isError = true,
                )

            isEmpty ->
                EmptyMessage(
                    title = "No workflows yet",
                    detail = "New workflow starts you from a template. They live in ${workspace.workspace?.workflowsDir}.",
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
                                "Can't be read",
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
                text = { Text("New workflow") },
            )
        }
    }

    if (creating) {
        if (isEmpty) {
            FirstWorkflowDialog(
                onDismiss = { creating = false },
                onConfirm = { name, template ->
                    creating = false
                    onCreate(name, template)
                },
            )
        } else {
            NameDialog(
                title = "New workflow",
                initial = "",
                confirmLabel = "Create",
                onDismiss = { creating = false },
                onConfirm = {
                    creating = false
                    onCreate(it, null)
                },
            )
        }
    }

    renaming?.let { workflow ->
        NameDialog(
            title = "Rename workflow",
            initial = workflow.name,
            confirmLabel = "Rename",
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
            title = { Text("Delete ${workflow.name}?") },
            text = { Text("Deletes the YAML file from the workspace. zopf can't undo this.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(workflow)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
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
                ContextMenuItem("Open") { onOpen() },
                ContextMenuItem("Run", enabled = workflow.nodes.isNotEmpty()) { onRun() },
                ContextMenuItem("Rename…") { onRename() },
                ContextMenuItem("Show in Finder") { onReveal() },
                ContextMenuItem("Delete…") { onDelete() },
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
                        val nodes = workflow.nodes.size
                        val repos = workflow.repos.size
                        "$nodes ${plural(nodes, "node")} · $repos ${plural(repos, "repo")}"
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
                Text("Run")
            }
            IconButton(onClick = onReveal) {
                Icon(ZopfIcons.Folder, contentDescription = "Show in Finder", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRename) {
                Icon(ZopfIcons.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(ZopfIcons.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
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
                    contentDescription = "Show in Finder",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun FirstWorkflowDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    val templates = remember { Templates.names.map { it to Templates.workflow(it, it) } }
    var template by remember { mutableStateOf<String?>(Templates.names.first()) }
    var value by remember { mutableStateOf(Templates.names.first()) }
    var typed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a workflow") },
        text = {
            Column(Modifier.width(TemplateGridWidth).verticalScroll(rememberScrollState())) {
                Text(
                    "Pick a starting point. You can change everything in it after.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (templates + listOf(null)).chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { entry ->
                                TemplateCard(
                                    title = entry?.let { Templates.label(it.first) } ?: "Empty",
                                    detail =
                                        entry?.second?.description?.firstParagraph()
                                            ?: "Draw your own graph from nothing.",
                                    workflow = entry?.second,
                                    isSelected = template == entry?.first,
                                    onClick = {
                                        template = entry?.first
                                        if (!typed) value = entry?.first.orEmpty()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        typed = true
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    supportingText = { Text("Becomes the filename: lower-cased and hyphenated.") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value, template) }, enabled = value.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
                label = { Text("Name") },
                supportingText = { Text("Becomes the filename: lower-cased and hyphenated.") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

private fun plural(
    count: Int,
    word: String,
) = if (count == 1) word else "${word}s"

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
