package com.dk.zopf.store

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText

internal fun Path.writeTextAtomically(text: String) {
    if (isDirectory()) throw IOException("$this is a directory, not a file zopf can write")
    val dir = parent ?: return writeText(text)
    dir.createDirectories()
    val temp = Files.createTempFile(dir, ".$name-", ".tmp")
    try {
        temp.writeText(text)
        runCatching {
            Files.move(temp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temp, this, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.deleteIfExists()
    }
}
