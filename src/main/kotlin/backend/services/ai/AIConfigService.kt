package backend.services.ai

import backend.config.Env
import backend.models.ai.AIConfigResponse
import backend.models.ai.AIConfigUpdateRequest
import backend.queries.SystemSettingsStore
import io.ktor.server.config.ApplicationConfig
import java.util.concurrent.atomic.AtomicReference

private data class AIConfigState(
    val provider: String,
    val timeoutMillis: Long,
    val ollamaModel: String
)

class AIConfigService(
    config: ApplicationConfig,
    private val settingsStore: SystemSettingsStore? = null,
    private val bridgeAvailable: (String) -> Boolean = { false }
) {
    private val defaultOllamaModel = "qwen3:8b"
    private val state = AtomicReference(
        AIConfigState(
            provider = "ollama",
            timeoutMillis = (envOrConfig("AI_TIMEOUT_MILLIS", config, "ai.timeoutMillis")?.toLongOrNull() ?: 60_000L)
                .coerceIn(5_000L, 120_000L),
            ollamaModel = settingsStore?.getByKey(OLLAMA_MODEL_KEY)?.trim()?.takeIf(::isValidOllamaModel)
                ?: envOrConfig("OLLAMA_MODEL", config, "ai.ollama.model")?.takeIf(::isValidOllamaModel)
                ?: defaultOllamaModel
        )
    )

    fun snapshot(): AIConfigResponse {
        val current = state.get()
        return current.toResponse()
    }

    fun update(request: AIConfigUpdateRequest): AIConfigResponse {
        val ollamaModel = request.ollamaModel.trim()
        require(ollamaModel.isNotBlank()) { "Ollama model selection is required" }
        require(isValidOllamaModel(ollamaModel)) { "Ollama model selection is invalid" }

        settingsStore?.upsert(OLLAMA_MODEL_KEY, ollamaModel)
        val updated = state.updateAndGet { it.copy(ollamaModel = ollamaModel) }
        return updated.toResponse()
    }

    private fun AIConfigState.toResponse() = AIConfigResponse(
        provider = provider,
        model = ollamaModel,
        timeoutMillis = timeoutMillis,
        ollamaModel = ollamaModel,
        localBridgeAvailable = bridgeAvailable(ollamaModel)
    )

    private fun isValidOllamaModel(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}(?::[A-Za-z0-9][A-Za-z0-9._-]{0,49})?"))

    private fun envOrConfig(envName: String, config: ApplicationConfig, path: String): String? {
        val env = Env.get(envName)?.trim()
        if (!env.isNullOrBlank()) return env
        return config.propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val OLLAMA_MODEL_KEY = "ai.ollama.model"
    }
}
