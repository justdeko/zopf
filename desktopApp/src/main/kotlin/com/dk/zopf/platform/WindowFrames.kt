package com.dk.zopf.platform

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.dk.zopf.store.WindowFrame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

val DefaultWindowSize = DpSize(1180.dp, 780.dp)

fun WindowFrame?.toWindowState(): WindowState {
    val frame = this?.takeIf { it.onSomeScreen() } ?: return WindowState(size = DefaultWindowSize)
    val position =
        if (frame.x != null && frame.y != null) {
            WindowPosition(frame.x!!.dp, frame.y!!.dp)
        } else {
            WindowPosition.PlatformDefault
        }
    return WindowState(size = DefaultWindowSize, position = position)
}

fun WindowState.toFrame(): WindowFrame? {
    val width = size.width.value.toInt()
    val height = size.height.value.toInt()
    if (width <= 0 || height <= 0) return null
    val placed = position.takeIf { it.isSpecified }
    return WindowFrame(
        width = width,
        height = height,
        x = placed?.x?.value?.toInt(),
        y = placed?.y?.value?.toInt(),
    )
}

private fun WindowFrame.onSomeScreen(): Boolean {
    if (x == null || y == null) return true
    val corner = Rectangle(x!!, y!!, 1, 1)
    return GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .screenDevices
        .any { it.defaultConfiguration.bounds.intersects(corner) }
}
