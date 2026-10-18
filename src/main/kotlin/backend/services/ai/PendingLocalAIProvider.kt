package backend.services.ai

import backend.models.ai.AIMessage

/**
 * Safe placeholder used until the authenticated Windows AI Bridge is available.
 * It performs no network requests and makes the existing deterministic fallbacks explicit.
 */
class PendingLocalAIProvider : AIProvider {
    private val unavailable = AIProviderResult(
        ok = false,
        content = "",
        errorMessage = "Local AI is unavailable until the secure AITSM AI Bridge is installed."
    )

    override fun chat(model: String, timeoutMillis: Long, messages: List<AIMessage>): AIProviderResult = unavailable

    override fun testConnection(model: String, timeoutMillis: Long): AIProviderResult = unavailable
}
