package app.touchai.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `isVersionNewer compares semantic version numbers numerically`() {
        assertTrue(isVersionNewer("1.3.6", "1.3.7"))
        assertTrue(isVersionNewer("1.3.6", "1.10.0"))
        assertTrue(isVersionNewer("1.3.6-debug", "v1.3.7"))
        assertTrue(isVersionNewer("1.3", "1.3.1"))
        assertTrue(isVersionNewer("1.3.6", "2.0.0"))

        assertFalse(isVersionNewer("1.3.6", "1.3.6"))
        assertFalse(isVersionNewer("1.3.6-debug", "v1.3.6"))
        assertFalse(isVersionNewer("1.4.0", "1.3.9"))
        assertFalse(isVersionNewer("1.3.6", "V1.3.6"))
        assertFalse(isVersionNewer("1.3.6", "invalid"))
    }

    @Test
    fun `check returns UpdateAvailable with apk url and release notes`() = runTest {
        var requestedUrl = ""
        val client = HttpClient(
            MockEngine { request ->
                requestedUrl = request.url.toString()
                respond(
                    content = """
                        {
                          "tag_name": "v1.4.0",
                          "html_url": "https://github.com/example/touchai/releases/tag/v1.4.0",
                          "body": "## Changes\n- Added update check",
                          "assets": [
                            {
                              "name": "checksums.txt",
                              "browser_download_url": "https://github.com/example/touchai/releases/download/v1.4.0/checksums.txt"
                            },
                            {
                              "name": "touchai-v1.4.0.apk",
                              "browser_download_url": "https://github.com/example/touchai/releases/download/v1.4.0/touchai-v1.4.0.apk"
                            }
                          ]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val checker = AppUpdateChecker(http = client, repository = "example/touchai")
        val result = checker.check(currentVersion = "1.3.6-debug")

        assertEquals("https://api.github.com/repos/example/touchai/releases/latest", requestedUrl)
        assertEquals(
            UpdateCheckResult.UpdateAvailable(
                latestVersion = "1.4.0",
                releaseUrl = "https://github.com/example/touchai/releases/tag/v1.4.0",
                apkUrl = "https://github.com/example/touchai/releases/download/v1.4.0/touchai-v1.4.0.apk",
                releaseNotes = "## Changes\n- Added update check",
            ),
            result,
        )
    }

    @Test
    fun `check returns UpToDate when release matches debug build version`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"tag_name":"v1.3.6","html_url":"https://github.com/example/touchai/releases/tag/v1.3.6","assets":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val checker = AppUpdateChecker(http = client, repository = "example/touchai")
        val result = checker.check(currentVersion = "1.3.6-debug")

        assertEquals(UpdateCheckResult.UpToDate(latestVersion = "1.3.6"), result)
    }

    @Test
    fun `check handles missing apk asset and fallback release url`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = """{"tag_name":"v1.5.0","body":"","assets":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val checker = AppUpdateChecker(http = client, repository = "example/touchai")
        val result = checker.check(currentVersion = "1.3.6")

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.5.0", update.latestVersion)
        assertEquals("https://github.com/example/touchai/releases/tag/v1.5.0", update.releaseUrl)
        assertNull(update.apkUrl)
        assertNull(update.releaseNotes)
    }

    @Test
    fun `check reports error for unconfigured repository and HTTP failures`() = runTest {
        val dummyClient = HttpClient(
            MockEngine { respond(content = "", status = HttpStatusCode.NotFound) },
        )
        val unconfigured = AppUpdateChecker(http = dummyClient, repository = "").check("1.3.6")
        assertTrue(unconfigured is UpdateCheckResult.Error)

        val notFound = AppUpdateChecker(http = dummyClient, repository = "example/touchai").check("1.3.6")
        assertEquals(UpdateCheckResult.Error("No published releases found yet."), notFound)

        val rateLimitClient = HttpClient(
            MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) },
        )
        val rateLimited = AppUpdateChecker(http = rateLimitClient, repository = "example/touchai").check("1.3.6")
        assertEquals(UpdateCheckResult.Error("GitHub API rate limit reached. Try again later."), rateLimited)
    }
}
