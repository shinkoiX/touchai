package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** A model a provider reports as available; [id] is what requests send. */
data class AvailableModel(val id: String, val name: String = id)

/** Lists models from an OpenAI-compatible `GET {baseUrl}/models` endpoint. */
class ApiModelCatalog(private val http: HttpClient) {
    suspend fun models(baseUrl: String, apiKey: String): List<AvailableModel> {
        val response = try {
            http.get("${baseUrl.trimEnd('/')}/models") { bearerAuth(apiKey) }
        } catch (error: CancellationException) { throw error }
        // Transport errors can include request details; keep the message generic.
        catch (_: Exception) { throw OpenAIRequestException("Could not reach the provider to list models.") }
        val status = response.status.value
        if (!response.status.isSuccess()) throw OpenAIRequestException(when (status) {
            401, 403 -> "The provider rejected this API key while listing models (HTTP $status)."
            404, 405 -> "This provider does not list models. Type the model ID instead."
            else -> "Could not load models: HTTP $status."
        }, status)
        return try { parseModelList(response.bodyAsText()) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw OpenAIRequestException("The provider returned a model list TouchAI could not read.") }
    }
}

/** Accepts the OpenAI shape (`{"data":[{"id":…}]}`) and the common `models` or bare-array variants. */
internal fun parseModelList(body: String): List<AvailableModel> {
    val root = Json.parseToJsonElement(body)
    val items = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["data"] ?: root["models"]) as? JsonArray ?: error("No model list")
        else -> error("No model list")
    }
    return items.mapNotNull { item ->
        val value = item as? JsonObject ?: return@mapNotNull (item as? JsonPrimitive)?.contentOrNull?.let(::AvailableModel)
        val id = (value["id"] ?: value["slug"] ?: value["name"])?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val name = (value["display_name"] ?: value["displayName"])?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: id
        AvailableModel(id, name)
    }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
}
