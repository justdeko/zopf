package com.dk.zopf

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.dk.zopf.platform.WithFullWindowContent
import com.dk.zopf.platform.ZopfMenuBar
import com.dk.zopf.platform.ZopfTray
import com.dk.zopf.platform.bringToFront
import com.dk.zopf.platform.toFrame
import com.dk.zopf.platform.toWindowState
import com.dk.zopf.store.Log
import com.dk.zopf.ui.AppState
import com.dk.zopf.ui.Screen
import java.awt.Desktop
import java.awt.Dimension

fun main() {
    System.setProperty("apple.awt.enableTemplateImages", "true")

    System.setProperty("apple.awt.application.appearance", "system")

    System.setProperty("apple.laf.useScreenMenuBar", "true")

    System.setProperty("apple.awt.application.name", "zopf")

    Log.start("zopf.app")
    Log.installCrashHandler("zopf.app")

    application {
        val app = remember { AppState() }
        val windowState =
            remember {
                app.settings.current.window
                    .toWindowState()
            }
        var windowVisible by remember { mutableStateOf(true) }
        var showRequests by remember { mutableStateOf(0) }

        fun quit() {
            Log.info("quitting")
            app.rememberWindow(windowState.toFrame())
            app.shutdown()
            exitApplication()
        }

        remember {
            runCatching {
                Desktop.getDesktop().setQuitHandler { _, _ -> quit() }
            }
        }

        fun showWindow() {
            windowVisible = true
            windowState.isMinimized = false
            showRequests++
        }

        remember {
            app.onActivateRun = { runId ->
                app.runs.runs
                    .firstOrNull { it.id == runId }
                    ?.let { app.runs.select(it) }
                app.screen = Screen.RUNS
                app.showRunPanel = true
                showWindow()
            }
        }

        ZopfTray(app, onShowWindow = { showWindow() }, onQuit = { quit() })

        @Suppress("DEPRECATION")
        val windowIcon = painterResource("icon.svg")

        Window(
            onCloseRequest = { windowVisible = false },
            state = windowState,
            visible = windowVisible,
            title = "zopf",
            icon = windowIcon,
        ) {
            LaunchedEffect(window) { window.minimumSize = Dimension(800, 600) }

            val windowInfo = LocalWindowInfo.current
            LaunchedEffect(windowInfo, windowVisible) {
                snapshotFlow { windowInfo.isWindowFocused }
                    .collect { app.windowFocused = it && windowVisible }
            }
            LaunchedEffect(window, showRequests) {
                if (showRequests > 0) window.bringToFront()
            }

            ZopfMenuBar(app, onHideWindow = { windowVisible = false })
            WithFullWindowContent(windowState.placement) {
                App(app)
            }
        }
    }
}
