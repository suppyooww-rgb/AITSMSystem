package backend.repository

import backend.config.AiBridgeJobsTable
import backend.config.MonitoringCollectorsTable
import backend.models.ai.AIBridgeHeartbeatRequest
import backend.models.ai.AIBridgeStatusResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

data class AIBridgeJobRecord(
    val id: UUID,
    val model: String,
    val requestPayload: String,
    val status: String,
    val responsePayload: String?,
    val errorMessage: String?
)

class AIBridgeRepository(private val clock: Clock = Clock.systemUTC()) {
    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    fun setCollectorEnabled(id: UUID, enabled: Boolean): Boolean = transaction {
        MonitoringCollectorsTable.update({
            (MonitoringCollectorsTable.id eq id) and
                (MonitoringCollectorsTable.scope eq "LAN") and
                MonitoringCollectorsTable.revokedAt.isNull() and
                MonitoringCollectorsTable.archivedAt.isNull()
        }) {
            it[aiBridgeEnabled] = enabled
            if (!enabled) {
                it[aiBridgeAvailable] = false
                it[aiBridgeLastError] = null
            }
        } > 0
    }

    fun heartbeat(identity: CollectorIdentity, request: AIBridgeHeartbeatRequest): Boolean = transaction {
        if (identity.scope != "LAN") return@transaction false
        val timestamp = now()
        var enabled = false
        MonitoringCollectorsTable.selectAll().where { MonitoringCollectorsTable.id eq identity.id }.singleOrNull()?.let {
            enabled = it[MonitoringCollectorsTable.aiBridgeEnabled]
        }
        MonitoringCollectorsTable.update({ MonitoringCollectorsTable.id eq identity.id }) {
            it[aiBridgeLastSeenAt] = timestamp
            // A model-less heartbeat is the collector asking which model to probe. Preserve
            // the last verified state until the immediately following probe result arrives,
            // otherwise the Settings page can briefly and incorrectly show the bridge offline.
            if (!request.model.isNullOrBlank()) {
                it[aiBridgeAvailable] = enabled && request.available
                it[aiBridgeModel] = request.model.trim().take(150)
                it[aiBridgeLastError] = request.error?.replace(Regex("[\\r\\n]+"), " ")?.take(500)
            }
        }
        enabled
    }

    fun status(configuredModel: String): AIBridgeStatusResponse = transaction {
        val healthyAfter = now().minusSeconds(90)
        val rows = MonitoringCollectorsTable.selectAll().where {
            (MonitoringCollectorsTable.scope eq "LAN") and
                MonitoringCollectorsTable.revokedAt.isNull() and
                MonitoringCollectorsTable.archivedAt.isNull()
        }.toList()
        val enabled = rows.count { it[MonitoringCollectorsTable.aiBridgeEnabled] }
        val healthy = rows.count {
            it[MonitoringCollectorsTable.aiBridgeEnabled] && it[MonitoringCollectorsTable.aiBridgeAvailable] &&
                (it[MonitoringCollectorsTable.aiBridgeLastSeenAt]?.isAfter(healthyAfter) == true) &&
                it[MonitoringCollectorsTable.aiBridgeModel].equals(configuredModel, ignoreCase = true)
        }
        AIBridgeStatusResponse(
            available = healthy > 0,
            enabledCollectors = enabled,
            healthyCollectors = healthy,
            configuredModel = configuredModel,
            message = when {
                healthy > 0 -> "Local AI bridge is online and the configured model is available."
                enabled == 0 -> "Open the Actions (⋯) menu beside an online company/LAN collector and choose Enable AI Bridge."
                else -> "AI Bridge is enabled but Ollama or the configured model is not available."
            }
        )
    }

    fun createJob(model: String, requestPayload: String, timeoutMillis: Long): UUID? = transaction {
        expireJobs()
        val healthyAfter = now().minusSeconds(90)
        val hasHealthy = MonitoringCollectorsTable.selectAll().where {
            (MonitoringCollectorsTable.scope eq "LAN") and
                (MonitoringCollectorsTable.aiBridgeEnabled eq true) and
                (MonitoringCollectorsTable.aiBridgeAvailable eq true) and
                MonitoringCollectorsTable.revokedAt.isNull() and
                MonitoringCollectorsTable.archivedAt.isNull()
        }.any {
            it[MonitoringCollectorsTable.aiBridgeLastSeenAt]?.isAfter(healthyAfter) == true &&
                it[MonitoringCollectorsTable.aiBridgeModel].equals(model, ignoreCase = true)
        }
        if (!hasHealthy) return@transaction null
        val id = UUID.randomUUID()
        val created = now()
        AiBridgeJobsTable.insert {
            it[AiBridgeJobsTable.id] = id
            it[collectorId] = null
            it[status] = "PENDING"
            it[AiBridgeJobsTable.model] = model
            it[AiBridgeJobsTable.requestPayload] = requestPayload
            it[responsePayload] = null
            it[errorMessage] = null
            it[createdAt] = created
            it[claimedAt] = null
            it[completedAt] = null
            it[expiresAt] = created.plusNanos(timeoutMillis * 1_000_000)
        }
        id
    }

    fun claimNext(identity: CollectorIdentity): AIBridgeJobRecord? = transaction {
        if (identity.scope != "LAN") return@transaction null
        expireJobs()
        val collector = MonitoringCollectorsTable.selectAll().where { MonitoringCollectorsTable.id eq identity.id }.singleOrNull()
            ?: return@transaction null
        if (!collector[MonitoringCollectorsTable.aiBridgeEnabled] || !collector[MonitoringCollectorsTable.aiBridgeAvailable]) return@transaction null
        val model = collector[MonitoringCollectorsTable.aiBridgeModel] ?: return@transaction null
        val candidate = AiBridgeJobsTable.selectAll().where {
            (AiBridgeJobsTable.status eq "PENDING") and (AiBridgeJobsTable.model eq model)
        }.orderBy(AiBridgeJobsTable.createdAt, SortOrder.ASC).limit(1).singleOrNull() ?: return@transaction null
        val claimed = AiBridgeJobsTable.update({
            (AiBridgeJobsTable.id eq candidate[AiBridgeJobsTable.id]) and (AiBridgeJobsTable.status eq "PENDING")
        }) {
            it[collectorId] = identity.id
            it[status] = "CLAIMED"
            it[claimedAt] = now()
        }
        if (claimed != 1) return@transaction null
        candidate.toRecord("CLAIMED")
    }

    fun complete(identity: CollectorIdentity, jobId: UUID, success: Boolean, content: String?, error: String?): Boolean = transaction {
        val changed = AiBridgeJobsTable.update({
            (AiBridgeJobsTable.id eq jobId) and
                (AiBridgeJobsTable.collectorId eq identity.id) and
                (AiBridgeJobsTable.status eq "CLAIMED")
        }) {
            it[status] = if (success) "COMPLETED" else "FAILED"
            it[responsePayload] = content?.take(100_000)
            it[errorMessage] = error?.replace(Regex("[\\r\\n]+"), " ")?.take(500)
            it[completedAt] = now()
        }
        changed == 1
    }

    fun findJob(id: UUID): AIBridgeJobRecord? = transaction {
        expireJobs()
        AiBridgeJobsTable.selectAll().where { AiBridgeJobsTable.id eq id }.singleOrNull()?.toRecord()
    }

    fun expireJob(id: UUID) = transaction {
        AiBridgeJobsTable.update({
            (AiBridgeJobsTable.id eq id) and ((AiBridgeJobsTable.status eq "PENDING") or (AiBridgeJobsTable.status eq "CLAIMED"))
        }) { it[status] = "EXPIRED"; it[completedAt] = now() }
    }

    private fun org.jetbrains.exposed.sql.Transaction.expireJobs() {
        val retentionCutoff = now().minusMinutes(10)
        AiBridgeJobsTable.deleteWhere {
            AiBridgeJobsTable.completedAt.isNotNull() and (AiBridgeJobsTable.completedAt less retentionCutoff)
        }
        AiBridgeJobsTable.update({
            (AiBridgeJobsTable.expiresAt less now()) and
                ((AiBridgeJobsTable.status eq "PENDING") or (AiBridgeJobsTable.status eq "CLAIMED"))
        }) { it[status] = "EXPIRED"; it[completedAt] = now() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRecord(statusOverride: String? = null) = AIBridgeJobRecord(
        id = this[AiBridgeJobsTable.id], model = this[AiBridgeJobsTable.model],
        requestPayload = this[AiBridgeJobsTable.requestPayload], status = statusOverride ?: this[AiBridgeJobsTable.status],
        responsePayload = this[AiBridgeJobsTable.responsePayload], errorMessage = this[AiBridgeJobsTable.errorMessage]
    )
}
