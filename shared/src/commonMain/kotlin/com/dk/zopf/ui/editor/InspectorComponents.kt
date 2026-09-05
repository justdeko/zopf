package com.dk.zopf.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.NodeRefs
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.ui.theme.colors

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun InspectorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    supportingText: String? = null,
    isError: Boolean = false,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        textStyle =
            if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        supportingText = supportingText?.let { { Text(it) } },
    )
}

@Composable
fun SuggestingField(
    label: String,
    value: String?,
    suggestions: List<String>,
    onChange: (String?) -> Unit,
    noneLabel: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    supportingText: String? = null,
) {
    var typing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value.orEmpty()) }

    val matches = suggestions.filter { it.contains(text.trim(), ignoreCase = true) }
    val open = typing && (matches.isNotEmpty() || text.isNotBlank())

    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { typing = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                typing = true
                onChange(it.trim().ifBlank { null })
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            label = { Text(label) },
            singleLine = true,
            textStyle =
                if (monospace) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            supportingText = { Text(if (text.isBlank()) noneLabel else supportingText.orEmpty()) },
            trailingIcon = {
                IconButton(onClick = { typing = !typing }) {
                    Icon(ZopfIcons.List, contentDescription = "Choose", Modifier.size(18.dp))
                }
            },
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { typing = false }) {
            if (text.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        text = ""
                        typing = false
                        onChange(null)
                    },
                )
            }
            matches.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        text = suggestion
                        typing = false
                        onChange(suggestion)
                    },
                )
            }
        }
    }
}

@Composable
fun <T> InspectorDropdown(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String? = null,
    supportingText: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: noneLabel.orEmpty(),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(ZopfIcons.List, contentDescription = "Choose", Modifier.size(18.dp))
                }
            },
        )

        Box(
            Modifier
                .matchParentSize()
                .padding(bottom = if (supportingText != null) 20.dp else 0.dp)
                .clickableNoRipple { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (noneLabel != null) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    },
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
            if (options.isEmpty() && noneLabel == null) {
                DropdownMenuItem(text = { Text("Nothing to choose from") }, onClick = {}, enabled = false)
            }
        }
    }
}

@Composable
fun ChipListField(
    label: String,
    values: List<String>,
    onChange: (List<String>) -> Unit,
    addLabel: String,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
    supportingText: String? = null,
    onBrowse: (() -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }

    fun add(value: String) {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && trimmed !in values) onChange(values + trimmed)
        draft = ""
    }

    Column(modifier.fillMaxWidth()) {
        SectionLabel(label)
        if (values.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                values.forEach { value ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(values - value) },
                        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(ZopfIcons.Clear, contentDescription = "Remove", Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text(addLabel) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            IconButton(onClick = { add(draft) }, enabled = draft.isNotBlank()) {
                Icon(ZopfIcons.Add, contentDescription = "Add", Modifier.size(18.dp))
            }
            onBrowse?.let {
                IconButton(onClick = it) {
                    Icon(ZopfIcons.Folder, contentDescription = "Browse", Modifier.size(18.dp))
                }
            }
        }
        val unused = suggestions.filterNot { it in values }
        if (unused.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                unused.forEach { suggestion ->
                    AssistChip(
                        onClick = { add(suggestion) },
                        label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(ZopfIcons.Add, contentDescription = null, Modifier.size(14.dp)) },
                    )
                }
            }
        }
        supportingText?.let {
            Text(
                it,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ToggleChipField(
    label: String,
    options: List<String>,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel(label)
        if (options.isEmpty()) {
            Text(
                emptyHint,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                val isOn = option in selected
                FilterChip(
                    selected = isOn,
                    onClick = { onChange(if (isOn) selected - option else selected + option) },
                    label = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
fun KeyValueField(
    label: String,
    values: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newKey by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        values.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { onChange(values + (key to it)) },
                    modifier = Modifier.weight(1f),
                    label = { Text(key) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                IconButton(onClick = { onChange(values - key) }) {
                    Icon(ZopfIcons.Delete, contentDescription = "Remove $key", Modifier.size(16.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                modifier = Modifier.weight(1f),
                label = { Text("New input name") },
                singleLine = true,
            )
            IconButton(
                onClick = {
                    onChange(values + (newKey.trim() to ""))
                    newKey = ""
                },
                enabled = newKey.isNotBlank() && newKey.trim() !in values,
            ) {
                Icon(ZopfIcons.Add, contentDescription = "Add input", Modifier.size(18.dp))
            }
        }
    }
}

data class UpstreamNodeReferences(
    val node: WorkflowNode,
    val fields: List<String>,
) {
    fun reference(field: String): String = NodeRefs.reference(node.id, field)
}

@Composable
fun ReferenceChips(
    groups: List<UpstreamNodeReferences>,
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.all { it.fields.isEmpty() }) return
    Column(modifier.fillMaxWidth()) {
        SectionLabel("Insert from upstream")
        groups.forEach { group ->
            if (group.fields.isEmpty()) return@forEach
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    group.node.type.icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint =
                        group.node.type
                            .colors()
                            .accent,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    group.node.displayTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                group.fields.forEach { field ->
                    AssistChip(
                        onClick = { onInsert(group.reference(field)) },
                        label = {
                            Text(
                                field,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun EdgeRow(
    text: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
        IconButton(onClick = onRemove) {
            Icon(ZopfIcons.Clear, contentDescription = "Disconnect", Modifier.size(16.dp))
        }
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(ZopfIcons.Delete, contentDescription = null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun Gap(height: Int = 12) = Spacer(Modifier.height(height.dp))

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

private val ComponentPreviewWidth = 340.dp

@Preview
@Composable
private fun ChipListFieldPreview() {
    var values by remember { mutableStateOf(listOf("Read", "Edit", "Bash(git *)")) }
    ZopfTheme {
        Surface(Modifier.width(ComponentPreviewWidth)) {
            Column(Modifier.padding(16.dp)) {
                ChipListField(
                    label = "Allowed tools",
                    values = values,
                    onChange = { values = it },
                    addLabel = "Tool rule",
                    suggestions = listOf("Read", "Edit", "Write", "Grep"),
                    supportingText = "Passed straight to --allowedTools.",
                )
            }
        }
    }
}

@Preview
@Composable
private fun InspectorDropdownPreview() {
    var selected by remember { mutableStateOf<String?>("sonnet") }
    ZopfTheme {
        Surface(Modifier.width(ComponentPreviewWidth)) {
            Column(Modifier.padding(16.dp)) {
                InspectorDropdown(
                    label = "Model",
                    selected = selected,
                    options = listOf("opus", "sonnet", "haiku"),
                    optionLabel = { it },
                    onSelect = { selected = it },
                    noneLabel = "Workflow default",
                    supportingText = "What this node may do without asking.",
                )
            }
        }
    }
}
