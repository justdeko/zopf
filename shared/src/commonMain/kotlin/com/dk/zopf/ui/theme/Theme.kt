package com.dk.zopf.ui.theme

import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.dk.zopf.store.ThemePreference

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFFAE3F16),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDBCE),
        onPrimaryContainer = Color(0xFF3A0A00),
        secondary = Color(0xFF7C5635),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDDBC),
        onSecondaryContainer = Color(0xFF2B1700),
        tertiary = Color(0xFF7A5900),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDEA0),
        onTertiaryContainer = Color(0xFF261A00),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFFFF8F6),
        onBackground = Color(0xFF231917),
        surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF231917),
        surfaceVariant = Color(0xFFF5DED5),
        onSurfaceVariant = Color(0xFF53433D),
        outline = Color(0xFF85736C),
        outlineVariant = Color(0xFFD8C2BA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF1EB),
        surfaceContainer = Color(0xFFFCEAE3),
        surfaceContainerHigh = Color(0xFFF7E4DC),
        surfaceContainerHighest = Color(0xFFF1DED6),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFFFFB59B),
        onPrimary = Color(0xFF5E1900),
        primaryContainer = Color(0xFF862F13),
        onPrimaryContainer = Color(0xFFFFDBCE),
        secondary = Color(0xFFEFBD93),
        onSecondary = Color(0xFF48290B),
        secondaryContainer = Color(0xFF623F1F),
        onSecondaryContainer = Color(0xFFFFDDBC),
        tertiary = Color(0xFFEFC24E),
        onTertiary = Color(0xFF402D00),
        tertiaryContainer = Color(0xFF5C4300),
        onTertiaryContainer = Color(0xFFFFDEA0),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF1A1210),
        onBackground = Color(0xFFF1DED7),
        surface = Color(0xFF1A1210),
        onSurface = Color(0xFFF1DED7),
        surfaceVariant = Color(0xFF53433D),
        onSurfaceVariant = Color(0xFFD8C2BA),
        outline = Color(0xFFA08D85),
        outlineVariant = Color(0xFF53433D),
        surfaceContainerLowest = Color(0xFF140C0A),
        surfaceContainerLow = Color(0xFF231917),
        surfaceContainer = Color(0xFF271E1A),
        surfaceContainerHigh = Color(0xFF322824),
        surfaceContainerHighest = Color(0xFF3D332E),
    )

private val LightNodeAccents =
    NodeAccents(
        agent = LightScheme.primary,
        shell = Color(0xFF62708A),
        connector = Color(0xFF3D5CC4),
        gate = Color(0xFF8A7500),
        branch = Color(0xFF8B36C6),
        input = Color(0xFF00695C),
    )

private val DarkNodeAccents =
    NodeAccents(
        agent = DarkScheme.primary,
        shell = Color(0xFFA5A2AA),
        connector = Color(0xFF9BB2F8),
        gate = Color(0xFFF5C63F),
        branch = Color(0xFFDE9FF0),
        input = Color(0xFF5ED4BC),
    )

private val LightStatusAccents =
    StatusAccents(
        queued = Color(0xFF7A6A63),
        running = Color(0xFF0F62C4),
        waiting = Color(0xFFA66200),
        succeeded = Color(0xFF17733F),
        failed = LightScheme.error,
        stopped = Color(0xFF6B5952),
        skipped = Color(0xFF86746D),
        detached = Color(0xFF6A45C0),
    )

private val DarkStatusAccents =
    StatusAccents(
        queued = Color(0xFF9A8B85),
        running = Color(0xFF7FB6F5),
        waiting = Color(0xFFF2C14E),
        succeeded = Color(0xFF5FD08D),
        failed = DarkScheme.error,
        stopped = Color(0xFFB6A49D),
        skipped = Color(0xFF8D7F79),
        detached = Color(0xFFC6ADFF),
    )

@Composable
fun ThemePreference.isDark(): Boolean =
    when (this) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

@Composable
fun ZopfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = if (darkTheme) DarkScheme else LightScheme
    val colors =
        remember(scheme, darkTheme) {
            zopfColors(
                scheme = scheme,
                accents = if (darkTheme) DarkNodeAccents else LightNodeAccents,
                statuses = if (darkTheme) DarkStatusAccents else LightStatusAccents,
                dark = darkTheme,
            )
        }
    CompositionLocalProvider(
        LocalZopfColors provides colors,
        LocalContextMenuRepresentation provides rememberContextMenus(scheme),
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = ZopfTypography,
            content = content,
        )
    }
}

@Composable
private fun rememberContextMenus(scheme: ColorScheme) =
    remember(scheme) {
        DefaultContextMenuRepresentation(
            backgroundColor = scheme.surfaceContainerHigh,
            textColor = scheme.onSurface,
            itemHoverColor = scheme.onSurface.copy(alpha = 0.08f),
            disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
        )
    }
