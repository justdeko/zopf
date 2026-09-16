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
import com.dk.zopf.store.workspace.BrokenConnector
import com.dk.zopf.store.workspace.Connector
import com.dk.zopf.store.workspace.ConnectorListing
import com.dk.zopf.store.workspace.ConnectorStore
import com.dk.zopf.store.workspace.OpenWorkspace
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors
import com.dk.zopf.util.Strings
import java.nio.file.Path

@Composable
fun ConnectorsScreen(
    workspace: OpenWorkspace?,
    listing: ConnectorListing,
    agent: String,
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
            EmptyState(workspace, agent)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listing.connectors, key = { it.dir.toString() }) { connector ->

                    ContextMenuArea(
                        items = {
                            listOf(
                                ContextMenuItem(Strings.Connectors.changeIn(agent)) { onChange(connector) },
                                ContextMenuItem(Strings.Actions.SHOW_IN_FINDER) { onReveal(connector.dir) },
                                ContextMenuItem(Strings.Actions.DELETE_MENU) { deleting = connector.name to connector.dir },
                            )
                        },
                    ) {
                        ConnectorCard(
                            connector = connector,
                            agent = agent,
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
                            Strings.Labels.UNREADABLE,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    items(listing.broken, key = { it.dir.toString() }) { broken ->
                        ContextMenuArea(
                            items = {
                                listOf(
                                    ContextMenuItem(Strings.Connectors.fixIn(agent)) { onFixBroken(broken) },
                                    ContextMenuItem(Strings.Actions.SHOW_IN_FINDER) { onReveal(broken.dir) },
                                    ContextMenuItem(Strings.Actions.DELETE_MENU) { deleting = broken.name to broken.dir },
                                )
                            },
                        ) {
                            BrokenCard(
                                broken = broken,
                                agent = agent,
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
                Icon(ZopfIcons.Refresh, contentDescription = Strings.Connectors.REFRESH)
            }
            Spacer(Modifier.width(8.dp))
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(ZopfIcons.Add, contentDescription = null) },
                text = { Text(Strings.Connectors.NEW) },
            )
        }
    }

    if (creating) {
        NewConnectorDialog(
            agent = agent,
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
            title = { Text(Strings.Actions.deleteTitle(name)) },
            text = { Text(Strings.Connectors.deleteBody(dir)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(dir)
                }) { Text(Strings.Actions.DELETE) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(Strings.Actions.CANCEL) } },
        )
    }
}

@Composable
private fun ConnectorCard(
    connector: Connector,
    agent: String,
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
                    Text(Strings.Connectors.changeIn(agent))
                }
                IconButton(onClick = onReveal) {
                    Icon(ZopfIcons.Folder, contentDescription = Strings.Actions.SHOW_IN_FINDER, Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(ZopfIcons.Delete, contentDescription = Strings.Actions.DELETE, Modifier.size(18.dp))
                }
            }

            if (manifest.inputs.isNotEmpty() || manifest.outputs.isNotEmpty() || manifest.env.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow(Strings.Labels.INPUTS, manifest.inputs.isNotEmpty()) {
                        manifest.inputs.forEach { input ->
                            Tag(
                                input.name + if (input.required) Strings.Connectors.REQUIRED_MARKER else "",
                                monospace = true,
                                filled = input.required,
                            )
                        }
                    }
                    DetailRow(Strings.Labels.OUTPUTS, manifest.outputs.isNotEmpty()) {
                        manifest.outputs.forEach { Tag(it.name, monospace = true) }
                    }

                    DetailRow(Strings.Connectors.SECRETS, manifest.env.isNotEmpty()) {
                        manifest.env.forEach { secret ->
                            Tag(
                                secret.name + if (!secret.required) Strings.Connectors.OPTIONAL_MARKER else "",
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
                                    Strings.Connectors.keychainHint(keychain),
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
                Strings.Connectors.detail(connector.source, manifest.run, manifest.timeoutSeconds),
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
    agent: String,
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
            TextButton(onClick = onFix) { Text(Strings.Connectors.fixIn(agent)) }
            IconButton(onClick = onReveal) {
                Icon(
                    ZopfIcons.Folder,
                    contentDescription = Strings.Actions.SHOW_IN_FINDER,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(ZopfIcons.Delete, contentDescription = Strings.Actions.DELETE, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NewConnectorDialog(
    agent: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, shared: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.Connectors.NEW) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(Strings.Labels.NAME) },
                    supportingText = { Text(Strings.Connectors.NAME_HINT) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shared, onCheckedChange = { shared = it })
                    Column {
                        Text(Strings.Connectors.SHARE_ACROSS_WORKSPACES, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Strings.Connectors.shareHint(ConnectorStore.defaultSharedRoot),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    Strings.Connectors.createHint(agent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, shared) },
                enabled = name.isNotBlank(),
            ) { Text(Strings.Actions.CREATE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.Actions.CANCEL) } },
    )
}

@Composable
private fun EmptyState(
    workspace: OpenWorkspace?,
    agent: String,
) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Strings.Connectors.NONE_YET, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            Strings.Connectors.emptyBody(agent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            workspace?.workspace?.let { Strings.Connectors.emptyWhere(it.connectorsDir, ConnectorStore.defaultSharedRoot) }
                ?: Strings.Workspaces.OPEN_FIRST,
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
                    agent = "Claude Code",
                    onChange = {},
                    onDelete = {},
                    onReveal = {},
                )
            }
        }
    }
}
