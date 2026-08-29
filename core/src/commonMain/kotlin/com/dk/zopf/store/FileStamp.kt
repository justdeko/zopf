package com.dk.zopf.store

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

data class FileStamp(
    val modifiedAtNanos: Long,
    val size: Long,
)

fun fileStamp(file: Path): FileStamp? =
    runCatching {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        FileStamp(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size())
    }.getOrNull()
