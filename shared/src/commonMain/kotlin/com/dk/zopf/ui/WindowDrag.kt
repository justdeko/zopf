package com.dk.zopf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

typealias WindowDragArea = @Composable (content: @Composable () -> Unit) -> Unit

val LocalWindowDragArea = staticCompositionLocalOf<WindowDragArea> { { content -> content() } }
