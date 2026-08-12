package com.dk.zopf.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.store.OpenWorkspace
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Path
import java.nio.file.Paths

private val NameWidth = 180.dp

private val ButtonPadding = PaddingValues(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)

@Composable
fun WorkspaceSwitcher(
    workspaces: List<OpenWorkspace>,
    active: OpenWorkspace?,
    onSelect: (Path) -> Unit,
    onAdd: (Path) -> Unit,
    onForget: (Path) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val offline = active?.isOnline == false

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { expanded = true }, contentPadding = ButtonPadding) {
            Icon(
                if (offline) ZopfIcons.Warning else ZopfIcons.Folder,
                contentDescription = if (offline) "Workspace unavailable" else null,
                modifier = Modifier.size(16.dp),
                tint = if (offline) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = active?.displayName ?: "No workspace",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = NameWidth),
            )
            Spacer(Modifier.width(4.dp))
            Icon(ZopfIcons.Dropdown, contentDescription = null, modifier = Modifier.size(18.dp))
        }

        IconButton(onClick = onRefresh) {
            Icon(ZopfIcons.Refresh, contentDescription = "Refresh workspaces", modifier = Modifier.size(18.dp))
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(workspace.displayName)
                            Text(
                                text = workspace.path.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingIcon =
                        if (workspace.isOnline) {
                            null
                        } else {
                            {
                                Icon(
                                    ZopfIcons.Warning,
                                    contentDescription = "Unavailable",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    trailingIcon = {
                        IconButton(onClick = {
                            expanded = false
                            onForget(workspace.path)
                        }) {
                            Icon(ZopfIcons.Clear, contentDescription = "Forget workspace")
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(workspace.path)
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            DropdownMenuItem(
                text = { Text("Open workspace…") },
                leadingIcon = { Icon(ZopfIcons.Add, contentDescription = null) },
                onClick = {
                    expanded = false

                    chooseDirectory("Open or create a zopf workspace")?.let(onAdd)
                },
            )
        }
    }
}

@Preview
@Composable
private fun WorkspaceSwitcherPreview() {
    val active = PreviewFixtures.openWorkspace()
    val offline = OpenWorkspace(path = Paths.get("/Volumes/gone/zopf"), workspace = null, lastOpened = 0L)
    ZopfTheme {
        WorkspaceSwitcher(
            workspaces = listOf(active, offline),
            active = active,
            onSelect = {},
            onAdd = {},
            onForget = {},
            onRefresh = {},
        )
    }
}
