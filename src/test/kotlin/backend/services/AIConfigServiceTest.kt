package backend.services

import backend.models.ai.AIConfigUpdateRequest
import backend.queries.SystemSettingsStore
import backend.services.ai.AIConfigService
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AIConfigServiceTest {
    private class InMemorySettingsStore : SystemSettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getByKey(key: String): String? = values[key]
        override fun upsert(key: String, value: String) { values[key] = value }
    }

    private fun config() = MapApplicationConfig(
        "ai.gemini.model" to "gemini-3.8-flash",
        "ai.timeoutMillis" to "60000"
    )

    @Test
    fun `ollama model preference is validated persisted and reported as not connected`() {
        val store = InMemorySettingsStore()
        val service = AIConfigService(config(), store)

        assertEquals("qwen3:8b", service.snapshot().ollamaModel)
        assertFalse(service.snapshot().localBridgeAvailable)

        val updated = service.update(AIConfigUpdateRequest("gemini-flash-latest", "qwen3:14b"))
        assertEquals("qwen3:14b", updated.ollamaModel)
        assertEquals("qwen3:14b", AIConfigService(config(), store).snapshot().ollamaModel)
    }

    @Test
    fun `ollama model preference rejects urls and commands`() {
        val service = AIConfigService(config(), InMemorySettingsStore())

        assertFailsWith<IllegalArgumentException> {
            service.update(AIConfigUpdateRequest("gemini-flash-latest", "http://localhost:11434; stop"))
        }
    }
}
