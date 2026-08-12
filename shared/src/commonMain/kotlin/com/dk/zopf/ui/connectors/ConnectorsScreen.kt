package com.dk.zopf.ui.connectors

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.NodeType
import com.dk.zopf.store.BrokenConnector
import com.dk.zopf.store.Connector
import com.dk.zopf.store.ConnectorListing
import com.dk.zopf.store.ConnectorStore
import com.dk.zopf.store.OpenWorkspace
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors
import java.nio.file.Path

@Composable
fun ConnectorsScreen(
    workspace: OpenWorkspace?,
    listing: ConnectorListing,
    onCreate: (name: String, shared: Boolean) -> Unit,
    onChange: (Connector) -> Unit,
    onFixBroken: (BrokenConnector) -> Unit,
    onDelete: (Path) -> Unit,
    onReveal: (Path) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    createRequested: Boolean = false,
    onCreateRequestHandled: () -> Unit = {},
) {
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Pair<String, Path>?>(null) }

    LaunchedEffect(createRequested) {
        if (!createRequested) return@LaunchedEffect
        creating = true
        onCreateRequestHandled()
    }

    Box(modifier.fillMaxSize()) {
        if (listing.isEmpty) {
            EmptyState(workspace)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listing.connectors, key = { it.dir.toString() }) { connector ->

                    ContextMenuArea(
                        items = {
                            listOf(
                                ContextMenuItem("Change in Claude") { onChange(connector) },
                                ContextMenuItem("Show in Finder") { onReveal(connector.dir) },
                                ContextMenuItem("Delete…") { deleting = connector.name to connector.dir },
                            )
                        },
                    ) {
                        ConnectorCard(
                            connector = connector,
                            onChange = { onChange(connector) },
                            onDelete = { deleting = connector.name to connector.dir },
                            onReveal = { onReveal(connector.dir) },
                        )
                    }
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
                    items(listing.broken, key = { it.dir.toString() }) { broken ->
                        ContextMenuArea(
                            items = {
                                listOf(
                                    ContextMenuItem("Fix in Claude") { onFixBroken(broken) },
                                    ContextMenuItem("Show in Finder") { onReveal(broken.dir) },
                                    ContextMenuItem("Delete…") { deleting = broken.name to broken.dir },
                                )
                            },
                        ) {
                            BrokenCard(
                                broken = broken,
                                onFix = { onFixBroken(broken) },
                                onDelete = { deleting = broken.name to broken.dir },
                                onReveal = { onReveal(broken.dir) },
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomEnd).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRefresh) {
                Icon(ZopfIcons.Refresh, contentDescription = "Refresh connectors")
            }
            Spacer(Modifier.width(8.dp))
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(ZopfIcons.Add, contentDescription = null) },
                text = { Text("New connector") },
            )
        }
    }

    if (creating) {
        NewConnectorDialog(
            onDismiss = { creating = false },
            onConfirm = { name, shared ->
                creating = false
                onCreate(name, shared)
            },
        )
    }

    deleting?.let { (name, dir) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { Text("Deletes $dir and everything in it. Workflows that call it will fail.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(dir)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectorCard(
    connector: Connector,
    onChange: () -> Unit,
    onDelete: () -> Unit,
    onReveal: () -> Unit,
) {
    val manifest = connector.manifest
    val scheme = MaterialTheme.colorScheme

    val accent = NodeType.CONNECTOR.colors()

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .background(accent.container, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ZopfIcons.NodeConnector,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = accent.onContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(connector.name, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        manifest.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onChange) {
                    Icon(ZopfIcons.NodeAgent, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Change in Claude")
                }
                IconButton(onClick = onReveal) {
                    Icon(ZopfIcons.Folder, contentDescription = "Show in Finder", Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(ZopfIcons.Delete, contentDescription = "Delete", Modifier.size(18.dp))
                }
            }

            if (manifest.inputs.isNotEmpty() || manifest.outputs.isNotEmpty() || manifest.env.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Inputs", manifest.inputs.isNotEmpty()) {
                        manifest.inputs.forEach { input ->
                            Tag(
                                input.name + if (input.required) " *" else "",
                                monospace = true,
                                filled = input.required,
                            )
                        }
                    }
                    DetailRow("Outputs", manifest.outputs.isNotEmpty()) {
                        manifest.outputs.forEach { Tag(it.name, monospace = true) }
                    }

                    DetailRow("Secrets", manifest.env.isNotEmpty()) {
                        manifest.env.forEach { secret ->
                            Tag(
                                secret.name + if (!secret.required) " (optional)" else "",
                                monospace = true,
                                filled = secret.required,
                            )
                        }
                    }
                }

                manifest.env.filter { it.keychain != null }.takeIf { it.isNotEmpty() }?.let { fromKeychain ->
                    Spacer(Modifier.height(10.dp))

                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = scheme.surfaceContainerLowest,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            fromKeychain.forEach { secret ->
                                Text(
                                    "${secret.name} · ${secret.sourceLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            fromKeychain.mapNotNull { it.keychain }.distinct().forEach { keychain ->
                                Text(
                                    "security add-generic-password -a \"\$USER\" -s $keychain -w",
                                    style =
                                        MaterialTheme.typography.labelSmall
                                            .copy(fontFamily = FontFamily.Monospace),
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "${connector.source} · ${manifest.run} · ${manifest.timeoutSeconds}s timeout",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    present: Boolean,
    tags: @Composable FlowRowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label.uppercase(),
            Modifier.width(72.dp).padding(top = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (present) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = tags,
            )
        } else {
            Text(
                "—",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tag(
    text: String,
    monospace: Boolean = false,
    filled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (filled) scheme.surfaceContainerLowest else Color.Transparent,
        contentColor = if (filled) scheme.onSurface else scheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
        border = if (filled) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style =
                MaterialTheme.typography.labelSmall.let {
                    if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it
                },
        )
    }
}

@Composable
private fun BrokenCard(
    broken: BrokenConnector,
    onFix: () -> Unit,
    onDelete: () -> Unit,
    onReveal: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
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
                    broken.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    broken.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onFix) { Text("Fix in Claude") }
            IconButton(onClick = onReveal) {
                Icon(
                    ZopfIcons.Folder,
                    contentDescription = "Show in Finder",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(ZopfIcons.Delete, contentDescription = "Delete", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NewConnectorDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, shared: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New connector") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    supportingText = { Text("Becomes the folder name: lower-cased and hyphenated.") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shared, onCheckedChange = { shared = it })
                    Column {
                        Text("Share across workspaces", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Writes it to ${ConnectorStore.defaultSharedRoot} instead of this workspace.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "zopf creates the folder and opens Claude Code in it, in your terminal. It knows " +
                        "the connector contract and will ask what this one should do.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, shared) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(workspace: OpenWorkspace?) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No connectors yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "A connector is a folder with a manifest and a script. zopf pipes JSON into it and reads " +
                "JSON back. Name one and Claude Code opens in its folder to write it with you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            workspace?.workspace?.let { "They live in ${it.connectorsDir} or ${ConnectorStore.defaultSharedRoot}." }
                ?: "Open a workspace first.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun ConnectorCardPreview() {
    ZopfTheme {
        Surface {
            Box(Modifier.padding(16.dp)) {
                ConnectorCard(
                    connector = PreviewFixtures.connector(),
                    onChange = {},
                    onDelete = {},
                    onReveal = {},
                )
            }
        }
    }
}
