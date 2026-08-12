package com.dk.zopf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.dk.kuiver.ui.KuiverColors
import com.dk.kuiver.ui.LocalKuiverColors

@Composable
fun KuiverBridge(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors =
        remember(scheme) {
            KuiverColors(
                edge = scheme.outline,
                backEdge = scheme.error,
                labelText = scheme.onSurfaceVariant,
                labelBackground = scheme.surfaceContainerHigh,
                labelBorder = scheme.outlineVariant,
            )
        }
    CompositionLocalProvider(LocalKuiverColors provides colors, content = content)
}
