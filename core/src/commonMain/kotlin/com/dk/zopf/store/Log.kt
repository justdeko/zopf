package com.dk.zopf.store

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.notExists
import kotlin.io.path.writeText

object Log {
    private const val MAX_BYTES = 2L * 1024 * 1024

    val file: Path get() = AppPaths.logsDir.resolve("zopf.log")

    private val lock = ReentrantLock()

    private var stream: java.io.Writer? = null

    fun start(who: String) {
        info("$who ${BuildInfo.version} · ${System.getProperty("java.version")} · ${System.getProperty("os.version")}")
    }

    fun info(message: String) = write("INFO", message, null)

    fun warn(
        message: String,
        failure: Throwable? = null,
    ) = write("WARN", message, failure)

    fun error(
        message: String,
        failure: Throwable? = null,
    ) = write("ERROR", message, failure)

    fun installCrashHandler(who: String) {
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            error("$who died on ${thread.name}", failure)
        }
    }

    private fun write(
        level: String,
        message: String,
        failure: Throwable?,
    ) {
        val line =
            buildString {
                append(Instant.now())
                append("  ")
                append(level.padEnd(5))
                append("  ")
                append(message)
                failure?.let {
                    append('\n')
                    append(StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) })
                }
            }
        lock.withLock {
            runCatching {
                roll()
                val writer = stream ?: openWriter().also { stream = it }
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun openWriter(): java.io.Writer {
        AppPaths.logsDir.createDirectories()
        if (file.notExists()) file.writeText("")
        return java.io.FileWriter(file.toFile(), true).buffered()
    }

    private fun roll() {
        val current = file
        if (current.notExists() || current.fileSize() < MAX_BYTES) return
        stream?.let { runCatching { it.close() } }
        stream = null
        val previous = AppPaths.logsDir.resolve("zopf.log.1")
        runCatching {
            previous.deleteIfExists()
            current.moveTo(previous)
        }
    }
}
