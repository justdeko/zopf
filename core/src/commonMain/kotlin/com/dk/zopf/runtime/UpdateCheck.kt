package com.dk.zopf.runtime

import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.BuildInfo
import com.dk.zopf.store.Log
import com.dk.zopf.store.writeTextAtomically
import com.dk.zopf.store.zopfJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

const val ZOPF_REPO = "justdeko/zopf"

private const val CHECK_EVERY_HOURS = 24L

private const val REACH_TIMEOUT_MILLIS = 5_000

data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String): Version? {
            val digits = raw.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            val parts = digits.split('.').map { it.toIntOrNull() }
            val major = parts.firstOrNull() ?: return null
            return Version(major, parts.getOrNull(1) ?: 0, parts.getOrNull(2) ?: 0)
        }
    }
}

data class Release(
    val version: Version,
    val url: String,
)

fun interface ReleaseSource {
    fun latest(): Result<Release>
}

@Serializable
data class UpdateState(
    val checkedAt: String = "",
    val latest: String = "",
    val url: String = "",
    val announced: String = "",
)

fun updateChecksSilenced(): Boolean = envSet("ZOPF_NO_UPDATE_CHECK") || envSet("DO_NOT_TRACK")

private fun envSet(name: String): Boolean {
    val value = System.getenv(name)?.trim() ?: return false
    return value.isNotEmpty() && value != "0" && !value.equals("false", ignoreCase = true)
}

class UpdateCheck(
    private val source: ReleaseSource = GitHubReleases(),
    private val file: Path = AppPaths.updateFile,
    current: String = BuildInfo.version,
    private val now: () -> Instant = Instant::now,
) {
    val running: Version? = Version.parse(current)

    fun cached(): Release? = read().let(::newer)

    fun refresh(): Release? {
        if (updateChecksSilenced()) return null
        val state = read()
        if (!stale(state)) return newer(state)
        return fetch().map(::newerThanRunning).getOrElse { newer(state) }
    }

    fun fetch(): Result<Release> =
        source
            .latest()
            .onSuccess { release ->
                write(read().copy(checkedAt = now().toString(), latest = release.version.toString(), url = release.url))
            }.onFailure { Log.warn("update check couldn't reach $ZOPF_REPO: ${it.message}") }

    fun announceOnce(release: Release): Boolean {
        val state = read()
        if (state.announced == release.version.toString()) return false
        write(state.copy(announced = release.version.toString()))
        return true
    }

    private fun newerThanRunning(release: Release): Release? = release.takeIf { running != null && it.version > running }

    private fun newer(state: UpdateState): Release? {
        val latest = Version.parse(state.latest) ?: return null
        return newerThanRunning(Release(latest, state.url))
    }

    private fun stale(state: UpdateState): Boolean {
        val last = runCatching { Instant.parse(state.checkedAt) }.getOrNull() ?: return true
        return Duration.between(last, now()) >= Duration.ofHours(CHECK_EVERY_HOURS)
    }

    private fun read(): UpdateState =
        if (!file.exists()) {
            UpdateState()
        } else {
            runCatching { zopfJson.decodeFromString(UpdateState.serializer(), file.readText()) }.getOrElse { UpdateState() }
        }

    private fun write(state: UpdateState) {
        runCatching { file.writeTextAtomically(zopfJson.encodeToString(UpdateState.serializer(), state)) }
            .onFailure { Log.warn("couldn't write $file: ${it.message}") }
    }
}

class GitHubReleases(
    private val repo: String = ZOPF_REPO,
) : ReleaseSource {
    override fun latest(): Result<Release> =
        runCatching {
            val connection =
                (URI("https://api.github.com/repos/$repo/releases/latest").toURL().openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "zopf/${BuildInfo.version}")
                    connectTimeout = REACH_TIMEOUT_MILLIS
                    readTimeout = REACH_TIMEOUT_MILLIS
                }
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub answered ${connection.responseCode}" }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.mapCatching { parse(it).getOrThrow() }

    internal fun parse(payload: String): Result<Release> =
        runCatching {
            val release = zopfJson.decodeFromString(GitHubRelease.serializer(), payload)
            val version = Version.parse(release.tagName) ?: error("\"${release.tagName}\" doesn't name a version")
            Release(version, release.htmlUrl.takeIf { it.startsWith("https://") } ?: releasesPage)
        }

    private val releasesPage: String get() = "https://github.com/$repo/releases/latest"
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
)
