package com.dk.zopf.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.capabilities
import com.dk.zopf.runtime.ASKABLE_TOOLS
import com.dk.zopf.runtime.Release
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.BuildInfo
import com.dk.zopf.store.DEFAULT_KEEP_RUNS
import com.dk.zopf.store.DEFAULT_TERMINAL_APP
import com.dk.zopf.store.Log
import com.dk.zopf.store.MAX_CONCURRENCY
import com.dk.zopf.store.ThemePreference
import com.dk.zopf.ui.editor.Gap
import com.dk.zopf.ui.editor.InspectorField
import com.dk.zopf.ui.editor.ModelField
import com.dk.zopf.ui.editor.SectionLabel
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Path

private val TerminalSuggestions = listOf(DEFAULT_TERMINAL_APP, "iTerm", "Ghostty", "Warp", "kitty", "Alacritty")

private val FormWidth = 620.dp

private val KeepRunsOptions = listOf(0, 50, 200, 1000)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    settingsFile: Path = AppPaths.settingsFile,
    update: Release? = null,
    onOpenRelease: () -> Unit = {},
) {
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesDialog { showLicenses = false }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Column(Modifier.widthIn(max = FormWidth)) {
            SectionLabel("Running")
            ConcurrencyField(settings.concurrency) { chosen -> onChange { it.copy(concurrency = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("Agents")
            AgentField(settings.defaultProvider) { chosen -> onChange { it.copy(defaultProvider = chosen) } }
            if (settings.defaultProvider.capabilities.modelSelection) {
                Gap(4)
                ModelField(
                    provider = settings.defaultProvider,
                    selected = settings.defaultModel,
                    onSelect = { model -> onChange { it.copy(defaultModel = model) } },
                    noneLabel = "Whatever ${settings.defaultProvider.cliValue} is set to",
                    supportingText =
                        "Used only when neither the node nor its workflow names one, and only for nodes " +
                            "running ${settings.defaultProvider.label} — a model name belongs to the CLI it was written for.",
                )
            }
            Hint(
                "These are only this machine's fallback. A workspace that names a provider or " +
                    "model under defaults: in its zopf.yaml wins over them, so a repo can pin what its " +
                    "own workflows run on.",
            )

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("Permissions")
            Gap(4)
            ApprovalField(settings.inlineApproval) { on -> onChange { it.copy(inlineApproval = on) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("Appearance")
            ThemeField(settings.theme) { chosen -> onChange { it.copy(theme = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("Take-over")
            Gap(4)
            TerminalField(settings.terminalApp) { app -> onChange { it.copy(terminalApp = app) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("History")
            KeepRunsField(settings.keepRuns) { chosen -> onChange { it.copy(keepRuns = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("Updates")
            Gap(4)
            UpdateField(
                value = settings.checkForUpdates,
                update = update,
                onOpenRelease = onOpenRelease,
            ) { on -> onChange { it.copy(checkForUpdates = on) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel("Open source")
            Gap(4)
            TextButton({ showLicenses = true }, Modifier.padding(start = 0.dp)) {
                Text("Open source licenses")
            }

            Gap(20)
            Text(
                "zopf ${BuildInfo.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Written to $settingsFile",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Logging to ${Log.file}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AgentField(
    value: AgentProviderId,
    onSelect: (AgentProviderId) -> Unit,
) {
    Text(
        "Default CLI",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = AgentProviderId.entries,
        selected = value,
        label = { it.label },
        onSelect = onSelect,
    )
    Hint(
        "Used only by an agent node that names no CLI, in a workflow whose defaults name none either. " +
            "zopf drives whichever you pick with the login you already have.",
    )
    caveats(value)?.let { Hint(it) }
}

private fun caveats(provider: AgentProviderId): String? {
    val can = provider.capabilities
    val missing =
        buildList {
            if (!can.followUps) add("follow-ups")
            if (!can.resumeInTerminal) add("take-over")
            if (!can.inlineApproval) add("inline approval")
            if (!can.toolPermissions) add("a permission mode")
            if (!can.skills) add("skills")
            if (!can.modelSelection) add("a model you pick")
            if (!can.reportsCostUsd) add("a dollar cost")
        }
    if (missing.isEmpty()) return null
    val head = missing.dropLast(1)
    return buildString {
        append(provider.label)
        append(if (can.followUps) " has no " else " takes one turn per node, with no ")
        if (head.isNotEmpty()) append("${head.joinToString()} or ")
        append(missing.last())
        append(".")
        if (can.sandbox) append(" It takes a sandbox instead.")
    }
}

@Composable
private fun ApprovalField(
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask before ${ASKABLE_TOOLS.joinToString()}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "The node pauses and the run console offers Allow or Deny. When off, each node's " +
                    "own permission mode decides.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun UpdateField(
    value: Boolean,
    update: Release?,
    onOpenRelease: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Check for new releases", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Once a day, zopf asks GitHub for the latest release and says so here. It sends its " +
                    "own version and nothing else, and never installs anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
    if (update != null) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "zopf ${update.version} is out. You have ${BuildInfo.version}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onOpenRelease) { Text("Open the release") }
        }
    }
    Hint("ZOPF_NO_UPDATE_CHECK=1 turns it off for `zopf` in a terminal too. `zopf check-update` asks on demand.")
}

@Composable
private fun KeepRunsField(
    value: Int,
    onSelect: (Int) -> Unit,
) {
    Text(
        "Runs to keep",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = KeepRunsOptions,
        selected = KeepRunsOptions.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_KEEP_RUNS,
        label = { if (it == 0) "None" else "$it" },
        onSelect = onSelect,
    )
    Hint(
        "A run archives every message, tool call and line of output, so a chatty build step can be " +
            "megabytes. zopf keeps all of it by default.",
    )
    Hint(
        "Pick a number and older runs are deleted the next time zopf starts. The Runs screen and " +
            "`zopf runs` read the same archive, so both lose them.",
    )
}

@Composable
private fun ConcurrencyField(
    value: Int,
    onSelect: (Int) -> Unit,
) {
    Text(
        "Nodes at once",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = (1..MAX_CONCURRENCY).toList(),
        selected = value,
        label = { "$it" },
        onSelect = onSelect,
    )
    Hint(
        "Counted per run. Two workflows going at once can each have this many processes in flight. " +
            "Gates and branches never take a slot, since they run nothing.",
    )
    Hint("Applies to the next run you start. Anything already going keeps the limit it began with.")
}

@Composable
private fun ThemeField(
    value: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    Text(
        "Colour scheme",
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = ThemePreference.entries,
        selected = value,
        label = { it.label },
        onSelect = onSelect,
    )
    Hint(
        if (value == ThemePreference.SYSTEM) {
            "Follows macOS, and changes with it while the app is open."
        } else {
            "Fixed, whichever theme macOS uses. File pickers and the menu bar icon still follow the system."
        },
    )
}

@Composable
private fun TerminalField(
    value: String,
    onChange: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(value) }

    fun set(app: String) {
        draft = app
        if (app.isNotBlank()) onChange(app)
    }

    InspectorField(
        value = draft,
        onValueChange = ::set,
        label = "Terminal app",
        supportingText =
            if (draft.isBlank()) {
                "Blank falls back to $DEFAULT_TERMINAL_APP."
            } else {
                "Opened with: open -a $draft"
            },
        isError = draft.isBlank(),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TerminalSuggestions.filterNot { it.equals(draft.trim(), ignoreCase = true) }.forEach { app ->
            AssistChip(
                onClick = { set(app) },
                label = { Text(app, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun <T> ConnectedChoices(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val text = label(option)
            val checked = option == selected
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    ToggleButton(
                        checked = checked,
                        onCheckedChange = { onSelect(option) },
                        modifier = Modifier.animateWidth(interactionSource),
                        shapes =
                            when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        interactionSource = interactionSource,
                    ) {
                        Text(text, maxLines = 1, softWrap = false)
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        trailingIcon =
                            if (!checked) {
                                null
                            } else {
                                { Icon(ZopfIcons.Check, contentDescription = "Current") }
                            },
                        onClick = {
                            onSelect(option)
                            menuState.dismiss()
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    var settings by remember { mutableStateOf(AppSettings(concurrency = 2, defaultModel = "sonnet")) }
    ZopfTheme {
        SettingsScreen(settings = settings, onChange = { settings = it(settings) })
    }
}
