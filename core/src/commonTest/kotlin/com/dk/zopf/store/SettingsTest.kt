package com.dk.zopf.store

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
    private val dirs = mutableListOf<Path>()

    private fun tempFile(): Path = Files.createTempDirectory("zopf-settings").also { dirs.add(it) }.resolve("settings.json")

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a first launch writes the defaults out, so there is a file to edit`() {
        val file = tempFile()

        val settings = SettingsStore(file).read().getOrThrow()

        assertEquals(AppSettings(), settings)
        assertTrue(file.exists())

        val written = file.readText()
        listOf("terminalApp", "defaultModel", "concurrency", "inlineApproval", "theme").forEach {
            assertTrue(it in written, written)
        }
    }

    @Test
    fun `what the file says is what the app gets`() {
        val file = tempFile()
        file.writeText("""{"terminalApp": "iTerm", "defaultModel": "sonnet", "concurrency": 4}""")

        val settings = SettingsStore(file).read().getOrThrow()

        assertEquals(AppSettings(terminalApp = "iTerm", defaultModel = "sonnet", concurrency = 4), settings)
    }

    @Test
    fun `a key this version doesn't know doesn't cost the ones it does`() {
        val file = tempFile()
        file.writeText("""{"terminalApp": "Ghostty", "somethingNewer": true}""")

        assertEquals("Ghostty", SettingsStore(file).read().getOrThrow().terminalApp)
    }

    @Test
    fun `values that would break something are clamped rather than obeyed`() {
        assertEquals(MAX_CONCURRENCY, AppSettings(concurrency = 200).sanitized().concurrency)
        assertEquals(1, AppSettings(concurrency = 0).sanitized().concurrency)
        assertEquals(1, AppSettings(concurrency = -3).sanitized().concurrency)
    }

    @Test
    fun `a blank value means unset, not an empty one`() {
        val sanitized = AppSettings(terminalApp = "  ", defaultModel = "  ").sanitized()

        assertEquals(DEFAULT_TERMINAL_APP, sanitized.terminalApp)

        assertNull(sanitized.defaultModel)
    }

    @Test
    fun `a file that doesn't parse is reported rather than ignored`() {
        val file = tempFile()
        file.writeText("{ this is not json")

        val result = SettingsStore(file).read()

        assertTrue(result.isFailure)
        assertTrue(file.toString() in result.exceptionOrNull()!!.message.orEmpty())
        assertEquals(AppSettings(), SettingsStore(file).load())
    }

    @Test
    fun `settings survive a round trip`() {
        val file = tempFile()
        val settings =
            AppSettings(
                terminalApp = "iTerm",
                defaultModel = "opus",
                concurrency = 3,
                inlineApproval = false,
                theme = ThemePreference.DARK,
            )

        SettingsStore(file).save(settings)

        assertEquals(settings, SettingsStore(file).read().getOrThrow())
    }

    @Test
    fun `a file written before a key existed takes that key's default`() {
        val file = tempFile()
        file.writeText("""{"terminalApp": "Terminal", "defaultModel": null, "concurrency": 3}""")

        val settings = SettingsStore(file).read().getOrThrow()

        assertTrue(settings.inlineApproval)
        assertEquals(ThemePreference.SYSTEM, settings.theme)
        assertEquals(KEEP_EVERY_RUN, settings.keepRuns)
    }

    @Test
    fun `the theme is written in the case a person would type it`() {
        val file = tempFile()

        SettingsStore(file).save(AppSettings(theme = ThemePreference.LIGHT))

        assertTrue(""""theme": "light"""" in file.readText(), file.readText())
    }

    @Test
    fun `a theme is read leniently, whatever case it is in and however unheard of`() {
        val file = tempFile()
        file.writeText("""{"terminalApp": "Ghostty", "concurrency": 4, "theme": "sepia"}""")

        val settings = SettingsStore(file).read().getOrThrow()

        assertEquals(ThemePreference.SYSTEM, settings.theme)
        assertEquals("Ghostty", settings.terminalApp)
        assertEquals(4, settings.concurrency)

        val cased = tempFile().also { it.writeText("""{"theme": "Dark"}""") }
        assertEquals(ThemePreference.DARK, SettingsStore(cased).read().getOrThrow().theme)
    }

    @Test
    fun `an edit is both in effect and on disk`() {
        val file = tempFile()
        val live = LiveSettings(store = SettingsStore(file))

        live.update { it.copy(terminalApp = "Ghostty") }.getOrThrow()

        assertEquals("Ghostty", live.current.terminalApp)
        assertEquals("Ghostty", SettingsStore(file).read().getOrThrow().terminalApp)
    }

    @Test
    fun `an edit is clamped the same way the file is`() {
        val live = LiveSettings(AppSettings(concurrency = 99))
        assertEquals(MAX_CONCURRENCY, live.current.concurrency)

        live.update { it.copy(concurrency = 0, terminalApp = "  ") }

        assertEquals(1, live.current.concurrency)
        assertEquals(DEFAULT_TERMINAL_APP, live.current.terminalApp)
    }

    @Test
    fun `a settings file that can't be written still changes the running app`() {
        val unwritable = Files.createTempDirectory("zopf-settings").also { dirs.add(it) }
        val live = LiveSettings(store = SettingsStore(unwritable))

        val result = live.update { it.copy(defaultModel = "haiku") }

        assertTrue(result.isFailure)
        assertEquals("haiku", live.current.defaultModel)
    }

    @Test
    fun `a fresh install keeps every run until it is told not to`() {
        val settings = AppSettings()

        assertEquals(KEEP_EVERY_RUN, settings.keepRuns)
        assertEquals(KEEP_EVERY_RUN, settings.sanitized().keepRuns)
        assertEquals(500, settings.copy(keepRuns = 500).sanitized().keepRuns)
    }
}
