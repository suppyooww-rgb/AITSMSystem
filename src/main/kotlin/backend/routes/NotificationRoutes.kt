package backend.routes

import backend.security.userId
import backend.models.ApiErrorResponse
import backend.services.NotificationService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class MarkReadRequest(val notificationId: Int)

@Serializable
private data class NotificationReadResponse(val updated: Boolean, val unreadCount: Long)

@Serializable
private data class NotificationBatchReadResponse(val updated: Int, val unreadCount: Long)

fun Route.notificationRoutes(service: NotificationService) {
    route("/api/notifications") {
        get {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Authentication required"))
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
            val status = call.request.queryParameters["status"] ?: "all"
            val type = call.request.queryParameters["type"]
            call.respond(service.page(userId, page, pageSize, status, type))
        }

        get("/unread-count") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Authentication required"))
            call.respond(mapOf("unreadCount" to service.unreadCount(userId)))
        }

        patch("/{id}/read") {
            val userId = call.userId() ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Authentication required"))
            val id = call.parameters["id"]?.toIntOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid notification id"))
            val updated = service.markAsRead(userId, id)
            if (!updated && !service.belongsToUser(userId, id)) {
                return@patch call.respond(HttpStatusCode.NotFound, ApiErrorResponse(404, "Notification not found"))
            }
            call.respond(NotificationReadResponse(updated, service.unreadCount(userId)))
        }

        post("/read") {
            val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Authentication required"))
            val body = call.receive<MarkReadRequest>()
            val updated = service.markAsRead(userId, body.notificationId)
            if (!updated && !service.belongsToUser(userId, body.notificationId)) {
                return@post call.respond(HttpStatusCode.NotFound, ApiErrorResponse(404, "Notification not found"))
            }
            call.respond(NotificationReadResponse(updated, service.unreadCount(userId)))
        }

        patch("/read-all") {
            val userId = call.userId() ?: return@patch call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Authentication required"))
            val updated = service.markAllAsRead(userId)
            call.respond(NotificationBatchReadResponse(updated, service.unreadCount(userId)))
        }
    }
}
