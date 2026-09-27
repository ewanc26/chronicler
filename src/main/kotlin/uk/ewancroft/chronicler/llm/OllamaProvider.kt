package uk.ewancroft.chronicler.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import uk.ewancroft.chronicler.config.LlmConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OllamaProvider(private val config: LlmConfig) : LlmProvider {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun name(): String = "ollama"

    override fun isAvailable(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.ollamaUrl}/api/tags"))
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

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("system", JsonPrimitive(systemPrompt))
            put("prompt", JsonPrimitive(prompt))
            put("stream", JsonPrimitive(false))
            putJsonObject("options") {
                put("temperature", JsonPrimitive(0.7))
                put("max_tokens", JsonPrimitive(300))
            }
        }

        return try {
            val bodyJson = requestBody.toString()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.ollamaUrl}/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null

            val responseBody = json.decodeFromString<JsonObject>(response.body())
            val text = (responseBody["response"] as? JsonPrimitive)?.content ?: return null
            parseArticle(text.trim())
        } catch (_: Exception) {
            null
        }
    }
}
