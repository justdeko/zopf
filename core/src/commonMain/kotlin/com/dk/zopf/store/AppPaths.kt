package com.dk.zopf.store

import java.nio.file.Path
import kotlin.io.path.createDirectories

object AppPaths {
    val appSupport: Path by lazy {
        homeDir().resolve("Library/Application Support/zopf").also { it.createDirectories() }
    }

    val workspacesFile: Path get() = appSupport.resolve("workspaces.json")
    val settingsFile: Path get() = appSupport.resolve("settings.json")
    val updateFile: Path get() = appSupport.resolve("update.json")
    val runsDir: Path by lazy { appSupport.resolve("runs").also { it.createDirectories() } }

    val logsDir: Path by lazy {
        homeDir().resolve("Library/Logs/zopf").also { it.createDirectories() }
    }

    val defaultWorkspace: Path get() = homeDir().resolve(".zopf")
}
