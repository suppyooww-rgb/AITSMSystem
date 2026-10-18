package backend.services

import backend.models.ai.AIMessage
import backend.services.ai.PendingLocalAIProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingLocalAIProviderTest {
    @Test
    fun `pending local provider makes no connection claim`() {
        val provider = PendingLocalAIProvider()

        val result = provider.chat("qwen3:8b", 5_000, listOf(AIMessage("user", "test")))

        assertFalse(result.ok)
        assertTrue(result.content.isBlank())
        assertTrue(result.errorMessage.orEmpty().contains("AI Bridge"))
    }
}
