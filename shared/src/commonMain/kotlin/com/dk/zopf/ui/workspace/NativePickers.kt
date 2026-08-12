package com.dk.zopf.ui.workspace

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

fun chooseDirectory(
    title: String,
    startIn: Path? = null,
): Path? {
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    System.setProperty(key, "true")
    return try {
        open(title, startIn)
    } finally {
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}

fun chooseFile(
    title: String,
    extensions: Set<String>,
    startIn: Path? = null,
): Path? = open(title, startIn) { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }

private fun open(
    title: String,
    startIn: Path?,
    filter: ((File, String) -> Boolean)? = null,
): Path? {
    val dialog =
        FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            startIn?.let { directory = it.toString() }
            filter?.let { accept -> setFilenameFilter { dir, name -> accept(dir, name) } }
            isVisible = true
        }
    val dir = dialog.directory
    val file = dialog.file
    return if (dir != null && file != null) Paths.get(dir, file) else null
}
