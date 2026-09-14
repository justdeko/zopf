package com.dk.zopf.runtime.macos

import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.Version
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunningBundleTest {
    @Test
    fun `the bundle is the app the launcher sits in`() {
        listOf(
            "/Applications/zopf.app/Contents/MacOS/zopf" to "/Applications/zopf.app",
            "/Users/someone/Applications/zopf.app/Contents/MacOS/zopf" to "/Users/someone/Applications/zopf.app",
            "/Users/someone/dev/zopf/desktopApp/build/compose/binaries/main/app/zopf.app/Contents/MacOS/zopf" to
                "/Users/someone/dev/zopf/desktopApp/build/compose/binaries/main/app/zopf.app",
            "/usr/local/bin/zopf" to null,
            "" to null,
            null to null,
        ).forEach { (launcher, expected) ->
            assertEquals(expected, runningBundle(launcher)?.toString(), launcher ?: "no launcher")
        }
    }
}

class SwapScriptTest {
    private val staged = Path.of("/Users/someone/Library/Caches/zopf/staged/zopf.app")
    private val target = Path.of("/Applications/zopf.app")

    @Test
    fun `the swap waits for the running app and keeps the old bundle until the new one lands`() {
        val script = swapScript(staged, target, reopen = false, pid = 4242)

        assertTrue(script.startsWith("#!/bin/sh\n"), script)
        assertTrue("while kill -0 4242 2>/dev/null; do sleep 0.2; done" in script, script)
        assertTrue("""mv "/Applications/zopf.app" "/Applications/zopf.app.old"""" in script, script)
        assertTrue("""|| { mv "/Applications/zopf.app.old" "/Applications/zopf.app"; exit 1; }""" in script, script)
        assertTrue("open" !in script, "quitting for good must not reopen the app")
    }

    @Test
    fun `restarting reopens the new bundle`() {
        assertTrue("""open "/Applications/zopf.app"""" in swapScript(staged, target, reopen = true, pid = 1))
    }
}

class AppUpdateTest {
    private val dir: Path = Files.createTempDirectory("zopf-app-update")
    private val bundle: Path = dir.resolve("Applications/zopf.app").also { it.createDirectories() }
    private val cache: Path = dir.resolve("cache").also { it.createDirectories() }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private val release =
        Release(
            version = Version(1, 4, 0),
            url = "https://github.com/justdeko/zopf/releases/tag/v1.4.0",
            app = "https://github.com/justdeko/zopf/releases/download/v1.4.0/zopf-1.4.0.dmg",
        )

    private val calls = mutableListOf<List<String>>()

    private val spawned = mutableListOf<List<String>>()

    private fun tools(
        team: String? = "TEAM123",
        verify: Result<String> = Result.success(""),
        assess: Result<String> = Result.success(""),
        inside: String = "1.4.0",
    ): (List<String>) -> Result<String> =
        { argv ->
            calls += argv
            when {
                argv.contains("-dv") ->
                    team?.let { Result.success("Identifier=com.dk.zopf\nTeamIdentifier=$it\n") }
                        ?: Result.success("Identifier=com.dk.zopf\nTeamIdentifier=not set\n")

                argv.contains("attach") -> {
                    Path.of(argv.last()).resolve("zopf.app").createDirectories()
                    Result.success("")
                }

                argv.contains("--verify") -> verify
                argv.contains("--assess") -> assess
                argv.contains("read") -> Result.success("$inside\n")
                argv.contains("/usr/bin/ditto") -> {
                    Path.of(argv.last()).createDirectories()
                    Result.success("")
                }

                else -> Result.success("")
            }
        }

    private fun update(
        bundle: Path? = this.bundle,
        tool: (List<String>) -> Result<String> = tools(),
        fetch: (String, Path, (Double) -> Unit) -> Result<Path> = { _, target, onProgress ->
            onProgress(1.0)
            Result.success(target)
        },
    ) = AppUpdate(bundle = bundle, cache = cache, fetch = fetch, tool = tool, spawn = { spawned += it })

    @Test
    fun `an update is staged when the signature matches the running app`() {
        val app = update()

        assertNull(app.blocker())
        assertTrue(app.install(release).isSuccess, "${app.state.value}")
        assertEquals(UpdateInstall.Ready(Version(1, 4, 0)), app.state.value)
        assertTrue(cache.resolve("staged/zopf.app").exists())

        val requirement = calls.first { it.contains("--verify") }.first { it.startsWith("-R=") }
        assertTrue("""identifier "com.dk.zopf"""" in requirement, requirement)
        assertTrue("""certificate leaf[subject.OU] = "TEAM123"""" in requirement, requirement)
    }

    @Test
    fun `an update the running app's team didn't sign is refused`() {
        val app = update(tool = tools(verify = Result.failure(IllegalStateException("code object is not signed at all"))))

        assertTrue(app.install(release).isFailure)
        assertTrue(app.state.value is UpdateInstall.Failed)
        assertTrue(!cache.resolve("staged/zopf.app").exists(), "nothing may be staged from a download that failed a check")
    }

    @Test
    fun `an update macOS won't vouch for is refused`() {
        val app = update(tool = tools(assess = Result.failure(IllegalStateException("rejected"))))

        assertTrue(app.install(release).isFailure)
    }

    @Test
    fun `a download of another version than the release names is refused`() {
        val app = update(tool = tools(inside = "1.3.1"))

        assertTrue(app.install(release).isFailure)
        assertEquals("The download says it is 1.3.1, not 1.4.0.", (app.state.value as UpdateInstall.Failed).reason)
    }

    @Test
    fun `an unsigned or unbundled build says why it can't update itself`() {
        assertNotNull(update(bundle = null).blocker())
        assertNotNull(update(tool = tools(team = null)).blocker())
        assertNotNull(
            update(bundle = dir.resolve("private/var/folders/x/AppTranslocation/A/d/zopf.app").also { it.createDirectories() }).blocker(),
        )
        assertTrue(update(bundle = null).install(release).isFailure)
    }

    @Test
    fun `a release already staged is not downloaded again`() {
        var downloads = 0
        val app =
            update(
                fetch = { _, target, _ ->
                    downloads += 1
                    Result.success(target)
                },
            )

        app.install(release)
        app.install(release)

        assertEquals(1, downloads, "a relaunch before the swap must not fetch the disk image again")
        assertEquals(UpdateInstall.Ready(Version(1, 4, 0)), app.state.value)
    }

    @Test
    fun `nothing is swapped in before an update is staged`() {
        val app = update()

        assertTrue(app.swap(reopen = true).isFailure)
        assertTrue(spawned.isEmpty())

        app.install(release)
        assertTrue(app.swap(reopen = true).isSuccess)
        assertEquals(listOf(listOf("/bin/sh", cache.resolve("swap.sh").toString())), spawned)
    }
}
