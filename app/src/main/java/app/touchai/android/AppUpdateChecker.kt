package app.touchai.android

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseUrl: String,
        val apkUrl: String?,
        val releaseNotes: String?,
    ) : UpdateCheckResult

    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/** Checks GitHub Releases for a newer signed APK version. */
class AppUpdateChecker(
    private val http: HttpClient,
    private val repository: String = BuildConfig.GITHUB_REPOSITORY,
) {
    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): UpdateCheckResult {
        val repo = repository.trim().trim('/')
        if (repo.isEmpty() || '/' !in repo) {
            return UpdateCheckResult.Error("Update repository is not configured.")
        }
        val response = try {
            http.get("https://api.github.com/repos/$repo/releases/latest") {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "TouchAI/$currentVersion")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return UpdateCheckResult.Error("Could not reach GitHub to check for updates.")
        }
        val status = response.status.value
        if (!response.status.isSuccess()) {
            return UpdateCheckResult.Error(
                when (status) {
                    404 -> "No published releases found yet."
                    403 -> "GitHub API rate limit reached. Try again later."
                    else -> "Could not check for updates (HTTP $status)."
                },
            )
        }
        return try {
            parseLatestRelease(response.bodyAsText(), currentVersion, repo)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            UpdateCheckResult.Error("Could not read release information from GitHub.")
        }
    }
}

internal fun parseLatestRelease(body: String, currentVersion: String, repository: String): UpdateCheckResult {
    val root = Json.parseToJsonElement(body).jsonObject
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    require(tag.isNotEmpty()) { "Missing release tag" }
    val latestVersion = normalizeVersionTag(tag)
    val releaseUrl = root["html_url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.startsWith("https://") }
        ?: "https://github.com/$repository/releases/tag/$tag"
    val apkUrl = (root["assets"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { asset ->
            asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
        }
        ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.startsWith("https://") }
    val notes = root["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    return if (isVersionNewer(currentVersion, latestVersion)) {
        UpdateCheckResult.UpdateAvailable(latestVersion, releaseUrl, apkUrl, notes)
    } else {
        UpdateCheckResult.UpToDate(latestVersion)
    }
}

internal fun normalizeVersionTag(version: String): String =
    version.trim().removePrefix("v").removePrefix("V")

internal fun parseNumericVersion(version: String): List<Int> {
    val core = normalizeVersionTag(version).substringBefore('-').substringBefore('+')
    return core.split('.').mapNotNull { segment -> segment.toIntOrNull() }
}

internal fun isVersionNewer(currentVersion: String, latestVersion: String): Boolean {
    val current = parseNumericVersion(currentVersion)
    val latest = parseNumericVersion(latestVersion)
    if (current.isEmpty() || latest.isEmpty()) return false
    val maxLen = maxOf(current.size, latest.size)
    for (i in 0 until maxLen) {
        val c = current.getOrElse(i) { 0 }
        val l = latest.getOrElse(i) { 0 }
        if (l != c) return l > c
    }
    return false
}
