package backend.routes

import backend.models.PaginatedResponse
import backend.models.PaginationMeta
import backend.models.TicketRequest
import backend.models.TicketResolutionUpdate
import backend.models.TicketStatusUpdate
import backend.models.TicketAssignmentUpdate
import backend.models.UserRole
import backend.security.requireAuthenticated
import backend.security.requireRole
import backend.security.userId
import backend.security.userRole
import backend.security.PermissionMatrix
import backend.security.Permission
import backend.services.TicketService
import backend.services.SlaAutomationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

fun Route.ticketRoutes(ticketService: TicketService, slaAutomation: SlaAutomationService) {
    route("/api/tickets") {
        post {
            if (!call.requireRole(UserRole.END_USER)) return@post
            val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.Created, ticketService.create(userId, call.receive<TicketRequest>()))
        }
        get {
            if (!call.requireAuthenticated()) return@get
            slaAutomation.evaluateIfDue()
            val uid = call.userId()
            val admin = Permission.TICKET_READ_ALL in PermissionMatrix.permissions(call.userRole())
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val page = ticketService.list(uid, admin, limit, offset)
            call.respond(
                PaginatedResponse(
                    data = page.items,
                    meta = PaginationMeta(
                        totalCount = page.total,
                        pageSize = limit,
                        offset = offset,
                        currentPage = (offset / limit).toInt() + 1
                    )
                )
            )
        }
        get("/{id}") {
            if (!call.requireAuthenticated()) return@get
            slaAutomation.evaluateIfDue()
            val actorId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val admin = Permission.TICKET_READ_ALL in PermissionMatrix.permissions(call.userRole())
            val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ticket = ticketService.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!admin && ticket.userId != actorId) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(ticket)
        }
        get("/{id}/history") {
            if (!call.requireAuthenticated()) return@get
            val actorId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val admin = Permission.TICKET_READ_ALL in PermissionMatrix.permissions(call.userRole())
            val id = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ticket = ticketService.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!admin && ticket.userId != actorId) return@get call.respond(HttpStatusCode.NotFound)
            val history = ticketService.history(id)
            call.respond(if (admin) history else history.map { it.copy(note = null) })
        }
        put("/{id}") {
            if (!call.requireAuthenticated()) return@put
            val actorId = call.userId() ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val admin = Permission.TICKET_MANAGE in PermissionMatrix.permissions(call.userRole())
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val existing = ticketService.get(id) ?: return@put call.respond(HttpStatusCode.NotFound)
            if (!admin && existing.userId != actorId) return@put call.respond(HttpStatusCode.NotFound)
            val updated = ticketService.update(id, call.receive<TicketRequest>(), actorId, call.userRole()) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(updated)
        }
        put("/{id}/status") {
            if (!call.requireAuthenticated()) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<TicketStatusUpdate>()
            val actorId = call.userId()
            val actor = actorId?.let { ticketService.actorDisplayName(it, call.userRole()) }
                ?: "${call.userRole()}-0"
            val ticket = ticketService.updateStatus(
                id, req.status, actor, actorId, call.userRole(), req.note, req.userMessage,
                req.resolutionCategory, req.notifyRequester
            ) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(ticket)
        }
        put("/{id}/resolution") {
            if (!call.requireAuthenticated()) return@put
            if (Permission.TICKET_MANAGE !in PermissionMatrix.permissions(call.userRole())) {
                return@put call.respond(HttpStatusCode.Forbidden)
            }
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<TicketResolutionUpdate>()
            val actorId = call.userId() ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val actor = ticketService.actorDisplayName(actorId, call.userRole())
            val ticket = ticketService.updateResolution(id, req.resolution, actor, actorId, call.userRole())
                ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(ticket)
        }
        post("/{id}/claim") {
            if (!call.requireAuthenticated()) return@post
            if (Permission.TICKET_MANAGE !in PermissionMatrix.permissions(call.userRole())) return@post call.respond(HttpStatusCode.Forbidden)
            val id = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actorId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val ticket = ticketService.claim(id, actorId, call.userRole()) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(ticket)
        }
        post("/{id}/release") {
            if (!call.requireAuthenticated()) return@post
            if (Permission.TICKET_MANAGE !in PermissionMatrix.permissions(call.userRole())) return@post call.respond(HttpStatusCode.Forbidden)
            val id = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val actorId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<TicketAssignmentUpdate>()
            val ticket = ticketService.release(id, actorId, call.userRole(), req.note) ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respond(ticket)
        }
        put("/{id}/assignment") {
            if (!call.requireRole(UserRole.SUPERADMIN)) return@put
            val id = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val actorId = call.userId() ?: return@put call.respond(HttpStatusCode.Unauthorized)
            val req = call.receive<TicketAssignmentUpdate>()
            val targetId = req.assigneeUserId ?: return@put call.respond(HttpStatusCode.BadRequest)
            val ticket = ticketService.reassign(id, targetId, actorId, call.userRole(), req.note) ?: return@put call.respond(HttpStatusCode.NotFound)
            call.respond(ticket)
        }
    }
}
