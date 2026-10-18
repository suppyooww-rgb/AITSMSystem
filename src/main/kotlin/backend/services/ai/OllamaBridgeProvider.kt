package backend.services.ai

import backend.models.ai.AIMessage
import backend.repository.AIBridgeRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class OllamaBridgeProvider(private val repository: AIBridgeRepository) : AIProvider {
    private val json = Json

    override fun chat(model: String, timeoutMillis: Long, messages: List<AIMessage>): AIProviderResult {
        val payload = json.encodeToString(ListSerializer(AIMessage.serializer()), messages)
        val jobId = repository.createJob(model, payload, timeoutMillis)
            ?: return AIProviderResult(false, "", "No healthy local AI bridge has the configured model available.")
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val job = repository.findJob(jobId) ?: break
            when (job.status) {
                "COMPLETED" -> return AIProviderResult(true, job.responsePayload.orEmpty())
                "FAILED" -> return AIProviderResult(false, "", job.errorMessage ?: "Local AI request failed.")
                "EXPIRED" -> return AIProviderResult(false, "", "Local AI request expired before completion.")
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        repository.expireJob(jobId)
        return AIProviderResult(false, "", "Local AI did not respond in time.")
    }

    override fun testConnection(model: String, timeoutMillis: Long): AIProviderResult = chat(
        model, timeoutMillis.coerceAtMost(30_000),
        listOf(AIMessage("user", "Reply with exactly: AITSM bridge ready"))
    )
}
