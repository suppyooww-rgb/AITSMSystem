package backend.routes

import backend.models.ai.AIChatRequest
import backend.models.ai.TicketAiAnalysisRequest
import backend.models.ai.AIConfigUpdateRequest
import backend.models.ai.AITicketDraftRequest
import backend.models.ai.AIBridgeHeartbeatRequest
import backend.models.ai.AIBridgeJobCompletionRequest
import backend.models.ai.AIBridgeCollectorUpdateRequest
import backend.models.ai.AIBridgeTestResponse
import backend.models.UserRole
import backend.models.TicketStatus
import backend.security.principal
import backend.security.requireAuthenticated
import backend.security.requireRole
import backend.security.RateLimiter
import backend.security.userId
import backend.security.requireReauthentication
import backend.services.ai.AIChatService
import backend.services.ai.AIConfigService
import backend.services.ai.TicketAiAdvisor
import backend.services.ai.OllamaBridgeService
import backend.services.ReauthScope
import backend.services.ReauthenticationService
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private val aiRateLimiter = RateLimiter(limit = 20, windowMillis = 60_000L)

fun Route.aiRoutes(chatService: AIChatService, configService: AIConfigService, ticketAiAdvisor: TicketAiAdvisor, reauthentication: ReauthenticationService, bridgeService: OllamaBridgeService) {
    post("/api/ai/bridge/collector/heartbeat") {
        val token = call.collectorBearerToken() ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Collector token is required"))
        val response = bridgeService.heartbeat(token, call.receive<AIBridgeHeartbeatRequest>())
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Collector token is invalid or revoked"))
        call.respond(response)
    }

    get("/api/ai/bridge/collector/jobs/next") {
        val token = call.collectorBearerToken() ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Collector token is required"))
        if (bridgeService.authenticate(token) == null) return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Collector token is invalid or revoked"))
        val job = bridgeService.nextJob(token)
        if (job == null) call.respond(HttpStatusCode.NoContent) else call.respond(job)
    }

    post("/api/ai/bridge/collector/jobs/{id}/complete") {
        val token = call.collectorBearerToken() ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Collector token is required"))
        val jobId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid AI job id"))
        when (bridgeService.complete(token, jobId, call.receive<AIBridgeJobCompletionRequest>())) {
            null -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Collector token is invalid or revoked"))
            false -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "AI job is unavailable or already completed"))
            true -> call.respond(mapOf("accepted" to true))
        }
    }
    post("/api/ai/chat") {
        if (!call.requireAuthenticated()) return@post
        if (!call.requireAiCapacity()) return@post

        val request = call.receive<AIChatRequest>()
        val message = request.message.trim()
        if (message.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message is required"))
            return@post
        }
        if (message.length > 2_000) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message exceeds 2000 characters"))
            return@post
        }

        val userId = call.userId()!!
        val sessionId = call.resolveSessionId()
        val response = withContext(Dispatchers.IO) { chatService.chat(userId, sessionId, message) }
        call.respond(response)
    }

    post("/api/ai-assistant/chat") {
        if (!call.requireAuthenticated()) return@post
        if (!call.requireAiCapacity()) return@post
        val request = call.receive<AIChatRequest>()
        val message = request.message.trim()
        if (message.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message is required"))
            return@post
        }
        if (message.length > 2_000) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message exceeds 2000 characters"))
            return@post
        }
        val userId = call.userId()!!
        val sessionId = call.resolveSessionId()
        val response = withContext(Dispatchers.IO) { chatService.chat(userId, sessionId, message) }
        call.respond(response)
    }

    post("/api/ai/clear") {
        if (!call.requireAuthenticated()) return@post
        chatService.clearConversation(call.userId()!!, call.resolveSessionId())
        call.respond(HttpStatusCode.OK, mapOf("cleared" to true))
    }

    post("/api/ai/create-ticket-draft") {
        if (!call.requireAuthenticated()) return@post
        val request = call.receive<AITicketDraftRequest>()
        call.respond(chatService.createTicketDraft(request))
    }

    post("/api/ai/ticket-insights") {
        if (!call.requireAuthenticated()) return@post
        if (!call.requireAiCapacity()) return@post
        val request = call.receive<TicketAiAnalysisRequest>()
        require(request.description.trim().length >= 10) { "Describe the issue in at least 10 characters." }
        val analysis = withContext(Dispatchers.IO) { ticketAiAdvisor.analyze(request) }
        call.respond(analysis)
    }

    post("/api/ai/tickets/{id}/assist") {
        if (!call.requireRole(UserRole.ADMIN)) return@post
        val ticketId = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid ticket id"))
        val ticket = ticketAiAdvisor.tickets.get(ticketId)
            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ticket not found"))
        if (ticket.status in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)) {
            return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Resolved or closed tickets cannot generate new assistance"))
        }
        val actor = call.principal()!!.user
        if (actor.role != UserRole.SUPERADMIN && ticket.assignedToUserId != actor.id) {
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only the assigned administrator can generate assistance for this ticket"))
        }
        if (!call.requireAiCapacity()) return@post
        val assistance = withContext(Dispatchers.IO) { ticketAiAdvisor.assist(ticketId) }
            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Ticket not found"))
        call.respond(assistance)
    }

    get("/api/ai/config") {
        if (!call.requireRole(UserRole.ADMIN)) return@get
        call.respond(configService.snapshot())
    }

    get("/api/ai/bridge/status") {
        if (!call.requireRole(UserRole.ADMIN)) return@get
        call.respond(bridgeService.status())
    }

    put("/api/ai/bridge/collectors/{id}") {
        if (!call.requireRole(UserRole.ADMIN)) return@put
        if (!call.requireReauthentication(reauthentication, ReauthScope.SECURITY_CONFIG)) return@put
        val collectorId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid collector id"))
        val request = call.receive<AIBridgeCollectorUpdateRequest>()
        if (!bridgeService.setEnabled(collectorId, request.enabled)) {
            return@put call.respond(HttpStatusCode.Conflict, mapOf("error" to "Only an active company/LAN collector can run the AI Bridge"))
        }
        call.respond(bridgeService.status())
    }

    post("/api/ai/bridge/test") {
        if (!call.requireRole(UserRole.ADMIN)) return@post
        val result = withContext(Dispatchers.IO) { bridgeService.testConnection() }
        if (result.ok) call.respond(AIBridgeTestResponse(true, message = "AITSM reached Ollama through the secure AI Bridge."))
        else call.respond(HttpStatusCode.ServiceUnavailable, AIBridgeTestResponse(false, error = result.errorMessage ?: "Local AI test failed"))
    }

    post("/api/ai/config") {
        if (!call.requireRole(UserRole.ADMIN)) return@post
        if (!call.requireReauthentication(reauthentication, ReauthScope.SECURITY_CONFIG)) return@post
        val request = call.receive<AIConfigUpdateRequest>()
        call.respond(configService.update(request))
    }

}

private fun ApplicationCall.collectorBearerToken(): String? =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }

private fun ApplicationCall.resolveSessionId(): String {
    val raw = request.headers["X-AI-Session-Id"].orEmpty().trim()
    return if (raw.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) raw else "session-${UUID.randomUUID()}"
}

private suspend fun ApplicationCall.requireAiCapacity(): Boolean {
    val actor = userId() ?: return false
    if (!aiRateLimiter.allow("user:$actor")) {
        respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded. Please try again shortly."))
        return false
    }
    return true
}
