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

class AnthropicProvider(private val config: LlmConfig) : LlmProvider {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun name(): String = "anthropic"

    override fun isAvailable(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.baseUrl}/messages"))
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(5))
                .method("POST", HttpRequest.BodyPublishers.ofString(
                    buildJsonObject {
                        put("model", JsonPrimitive(config.model))
                        put("max_tokens", JsonPrimitive(3))
                        putJsonArray("messages") {
                            add(buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", JsonPrimitive("ping"))
                            })
                        }
                    }.toString()
                ))
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
            put("max_tokens", JsonPrimitive(300))
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(prompt))
                })
            }
        }

        return try {
            val bodyJson = requestBody.toString()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${config.baseUrl}/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(config.timeoutSeconds.toLong()))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null

            val responseBody = json.decodeFromString<JsonObject>(response.body())
            val content = responseBody["content"] as? kotlinx.serialization.json.JsonArray ?: return null
            val firstBlock = content.firstOrNull() as? JsonObject ?: return null
            val text = firstBlock["text"] as? JsonPrimitive ?: return null
            parseArticle(text.content.trim())
        } catch (_: Exception) {
            null
        }
    }
}
