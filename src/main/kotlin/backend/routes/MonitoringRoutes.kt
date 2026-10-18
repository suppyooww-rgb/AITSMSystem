package backend.routes

import backend.models.ApiErrorResponse
import backend.models.CollectorEnrollmentRequest
import backend.models.CollectorInstallerDownloadRequest
import backend.models.CollectorPackageRequest
import backend.models.CollectorRedeemRequest
import backend.models.CollectorReportRequest
import backend.models.InventoryUpsertDetectedRequest
import backend.models.UserRole
import backend.repository.DeviceRepository
import backend.security.requireRole
import backend.security.requireAuthenticated
import backend.security.userId
import backend.security.userRole
import backend.config.Env
import backend.services.AssetDetectionService
import backend.services.CollectorRateLimitException
import backend.services.InventoryService
import backend.services.InvalidCollectorTokenException
import backend.services.MonitoringService
import backend.services.CollectorInstallerProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Base64

fun Route.monitoringRoutes(
    service: MonitoringService,
    deviceRepository: DeviceRepository,
    assetDetectionService: AssetDetectionService,
    inventoryService: InventoryService
) {
    route("/api/monitoring") {
        post("/collector/reports") {
            val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Collector token is required"))
            try {
                call.respond(HttpStatusCode.Accepted, service.ingest(token, call.receive<CollectorReportRequest>()))
            } catch (_: InvalidCollectorTokenException) {
                call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Collector token is invalid or revoked"))
            } catch (_: CollectorRateLimitException) {
                call.respond(HttpStatusCode.TooManyRequests, ApiErrorResponse(429, "Collector report rate limit exceeded"))
            }
        }
        get("/collector/commands") {
            val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Collector token is required"))
            val command = try { service.nextCommand(token) } catch (_: InvalidCollectorTokenException) {
                return@get call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Collector token is invalid or revoked"))
            }
            if (command == null) call.respond(HttpStatusCode.NoContent) else call.respond(command)
        }
        post("/collector/redeem") {
            val request = call.receive<CollectorRedeemRequest>()
            val enrolled = service.redeemPackage(request.enrollmentCode)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ApiErrorResponse(401, "Enrollment code is invalid, expired, or already used"))
            call.respond(HttpStatusCode.Created, enrolled)
        }

        post("/collector-packages") {
            if (!call.requireAuthenticated()) return@post
            val actorId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(HttpStatusCode.Created, service.createPackage(actorId, call.userRole(), call.receive<CollectorPackageRequest>()))
        }
        post("/collector-installer/windows") {
            if (!call.requireAuthenticated()) return@post
            val actorId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val request = call.receive<CollectorInstallerDownloadRequest>()
            if (!service.canDownloadPackage(request.enrollmentCode, actorId)) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(403, "Enrollment package is unavailable"))
            }
            if (request.repairCollectorId != null && !service.canRepairCollector(request.repairCollectorId, actorId, call.userRole())) {
                return@post call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(403, "Collector repair target is unavailable"))
            }
            val executable = runCatching { CollectorInstallerProvider.openStream() }.getOrElse {
                call.application.environment.log.error("Collector installer is unavailable", it)
                return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiErrorResponse(503, "Windows collector installer is temporarily unavailable"))
            }
            val origin = Env.get("APP_BASE_URL")?.trim()?.removeSuffix("/")
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiErrorResponse(503, "Application base URL is not configured"))
            val encodedOrigin = Base64.getUrlEncoder().withoutPadding().encodeToString(origin.toByteArray(Charsets.UTF_8))
            val repairTarget = request.repairCollectorId ?: "new"
            val filename = "AITSMCollectorSetup--$encodedOrigin--${request.enrollmentCode}--$repairTarget.exe"
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
            call.respondOutputStream(ContentType.Application.OctetStream) {
                executable.use { it.copyTo(this) }
            }
        }

        get("/collectors") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.collectors()) }
        get("/collectors/{id}/diagnostics") {
            if (!call.requireRole(UserRole.ADMIN)) return@get
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(400, "Collector id is required"))
            val diagnostics = service.collectorDiagnostics(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiErrorResponse(404, "Collector not found"))
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"AITSM-collector-$id-diagnostics.txt\"")
            call.respondText(diagnostics, ContentType.Text.Plain)
        }
        get("/my-collectors") {
            if (!call.requireAuthenticated()) return@get
            val actorId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            call.respond(service.collectorsForUser(actorId))
        }
        get("/my-collectors/{id}/diagnostics") {
            if (!call.requireAuthenticated()) return@get
            val actorId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(400, "Collector id is required"))
            val diagnostics = service.collectorDiagnostics(id, actorId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiErrorResponse(404, "Collector not found"))
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"AITSM-collector-diagnostics.txt\"")
            call.respondText(diagnostics, ContentType.Text.Plain)
        }
        post("/collectors") {
            if (!call.requireRole(UserRole.ADMIN)) return@post
            call.respond(HttpStatusCode.Created, service.enroll(call.receive<CollectorEnrollmentRequest>()))
        }
        delete("/collectors/{id}") {
            if (!call.requireRole(UserRole.ADMIN)) return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(400, "Collector id is required"))
            if (!runCatching { service.revoke(id) }.getOrDefault(false)) return@delete call.respond(HttpStatusCode.NotFound, ApiErrorResponse(404, "Collector not found"))
            call.respond(mapOf("revoked" to true))
        }
        delete("/collectors/{id}/archive") {
            if (!call.requireRole(UserRole.ADMIN)) return@delete
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(400, "Collector id is required"))
            if (!runCatching { service.archive(id) }.getOrDefault(false)) {
                return@delete call.respond(HttpStatusCode.Conflict, ApiErrorResponse(409, "Revoke the collector before removing it from the active list"))
            }
            call.respond(mapOf("archived" to true))
        }
        delete("/my-collectors/{id}") {
            if (!call.requireAuthenticated()) return@delete
            val actorId = call.userId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(400, "Collector id is required"))
            if (!runCatching { service.revokeOwned(id, actorId) }.getOrDefault(false)) {
                return@delete call.respond(HttpStatusCode.NotFound, ApiErrorResponse(404, "Collector not found"))
            }
            call.respond(mapOf("revoked" to true))
        }

        post("/client-metrics") {
            call.respond(HttpStatusCode.Gone, ApiErrorResponse(410, "Legacy client metrics are disabled. Enroll an authenticated collector."))
        }
        get("/host-telemetry") {
            if (!call.requireRole(UserRole.ADMIN)) return@get
            call.respond(HttpStatusCode.Gone, ApiErrorResponse(410, "Cloud service host metrics are not LAN device telemetry."))
        }
        get("/lan-devices") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.lanDevices()) }
        get("/lan-peer-ips") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.lanPeerIps()) }
        get("/summary") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.summary()) }
        post("/refresh-discovery") {
            if (!call.requireRole(UserRole.ADMIN)) return@post
            call.respond(HttpStatusCode.Accepted, service.requestDiscovery())
        }
        get("/devices") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.devices()) }
        get("/cpu") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.cpu()) }
        get("/alerts") { if (call.requireRole(UserRole.ADMIN)) call.respond(service.alerts()) }
    }
}
