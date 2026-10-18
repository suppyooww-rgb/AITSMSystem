package backend.models.ai

import kotlinx.serialization.Serializable

@Serializable data class TicketAiAnalysisRequest(val title: String = "", val description: String)
@Serializable data class TicketAiSuggestion(val title: String, val description: String, val category: String, val priority: String, val affectedService: String, val advisory: String)
@Serializable data class SimilarTicket(val id: Int, val title: String, val status: String, val priority: String, val score: Int, val reason: String)
@Serializable data class KnowledgeRecommendation(val id: Int, val title: String, val category: String, val relevance: String, val articleUrl: String)
@Serializable data class TicketAiInsights(
    val suggestion: TicketAiSuggestion,
    val knowledgeIds: List<Int>,
    val knowledgeArticles: List<KnowledgeRecommendation>,
    val duplicates: List<SimilarTicket>,
    val source: String,
    val fallbackReason: String? = null
)

@Serializable data class TicketAiAssistance(
    val ticketId: Int,
    val summary: String,
    val resolutionDraft: String,
    val troubleshootingChecklist: List<String>,
    val advisory: String,
    val source: String,
    val fallbackReason: String? = null
)

@Serializable
data class AIConfigResponse(
    val provider: String,
    val model: String,
    val timeoutMillis: Long,
    val ollamaModel: String,
    val localBridgeAvailable: Boolean = false
)

@Serializable
data class AIConfigUpdateRequest(
    val ollamaModel: String
)

@Serializable
data class AITicketDraftRequest(
    val issueSummary: String,
    val ticketDescription: String,
    val suggestedPriority: String,
    val originalUserMessage: String? = null,
    val ticketTitle: String? = null
)

@Serializable
data class AITicketDraftResponse(
    val title: String,
    val description: String,
    val priority: String
)
