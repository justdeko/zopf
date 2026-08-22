package com.dk.zopf.runtime

import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun `a version is compared by number, not by spelling`() {
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.0")!!)
        assertTrue(Version.parse("1.0.0")!! > Version.parse("0.99.99")!!)
        assertEquals(Version.parse("1.0.0"), Version.parse("1.0.0"))
    }

    @Test
    fun `a release tag and a build number are the same version`() {
        assertEquals(Version.parse("1.1.0"), Version.parse("v1.1.0"))
        assertEquals(Version(1, 1, 0), Version.parse("1.1.0-rc1"))
        assertEquals(Version(1, 0, 0), Version.parse("1"))
    }

    @Test
    fun `anything that isn't a version is no version at all`() {
        assertNull(Version.parse("unknown"))
        assertNull(Version.parse(""))
        assertNull(Version.parse("main"))
    }
}

class UpdateCheckTest {
    private val dir: Path = Files.createTempDirectory("zopf-update")
    private val file: Path = dir.resolve("update.json")

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private val morning: Instant = Instant.parse("2026-08-15T09:00:00Z")

    private fun check(
        latest: String = "1.1.0",
        current: String = "1.0.0",
        now: Instant = morning,
        source: ReleaseSource = ReleaseSource { Result.success(Release(Version.parse(latest)!!, "https://example.test/$latest")) },
    ) = UpdateCheck(source = source, file = file, current = current, now = { now })

    @Test
    fun `a newer release is reported and cached for the next run to read`() {
        val release = check().refresh()

        assertEquals(Version(1, 1, 0), release?.version)
        assertTrue(file.exists(), "the check has to leave its answer behind")

        val offline = UpdateCheck(source = { Result.failure(UnknownHostException()) }, file = file, current = "1.0.0")
        assertEquals(Version(1, 1, 0), offline.cached()?.version)
    }

    @Test
    fun `the release you are already running is not news`() {
        assertNull(check(latest = "1.0.0").refresh())
        assertNull(check(latest = "0.9.9").refresh())
    }

    @Test
    fun `a build with no version number says nothing`() {
        assertNull(check(current = "unknown").refresh())
    }

    @Test
    fun `the check runs once a day, not once a launch`() {
        var asked = 0
        val source =
            ReleaseSource {
                asked += 1
                Result.success(Release(Version(1, 1, 0), "https://example.test"))
            }

        check(source = source).refresh()
        check(source = source, now = morning.plusSeconds(3600)).refresh()

        assertEquals(1, asked, "a second launch an hour later must not ask again")

        check(source = source, now = morning.plusSeconds(25 * 3600)).refresh()
        assertEquals(2, asked)
    }

    @Test
    fun `a check that cannot reach GitHub is silent and keeps what it knew`() {
        check().refresh()

        val release =
            check(
                source = { Result.failure(UnknownHostException("api.github.com")) },
                now = morning.plusSeconds(5 * 24 * 3600),
            ).refresh()

        assertEquals(Version(1, 1, 0), release?.version, "a failed check falls back on the last answer")
    }

    @Test
    fun `a version is announced once, however many times it is seen`() {
        val checker = check()
        val release = checker.refresh()!!

        assertTrue(checker.announceOnce(release))
        assertFalse(checker.announceOnce(release), "the same version must never interrupt twice")

        val next = check(latest = "1.2.0", now = morning.plusSeconds(48 * 3600))
        assertTrue(next.announceOnce(next.refresh()!!), "but a further release is worth saying once")
    }

    @Test
    fun `an unreadable cache is a missing cache, not a crash`() {
        file.writeText("{ this is not json")

        assertNull(check(source = { Result.failure(UnknownHostException()) }).cached())
        assertEquals(Version(1, 1, 0), check().refresh()?.version)
    }

    @Test
    fun `fetch answers with the latest release whether or not it is newer`() {
        val checker = check(latest = "1.0.0")

        assertEquals(Version(1, 0, 0), checker.fetch().getOrThrow().version)
        assertNull(checker.cached(), "check-update can say \"you are current\"; the notice still must not fire")
    }
}

class GitHubReleasesTest {
    private val payload =
        """
        {
          "tag_name": "v1.1.0",
          "name": "1.1.0",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/justdeko/zopf/releases/tag/v1.1.0",
          "assets": [{ "name": "zopf-1.1.0.dmg" }, { "name": "zopf-cli-1.1.0.tar.gz" }]
        }
        """.trimIndent()

    @Test
    fun `the tag is the version and the page is the link`() {
        val release = GitHubReleases().parse(payload).getOrThrow()

        assertEquals(Version(1, 1, 0), release.version)
        assertEquals("https://github.com/justdeko/zopf/releases/tag/v1.1.0", release.url)
    }

    @Test
    fun `every other field GitHub sends is ignored rather than refused`() {
        assertTrue(GitHubReleases().parse(payload).isSuccess, "GitHub adds keys between releases")
    }

    @Test
    fun `a payload with no version in it fails rather than inventing one`() {
        assertTrue(GitHubReleases().parse("""{"tag_name":"nightly"}""").isFailure)
        assertTrue(GitHubReleases().parse("not json").isFailure)
    }

    @Test
    fun `a link off the network is either https or not a link at all`() {
        val release = GitHubReleases().parse("""{"tag_name":"v1.2.0","html_url":"file:///etc/passwd"}""").getOrThrow()

        assertEquals("https://github.com/justdeko/zopf/releases/latest", release.url)
        assertEquals(listOf("open", "https://example.test"), Browser.command("https://example.test"))
        assertTrue(Browser.open("file:///etc/passwd").isFailure)
    }
}
