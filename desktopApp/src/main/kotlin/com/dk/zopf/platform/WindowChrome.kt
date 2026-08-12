package com.dk.zopf.platform

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import com.dk.zopf.ui.LocalTitleBarInset
import com.dk.zopf.ui.LocalWindowDragArea
import com.dk.zopf.ui.WindowDragArea

private val TitleBarHeight = 28.dp

@Composable
fun FrameWindowScope.WithFullWindowContent(
    placement: WindowPlacement,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(window) {
        window.rootPane.apply {
            putClientProperty("apple.awt.fullWindowContent", true)
            putClientProperty("apple.awt.transparentTitleBar", true)
            putClientProperty("apple.awt.windowTitleVisible", false)
        }
    }

    val dragArea: WindowDragArea =
        remember(window, placement) {
            if (placement == WindowPlacement.Fullscreen) {
                { content -> content() }
            } else {
                { content -> WindowDraggableArea(content = content) }
            }
        }
    CompositionLocalProvider(
        LocalTitleBarInset provides
            if (placement == WindowPlacement.Fullscreen) 0.dp else TitleBarHeight,
        LocalWindowDragArea provides dragArea,
        content = content,
    )
}
