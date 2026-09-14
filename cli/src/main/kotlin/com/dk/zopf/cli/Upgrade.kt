package com.dk.zopf.cli

import com.dk.zopf.runtime.Download
import com.dk.zopf.runtime.GitHubReleases
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.Version
import com.dk.zopf.runtime.ZOPF_REPO
import com.dk.zopf.store.BuildInfo
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

val UPGRADE_OPTIONS = setOf("version")

val UPGRADE_SWITCHES = setOf("dry-run")

private const val INSTALL_PREFIX = "zopf-cli-"

private const val UNTAR_TIMEOUT_SECONDS = 120L

private const val INSTALLER = "curl -fsSL https://raw.githubusercontent.com/$ZOPF_REPO/main/install.sh | sh"

data class CliInstall(
    val prefix: Path,
    val current: Path,
    val link: Path,
) {
    val opt: Path get() = prefix.resolve("opt")

    fun versionDir(version: Version): Path = opt.resolve("$INSTALL_PREFIX$version")
}

fun upgradeCli(
    options: Options,
    out: PrintStream,
    err: PrintStream,
    install: CliInstall? = locateCliInstall(),
    releases: (Version?) -> Result<Release> = ::publishedRelease,
    running: Version? = Version.parse(BuildInfo.version),
    download: (String, Path) -> Result<Path> = { url, target -> Download.to(url, target) },
    checksum: (String) -> Result<String> = Download::text,
): Int {
    if (install == null) {
        err.println("zopf: this copy wasn't put there by the installer, so it can't replace itself. Reinstall with")
        err.println("  $INSTALLER")
        return EXIT_FAILED
    }
    val pinned = options.one("version")?.let { Version.parse(it) ?: throw UsageError("--version takes x.y.z, not \"$it\"") }
    val release =
        releases(pinned).getOrElse {
            err.println("zopf: couldn't ask GitHub for ${pinned?.toString() ?: "the latest release"}: ${it.message}")
            return EXIT_FAILED
        }
    if (pinned == null && running != null && release.version <= running) {
        out.println("zopf $running is the latest release")
        return EXIT_OK
    }
    val target = install.versionDir(release.version)
    if (target == install.current) {
        out.println("zopf ${release.version} is already what's installed")
        return EXIT_OK
    }
    val url = release.cli
    if (url.isBlank()) {
        err.println("zopf: release ${release.version} publishes no command line to install. ${release.url}")
        return EXIT_FAILED
    }
    if (options.has("dry-run")) {
        out.println("would install ${release.version} to $target and point ${install.link} at it")
        return EXIT_OK
    }

    val staging = Files.createTempDirectory("zopf-upgrade")
    try {
        val archive = staging.resolve(url.substringAfterLast('/'))
        out.println("Downloading zopf ${release.version}")
        download(url, archive).getOrElse {
            err.println("zopf: couldn't download ${archive.name}: ${it.message}")
            return EXIT_FAILED
        }
        when (val checked = verified(archive, checksum("$url.sha256").getOrNull())) {
            null -> err.println("note: ${release.version} publishes no .sha256, so the download wasn't verified")
            else ->
                if (!checked) {
                    err.println("zopf: ${archive.name} isn't the file GitHub says it is. Nothing was installed.")
                    return EXIT_FAILED
                }
        }

        target.toFile().deleteRecursively()
        untar(archive, install.opt).getOrElse {
            err.println("zopf: couldn't unpack ${archive.name}: ${it.message}")
            return EXIT_FAILED
        }
        val binary = target.resolve("bin/zopf")
        if (!binary.isExecutable()) {
            target.toFile().deleteRecursively()
            err.println("zopf: ${archive.name} holds no bin/zopf where one was expected. Nothing was installed.")
            return EXIT_FAILED
        }
        relink(install.link, binary)
    } finally {
        staging.toFile().deleteRecursively()
    }

    out.println("Updated zopf ${running ?: "unknown"} → ${release.version} at ${install.link}")
    return EXIT_OK
}

fun publishedRelease(version: Version?): Result<Release> = if (version == null) GitHubReleases().latest() else GitHubReleases().tagged(version)

fun locateCliInstall(home: Path? = installedHome()): CliInstall? {
    val current = home?.takeIf { it.name.startsWith(INSTALL_PREFIX) } ?: return null
    if (!current.resolve("bin/zopf").exists()) return null
    val opt = current.parent?.takeIf { it.name == "opt" } ?: return null
    val prefix = opt.parent ?: return null
    val link = prefix.resolve("bin/zopf")
    if (!link.isSymbolicLink() || !link.exists()) return null
    return CliInstall(prefix, current, link)
}

private fun installedHome(): Path? =
    runCatching {
        val jar =
            Path.of(
                CliInstall::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        jar.parent?.takeIf { it.name == "lib" }?.parent
    }.getOrNull()

private fun verified(
    archive: Path,
    published: String?,
): Boolean? {
    val expected = published?.trim()?.substringBefore(' ')?.takeIf { it.length == 64 } ?: return null
    return expected.equals(Download.sha256(archive), ignoreCase = true)
}

private fun untar(
    archive: Path,
    into: Path,
): Result<Unit> =
    runCatching {
        val process =
            ProcessBuilder("/usr/bin/tar", "xzf", archive.toString(), "-C", into.toString())
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(UNTAR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("tar took too long")
        }
        check(process.exitValue() == 0) { output.trim().lines().lastOrNull() ?: "tar failed" }
    }

private fun relink(
    link: Path,
    binary: Path,
) {
    val temp = link.resolveSibling(".${link.name}-next")
    temp.deleteIfExists()
    Files.createSymbolicLink(temp, binary)
    Files.move(temp, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
