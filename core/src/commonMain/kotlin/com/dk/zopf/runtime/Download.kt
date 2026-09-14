package com.dk.zopf.runtime

import com.dk.zopf.store.BuildInfo
import com.dk.zopf.util.Strings
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

private const val CONNECT_TIMEOUT_MILLIS = 10_000

private const val READ_TIMEOUT_MILLIS = 30_000

private const val CHUNK = 64 * 1024

object Download {
    fun to(
        url: String,
        target: Path,
        onProgress: (Double) -> Unit = {},
    ): Result<Path> =
        runCatching {
            val parent = target.parent ?: error(Strings.Updates.nowhereToLand(target))
            parent.createDirectories()
            val partial = Files.createTempFile(parent, ".${target.name}-", ".part")
            try {
                open(url).use { source ->
                    val total = source.length
                    var read = 0L
                    partial.outputStream().use { sink ->
                        val buffer = ByteArray(CHUNK)
                        while (true) {
                            val count = source.stream.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                            read += count
                            if (total > 0) onProgress((read.toDouble() / total).coerceIn(0.0, 1.0))
                        }
                    }
                    check(total <= 0 || read == total) { Strings.Updates.downloadStopped(read, total) }
                }
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
                target
            } finally {
                partial.deleteIfExists()
            }
        }

    fun text(url: String): Result<String> = runCatching { open(url).use { it.stream.bufferedReader().readText() } }

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: String): Body {
        check(url.startsWith("https://")) { Strings.Updates.notHttps(url) }
        val connection =
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "zopf/${BuildInfo.version}")
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
            }
        val code =
            runCatching { connection.responseCode }.getOrElse {
                connection.disconnect()
                throw it
            }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            error(Strings.Updates.answeredWith(url, code))
        }
        return Body(connection)
    }
}

private class Body(
    private val connection: HttpURLConnection,
) : AutoCloseable {
    val stream = connection.inputStream

    val length = connection.contentLengthLong

    override fun close() {
        runCatching { stream.close() }
        connection.disconnect()
    }
}
