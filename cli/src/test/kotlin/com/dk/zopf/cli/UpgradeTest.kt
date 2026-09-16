package com.dk.zopf.cli

import com.dk.zopf.runtime.Download
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.Version
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliInstallTest {
    private val dir: Path = Files.createTempDirectory("zopf-locate")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `an install is a versioned directory under opt with the link pointing into it`() {
        val home = dir.resolve("opt/zopf-cli-1.3.1")
        home.resolve("bin").createDirectories()
        home.resolve("bin/zopf").writeText("")
        dir.resolve("bin").createDirectories()
        Files.createSymbolicLink(dir.resolve("bin/zopf"), home.resolve("bin/zopf"))

        assertEquals(dir, locateCliInstall(home)?.prefix)
        assertNull(locateCliInstall(dir.resolve("opt/zopf-cli-1.3.1/bin")), "the lib directory's parent is the install, not bin")
        assertNull(locateCliInstall(dir.resolve("opt/elsewhere")), "an install zopf didn't lay out is not one to replace")
        assertNull(locateCliInstall(null))

        Files.delete(dir.resolve("bin/zopf"))
        assertNull(locateCliInstall(home), "without the link there is nothing to repoint")
    }
}

class UpgradeTest {
    private val dir: Path = Files.createTempDirectory("zopf-upgrade-test")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private val running = Version(1, 3, 1)

    private val install: CliInstall =
        run {
            val home = dir.resolve("opt/zopf-cli-$running")
            home.resolve("bin").createDirectories()
            home.resolve("bin/zopf").writeText("#!/bin/sh\n")
            dir.resolve("bin").createDirectories()
            val link = dir.resolve("bin/zopf")
            Files.createSymbolicLink(link, home.resolve("bin/zopf"))
            CliInstall(prefix = dir, current = home, link = link)
        }

    private val tarball: Path = tarball(Version(1, 4, 0))

    private fun tarball(version: Version): Path {
        val staging = Files.createTempDirectory(dir, "pack")
        val root = staging.resolve("zopf-cli-$version")
        root.resolve("bin").createDirectories()
        root.resolve("bin/zopf").writeText("#!/bin/sh\necho $version\n")
        root.resolve("bin/zopf").toFile().setExecutable(true)
        val archive = dir.resolve("zopf-cli-$version.tar.gz")
        val packed =
            ProcessBuilder("/usr/bin/tar", "czf", archive.toString(), "-C", staging.toString(), "zopf-cli-$version")
                .redirectErrorStream(true)
                .start()
        check(packed.waitFor() == 0) { packed.inputStream.bufferedReader().readText() }
        return archive
    }

    private fun release(version: Version = Version(1, 4, 0)) =
        Release(
            version = version,
            url = "https://github.com/justdeko/zopf/releases/tag/v$version",
            cli = "https://github.com/justdeko/zopf/releases/download/v$version/zopf-cli-$version.tar.gz",
        )

    private fun upgrade(
        args: List<String> = emptyList(),
        install: CliInstall? = this.install,
        release: Result<Release> = Result.success(release()),
        source: Path = tarball,
        checksum: (String) -> Result<String> = { Result.success("${Download.sha256(tarball)}  zopf-cli-1.4.0.tar.gz") },
    ): Pair<Int, Streams> {
        val streams = Streams()
        val code =
            upgradeCli(
                Options.parse(args, UPGRADE_OPTIONS, UPGRADE_SWITCHES),
                streams.out,
                streams.err,
                install = install,
                releases = { release },
                running = running,
                download = { _, target -> runCatching { Files.copy(source, target) } },
                checksum = checksum,
            )
        return code to streams
    }

    @Test
    fun `an upgrade unpacks the release and points the link at it`() {
        val (code, streams) = upgrade()

        assertEquals(EXIT_OK, code, streams.errors())
        assertEquals(dir.resolve("opt/zopf-cli-1.4.0/bin/zopf"), install.link.readSymbolicLink())
        assertTrue("1.3.1 → 1.4.0" in streams.output(), streams.output())
        assertTrue(install.current.exists(), "the version that was running stays put to fall back on")
    }

    @Test
    fun `an upgrade removes every version older than the one running`() {
        val stale = dir.resolve("opt/zopf-cli-1.2.0").also { it.resolve("bin").createDirectories() }

        val (code, streams) = upgrade()

        assertEquals(EXIT_OK, code, streams.errors())
        assertTrue(!stale.exists(), "1.2.0 should be gone")
        assertTrue(install.current.exists(), "the running version stays until the next upgrade")
        assertTrue(dir.resolve("opt/zopf-cli-1.4.0").exists())
    }

    @Test
    fun `a tarball that is not what github published is refused`() {
        val (code, streams) = upgrade(checksum = { Result.success("${"0".repeat(64)}  zopf-cli-1.4.0.tar.gz") })

        assertEquals(EXIT_FAILED, code)
        assertTrue("Nothing was installed" in streams.errors(), streams.errors())
        assertEquals(install.current.resolve("bin/zopf"), install.link.readSymbolicLink())
        assertTrue(!dir.resolve("opt/zopf-cli-1.4.0").exists())
    }

    @Test
    fun `an unverifiable tarball is installed with a note`() {
        val (code, streams) = upgrade(checksum = { Result.failure(IllegalStateException("404")) })

        assertEquals(EXIT_OK, code, streams.errors())
        assertTrue("wasn't verified" in streams.errors(), streams.errors())
    }

    @Test
    fun `an archive holding no zopf leaves the link alone`() {
        val (code, streams) = upgrade(source = tarball(Version(9, 9, 9)))

        assertEquals(EXIT_FAILED, code)
        assertTrue("Nothing was installed" in streams.errors(), streams.errors())
        assertEquals(install.current.resolve("bin/zopf"), install.link.readSymbolicLink())
    }

    @Test
    fun `the release already installed installs nothing`() {
        val (code, streams) = upgrade(release = Result.success(release(running)))

        assertEquals(EXIT_OK, code)
        assertTrue("is the latest release" in streams.output(), streams.output())
    }

    @Test
    fun `a pinned version reinstalls nothing when it is the one running`() {
        val (code, streams) = upgrade(args = listOf("--version", "1.3.1"), release = Result.success(release(running)))

        assertEquals(EXIT_OK, code)
        assertTrue("already installed" in streams.output(), streams.output())
    }

    @Test
    fun `a dry run says what it would do and downloads nothing`() {
        val (code, streams) = upgrade(args = listOf("--dry-run"))

        assertEquals(EXIT_OK, code)
        assertTrue("would install 1.4.0" in streams.output(), streams.output())
        assertTrue(!dir.resolve("opt/zopf-cli-1.4.0").exists())
    }

    @Test
    fun `a copy the installer didn't lay out says how to reinstall`() {
        val (code, streams) = upgrade(install = null)

        assertEquals(EXIT_FAILED, code)
        assertTrue("install.sh" in streams.errors(), streams.errors())
    }

    @Test
    fun `a release github won't talk about fails`() {
        val (code, streams) = upgrade(release = Result.failure(IllegalStateException("GitHub answered 404")))

        assertEquals(EXIT_FAILED, code)
        assertTrue("GitHub answered 404" in streams.errors(), streams.errors())
    }
}
