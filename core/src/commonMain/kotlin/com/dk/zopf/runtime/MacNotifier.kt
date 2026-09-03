package com.dk.zopf.runtime

import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.Log
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private const val BUILD_TIMEOUT_SECONDS = 60L
private const val SIGN_TIMEOUT_SECONDS = 20L
private const val POST_TIMEOUT_SECONDS = 40L
private const val REGISTER_TIMEOUT_SECONDS = 20L
private const val MAX_BODY = 240

private const val LSREGISTER =
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

private val SOURCES = listOf("main.swift", "Info.plist", "icon.icns")

class MacNotifier(
    root: Path = AppPaths.appSupport,
) : Notifier {
    private val app = root.resolve("zopf-notify.app")
    private val executable = app.resolve("Contents/MacOS/zopf-notify")
    private val stamp = root.resolve("zopf-notify.stamp")
    private val installRoot = root

    private val helper: Path? by lazy { install() }

    override fun post(notification: RunNotification) {
        val argv = helper?.let { listOf(it.toString()) + flags(notification) + positional(notification) }
        if (argv == null) {
            osascript(notification)
            return
        }
        val failure = runCatching { spawn(argv).awaitOrKill(POST_TIMEOUT_SECONDS) }.exceptionOrNull()
        if (failure != null) {
            Log.warn("zopf-notify would not run: ${failure.message}")
            osascript(notification)
        }
    }

    override fun ask(
        notification: RunNotification,
        timeoutSeconds: Long,
        onAnswer: (String) -> Unit,
    ): NotificationHandle {
        val binary = helper
        if (binary == null || notification.actions.isEmpty()) {
            post(notification)
            return SilentNotifier.ask(notification, timeoutSeconds, onAnswer)
        }

        val argv =
            listOf(binary.toString(), "--respond", "--timeout", timeoutSeconds.toString()) +
                notification.actions.flatMap { listOf("--action", "${it.id}=${it.label}") } +
                flags(notification) +
                positional(notification)

        val process =
            runCatching { spawn(argv) }.getOrElse {
                Log.warn("zopf-notify would not run: ${it.message}")
                post(notification)
                return SilentNotifier.ask(notification, timeoutSeconds, onAnswer)
            }

        val reader =
            Thread {
                val answer = runCatching { process.inputStream.bufferedReader().readLine() }.getOrNull()
                if (!answer.isNullOrBlank()) onAnswer(answer.trim())
            }
        reader.isDaemon = true
        reader.start()

        return object : NotificationHandle {
            override fun cancel() {
                process.destroy()
                withdraw(notification.key)
            }
        }
    }

    override fun withdraw(key: String) {
        val binary = helper ?: return
        runCatching { spawn(listOf(binary.toString(), "--withdraw", key)).awaitOrKill(POST_TIMEOUT_SECONDS) }
    }

    private fun flags(notification: RunNotification): List<String> =
        listOf("--id", notification.key) +
            if (notification.thread.isBlank()) emptyList() else listOf("--thread", notification.thread)

    private fun positional(notification: RunNotification): List<String> =
        listOf(
            collapse(notification.body).ifBlank { "(no output)" }.trimTo(MAX_BODY),
            collapse(notification.title).ifBlank { "zopf" },
            collapse(notification.subtitle),
            notification.sound,
        )

    private fun spawn(argv: List<String>): Process =
        ProcessBuilder(argv)
            .redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            .start()

    private fun osascript(notification: RunNotification) {
        val body = collapse(notification.body).ifBlank { "(no output)" }.trimTo(MAX_BODY)
        val title = collapse(notification.title).ifBlank { "zopf" }
        val subtitle = collapse(notification.subtitle)
        val clauses =
            buildList {
                add("display notification (item 1 of argv) with title (item 2 of argv)")
                if (subtitle.isNotBlank()) add("subtitle (item 3 of argv)")
                if (notification.sound.isNotBlank()) add("sound name (item 4 of argv)")
            }
        val script = "on run argv\n  " + clauses.joinToString(" ") + "\nend run"
        runCatching {
            spawn(listOf("osascript", "-e", script, body, title, subtitle, notification.sound))
                .awaitOrKill(POST_TIMEOUT_SECONDS)
        }.onFailure { Log.warn("couldn't post a notification: ${it.message}") }
    }

    private fun install(): Path? {
        val sources = SOURCES.associateWith { resource(it) ?: return null }
        val wanted = fingerprint(sources)
        if (executable.isRegularFile() && stampMatches(wanted)) return executable

        val swiftc = CommandLookup.which("swiftc") ?: return fallbackWarn("swiftc isn't installed")
        val staging = Files.createTempDirectory(installRoot, ".zopf-notify-")
        val staged = staging.resolve("zopf-notify.app")
        return try {
            staged.resolve("Contents/MacOS").createDirectories()
            staged.resolve("Contents/Resources").createDirectories()
            staged.resolve("Contents/Info.plist").writeBytes(sources.getValue("Info.plist"))
            staged.resolve("Contents/Resources/icon.icns").writeBytes(sources.getValue("icon.icns"))
            val source = staging.resolve("main.swift")
            source.writeBytes(sources.getValue("main.swift"))

            val binary = staged.resolve("Contents/MacOS/zopf-notify")
            val built =
                ProcessBuilder(swiftc.toString(), "-O", "-o", binary.toString(), source.toString())
                    .redirectErrorStream(true)
                    .start()
            val log = built.inputStream.bufferedReader().readText()
            if (!built.waitFor(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS) || built.exitValue() != 0) {
                return fallbackWarn("the notifier wouldn't build: ${log.trim().takeLast(300)}")
            }

            CommandLookup.which("codesign")?.let {
                ProcessBuilder(it.toString(), "--force", "--sign", "-", staged.toString())
                    .redirectErrorStream(true)
                    .start()
                    .awaitOrKill(SIGN_TIMEOUT_SECONDS)
            }

            app.toFile().deleteRecursively()
            Files.move(staged, app)
            if (Path.of(LSREGISTER).exists()) {
                ProcessBuilder(LSREGISTER, "-f", app.toString())
                    .redirectErrorStream(true)
                    .start()
                    .awaitOrKill(REGISTER_TIMEOUT_SECONDS)
            }
            stamp.writeText(wanted)
            Log.info("built the notifier bundle at $app")
            executable.takeIf { it.isRegularFile() }
        } catch (failure: Exception) {
            fallbackWarn("the notifier wouldn't build: ${failure.message}")
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun stampMatches(wanted: String): Boolean = runCatching { stamp.readText().trim() == wanted }.getOrDefault(false)

    private fun fallbackWarn(reason: String): Path? {
        Log.warn("$reason. Notifications will go through osascript, under Script Editor's icon.")
        runCatching { stamp.deleteIfExists() }
        return null
    }

    private fun resource(name: String): ByteArray? = MacNotifier::class.java.getResourceAsStream("/notifier/$name")?.use { it.readBytes() }

    private fun fingerprint(sources: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        SOURCES.forEach { digest.update(sources.getValue(it)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Process.awaitOrKill(seconds: Long) {
        if (!waitFor(seconds, TimeUnit.SECONDS)) destroy()
    }
}

private fun collapse(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

private fun String.trimTo(limit: Int): String = if (length <= limit) this else take(limit - 1).trimEnd() + "…"
