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
