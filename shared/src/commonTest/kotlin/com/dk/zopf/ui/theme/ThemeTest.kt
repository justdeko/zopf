package com.dk.zopf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.dk.zopf.model.NodeType
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.store.ThemePreference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Palette(
    val zopf: ZopfColors,
    val scheme: ColorScheme,
)

@OptIn(ExperimentalTestApi::class)
private fun palette(dark: Boolean): Palette {
    lateinit var captured: Palette
    runDesktopComposeUiTest(10, 10) {
        setContent {
            ZopfTheme(darkTheme = dark) {
                captured = Palette(ZopfTheme.colors, MaterialTheme.colorScheme)
            }
        }
        waitForIdle()
    }
    return captured
}

private fun Color.relativeLuminance(): Float {
    fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

private fun contrast(
    a: Color,
    b: Color,
): Float {
    val (x, y) = a.relativeLuminance() to b.relativeLuminance()
    return (max(x, y) + 0.05f) / (min(x, y) + 0.05f)
}

private fun apart(
    a: Color,
    b: Color,
): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

class NodeColorsTest {
    @Test
    fun `every accent holds contrast on node surfaces`() {
        listOf(false, true).forEach { dark ->
            val (nodes, scheme) = palette(dark).let { it.zopf to it.scheme }
            NodeType.entries.forEach { type ->
                val accent = nodes.node(type).accent

                listOf(scheme.surfaceContainerLowest, scheme.surfaceContainer).forEach { surface ->
                    val ratio = contrast(accent, surface)
                    assertTrue(ratio >= 3f, "$type accent on $surface is $ratio:1 (dark=$dark)")
                }
            }
        }
    }

    @Test
    fun `container content holds contrast against it`() {
        listOf(false, true).forEach { dark ->
            val nodes = palette(dark).zopf
            NodeType.entries.forEach { type ->
                val roles = nodes.node(type)
                val ratio = contrast(roles.onContainer, roles.container)
                assertTrue(ratio >= 3f, "$type onContainer on its container is $ratio:1 (dark=$dark)")
            }
        }
    }

    @Test
    fun `no two node types share a colour`() {
        listOf(false, true).forEach { dark ->
            val nodes = palette(dark).zopf
            val types = NodeType.entries
            types.forEachIndexed { i, a ->
                types.drop(i + 1).forEach { b ->
                    val x = nodes.node(a).accent
                    val y = nodes.node(b).accent

                    val distance = apart(x, y)
                    assertTrue(distance > 0.3f, "$a and $b are $distance apart (dark=$dark)")
                }
            }
        }
    }

    @Test
    fun `a container is the theme surface tinted`() {
        listOf(false, true).forEach { dark ->
            val (nodes, scheme) = palette(dark).let { it.zopf to it.scheme }
            NodeType.entries.forEach { type ->
                val roles = nodes.node(type)
                val fromSurface = contrast(roles.container, scheme.surfaceContainer)
                assertTrue(fromSurface < 2.5f, "$type container drifted from the card (dark=$dark)")
                assertTrue(roles.container != scheme.surfaceContainer, "$type container is untinted")
            }
        }
    }

    @Test
    fun `the claude node tracks the theme primary`() {
        listOf(false, true).forEach { dark ->
            val (nodes, scheme) = palette(dark).let { it.zopf to it.scheme }
            assertEquals(scheme.primary, nodes.node(NodeType.AGENT).accent, "dark=$dark")
        }
    }
}

class StatusColorsTest {
    @Test
    fun `every status accent holds contrast on the surfaces it sits on`() {
        listOf(false, true).forEach { dark ->
            val (statuses, scheme) = palette(dark).let { it.zopf to it.scheme }
            RunStatus.entries.forEach { status ->
                val accent = statuses.status(status).accent

                listOf(scheme.surfaceContainer, scheme.surfaceContainerHigh).forEach { surface ->
                    val ratio = contrast(accent, surface)
                    assertTrue(ratio >= 3f, "$status accent on $surface is $ratio:1 (dark=$dark)")
                }
            }
        }
    }

    @Test
    fun `a badge glyph holds contrast on the status it fills`() {
        val filled =
            listOf(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.STOPPED, RunStatus.DETACHED)

        listOf(false, true).forEach { dark ->
            val statuses = palette(dark).zopf
            filled.forEach { status ->
                val roles = statuses.status(status)
                val ratio = contrast(roles.onAccent, roles.accent)
                assertTrue(ratio >= 4.5f, "$status glyph on its badge is $ratio:1 (dark=$dark)")
            }
        }
    }

    @Test
    fun `the four statuses worth reacting to are told apart by colour`() {
        val decisive =
            listOf(RunStatus.RUNNING, RunStatus.WAITING, RunStatus.SUCCEEDED, RunStatus.FAILED)

        listOf(false, true).forEach { dark ->
            val statuses = palette(dark).zopf
            decisive.forEachIndexed { i, a ->
                decisive.drop(i + 1).forEach { b ->
                    val distance = apart(statuses.status(a).accent, statuses.status(b).accent)
                    assertTrue(distance > 0.4f, "$a and $b are $distance apart (dark=$dark)")
                }
            }
        }
    }

    @Test
    fun `a finished run never wears the colour of a running one`() {
        listOf(false, true).forEach { dark ->
            val statuses = palette(dark).zopf
            assertTrue(
                statuses.status(RunStatus.SUCCEEDED).accent != statuses.status(RunStatus.RUNNING).accent,
                "done and running share a colour (dark=$dark)",
            )
        }
    }
}

class ThemePreferenceTest {
    @OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
    private fun resolve(
        preference: ThemePreference,
        system: SystemTheme,
    ): Boolean {
        var dark = false
        runDesktopComposeUiTest(10, 10) {
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides system) {
                    dark = preference.isDark()
                }
            }
            waitForIdle()
        }
        return dark
    }

    @Test
    fun `the default theme follows the system`() {
        assertEquals(true, resolve(ThemePreference.SYSTEM, SystemTheme.Dark))
        assertEquals(false, resolve(ThemePreference.SYSTEM, SystemTheme.Light))
    }

    @Test
    fun `an override wins over the system`() {
        assertEquals(true, resolve(ThemePreference.DARK, SystemTheme.Light))
        assertEquals(false, resolve(ThemePreference.LIGHT, SystemTheme.Dark))
    }

    @Test
    fun `every preference has a readable button label`() {
        assertEquals(listOf("System", "Light", "Dark"), ThemePreference.entries.map { it.label })
    }
}
