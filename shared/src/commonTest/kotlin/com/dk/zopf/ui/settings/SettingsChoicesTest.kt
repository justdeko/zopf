package com.dk.zopf.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.Version
import com.dk.zopf.runtime.macos.UpdateInstall
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.MAX_CONCURRENCY
import com.dk.zopf.store.ThemePreference
import com.dk.zopf.ui.theme.ZopfTheme
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsChoicesTest {
    private val settings = AppSettings()

    private fun runSettings(
        width: Int,
        current: AppSettings = settings,
        body: suspend DesktopComposeUiTest.(MutableList<AppSettings>) -> Unit,
    ) {
        val written = mutableListOf<AppSettings>()
        runDesktopComposeUiTest(width, 700) {
            setContent {
                ZopfTheme(darkTheme = false) {
                    Box(Modifier.size(width.dp, 700.dp)) {
                        SettingsScreen(
                            settings = current,
                            onChange = { written += it(current) },
                            settingsFile = Path.of("/tmp/settings.json"),
                        )
                    }
                }
            }
            waitForIdle()
            body(written)
        }
    }

    @Test
    fun `a narrow window overflows without crashing`() {
        runSettings(width = 360) {
            onNodeWithText("1").assertExists()
        }
    }

    @Test
    fun `a wide window shows every choice`() {
        runSettings(width = 900) {
            (1..MAX_CONCURRENCY).forEach { onNodeWithText("$it").assertExists() }
            ThemePreference.entries.forEach { onNodeWithText(it.label).assertExists() }
        }
    }

    @Test
    fun `the runs-to-keep row survives a narrow window`() {
        runSettings(width = 360) {
            onNodeWithText("All").assertExists()
        }
        runSettings(width = 900) { written ->
            listOf("All", "50", "200", "1000").forEach { onNodeWithText(it).assertExists() }

            onNodeWithText("1000").performScrollTo().performClick()
            assertEquals(listOf(1000), written.map { it.keepRuns })
        }
    }

    @Test
    fun `holding a button does not wrap neighbouring labels`() {
        runSettings(width = 900) {
            fun labelHeights() =
                ThemePreference.entries.associate {
                    it.label to onNodeWithText(it.label, useUnmergedTree = true).getBoundsInRoot().height
                }

            fun buttonWidths() =
                ThemePreference.entries.associate {
                    it.label to onNodeWithText(it.label).getBoundsInRoot().width
                }

            onNodeWithText(ThemePreference.DARK.label).performScrollTo()
            waitForIdle()

            val relaxedHeights = labelHeights()
            val relaxedWidths = buttonWidths()

            onNodeWithText(ThemePreference.DARK.label).performMouseInput {
                moveTo(center)
                press()
            }
            waitForIdle()

            assertTrue(
                buttonWidths().any { (label, width) -> width < relaxedWidths.getValue(label) },
                "no button was squeezed, so this test is no longer testing anything: " +
                    "$relaxedWidths then ${buttonWidths()}",
            )
            assertEquals(relaxedHeights, labelHeights(), "a label gained a line while a neighbour was held")
        }
    }

    @Test
    fun `re-clicking the chosen option keeps it`() {
        runSettings(width = 900, current = AppSettings(theme = ThemePreference.DARK)) { written ->
            onNodeWithText(ThemePreference.DARK.label).performScrollTo().performClick()
            waitForIdle()
            assertEquals(listOf(ThemePreference.DARK), written.map { it.theme })
        }
    }
}

@OptIn(ExperimentalTestApi::class)
class SettingsUpdatesTest {
    private val release = Release(Version(1, 4, 0), "https://github.com/justdeko/zopf/releases/tag/v1.4.0")

    private fun runUpdates(
        settings: AppSettings = AppSettings(),
        update: Release? = release,
        install: UpdateInstall = UpdateInstall.Idle,
        blocker: String? = null,
        body: suspend DesktopComposeUiTest.(MutableList<String>) -> Unit,
    ) {
        val pressed = mutableListOf<String>()
        runDesktopComposeUiTest(900, 700) {
            setContent {
                ZopfTheme(darkTheme = false) {
                    Box(Modifier.size(900.dp, 700.dp)) {
                        SettingsScreen(
                            settings = settings,
                            onChange = {},
                            settingsFile = Path.of("/tmp/settings.json"),
                            update = update,
                            install = install,
                            blocker = blocker,
                            onInstall = { pressed += "install" },
                            onRestart = { pressed += "restart" },
                        )
                    }
                }
            }
            waitForIdle()
            body(pressed)
        }
    }

    @Test
    fun `a release zopf can install offers to install it`() {
        runUpdates { pressed ->
            onNodeWithText("Install it").performScrollTo().performClick()
            assertEquals(listOf("install"), pressed)
        }
    }

    @Test
    fun `a copy that can't replace itself says why and offers the release instead`() {
        runUpdates(blocker = "zopf can't write to /Applications, so it can't replace itself there.") {
            onNodeWithText("zopf can't write to /Applications, so it can't replace itself there.").assertExists()
            onNodeWithText("Open the release").assertExists()
            onNodeWithText("Install it").assertDoesNotExist()
        }
    }

    @Test
    fun `a staged release offers a restart`() {
        runUpdates(install = UpdateInstall.Ready(Version(1, 4, 0))) { pressed ->
            onNodeWithText("Restart now").performScrollTo().performClick()
            assertEquals(listOf("restart"), pressed)
        }
    }

    @Test
    fun `nothing about installing shows while no release is waiting`() {
        runUpdates(update = null) {
            onNodeWithText("Install it").assertDoesNotExist()
            onNodeWithText("Restart now").assertDoesNotExist()
        }
    }

    @Test
    fun `turning the check off takes the automatic switch with it`() {
        runUpdates(settings = AppSettings(checkForUpdates = false, autoUpdate = false)) {
            onNodeWithText("Install them on its own").assertDoesNotExist()
        }
    }
}
