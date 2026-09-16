package com.dk.zopf.runtime.macos

import com.dk.zopf.runtime.run.RunNotification
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.Log
import com.dk.zopf.util.Strings
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private const val NOTIFIER_LIBRARY = "libzopf-notify.dylib"

private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
private const val OSASCRIPT_TIMEOUT_SECONDS = 40L
private const val MAX_BODY = 240

private const val LSREGISTER =
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"

class MacNotifier(
    private val library: Path? = System.getProperty(RESOURCES_DIR_PROPERTY)?.let { Path.of(it, NOTIFIER_LIBRARY) },
) : Notifier {
    private val native: Boolean by lazy { start() }

    private val waiting = ConcurrentHashMap<String, (String) -> Unit>()

    private val deadlines =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "zopf-notify-deadlines").apply { isDaemon = true }
        }

    override fun post(notification: RunNotification) {
        if (native) show(notification) else osascript(notification)
    }

    override fun ask(
        notification: RunNotification,
        timeoutSeconds: Long,
        onAnswer: (String) -> Unit,
    ): NotificationHandle {
        if (!native) {
            osascript(notification)
            return SilentNotifier.ask(notification, timeoutSeconds, onAnswer)
        }
        val key = notification.key
        waiting[key] = onAnswer
        show(notification)
        val deadline = deadlines.schedule({ withdraw(key) }, timeoutSeconds, TimeUnit.SECONDS)
        return object : NotificationHandle {
            override fun cancel() {
                deadline.cancel(false)
                withdraw(key)
            }
        }
    }

    override fun withdraw(key: String) {
        waiting.remove(key)
        if (native) nativeWithdraw(key)
    }

    private fun show(notification: RunNotification) {
        nativePost(
            notification.key,
            collapse(notification.title).ifBlank { Strings.APP_NAME },
            collapse(notification.subtitle),
            body(notification),
            notification.thread,
            notification.sound,
            notification.actions.map { it.id }.toTypedArray(),
            notification.actions.map { it.label }.toTypedArray(),
        )
    }

    private fun start(): Boolean {
        removeBuiltHelper()
        if (library == null || !library.isRegularFile()) return fallback("$NOTIFIER_LIBRARY isn't in the app's resources")
        runCatching { System.load(library.toString()) }
            .onFailure { return fallback("$library wouldn't load: ${it.message}") }
        if (!nativeStart()) return fallback("this process isn't an app bundle")
        Thread(::answer, "zopf-notify-answers").apply { isDaemon = true }.start()
        return true
    }

    private fun answer() {
        while (true) {
            val (key, action) = nativeAwaitAnswer().split('\n', limit = 2).takeIf { it.size == 2 } ?: continue
            val onAnswer = waiting.remove(key) ?: continue
            runCatching { onAnswer(action) }.onFailure { Log.warn("a notification answer failed: ${it.message}") }
        }
    }

    private fun fallback(reason: String): Boolean {
        Log.warn("$reason. Notifications will go through osascript, under Script Editor's icon.")
        return false
    }

    private fun removeBuiltHelper() {
        val helper = AppPaths.appSupport.resolve("zopf-notify.app")
        runCatching {
            AppPaths.appSupport.resolve("zopf-notify.stamp").deleteIfExists()
            if (!helper.exists()) return
            if (Path.of(LSREGISTER).exists()) {
                ProcessBuilder(LSREGISTER, "-u", helper.toString()).redirectErrorStream(true).start().waitFor()
            }
            helper.toFile().deleteRecursively()
        }.onFailure { Log.warn("couldn't remove $helper: ${it.message}") }
    }

    private fun osascript(notification: RunNotification) {
        val title = collapse(notification.title).ifBlank { Strings.APP_NAME }
        val subtitle = collapse(notification.subtitle)
        val clauses =
            buildList {
                add("display notification (item 1 of argv) with title (item 2 of argv)")
                if (subtitle.isNotBlank()) add("subtitle (item 3 of argv)")
                if (notification.sound.isNotBlank()) add("sound name (item 4 of argv)")
            }
        val script = "on run argv\n  " + clauses.joinToString(" ") + "\nend run"
        runCatching {
            val process =
                ProcessBuilder("osascript", "-e", script, body(notification), title, subtitle, notification.sound)
                    .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                    .start()
            if (!process.waitFor(OSASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroy()
        }.onFailure { Log.warn("couldn't post a notification: ${it.message}") }
    }

    private fun body(notification: RunNotification): String = collapse(notification.body).ifBlank { Strings.Notifications.FALLBACK_BODY }.trimTo(MAX_BODY)

    private external fun nativeStart(): Boolean

    private external fun nativePost(
        key: String,
        title: String,
        subtitle: String,
        body: String,
        thread: String,
        sound: String,
        actionIds: Array<String>,
        actionLabels: Array<String>,
    )

    private external fun nativeWithdraw(key: String)

    private external fun nativeAwaitAnswer(): String
}

private fun collapse(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

private fun String.trimTo(limit: Int): String = if (length <= limit) this else take(limit - 1).trimEnd() + "…"
