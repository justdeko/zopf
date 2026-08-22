package com.dk.zopf.platform

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Desktop

fun ComposeWindow.bringToFront() {
    runCatching {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
            desktop.requestForeground(true)
        }
    }
    toFront()
    requestFocus()
}
