package backend.repository

import backend.config.SLAPoliciesTable
import backend.config.SlaRiskPredictionsTable
import backend.config.TicketHistoryTable
import backend.config.TicketsTable
import backend.models.Ticket
import backend.models.TicketHistoryEvent
import backend.models.TicketRequest
import backend.models.TicketStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.LocalDateTime

class TicketRepository {
    fun create(userId: Int, req: TicketRequest): Ticket = transaction {
        val now = LocalDateTime.now()
        val (responseTarget, resolutionTarget) = policyTargets(req.priority)
        val id = TicketsTable.insert {
            it[TicketsTable.userId] = userId
            it[title] = req.title
            it[description] = req.description
            it[priority] = req.priority
            it[category] = req.category
            it[status] = TicketStatus.OPEN.name
            it[deviceId] = req.deviceId
            it[createdAt] = now
            it[updatedAt] = now
            it[slaResponseTargetMinutes] = responseTarget
            it[slaResolutionTargetMinutes] = resolutionTarget
            it[slaResponseDueAt] = now.plusMinutes(responseTarget.toLong())
            it[slaResolutionDueAt] = now.plusMinutes(resolutionTarget.toLong())
            it[slaFirstResponseAt] = null
            it[slaResolvedAt] = null
            it[slaResponseWarningLevel] = 0
            it[slaResolutionWarningLevel] = 0
            it[slaResponseBreachedAt] = null
            it[slaResolutionBreachedAt] = null
            it[slaReopenedCount] = 0
        }[TicketsTable.id]

        TicketHistoryTable.insert {
            it[ticketId] = id
            it[status] = TicketStatus.OPEN.name
            it[previousStatus] = null
            it[workNote] = "Ticket created"
            it[userMessage] = null
            it[resolutionCategory] = null
            it[notifyRequester] = false
            it[updatedBy] = "user-$userId"
            it[timestamp] = now
        }
        get(id)!!
    }

    fun list(userId: Int?, admin: Boolean, limit: Int, offset: Long): PagedResult<Ticket> = transaction {
        val base = if (admin) TicketsTable.selectAll() else TicketsTable.selectAll().where { TicketsTable.userId eq userId!! }
        val total = base.count()
        val items = base.limit(limit, offset).map(::toTicket)
        PagedResult(items, total)
    }

    fun get(id: Int): Ticket? = transaction { TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull()?.let(::toTicket) }

    fun history(id: Int, limit: Int = 20): List<TicketHistoryEvent> = transaction {
        TicketHistoryTable.selectAll()
            .where { TicketHistoryTable.ticketId eq id }
            .orderBy(TicketHistoryTable.timestamp, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(limit)
            .map {
                TicketHistoryEvent(
                    status = it[TicketHistoryTable.status],
                    previousStatus = it[TicketHistoryTable.previousStatus],
                    note = it[TicketHistoryTable.workNote],
                    userMessage = it[TicketHistoryTable.userMessage],
                    resolutionCategory = it[TicketHistoryTable.resolutionCategory],
                    notifyRequester = it[TicketHistoryTable.notifyRequester],
                    updatedBy = it[TicketHistoryTable.updatedBy],
                    timestamp = it[TicketHistoryTable.timestamp].toString()
                )
            }
            .reversed()
    }

    fun update(id: Int, req: TicketRequest, expectedAssigneeUserId: Int? = null, enforceOwner: Boolean = false): Ticket? = transaction {
        val existing = TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull() ?: return@transaction null
        val priorityChanged = existing[TicketsTable.priority] != req.priority
        val (responseTarget, resolutionTarget) = policyTargets(req.priority)
        val updated = TicketsTable.update({
            if (enforceOwner) (TicketsTable.id eq id) and (TicketsTable.assignedToUserId eq expectedAssigneeUserId)
            else TicketsTable.id eq id
        }) {
            it[title] = req.title
            it[description] = req.description
            it[priority] = req.priority
            it[category] = req.category
            it[deviceId] = req.deviceId
            it[updatedAt] = LocalDateTime.now()
            if (priorityChanged) {
                it[slaResponseTargetMinutes] = responseTarget
                it[slaResolutionTargetMinutes] = resolutionTarget
                it[slaResponseDueAt] = existing[TicketsTable.createdAt].plusMinutes(responseTarget.toLong())
                it[slaResolutionDueAt] = existing[TicketsTable.createdAt].plusMinutes(resolutionTarget.toLong())
                it[slaResponseWarningLevel] = 0
                it[slaResolutionWarningLevel] = 0
            }
        }
        if (updated == 0) error("Ticket ownership changed before the update could be saved")
        get(id)
    }

    fun claim(id: Int, assigneeUserId: Int, assigneeName: String): Ticket? = transaction {
        val now = LocalDateTime.now()
        val changed = TicketsTable.update({
            (TicketsTable.id eq id) and
                TicketsTable.assignedToUserId.isNull() and
                (TicketsTable.status eq TicketStatus.OPEN.name)
        }) {
            it[assignedToUserId] = assigneeUserId
            it[assignedTo] = assigneeName
            it[status] = TicketStatus.PENDING.name
            it[updatedAt] = now
            it[slaFirstResponseAt] = now
        }
        if (changed == 0) {
            val current = TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull()
                ?: return@transaction null
            if (current[TicketsTable.assignedToUserId] == assigneeUserId) return@transaction toTicket(current)
            error("This ticket was already taken by ${current[TicketsTable.assignedTo] ?: "another administrator"}")
        }
        TicketHistoryTable.insert {
            it[ticketId] = id
            it[status] = TicketStatus.PENDING.name
            it[previousStatus] = TicketStatus.OPEN.name
            it[workNote] = "Ticket claimed by $assigneeName"
            it[userMessage] = "Your ticket is now being handled by $assigneeName."
            it[resolutionCategory] = null
            it[notifyRequester] = true
            it[updatedBy] = assigneeName
            it[timestamp] = now
        }
        TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull()?.let(::toTicket)
    }

    fun release(id: Int, assigneeUserId: Int, actorName: String, note: String): Ticket? = transaction {
        val now = LocalDateTime.now()
        val existing = TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull() ?: return@transaction null
        val changed = TicketsTable.update({
            (TicketsTable.id eq id) and (TicketsTable.assignedToUserId eq assigneeUserId)
        }) {
            it[assignedToUserId] = null
            it[assignedTo] = null
            it[status] = TicketStatus.OPEN.name
            it[updatedAt] = now
        }
        if (changed == 0) error("Ticket ownership changed before it could be released")
        TicketHistoryTable.insert {
            it[ticketId] = id
            it[status] = TicketStatus.OPEN.name
            it[previousStatus] = existing[TicketsTable.status]
            it[workNote] = note
            it[userMessage] = null
            it[resolutionCategory] = null
            it[notifyRequester] = false
            it[updatedBy] = actorName
            it[timestamp] = now
        }
        TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull()?.let(::toTicket)
    }

    fun reassign(id: Int, assigneeUserId: Int, assigneeName: String, actorName: String, note: String): Ticket? = transaction {
        val now = LocalDateTime.now()
        val existing = TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull() ?: return@transaction null
        val nextStatus = if (existing[TicketsTable.status] == TicketStatus.OPEN.name) TicketStatus.PENDING.name else existing[TicketsTable.status]
        TicketsTable.update({ TicketsTable.id eq id }) {
            it[assignedToUserId] = assigneeUserId
            it[assignedTo] = assigneeName
            it[status] = nextStatus
            it[updatedAt] = now
            if (existing[TicketsTable.slaFirstResponseAt] == null) it[slaFirstResponseAt] = now
        }
        TicketHistoryTable.insert {
            it[ticketId] = id
            it[status] = nextStatus
            it[previousStatus] = existing[TicketsTable.status]
            it[workNote] = note
            it[userMessage] = null
            it[resolutionCategory] = null
            it[notifyRequester] = false
            it[updatedBy] = actorName
            it[timestamp] = now
        }
        TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull()?.let(::toTicket)
    }

    fun updateStatus(
        id: Int,
        newStatus: String,
        updatedBy: String,
        workNote: String?,
        userMessageValue: String?,
        resolutionCategoryValue: String?,
        notifyRequesterValue: Boolean,
        recordFirstResponse: Boolean = false,
        expectedAssigneeUserId: Int? = null,
        enforceOwner: Boolean = false
    ): Ticket? = transaction {
        val now = LocalDateTime.now()
        val existing = TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull() ?: return@transaction null
        val oldStatus = runCatching { TicketStatus.valueOf(existing[TicketsTable.status]) }.getOrDefault(TicketStatus.OPEN)
        val nextStatus = runCatching { TicketStatus.valueOf(newStatus) }.getOrDefault(TicketStatus.OPEN)
        val reopening = oldStatus in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) && nextStatus in setOf(TicketStatus.OPEN, TicketStatus.PENDING)
        val updated = TicketsTable.update({
            if (enforceOwner) (TicketsTable.id eq id) and (TicketsTable.assignedToUserId eq expectedAssigneeUserId)
            else TicketsTable.id eq id
        }) {
            it[status] = newStatus
            it[updatedAt] = now
            if (recordFirstResponse && existing[TicketsTable.slaFirstResponseAt] == null) {
                it[slaFirstResponseAt] = now
                val responseDueAt = existing[TicketsTable.slaResponseDueAt]
                if (responseDueAt != null && now.isAfter(responseDueAt) && existing[TicketsTable.slaResponseBreachedAt] == null) {
                    it[slaResponseBreachedAt] = now
                }
            }
            if (nextStatus in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) && existing[TicketsTable.slaResolvedAt] == null) {
                it[slaResolvedAt] = now
                val resolutionDueAt = existing[TicketsTable.slaResolutionDueAt]
                if (resolutionDueAt != null && now.isAfter(resolutionDueAt) && existing[TicketsTable.slaResolutionBreachedAt] == null) {
                    it[slaResolutionBreachedAt] = now
                }
            }
            if (reopening) {
                it[slaResolvedAt] = null
                it[slaReopenedCount] = existing[TicketsTable.slaReopenedCount] + 1
            }
            if (nextStatus == TicketStatus.RESOLVED && !userMessageValue.isNullOrBlank()) {
                it[reviewedResolution] = userMessageValue
                it[resolutionUpdatedBy] = updatedBy
                it[resolutionUpdatedAt] = now
            }
        }
        if (updated == 0) error("Ticket ownership changed before the status update could be saved")
        TicketHistoryTable.insert {
            it[ticketId] = id
            it[status] = newStatus
            it[previousStatus] = oldStatus.name
            it[TicketHistoryTable.workNote] = workNote
            it[userMessage] = userMessageValue
            it[resolutionCategory] = resolutionCategoryValue
            it[notifyRequester] = notifyRequesterValue
            it[TicketHistoryTable.updatedBy] = updatedBy
            it[timestamp] = now
        }
        get(id)
    }

    fun allForSla(): List<Ticket> = transaction {
        TicketsTable.selectAll().map(::toTicket)
    }

    fun claimResponseWarning(ticketId: Int, level: Int): Boolean = transaction {
        TicketsTable.update({
            (TicketsTable.id eq ticketId) and
                (TicketsTable.slaResponseWarningLevel less level) and
                TicketsTable.slaFirstResponseAt.isNull()
        }) { it[slaResponseWarningLevel] = level } > 0
    }

    fun claimResolutionWarning(ticketId: Int, level: Int): Boolean = transaction {
        TicketsTable.update({
            (TicketsTable.id eq ticketId) and (TicketsTable.slaResolutionWarningLevel less level)
        }) { it[slaResolutionWarningLevel] = level } > 0
    }

    fun claimResponseBreach(ticketId: Int, occurredAt: LocalDateTime): Boolean = transaction {
        TicketsTable.update({
            (TicketsTable.id eq ticketId) and TicketsTable.slaResponseBreachedAt.isNull() and TicketsTable.slaFirstResponseAt.isNull()
        }) { it[slaResponseBreachedAt] = occurredAt } > 0
    }

    fun claimResolutionBreach(ticketId: Int, occurredAt: LocalDateTime): Boolean = transaction {
        TicketsTable.update({ (TicketsTable.id eq ticketId) and TicketsTable.slaResolutionBreachedAt.isNull() }) {
            it[slaResolutionBreachedAt] = occurredAt
        } > 0
    }

    fun recordRiskPrediction(ticketId: Int, score: Int, level: String, reasons: List<String>) = transaction {
        SlaRiskPredictionsTable.insertIgnore {
            it[SlaRiskPredictionsTable.ticketId] = ticketId
            it[SlaRiskPredictionsTable.score] = score.coerceIn(0, 100)
            it[SlaRiskPredictionsTable.level] = level.take(20)
            it[SlaRiskPredictionsTable.reasons] = reasons.joinToString(" | ").take(2_000)
            it[createdAt] = LocalDateTime.now()
        }
    }

    fun highRiskPredictionOutcomes(): Pair<Int, Int> = transaction {
        val predictedTicketIds = SlaRiskPredictionsTable.selectAll()
            .where { SlaRiskPredictionsTable.level inList listOf("HIGH", "CRITICAL") }
            .map { it[SlaRiskPredictionsTable.ticketId] }
            .distinct()
        val completed = predictedTicketIds.mapNotNull { id ->
            TicketsTable.selectAll().where { TicketsTable.id eq id }.singleOrNull()
        }.filter { runCatching { TicketStatus.valueOf(it[TicketsTable.status]) }.getOrDefault(TicketStatus.OPEN) in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED) }
        completed.size to completed.count { it[TicketsTable.slaResolutionBreachedAt] != null }
    }

    fun updateResolution(
        id: Int,
        resolution: String,
        updatedBy: String,
        expectedAssigneeUserId: Int?,
        enforceOwner: Boolean = true
    ): Ticket? = transaction {
        val now = LocalDateTime.now()
        val updated = TicketsTable.update({
            if (enforceOwner) (TicketsTable.id eq id) and (TicketsTable.assignedToUserId eq expectedAssigneeUserId)
            else TicketsTable.id eq id
        }) {
            it[reviewedResolution] = resolution
            it[resolutionUpdatedBy] = updatedBy
            it[resolutionUpdatedAt] = now
            it[updatedAt] = now
        }
        if (updated == 0) error("Ticket ownership changed before the resolution could be saved")
        get(id)
    }

    private fun toTicket(row: ResultRow): Ticket {
        val priorityValue = row[TicketsTable.priority]
        val policy = policyTargets(priorityValue)
        val responseTarget = row[TicketsTable.slaResponseTargetMinutes] ?: policy.first
        val resolutionTarget = row[TicketsTable.slaResolutionTargetMinutes] ?: policy.second
        val responseDue = row[TicketsTable.slaResponseDueAt] ?: row[TicketsTable.createdAt].plusMinutes(responseTarget.toLong())
        val resolutionDue = row[TicketsTable.slaResolutionDueAt] ?: row[TicketsTable.createdAt].plusMinutes(resolutionTarget.toLong())
        val firstResponseAt = row[TicketsTable.slaFirstResponseAt]
        val resolvedAt = row[TicketsTable.slaResolvedAt]
        val now = LocalDateTime.now()
        val ticketStatus = runCatching { TicketStatus.valueOf(row[TicketsTable.status]) }.getOrDefault(TicketStatus.OPEN)
        val terminal = ticketStatus in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)
        val responseReference = firstResponseAt ?: now
        val resolutionReference = resolvedAt ?: if (terminal) row[TicketsTable.updatedAt] else now
        val responseRemaining = Duration.between(responseReference, responseDue).toMinutes()
        val resolutionRemaining = Duration.between(resolutionReference, resolutionDue).toMinutes()
        val responseState = slaState(firstResponseAt, responseDue, row[TicketsTable.slaResponseBreachedAt], now)
        val resolutionState = slaState(if (terminal) resolutionReference else null, resolutionDue, row[TicketsTable.slaResolutionBreachedAt], now)
        return Ticket(
            id = row[TicketsTable.id],
            userId = row[TicketsTable.userId],
            title = row[TicketsTable.title],
            description = row[TicketsTable.description],
            priority = priorityValue,
            category = row[TicketsTable.category],
            status = ticketStatus,
            assignedToUserId = row[TicketsTable.assignedToUserId],
            assignedTo = row[TicketsTable.assignedTo],
            reviewedResolution = row[TicketsTable.reviewedResolution],
            resolutionUpdatedBy = row[TicketsTable.resolutionUpdatedBy],
            resolutionUpdatedAt = row[TicketsTable.resolutionUpdatedAt]?.toString(),
            deviceId = row[TicketsTable.deviceId],
            createdAt = row[TicketsTable.createdAt].toString(),
            updatedAt = row[TicketsTable.updatedAt].toString(),
            slaResponseTargetMinutes = responseTarget,
            slaResolutionTargetMinutes = resolutionTarget,
            slaResponseDueAt = responseDue.toString(),
            slaResolutionDueAt = resolutionDue.toString(),
            slaFirstResponseAt = firstResponseAt?.toString(),
            slaResolvedAt = resolvedAt?.toString(),
            slaResponseRemainingMinutes = responseRemaining,
            slaRemainingMinutes = resolutionRemaining,
            slaResponseState = responseState,
            slaResolutionState = resolutionState,
            slaReopenedCount = row[TicketsTable.slaReopenedCount],
            overdue = resolutionState == "BREACHED" && !terminal
        )
    }

    private fun policyTargets(priority: String): Pair<Int, Int> =
        SLAPoliciesTable.selectAll().where { SLAPoliciesTable.priority eq priority }.singleOrNull()?.let {
            it[SLAPoliciesTable.responseTime] to it[SLAPoliciesTable.resolutionTime]
        } ?: (60 to 480)
}

internal fun isTicketSlaOverdue(remainingMinutes: Long, status: TicketStatus): Boolean =
    remainingMinutes < 0 && status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)

internal fun slaState(stoppedAt: LocalDateTime?, dueAt: LocalDateTime?, breachedAt: LocalDateTime?, now: LocalDateTime): String = when {
    dueAt == null -> "NOT_TRACKED"
    breachedAt != null -> "BREACHED"
    stoppedAt != null -> if (stoppedAt.isAfter(dueAt)) "BREACHED" else "MET"
    now.isAfter(dueAt) -> "BREACHED"
    else -> "ACTIVE"
}
