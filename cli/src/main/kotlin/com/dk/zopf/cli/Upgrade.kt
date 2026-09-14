package com.dk.zopf.cli

import com.dk.zopf.runtime.Download
import com.dk.zopf.runtime.GitHubReleases
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.Version
import com.dk.zopf.runtime.ZOPF_REPO
import com.dk.zopf.store.BuildInfo
import com.dk.zopf.util.Strings
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
        err.println(Strings.Cli.NOT_INSTALLED_BY_INSTALLER)
        err.println("  $INSTALLER")
        return EXIT_FAILED
    }
    val pinned = options.one("version")?.let { Version.parse(it) ?: throw UsageError(Strings.Cli.versionTakesSemver(it)) }
    val release =
        releases(pinned).getOrElse {
            err.println(Strings.Cli.couldntAskGitHubFor(pinned?.toString() ?: Strings.Cli.THE_LATEST_RELEASE, it.message))
            return EXIT_FAILED
        }
    if (pinned == null && running != null && release.version <= running) {
        out.println(Strings.Cli.latestAlready("$running"))
        return EXIT_OK
    }
    val target = install.versionDir(release.version)
    if (target == install.current) {
        out.println(Strings.Cli.alreadyInstalled("${release.version}"))
        return EXIT_OK
    }
    val url = release.cli
    if (url.isBlank()) {
        err.println(Strings.Cli.noCliAsset("${release.version}", release.url))
        return EXIT_FAILED
    }
    if (options.has("dry-run")) {
        out.println(Strings.Cli.wouldInstall("${release.version}", target, install.link))
        return EXIT_OK
    }

    val staging = Files.createTempDirectory("zopf-upgrade")
    try {
        val archive = staging.resolve(url.substringAfterLast('/'))
        out.println(Strings.Cli.downloading("${release.version}"))
        download(url, archive).getOrElse {
            err.println(Strings.Cli.couldntDownload(archive.name, it.message))
            return EXIT_FAILED
        }
        when (val checked = verified(archive, checksum("$url.sha256").getOrNull())) {
            null -> err.println(Strings.Cli.noChecksum("${release.version}"))
            else ->
                if (!checked) {
                    err.println(Strings.Cli.checksumMismatch(archive.name))
                    return EXIT_FAILED
                }
        }

        target.toFile().deleteRecursively()
        untar(archive, install.opt).getOrElse {
            err.println(Strings.Cli.couldntUnpack(archive.name, it.message))
            return EXIT_FAILED
        }
        val binary = target.resolve("bin/zopf")
        if (!binary.isExecutable()) {
            target.toFile().deleteRecursively()
            err.println(Strings.Cli.noBinaryInside(archive.name))
            return EXIT_FAILED
        }
        relink(install.link, binary)
    } finally {
        staging.toFile().deleteRecursively()
    }

    out.println(Strings.Cli.updated(running?.toString() ?: Strings.Cli.UNKNOWN_VERSION, "${release.version}", install.link))
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
            error(Strings.Cli.TAR_TOOK_TOO_LONG)
        }
        check(process.exitValue() == 0) { output.trim().lines().lastOrNull() ?: Strings.Cli.TAR_FAILED }
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
