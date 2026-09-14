package com.dk.zopf.runtime.macos

import com.dk.zopf.runtime.Download
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.Version
import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isWritable
import kotlin.io.path.name
import kotlin.io.path.writeText

const val APP_BUNDLE_ID = "com.dk.zopf"

private const val APP_BUNDLE_NAME = "zopf.app"

private const val TOOL_TIMEOUT_SECONDS = 300L

private const val TRANSLOCATION = "/AppTranslocation/"

private const val VERSION_KEY = "CFBundleShortVersionString"

sealed interface UpdateInstall {
    data object Idle : UpdateInstall

    data class Downloading(
        val fraction: Double,
    ) : UpdateInstall

    data object Verifying : UpdateInstall

    data class Ready(
        val version: Version,
    ) : UpdateInstall

    data class Failed(
        val reason: String,
    ) : UpdateInstall
}

class AppUpdate(
    private val bundle: Path? = runningBundle(),
    private val cache: Path = AppPaths.cacheDir,
    private val fetch: (String, Path, (Double) -> Unit) -> Result<Path> = Download::to,
    private val tool: (List<String>) -> Result<String> = ::runTool,
    private val spawn: (List<String>) -> Unit = ::spawnDetached,
) {
    private val _state = MutableStateFlow<UpdateInstall>(UpdateInstall.Idle)

    val state: StateFlow<UpdateInstall> = _state.asStateFlow()

    private val staged: Path get() = cache.resolve("staged/$APP_BUNDLE_NAME")

    fun blocker(): String? =
        when {
            bundle == null -> "zopf installs updates only when it runs as an app. The release page has the new one."
            bundle.toString().contains(TRANSLOCATION) ->
                "macOS is running zopf out of the disk image. Drag it to Applications and it can update itself."
            bundle.parent?.isWritable() != true -> "zopf can't write to ${bundle.parent}, so it can't replace itself there."
            '"' in bundle.toString() -> "zopf can't update itself from a path with a quote in it."
            team == null -> "This build isn't signed, so zopf can't tell a real update from a forged one."
            else -> null
        }

    fun install(release: Release): Result<Unit> =
        runCatching {
            blocker()?.let { error(it) }
            val signer = team ?: error("This build isn't signed, so zopf can't tell a real update from a forged one.")
            val url = release.app.ifBlank { error("Release ${release.version} publishes no app to install. The release page has it.") }

            val waiting = stagedVersion()
            if (waiting != null && waiting >= release.version) {
                _state.value = UpdateInstall.Ready(waiting)
                return@runCatching
            }

            cache.createDirectories()
            val dmg = cache.resolve("zopf-${release.version}.dmg")
            _state.value = UpdateInstall.Downloading(0.0)
            fetch(url, dmg) { _state.value = UpdateInstall.Downloading(it) }.getOrThrow()

            _state.value = UpdateInstall.Verifying
            try {
                mounted(dmg) { volume ->
                    val app = volume.resolve(APP_BUNDLE_NAME)
                    check(app.exists()) { "The disk image holds no $APP_BUNDLE_NAME." }
                    verify(app, signer, release.version)
                    stage(app)
                }
            } finally {
                dmg.deleteIfExists()
            }
            _state.value = UpdateInstall.Ready(release.version)
            Log.info("zopf ${release.version} is staged at $staged")
        }.onFailure { failure ->
            val reason = failure.message ?: "the update wouldn't install"
            _state.value = UpdateInstall.Failed(reason)
            Log.warn("update install failed: $reason")
        }

    fun swap(reopen: Boolean): Result<Unit> =
        runCatching {
            val ready = _state.value as? UpdateInstall.Ready ?: error("There is no update waiting to be installed.")
            val target = bundle ?: error("zopf isn't running from an app bundle.")
            check(staged.exists()) { "The staged ${ready.version} has gone missing from $cache." }
            val script = cache.resolve("swap.sh")
            script.writeText(swapScript(staged, target, reopen))
            Log.info("swapping in zopf ${ready.version} at $target")
            spawn(listOf("/bin/sh", script.toString()))
        }

    private fun stagedVersion(): Version? =
        staged
            .takeIf { it.exists() }
            ?.let { app -> tool(listOf("/usr/bin/defaults", "read", app.resolve("Contents/Info.plist").toString(), VERSION_KEY)) }
            ?.getOrNull()
            ?.trim()
            ?.let(Version::parse)

    private val team: String? by lazy {
        bundle?.let { app ->
            tool(listOf("/usr/bin/codesign", "-dv", "--verbose=4", app.toString()))
                .getOrNull()
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("TeamIdentifier=") }
                ?.removePrefix("TeamIdentifier=")
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "not set" }
        }
    }

    private fun verify(
        app: Path,
        team: String,
        version: Version,
    ) {
        val requirement = "identifier \"$APP_BUNDLE_ID\" and anchor apple generic and certificate leaf[subject.OU] = \"$team\""
        tool(listOf("/usr/bin/codesign", "--verify", "--deep", "--strict", "-R=$requirement", app.toString()))
            .getOrElse { error("The download isn't signed by the team that signed this copy of zopf. Nothing was installed.") }
        tool(listOf("/usr/sbin/spctl", "--assess", "--type", "execute", app.toString()))
            .getOrElse { error("macOS won't vouch for the download, so zopf left it alone.") }
        val found = tool(listOf("/usr/bin/defaults", "read", app.resolve("Contents/Info.plist").toString(), VERSION_KEY))
        val inside = Version.parse(found.getOrNull()?.trim().orEmpty())
        check(inside == version) { "The download says it is ${inside ?: "nothing in particular"}, not $version." }
    }

    private fun stage(app: Path) {
        staged.parent.toFile().deleteRecursively()
        staged.parent.createDirectories()
        tool(listOf("/usr/bin/ditto", app.toString(), staged.toString()))
            .getOrElse { error("The new zopf wouldn't copy out of the disk image: ${it.message}") }
    }

    private fun <T> mounted(
        dmg: Path,
        block: (Path) -> T,
    ): T {
        val volume = Files.createTempDirectory(cache, "mount-")
        tool(listOf("/usr/bin/hdiutil", "attach", dmg.toString(), "-nobrowse", "-readonly", "-mountpoint", volume.toString()))
            .getOrElse {
                runCatching { volume.deleteIfExists() }
                error("The disk image wouldn't mount: ${it.message}")
            }
        try {
            return block(volume)
        } finally {
            tool(listOf("/usr/bin/hdiutil", "detach", volume.toString(), "-quiet"))
            runCatching { volume.deleteIfExists() }
        }
    }
}

internal fun runningBundle(launcher: String? = System.getProperty("jpackage.app-path")): Path? {
    var path = launcher?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.parent
    while (path != null && !path.name.endsWith(".app")) path = path.parent
    return path
}

internal fun swapScript(
    staged: Path,
    target: Path,
    reopen: Boolean,
    pid: Long = ProcessHandle.current().pid(),
): String =
    """
    #!/bin/sh
    # replaces the bundle once the running zopf has quit, keeping the old one until the new one is in place
    set -e
    while kill -0 $pid 2>/dev/null; do sleep 0.2; done
    ditto "$staged" "$target.new"
    rm -rf "$target.old"
    mv "$target" "$target.old"
    mv "$target.new" "$target" || { mv "$target.old" "$target"; exit 1; }
    xattr -dr com.apple.quarantine "$target" 2>/dev/null || true
    rm -rf "$target.old" "$staged"
    ${if (reopen) "open \"$target\"" else ":"}
    """.trimIndent() + "\n"

private fun runTool(argv: List<String>): Result<String> =
    runCatching {
        val process =
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${argv.first()} took too long")
        }
        check(process.exitValue() == 0) { output.trim().lines().lastOrNull() ?: "${argv.first()} failed" }
        output
    }

private fun spawnDetached(argv: List<String>) {
    val log = AppPaths.logsDir.resolve("update.log").toFile()
    ProcessBuilder(argv)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .start()
}
