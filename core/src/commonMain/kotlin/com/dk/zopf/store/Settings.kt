package com.dk.zopf.store

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dk.zopf.model.AgentProviderId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

const val DEFAULT_CONCURRENCY = 2

const val MAX_CONCURRENCY = 8

const val DEFAULT_TERMINAL_APP = "Terminal"

@Serializable(with = ThemePreferenceSerializer::class)
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

internal object ThemePreferenceSerializer : KSerializer<ThemePreference> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.dk.zopf.store.ThemePreference", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: ThemePreference,
    ) = encoder.encodeString(value.name.lowercase())

    override fun deserialize(decoder: Decoder): ThemePreference {
        val raw = decoder.decodeString()
        return ThemePreference.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ThemePreference.SYSTEM
    }
}

const val KEEP_EVERY_RUN = 0

const val DEFAULT_KEEP_RUNS = KEEP_EVERY_RUN

const val MAX_KEEP_RUNS = 5_000

@Serializable
data class WindowFrame(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null,
)

@Serializable
data class AppSettings(
    val terminalApp: String = DEFAULT_TERMINAL_APP,
    val defaultProvider: AgentProviderId = AgentProviderId.CLAUDE,
    val defaultModel: String? = null,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val inlineApproval: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val keepRuns: Int = DEFAULT_KEEP_RUNS,
    val checkForUpdates: Boolean = true,
    val window: WindowFrame? = null,
) {
    fun sanitized(): AppSettings =
        AppSettings(
            terminalApp = terminalApp.trim().ifBlank { DEFAULT_TERMINAL_APP },
            defaultProvider = defaultProvider,
            defaultModel = defaultModel?.trim()?.takeIf { it.isNotEmpty() },
            concurrency = concurrency.coerceIn(1, MAX_CONCURRENCY),
            inlineApproval = inlineApproval,
            theme = theme,
            keepRuns = keepRuns.coerceIn(0, MAX_KEEP_RUNS),
            checkForUpdates = checkForUpdates,
            window = window?.takeIf { it.width > 0 && it.height > 0 },
        )
}

class SettingsStore(
    private val file: Path = AppPaths.settingsFile,
) {
    fun read(): Result<AppSettings> {
        if (!file.exists()) {
            val defaults = AppSettings()

            save(defaults)
            return Result.success(defaults)
        }
        return runCatching { zopfJson.decodeFromString(AppSettings.serializer(), file.readText()).sanitized() }
            .recoverCatching { failure ->
                throw IllegalStateException("$file couldn't be read: ${failure.message}", failure)
            }
    }

    fun load(): AppSettings = read().getOrElse { AppSettings() }

    fun save(settings: AppSettings): Result<Unit> =
        runCatching {
            file.writeTextAtomically(zopfJson.encodeToString(AppSettings.serializer(), settings.sanitized()))
        }
}

@Stable
class LiveSettings(
    initial: AppSettings = AppSettings(),
    private val store: SettingsStore? = null,
) {
    var current: AppSettings by mutableStateOf(initial.sanitized())
        private set

    fun update(change: (AppSettings) -> AppSettings): Result<Unit> {
        val next = change(current).sanitized()
        if (next == current) return Result.success(Unit)
        current = next
        return store?.save(next) ?: Result.success(Unit)
    }
}
