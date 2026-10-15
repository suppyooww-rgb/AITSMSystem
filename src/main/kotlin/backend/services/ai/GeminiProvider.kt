package backend.services.ai

import backend.config.Env
import backend.models.ai.AIMessage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private data class GeminiAttempt(val result: AIProviderResult, val retryable: Boolean, val statusCode: Int? = null)

/** Hosted Gemini integration. The API key is read only on the server. */
class GeminiProvider : AIProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val apiKey get() = Env.get("GEMINI_API_KEY")
    private val fallbackModel get() = Env.get("GEMINI_FALLBACK_MODEL")?.trim()?.removePrefix("models/")
        ?.takeIf { it.isNotBlank() } ?: "gemini-flash-latest"
    private val logger = LoggerFactory.getLogger(GeminiProvider::class.java)

    override fun chat(model: String, timeoutMillis: Long, messages: List<AIMessage>): AIProviderResult {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: return AIProviderResult(false, "", "Gemini is not configured. Contact an administrator.")
        val system = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val contents = messages.filter { it.role != "system" }
        val input = contents.joinToString("\n\n") { message ->
            val speaker = if (message.role == "assistant") "Assistant" else "User"
            "$speaker:\n${message.content}"
        }
        val payload = buildJsonObject {
            if (system.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", input) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.35)
                put("maxOutputTokens", 1_000)
            }
        }
        return execute(key, model, timeoutMillis, payload.toString())
    }

    override fun testConnection(model: String, timeoutMillis: Long): AIProviderResult =
        chat(model, minOf(timeoutMillis, 15_000L), listOf(AIMessage("user", "Reply exactly: OK")))

    private fun execute(key: String, model: String, timeoutMillis: Long, payload: String): AIProviderResult {
        val deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos()
        val models = listOf(model.removePrefix("models/"), fallbackModel).distinct()
        var lastResult = AIProviderResult(false, "", "Gemini is currently unavailable. Please try again later.")

        models.forEachIndexed { modelIndex, modelName ->
            val attempts = if (modelIndex == 0) 2 else 1
            repeat(attempts) { attemptIndex ->
                val remainingMillis = Duration.ofNanos(deadline - System.nanoTime()).toMillis()
                if (remainingMillis < 1_000L) return lastResult
                val requestTimeout = minOf(remainingMillis, maxOf(3_000L, timeoutMillis / 3))
                val attempt = executeOnce(key, modelName, requestTimeout, payload)
                if (attempt.result.ok) return attempt.result
                lastResult = attempt.result
                logger.warn(
                    "Gemini request failed model={} status={} attempt={}/{} retryable={}",
                    modelName, attempt.statusCode ?: "network", attemptIndex + 1, attempts, attempt.retryable
                )
                if (!attempt.retryable) return attempt.result
                if (attemptIndex + 1 < attempts) {
                    val delay = 400L * (1L shl attemptIndex) + ThreadLocalRandom.current().nextLong(100L, 351L)
                    val delayWithinDeadline = minOf(delay, Duration.ofNanos(deadline - System.nanoTime()).toMillis().coerceAtLeast(0L))
                    if (delayWithinDeadline > 0) Thread.sleep(delayWithinDeadline)
                }
            }
        }
        return lastResult
    }

    private fun executeOnce(key: String, modelName: String, timeoutMillis: Long, payload: String): GeminiAttempt = try {
        val request = HttpRequest.newBuilder(URI("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"))
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val message = when (response.statusCode()) {
                400 -> "Gemini API credentials or request configuration is invalid."
                401, 403 -> "Gemini credentials are invalid or unavailable."
                404 -> "The configured Gemini model is unavailable. Select a supported model and try again."
                429 -> "Gemini quota is temporarily exhausted. Please try again shortly."
                in 500..599 -> "Gemini is temporarily unavailable. Please try again shortly."
                else -> "Gemini request could not be completed."
            }
            GeminiAttempt(AIProviderResult(false, "", message), response.statusCode() in setOf(408, 429) || response.statusCode() >= 500, response.statusCode())
        } else {
            val text = runCatching {
                json.parseToJsonElement(response.body())
                    .jsonObject["candidates"]
                    ?.jsonArray
                    ?.asSequence()
                    ?.mapNotNull { candidate ->
                        candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray?.joinToString("") { part ->
                            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        }
                    }
                    ?.joinToString("")
            }.getOrNull().orEmpty().trim()
            if (text.isBlank()) GeminiAttempt(AIProviderResult(false, "", "Gemini returned an empty response."), true, response.statusCode())
            else GeminiAttempt(AIProviderResult(true, text), false, response.statusCode())
        }
    } catch (_: java.net.http.HttpTimeoutException) {
        GeminiAttempt(AIProviderResult(false, "", "Gemini did not respond in time. Please try again."), true)
    } catch (_: Exception) {
        GeminiAttempt(AIProviderResult(false, "", "Gemini is currently unavailable. Please try again later."), true)
    }
}
