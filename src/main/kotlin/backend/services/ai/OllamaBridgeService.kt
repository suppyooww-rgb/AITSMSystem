package backend.services.ai

import backend.models.ai.AIBridgeHeartbeatRequest
import backend.models.ai.AIBridgeHeartbeatResponse
import backend.models.ai.AIBridgeJobCompletionRequest
import backend.models.ai.AIBridgeJobResponse
import backend.models.ai.AIBridgeStatusResponse
import backend.models.ai.AIMessage
import backend.repository.AIBridgeRepository
import backend.repository.MonitoringRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

class OllamaBridgeService(
    private val repository: AIBridgeRepository,
    private val monitoringRepository: MonitoringRepository,
    private val configService: AIConfigService
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authenticate(rawToken: String) = monitoringRepository.authenticate(rawToken)

    fun heartbeat(rawToken: String, request: AIBridgeHeartbeatRequest): AIBridgeHeartbeatResponse? {
        val identity = authenticate(rawToken) ?: return null
        val enabled = repository.heartbeat(identity, request)
        return AIBridgeHeartbeatResponse(enabled, configService.snapshot().ollamaModel)
    }

    fun nextJob(rawToken: String): AIBridgeJobResponse? {
        val identity = authenticate(rawToken) ?: return null
        val job = repository.claimNext(identity) ?: return null
        return AIBridgeJobResponse(
            job.id.toString(), job.model,
            json.decodeFromString(ListSerializer(AIMessage.serializer()), job.requestPayload),
            configService.snapshot().timeoutMillis
        )
    }

    fun complete(rawToken: String, jobId: UUID, request: AIBridgeJobCompletionRequest): Boolean? {
        val identity = authenticate(rawToken) ?: return null
        return repository.complete(identity, jobId, request.success, request.content, request.error)
    }

    fun setEnabled(collectorId: UUID, enabled: Boolean) = repository.setCollectorEnabled(collectorId, enabled)
    fun status(): AIBridgeStatusResponse = repository.status(configService.snapshot().ollamaModel)
    fun isAvailable(): Boolean = status().available
    fun testConnection(): AIProviderResult {
        val config = configService.snapshot()
        return OllamaBridgeProvider(repository).testConnection(config.ollamaModel, config.timeoutMillis)
    }
}
