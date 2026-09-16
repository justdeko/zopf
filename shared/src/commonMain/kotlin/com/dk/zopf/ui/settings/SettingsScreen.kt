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
import androidx.compose.material3.LinearProgressIndicator
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
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.agent.ASKABLE_TOOLS
import com.dk.zopf.runtime.macos.UpdateInstall
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.BuildInfo
import com.dk.zopf.store.DEFAULT_KEEP_RUNS
import com.dk.zopf.store.DEFAULT_TERMINAL_APP
import com.dk.zopf.store.KEEP_EVERY_RUN
import com.dk.zopf.store.Log
import com.dk.zopf.store.MAX_CONCURRENCY
import com.dk.zopf.store.NotifyLevel
import com.dk.zopf.store.ThemePreference
import com.dk.zopf.ui.editor.Gap
import com.dk.zopf.ui.editor.InspectorField
import com.dk.zopf.ui.editor.ModelField
import com.dk.zopf.ui.editor.SectionLabel
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme
import com.dk.zopf.util.Strings
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
    install: UpdateInstall = UpdateInstall.Idle,
    blocker: String? = null,
    onOpenRelease: () -> Unit = {},
    onInstall: () -> Unit = {},
    onRestart: () -> Unit = {},
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
            SectionLabel(Strings.Settings.RUNNING)
            ConcurrencyField(settings.concurrency) { chosen -> onChange { it.copy(concurrency = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.AGENTS)
            AgentField(settings.defaultProvider) { chosen -> onChange { it.copy(defaultProvider = chosen) } }
            if (settings.defaultProvider.capabilities.modelSelection) {
                Gap(4)
                ModelField(
                    provider = settings.defaultProvider,
                    selected = settings.defaultModel,
                    onSelect = { model -> onChange { it.copy(defaultModel = model) } },
                    noneLabel = Strings.Labels.cliDefault(settings.defaultProvider.cliValue),
                    supportingText = Strings.Settings.defaultModelHint(settings.defaultProvider.label),
                )
            }
            Hint(Strings.Settings.DEFAULTS_SCOPE)

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.PERMISSIONS)
            Gap(4)
            ApprovalField(settings.inlineApproval) { on -> onChange { it.copy(inlineApproval = on) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.NOTIFICATIONS)
            NotifyField(settings.notify) { chosen -> onChange { it.copy(notify = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.APPEARANCE)
            ThemeField(settings.theme) { chosen -> onChange { it.copy(theme = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.TAKE_OVER)
            Gap(4)
            TerminalField(settings.terminalApp) { app -> onChange { it.copy(terminalApp = app) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.HISTORY)
            KeepRunsField(settings.keepRuns) { chosen -> onChange { it.copy(keepRuns = chosen) } }

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.UPDATES)
            Gap(4)
            UpdateField(
                settings = settings,
                update = update,
                install = install,
                blocker = blocker,
                onOpenRelease = onOpenRelease,
                onInstall = onInstall,
                onRestart = onRestart,
                onChange = onChange,
            )

            Gap()
            HorizontalDivider()
            Gap(8)
            SectionLabel(Strings.Settings.OPEN_SOURCE)
            Gap(4)
            TextButton({ showLicenses = true }, Modifier.padding(start = 0.dp)) {
                Text(Strings.Settings.OPEN_SOURCE_LICENSES)
            }

            Gap(20)
            Text(
                Strings.version(BuildInfo.version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Strings.Settings.writtenTo(settingsFile),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Strings.Settings.loggingTo(Log.file),
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
        Strings.Settings.DEFAULT_CLI_LABEL,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = AgentProviderId.entries,
        selected = value,
        label = { it.label },
        onSelect = onSelect,
    )
    Hint(Strings.Settings.DEFAULT_CLI_HINT)
    caveats(value)?.let { Hint(it) }
}

private fun caveats(provider: AgentProviderId): String? {
    val can = provider.capabilities
    val missing =
        buildList {
            if (!can.followUps) add(Strings.Settings.NO_FOLLOW_UPS)
            if (!can.resumeInTerminal) add(Strings.Settings.NO_TAKE_OVER)
            if (!can.inlineApproval) add(Strings.Settings.NO_INLINE_APPROVAL)
            if (!can.toolPermissions) add(Strings.Settings.NO_PERMISSION_MODE)
            if (!can.skills) add(Strings.Settings.NO_SKILLS)
            if (!can.modelSelection) add(Strings.Settings.NO_MODEL_CHOICE)
            if (!can.reportsCostUsd) add(Strings.Settings.NO_COST_REPORT)
        }
    if (missing.isEmpty()) return null
    return Strings.Settings.capabilityNote(provider.label, missing, can.followUps, can.sandbox)
}

@Composable
private fun ApprovalField(
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Strings.Settings.askBefore(ASKABLE_TOOLS.joinToString()), style = MaterialTheme.typography.bodyMedium)
            Text(
                Strings.Settings.ASK_BEFORE_HINT,
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
    settings: AppSettings,
    update: Release?,
    install: UpdateInstall,
    blocker: String?,
    onOpenRelease: () -> Unit,
    onInstall: () -> Unit,
    onRestart: () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(Strings.Settings.CHECK_FOR_RELEASES, style = MaterialTheme.typography.bodyMedium)
            Text(
                Strings.Settings.CHECK_FOR_RELEASES_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = settings.checkForUpdates, onCheckedChange = { on -> onChange { it.copy(checkForUpdates = on) } })
    }
    if (settings.checkForUpdates) {
        Gap(4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Strings.Settings.INSTALL_ON_ITS_OWN, style = MaterialTheme.typography.bodyMedium)
                Text(
                    Strings.Settings.INSTALL_ON_ITS_OWN_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = settings.autoUpdate,
                enabled = blocker == null,
                onCheckedChange = { on -> onChange { it.copy(autoUpdate = on) } },
            )
        }
    }
    blocker?.let { Hint(it) }
    if (update != null) {
        Gap(8)
        Text(
            Strings.Updates.available("${update.version}", BuildInfo.version),
            style = MaterialTheme.typography.bodyMedium,
        )
        when (install) {
            is UpdateInstall.Downloading -> {
                Gap(8)
                LinearProgressIndicator({ install.fraction.toFloat() }, Modifier.fillMaxWidth())
                Hint(Strings.Settings.downloading((install.fraction * 100).toInt()))
            }

            UpdateInstall.Verifying -> Hint(Strings.Settings.CHECKING_SIGNATURE)

            is UpdateInstall.Ready ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Strings.Settings.readyToInstall("${install.version}"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onRestart) { Text(Strings.Settings.RESTART_NOW) }
                }

            is UpdateInstall.Failed ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(install.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onOpenRelease) { Text(Strings.Settings.OPEN_THE_RELEASE) }
                }

            UpdateInstall.Idle ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (blocker == null) {
                        TextButton(onClick = onInstall) { Text(Strings.Settings.INSTALL_IT) }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onOpenRelease) { Text(Strings.Settings.OPEN_THE_RELEASE) }
                }
        }
    }
    Hint(Strings.Settings.UPDATE_ENV_HINT)
}

@Composable
private fun KeepRunsField(
    value: Int,
    onSelect: (Int) -> Unit,
) {
    Text(
        Strings.Settings.RUNS_TO_KEEP,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = KeepRunsOptions,
        selected = KeepRunsOptions.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_KEEP_RUNS,
        label = { if (it == KEEP_EVERY_RUN) Strings.Settings.KEEP_ALL else "$it" },
        onSelect = onSelect,
    )
    Hint(
        Strings.Settings.RUNS_TO_KEEP_HINT,
    )
    Hint(
        Strings.Settings.PRUNE_HINT,
    )
}

@Composable
private fun ConcurrencyField(
    value: Int,
    onSelect: (Int) -> Unit,
) {
    Text(
        Strings.Settings.CONCURRENCY,
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
        Strings.Settings.CONCURRENCY_HINT,
    )
    Hint(Strings.Settings.CONCURRENCY_APPLIES_NEXT)
}

@Composable
private fun NotifyField(
    value: NotifyLevel,
    onSelect: (NotifyLevel) -> Unit,
) {
    Text(
        Strings.Settings.NOTIFY_ABOUT,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ConnectedChoices(
        options = NotifyLevel.entries,
        selected = value,
        label = { it.label },
        onSelect = onSelect,
    )
    Hint(Strings.Settings.notifyHint(value.hint))
}

@Composable
private fun ThemeField(
    value: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    Text(
        Strings.Settings.COLOUR_SCHEME,
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
            Strings.Settings.THEME_SYSTEM_HINT
        } else {
            Strings.Settings.THEME_FIXED_HINT
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
        label = Strings.Settings.TERMINAL_APP_LABEL,
        supportingText =
            if (draft.isBlank()) {
                Strings.Settings.terminalFallback(DEFAULT_TERMINAL_APP)
            } else {
                Strings.Settings.openedWith(draft)
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
                                { Icon(ZopfIcons.Check, contentDescription = Strings.Settings.CURRENT) }
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
