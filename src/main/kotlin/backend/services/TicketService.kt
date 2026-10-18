package backend.services

import backend.models.Ticket
import backend.models.TicketRequest
import backend.models.TicketStatus
import backend.models.SLAAnalytics
import backend.models.UserRole
import backend.security.Permission
import backend.security.PermissionMatrix
import backend.repository.AuditRepository
import backend.repository.TicketRepository
import backend.repository.UserRepository
import java.time.Duration
import java.time.LocalDateTime
import java.math.RoundingMode

class TicketService(
    private val repository: TicketRepository,
    private val audit: AuditRepository,
    private val notifications: NotificationService,
    private val users: UserRepository
) {
    private val followUpThresholdHours = 24L
    private val allowedPriorities = setOf("Critical", "High", "Medium", "Low")
    private val allowedCategories = setOf("Hardware", "Software", "Network", "Database", "Security", "Other")
    private val resolutionCategories = setOf(
        "Configuration Change", "Software Fix", "Hardware Repair", "Access Restored",
        "Network Fix", "User Guidance", "Workaround", "No Fault Found", "Other"
    )

    fun create(userId: Int, req: TicketRequest): Ticket {
        val normalized = normalizeTicketRequest(req)
        validateTicketRequest(normalized)
        return repository.create(userId, normalized).also {
            audit.log(userId, "Created ticket #${it.id}", "tickets")
            notifyAdminsOncePerTicket(
                eventType = "ticket_created",
                ticketId = it.id,
                title = "New Ticket Created",
                message = "Ticket #${it.id} created by user #$userId"
            )
        }
    }

    fun list(userId: Int?, admin: Boolean, limit: Int, offset: Long) = repository.list(userId, admin, limit, offset).let {
        it.copy(items = enrichRisk(it.items))
    }
    fun get(id: Int): Ticket? = repository.get(id)?.let { enrichRisk(listOf(it)).first() }
    fun history(id: Int) = repository.history(id)
    fun actorDisplayName(userId: Int, role: UserRole): String =
        users.findById(userId)?.let { "${it.fullName} (${role.asHeaderValue()})" } ?: "${role.asHeaderValue()}-$userId"
    fun allForAdvisory() = enrichRisk(repository.list(null, true, 500, 0).items)

    fun update(id: Int, req: TicketRequest, userId: Int?, role: UserRole): Ticket? {
        val existing = repository.get(id) ?: return null
        val admin = Permission.TICKET_MANAGE in PermissionMatrix.permissions(role)
        val enforceOwner = admin && role != UserRole.SUPERADMIN
        if (enforceOwner) requireTicketOwner(existing, userId)
        val normalized = normalizeTicketRequest(req)
        validateTicketRequest(normalized)
        return repository.update(id, normalized, userId, enforceOwner = enforceOwner).also { updated ->
            audit.log(userId, "Updated ticket #$id", "tickets")
            if (updated != null && userId != updated.userId) {
                val priorityChanged = existing.priority != updated.priority
                notifications.pushOncePerTicketEvent(
                    userId = updated.userId,
                    eventType = if (priorityChanged) "ticket_priority_changed" else "ticket_updated",
                    ticketId = updated.id,
                    title = if (priorityChanged) "Ticket Priority Changed" else "Ticket Updated",
                    message = if (priorityChanged) "Ticket #${updated.id} priority changed to ${updated.priority}" else "Ticket #${updated.id} was updated",
                    occurrenceKey = updated.updatedAt
                )
            }
            if (updated != null && role == UserRole.SUPERADMIN && existing.assignedToUserId != userId) {
                notifySuperAdminOverride(updated, actorDisplayName(userId ?: 0, role), "edited ticket details")
            }
        }
    }

    fun updateStatus(
        id: Int,
        statusValue: String,
        actor: String,
        userId: Int?,
        role: UserRole,
        noteValue: String? = null,
        userMessageValue: String? = null,
        resolutionCategoryValue: String? = null,
        notifyRequester: Boolean = true
    ): Ticket? {
        val existing = repository.get(id) ?: return null
        val requestedAction = statusValue.trim().lowercase()
        val status = parseStatus(statusValue)
        val admin = Permission.TICKET_MANAGE in PermissionMatrix.permissions(role)
        if (existing.status == status) return existing
        val note = noteValue?.trim()?.takeIf { it.isNotBlank() }
        val userMessage = userMessageValue?.trim()?.takeIf { it.isNotBlank() }
        val resolutionCategory = resolutionCategoryValue?.trim()?.takeIf { it.isNotBlank() }

        when {
            admin -> {
                if (role != UserRole.SUPERADMIN) requireTicketOwner(existing, userId)
                require(status in setOf(TicketStatus.OPEN, TicketStatus.PENDING, TicketStatus.RESOLVED, TicketStatus.CLOSED)) { "Unsupported status for admin" }
                require(requestedAction !in setOf("cancelled", "canceled", "cancel")) { "Admin cannot cancel tickets" }
                require(note != null && note.length >= 5) { "A work note of at least 5 characters is required for every administrator status change" }
                require(note.length <= 4_000) { "Work note must not exceed 4,000 characters" }
                require(userMessage == null || userMessage.length <= 4_000) { "Requester message must not exceed 4,000 characters" }
                if (status == TicketStatus.RESOLVED) {
                    require(userMessage != null && userMessage.length >= 10) { "A requester-visible resolution of at least 10 characters is required" }
                    require(resolutionCategory in resolutionCategories) { "Select a valid resolution category" }
                }
                if (status == TicketStatus.CLOSED) {
                    require(existing.status == TicketStatus.RESOLVED) { "Resolve the ticket before closing it" }
                }
            }
            else -> {
                require(existing.userId == (userId ?: -1)) { "You can only modify your own tickets" }
                when (status) {
                    TicketStatus.CLOSED -> {
                        if (requestedAction in setOf("cancelled", "canceled", "cancel")) {
                            require(existing.status in setOf(TicketStatus.OPEN, TicketStatus.PENDING)) { "Only open or pending tickets can be cancelled" }
                        } else {
                            require(existing.status == TicketStatus.RESOLVED) { "Only resolved tickets can be closed" }
                        }
                    }
                    TicketStatus.PENDING -> {
                        require(existing.status != TicketStatus.RESOLVED && existing.status != TicketStatus.CLOSED) { "Resolved/closed tickets cannot move to pending" }
                        val updatedAt = runCatching { LocalDateTime.parse(existing.updatedAt) }.getOrNull() ?: LocalDateTime.now()
                        val ageHours = Duration.between(updatedAt, LocalDateTime.now()).toHours()
                        require(ageHours >= followUpThresholdHours) { "Pending follow-up can be requested after $followUpThresholdHours hours without resolution" }
                    }
                    TicketStatus.OPEN, TicketStatus.RESOLVED -> error("Unsupported status for end-user")
                }

                if (requestedAction in setOf("closed", "close")) {
                    require(existing.status == TicketStatus.RESOLVED) { "Only resolved tickets can be closed" }
                }
            }
        }

        val recordedNote = note ?: when {
            requestedAction in setOf("cancelled", "canceled", "cancel") -> "Requester cancelled the ticket"
            status == TicketStatus.CLOSED -> "Requester confirmed the resolution and closed the ticket"
            else -> "Requester requested follow-up"
        }
        return repository.updateStatus(
            id = id,
            newStatus = status.name,
            updatedBy = actor,
            workNote = recordedNote,
            userMessageValue = userMessage,
            resolutionCategoryValue = resolutionCategory,
            notifyRequesterValue = admin && notifyRequester,
            recordFirstResponse = admin,
            expectedAssigneeUserId = userId,
            enforceOwner = admin && role != UserRole.SUPERADMIN
        )?.let { enrichRisk(listOf(it)).first() }?.also { updated ->
            audit.log(userId, "Changed ticket #$id from ${existing.status.name} to ${status.name} with a recorded work note", "tickets")
            if (admin) {
                val displayStatus = status.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
                if (notifyRequester) {
                    notifications.pushOncePerTicketEvent(
                        userId = updated.userId,
                        eventType = "ticket_status_updated_${status.name.lowercase()}",
                        ticketId = updated.id,
                        title = "Ticket Status Updated",
                        message = userMessage ?: "Ticket #${updated.id} updated to $displayStatus",
                        occurrenceKey = updated.updatedAt
                    )
                }
                if (existing.slaFirstResponseAt == null && updated.slaResponseState == "BREACHED") {
                    notifySlaBreach(updated, "response")
                }
                if (existing.slaResolvedAt == null && status in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) && updated.slaResolutionState == "BREACHED") {
                    notifySlaBreach(updated, "resolution")
                }
                if (role == UserRole.SUPERADMIN && existing.assignedToUserId != userId) {
                    notifySuperAdminOverride(updated, actor, "changed status to $displayStatus")
                }
            }
        }
    }

    fun updateResolution(id: Int, resolutionValue: String, actor: String, userId: Int?, role: UserRole): Ticket? {
        val existing = repository.get(id) ?: return null
        if (role != UserRole.SUPERADMIN) requireTicketOwner(existing, userId)
        require(existing.status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)) { "Resolved or closed tickets cannot accept a new resolution draft" }
        val resolution = resolutionValue.trim()
        require(resolution.isNotBlank()) { "Resolution is required" }
        require(resolution.length <= 10_000) { "Resolution must not exceed 10,000 characters" }

        return repository.updateResolution(
            id, resolution, actor, userId,
            enforceOwner = role != UserRole.SUPERADMIN
        )?.also { updated ->
            audit.log(userId, "Saved reviewed resolution for ticket #$id", "tickets")
            notifications.pushOncePerTicketEvent(
                userId = updated.userId,
                eventType = "ticket_resolution_updated",
                ticketId = updated.id,
                title = "Ticket Resolution Updated",
                message = "A reviewed resolution was saved for ticket #${updated.id}",
                occurrenceKey = updated.resolutionUpdatedAt ?: updated.updatedAt
            )
            if (role == UserRole.SUPERADMIN && existing.assignedToUserId != userId) {
                notifySuperAdminOverride(updated, actor, "saved a reviewed resolution")
            }
        }
    }

    fun claim(id: Int, actorId: Int, role: UserRole): Ticket? {
        require(Permission.TICKET_MANAGE in PermissionMatrix.permissions(role)) { "Ticket management permission is required" }
        val actor = actorDisplayName(actorId, role)
        val existing = repository.get(id) ?: return null
        require(existing.status == TicketStatus.OPEN) { "Only an open ticket can be taken" }
        val updated = repository.claim(id, actorId, actor) ?: return null
        audit.log(actorId, "Claimed ticket #$id", "tickets")
        notifyAdminsOncePerTicket(
            eventType = "ticket_assigned",
            ticketId = id,
            title = "Ticket Being Handled",
            message = "Ticket #$id is being handled by $actor",
            occurrenceKey = updated.updatedAt
        )
        notifications.pushOncePerTicketEvent(
            userId = updated.userId,
            eventType = "ticket_assigned_requester",
            ticketId = id,
            title = "Ticket Assigned",
            message = "Ticket #$id is now being handled by $actor",
            occurrenceKey = updated.updatedAt
        )
        return enrichRisk(listOf(updated)).first()
    }

    fun release(id: Int, actorId: Int, role: UserRole, noteValue: String?): Ticket? {
        require(Permission.TICKET_MANAGE in PermissionMatrix.permissions(role)) { "Ticket management permission is required" }
        val existing = repository.get(id) ?: return null
        requireTicketOwner(existing, actorId)
        require(existing.status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)) { "Resolved or closed tickets cannot be released" }
        val note = noteValue?.trim().orEmpty()
        require(note.length >= 5) { "A release note of at least 5 characters is required" }
        require(note.length <= 4_000) { "Release note must not exceed 4,000 characters" }
        val actor = actorDisplayName(actorId, role)
        val updated = repository.release(id, actorId, actor, note) ?: return null
        audit.log(actorId, "Released ticket #$id back to the queue", "tickets")
        notifyAdminsOncePerTicket(
            eventType = "ticket_released",
            ticketId = id,
            title = "Ticket Returned to Queue",
            message = "Ticket #$id was released by $actor and is available",
            occurrenceKey = updated.updatedAt
        )
        return enrichRisk(listOf(updated)).first()
    }

    fun reassign(id: Int, targetUserId: Int, actorId: Int, role: UserRole, noteValue: String?): Ticket? {
        require(role == UserRole.SUPERADMIN) { "Only a Super Admin can reassign an owned ticket" }
        val existing = repository.get(id) ?: return null
        require(existing.status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)) { "Resolved or closed tickets cannot be reassigned" }
        val target = users.findById(targetUserId) ?: throw IllegalArgumentException("Selected assignee was not found")
        require(Permission.TICKET_MANAGE in PermissionMatrix.permissions(target.role)) { "Selected user cannot manage tickets" }
        val note = noteValue?.trim().orEmpty()
        require(note.length >= 5) { "A reassignment note of at least 5 characters is required" }
        require(note.length <= 4_000) { "Reassignment note must not exceed 4,000 characters" }
        val actor = actorDisplayName(actorId, role)
        val targetName = "${target.fullName} (${target.role.asHeaderValue()})"
        val updated = repository.reassign(id, target.id, targetName, actor, note) ?: return null
        audit.log(actorId, "Reassigned ticket #$id to ${target.fullName}", "tickets")
        notifyAdminsOncePerTicket(
            eventType = "ticket_reassigned",
            ticketId = id,
            title = "Ticket Reassigned",
            message = "Ticket #$id was assigned to $targetName by $actor",
            occurrenceKey = updated.updatedAt
        )
        return enrichRisk(listOf(updated)).first()
    }

    private fun requireTicketOwner(ticket: Ticket, actorId: Int?) {
        require(ticket.assignedToUserId != null) { "Take this ticket before editing it" }
        require(ticket.assignedToUserId == actorId) { "This ticket is being handled by ${ticket.assignedTo ?: "another administrator"}" }
    }

    private fun notifySuperAdminOverride(ticket: Ticket, actor: String, action: String) {
        notifyAdminsOncePerTicket(
            eventType = "ticket_superadmin_override",
            ticketId = ticket.id,
            title = "Super Admin Ticket Override",
            message = "$actor $action on ticket #${ticket.id}",
            occurrenceKey = ticket.updatedAt
        )
    }

    private fun parseStatus(raw: String): TicketStatus {
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "open" -> TicketStatus.OPEN
            "in progress", "in-progress", "pending", "follow-up requested", "follow up requested" -> TicketStatus.PENDING
            "resolved" -> TicketStatus.RESOLVED
            "closed", "cancelled", "canceled" -> TicketStatus.CLOSED
            else -> throw IllegalArgumentException("Unsupported ticket status: $raw")
        }
    }

    private fun normalizeTicketRequest(req: TicketRequest): TicketRequest = req.copy(
        title = req.title.trim(),
        description = req.description.trim(),
        priority = req.priority.trim(),
        category = req.category.trim()
    )

    private fun validateTicketRequest(req: TicketRequest) {
        require(req.title.isNotBlank()) { "Ticket title is required" }
        require(req.title.length in 5..200) { "Ticket title must be between 5 and 200 characters" }
        require(req.description.isNotBlank()) { "Ticket description is required" }
        require(req.description.length >= 10) { "Ticket description must be at least 10 characters" }
        require(req.priority in allowedPriorities) { "Invalid ticket priority" }
        require(req.category in allowedCategories) { "Invalid ticket category" }
    }

    private fun notifyAdminsOncePerTicket(
        eventType: String,
        ticketId: Int,
        title: String,
        message: String,
        occurrenceKey: String = eventType
    ) {
        val adminIds = users.listUsers()
            .filter { it.role in setOf(UserRole.ADMIN, UserRole.IT_ADMIN, UserRole.SUPERADMIN) }
            .map { it.id }

        adminIds.forEach {
            notifications.pushOncePerTicketEvent(
                userId = it,
                eventType = eventType,
                ticketId = ticketId,
                title = title,
                message = message,
                occurrenceKey = occurrenceKey
            )
        }
    }

    private fun notifySlaBreach(ticket: Ticket, clock: String) {
        val title = "SLA ${clock.replaceFirstChar { it.titlecase() }} Breached"
        val message = "Ticket #${ticket.id} exceeded its $clock target"
        val eventType = "ticket_sla_${clock}_breached"
        notifyAdminsOncePerTicket(eventType, ticket.id, title, message)
        notifications.pushOncePerTicketEvent(
            userId = ticket.userId,
            eventType = eventType,
            ticketId = ticket.id,
            title = title,
            message = message
        )
    }

    fun slaAnalytics(): SLAAnalytics {
        val tickets = enrichRisk(repository.allForSla())
        val active = tickets.filter { it.status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) }
        val responseMeasured = tickets.filter { it.slaResponseState in setOf("MET", "BREACHED") }
        val resolutionMeasured = tickets.filter { it.status in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) && it.slaResolutionState in setOf("MET", "BREACHED") }
        val responseMet = responseMeasured.count { it.slaResponseState == "MET" }
        val resolutionMet = resolutionMeasured.count { it.slaResolutionState == "MET" }
        val responseDurations = tickets.mapNotNull { ticket ->
            durationMinutes(ticket.createdAt, ticket.slaFirstResponseAt)
        }
        val resolutionDurations = tickets.mapNotNull { ticket ->
            durationMinutes(ticket.createdAt, ticket.slaResolvedAt)
        }
        val (predictionSamples, correctHighRiskPredictions) = repository.highRiskPredictionOutcomes()
        return SLAAnalytics(
            totalTickets = tickets.size,
            activeTickets = active.size,
            responseMeasured = responseMeasured.size,
            responseMet = responseMet,
            responseBreached = responseMeasured.size - responseMet,
            responseCompliancePercent = percent(responseMet, responseMeasured.size),
            resolutionMeasured = resolutionMeasured.size,
            resolutionMet = resolutionMet,
            resolutionBreached = resolutionMeasured.size - resolutionMet,
            resolutionCompliancePercent = percent(resolutionMet, resolutionMeasured.size),
            averageResponseMinutes = average(responseDurations),
            averageResolutionMinutes = average(resolutionDurations),
            activeHighRisk = active.count { it.slaRiskLevel in setOf("HIGH", "CRITICAL") },
            activeBreaches = active.count { it.slaResolutionState == "BREACHED" || it.slaResponseState == "BREACHED" },
            predictionSamples = predictionSamples,
            highRiskPrecisionPercent = percent(correctHighRiskPredictions, predictionSamples)
        )
    }

    private fun enrichRisk(tickets: List<Ticket>): List<Ticket> {
        if (tickets.isEmpty()) return tickets
        val population = repository.allForSla()
        val activeQueueSize = population.count { it.status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) }
        val completed = population.filter { it.status in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) }
        return tickets.map { ticket ->
            val comparable = completed.filter { it.priority == ticket.priority && it.category == ticket.category }
            val breachRate = if (comparable.isEmpty()) 0.0 else comparable.count { it.slaResolutionState == "BREACHED" }.toDouble() / comparable.size
            val assessment = assessSlaRisk(ticket, activeQueueSize, breachRate)
            ticket.copy(slaRiskScore = assessment.score, slaRiskLevel = assessment.level, slaRiskReasons = assessment.reasons)
        }
    }

    private fun durationMinutes(start: String, end: String?): Long? {
        val from = runCatching { LocalDateTime.parse(start) }.getOrNull() ?: return null
        val to = end?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
        return Duration.between(from, to).toMinutes().coerceAtLeast(0)
    }

    private fun percent(met: Int, measured: Int): Double = if (measured == 0) 0.0 else
        (met.toBigDecimal() * 100.toBigDecimal() / measured.toBigDecimal()).setScale(1, RoundingMode.HALF_UP).toDouble()

    private fun average(values: List<Long>): Double? = if (values.isEmpty()) null else
        (values.average().toBigDecimal()).setScale(1, RoundingMode.HALF_UP).toDouble()
}
