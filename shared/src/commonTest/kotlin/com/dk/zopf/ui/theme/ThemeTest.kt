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
import com.dk.zopf.store.ThemePreference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeColorsTest {
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

    @Test
    fun `every accent holds contrast on the surfaces nodes are drawn on`() {
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
    fun `what sits on a container can be read against it`() {
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
    fun `no two types read as the same colour`() {
        listOf(false, true).forEach { dark ->
            val nodes = palette(dark).zopf
            val types = NodeType.entries
            types.forEachIndexed { i, a ->
                types.drop(i + 1).forEach { b ->
                    val x = nodes.node(a).accent
                    val y = nodes.node(b).accent

                    val apart = abs(x.red - y.red) + abs(x.green - y.green) + abs(x.blue - y.blue)
                    assertTrue(apart > 0.3f, "$a and $b are $apart apart (dark=$dark)")
                }
            }
        }
    }

    @Test
    fun `a container is the theme's own surface, tinted`() {
        listOf(false, true).forEach { dark ->
            val (nodes, scheme) = palette(dark).let { it.zopf to it.scheme }
            NodeType.entries.forEach { type ->
                val roles = nodes.node(type)
                val fromSurface = contrast(roles.container, scheme.surfaceContainer)
                assertTrue(fromSurface < 2f, "$type container drifted from the card (dark=$dark)")
                assertTrue(roles.container != scheme.surfaceContainer, "$type container is untinted")
            }
        }
    }

    @Test
    fun `the Claude node tracks the theme's primary`() {
        listOf(false, true).forEach { dark ->
            val (nodes, scheme) = palette(dark).let { it.zopf to it.scheme }
            assertEquals(scheme.primary, nodes.node(NodeType.AGENT).accent, "dark=$dark")
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
    fun `the default follows the system, whichever way it is set`() {
        assertEquals(true, resolve(ThemePreference.SYSTEM, SystemTheme.Dark))
        assertEquals(false, resolve(ThemePreference.SYSTEM, SystemTheme.Light))
    }

    @Test
    fun `an override wins over the system, which is the whole point of having one`() {
        assertEquals(true, resolve(ThemePreference.DARK, SystemTheme.Light))
        assertEquals(false, resolve(ThemePreference.LIGHT, SystemTheme.Dark))
    }

    @Test
    fun `every preference has a button label that isn't shouted`() {
        assertEquals(listOf("System", "Light", "Dark"), ThemePreference.entries.map { it.label })
    }
}
