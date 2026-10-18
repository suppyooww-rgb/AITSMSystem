package backend.models.ai

import kotlinx.serialization.Serializable

@Serializable data class AIBridgeHeartbeatRequest(
    val available: Boolean,
    val model: String? = null,
    val agentVersion: String? = null,
    val error: String? = null
)

@Serializable data class AIBridgeHeartbeatResponse(
    val enabled: Boolean,
    val configuredModel: String,
    val pollSeconds: Int = 2
)

@Serializable data class AIBridgeJobResponse(
    val jobId: String,
    val model: String,
    val messages: List<AIMessage>,
    val timeoutMillis: Long
)

@Serializable data class AIBridgeJobCompletionRequest(
    val success: Boolean,
    val content: String? = null,
    val error: String? = null
)

@Serializable data class AIBridgeCollectorUpdateRequest(val enabled: Boolean)

@Serializable data class AIBridgeTestResponse(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null
)

@Serializable data class AIBridgeStatusResponse(
    val available: Boolean,
    val enabledCollectors: Int,
    val healthyCollectors: Int,
    val configuredModel: String,
    val message: String
)
