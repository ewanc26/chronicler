package uk.ewancroft.chronicler.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import uk.ewancroft.chronicler.config.LlmConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LMStudioProvider(private val config: LlmConfig) : LlmProvider {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun name(): String = "lmstudio"

    override fun isAvailable(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.lmStudioUrl}/v1/models"))
                .header("Authorization", "Bearer ${config.apiKey}")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (_: Exception) {
            false
        }
    }

    override fun generate(systemPrompt: String, sectionTitle: String, eventSummary: String): ArticleResult? {
        val prompt = buildPrompt(sectionTitle, eventSummary)
        val resolvedSystemPrompt = systemPrompt
            .replace("{series_title}", "The Weekly Chronicle")
            .replace("{server_name}", "this server")

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(resolvedSystemPrompt))
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                })
            }
            put("temperature", JsonPrimitive(0.7))
            put("max_tokens", JsonPrimitive(800))
        }

        return try {
            val bodyJson = requestBody.toString()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.lmStudioUrl}/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null

            val responseBody = json.decodeFromString<JsonObject>(response.body())
            val choices = responseBody["choices"] as? kotlinx.serialization.json.JsonArray ?: return null
            val firstChoice = choices.firstOrNull() as? JsonObject ?: return null
            val message = firstChoice["message"] as? JsonObject ?: return null
            val text = message["content"] as? JsonPrimitive ?: return null
            parseArticle(text.content.trim())
        } catch (_: Exception) {
            null
        } finally {
            scheduleUnload()
        }
    }

    private fun scheduleUnload() {
        try {
            val dataDir = System.getProperty("chronicler.dataDir") ?: "/data/plugins/Chronicler"
            val triggerFile = java.io.File(dataDir, "unload-lms.trigger")
            triggerFile.parentFile.mkdirs()
            triggerFile.writeText(System.currentTimeMillis().toString())
        } catch (_: Exception) { }
    }
}