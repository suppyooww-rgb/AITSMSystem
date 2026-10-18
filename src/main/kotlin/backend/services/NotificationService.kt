package backend.services

import backend.config.NotificationsTable
import backend.config.UsersTable
import backend.config.Env
import backend.models.Notification
import backend.models.NotificationPage
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import kotlin.math.ceil

class NotificationService {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun page(userId: Int, page: Int, pageSize: Int, status: String = "all", type: String? = null): NotificationPage = transaction {
        val safePage = page.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 100)
        val normalizedStatus = status.trim().lowercase().takeIf { it in setOf("all", "read", "unread") } ?: "all"
        val normalizedType = type?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        var condition: Op<Boolean> = (NotificationsTable.userId eq userId) and (NotificationsTable.type neq "direct_message")
        condition = when (normalizedStatus) {
            "read" -> condition and (NotificationsTable.isRead eq true)
            "unread" -> condition and (NotificationsTable.isRead eq false)
            else -> condition
        }
        if (normalizedType != null) condition = condition and (NotificationsTable.type eq normalizedType)

        val total = NotificationsTable.selectAll().where { condition }.count()
        val totalPages = if (total == 0L) 0 else ceil(total.toDouble() / safePageSize).toInt()
        val items = NotificationsTable.join(
            UsersTable,
            JoinType.LEFT,
            additionalConstraint = { NotificationsTable.relatedUserId eq UsersTable.id }
        ).selectAll()
            .where { condition }
            .orderBy(NotificationsTable.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(safePageSize, ((safePage - 1) * safePageSize).toLong())
            .map {
                Notification(
                    id = it[NotificationsTable.id],
                    userId = it[NotificationsTable.userId],
                    title = it[NotificationsTable.title],
                    message = it[NotificationsTable.message],
                    type = it[NotificationsTable.type],
                    relatedTicketId = it[NotificationsTable.relatedTicketId],
                    relatedUserId = it[NotificationsTable.relatedUserId],
                    relatedUserDisplayName = it.getOrNull(UsersTable.fullName),
                    relatedUserProfileImageUrl = it.getOrNull(UsersTable.profilePhotoUrl),
                    destination = destinationFor(
                        it[NotificationsTable.type],
                        it[NotificationsTable.relatedTicketId],
                        it[NotificationsTable.relatedUserId]
                    ),
                    isRead = it[NotificationsTable.isRead],
                    createdAt = it[NotificationsTable.createdAt].toString(),
                    readAt = it[NotificationsTable.readAt]?.toString()
                )
            }
        NotificationPage(items, safePage, safePageSize, total, totalPages, safePage < totalPages)
    }

    fun list(userId: Int, limit: Int = 50): List<Notification> = page(userId, 1, limit).items

    fun unreadCount(userId: Int): Long = transaction {
        NotificationsTable.selectAll()
            .where { (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false) and (NotificationsTable.type neq "direct_message") }
            .count()
    }

    fun markAsRead(userId: Int, notificationId: Int): Boolean = transaction {
        NotificationsTable.update({
                (NotificationsTable.id eq notificationId) and
                (NotificationsTable.userId eq userId) and
                (NotificationsTable.type neq "direct_message") and
                (NotificationsTable.isRead eq false)
        }) {
            it[isRead] = true
            it[readAt] = LocalDateTime.now()
        } > 0
    }

    fun markAllAsRead(userId: Int): Int = transaction {
        NotificationsTable.update({
            (NotificationsTable.userId eq userId) and
                (NotificationsTable.type neq "direct_message") and
                (NotificationsTable.isRead eq false)
        }) {
            it[isRead] = true
            it[readAt] = LocalDateTime.now()
        }
    }

    fun belongsToUser(userId: Int, notificationId: Int): Boolean = transaction {
        NotificationsTable.selectAll().where {
            (NotificationsTable.id eq notificationId) and
                (NotificationsTable.userId eq userId) and
                (NotificationsTable.type neq "direct_message")
        }.any()
    }

    fun push(
        userId: Int,
        title: String,
        message: String,
        type: String = "info",
        relatedTicketId: Int? = null,
        relatedUserId: Int? = null,
        eventKey: String? = null
    ) = transaction {
        NotificationsTable.insertIgnore {
            it[NotificationsTable.userId] = userId
            it[NotificationsTable.title] = title
            it[NotificationsTable.message] = message
            it[NotificationsTable.type] = type
            it[NotificationsTable.relatedTicketId] = relatedTicketId
            it[NotificationsTable.relatedUserId] = relatedUserId
            it[NotificationsTable.eventKey] = eventKey?.trim()?.takeIf(String::isNotBlank)?.take(180)
            it[NotificationsTable.isRead] = false
            it[NotificationsTable.createdAt] = LocalDateTime.now()
            it[NotificationsTable.readAt] = null
        }
    }

    fun pushOncePerTicketEvent(
        userId: Int,
        eventType: String,
        ticketId: Int,
        title: String,
        message: String,
        occurrenceKey: String = eventType
    ) = transaction {
        NotificationsTable.insertIgnore {
            it[NotificationsTable.userId] = userId
            it[NotificationsTable.title] = title
            it[NotificationsTable.message] = message
            it[NotificationsTable.type] = eventType
            it[NotificationsTable.relatedTicketId] = ticketId
            it[NotificationsTable.relatedUserId] = null
            it[NotificationsTable.eventKey] = ticketNotificationEventKey(ticketId, eventType, occurrenceKey, userId)
            it[NotificationsTable.isRead] = false
            it[NotificationsTable.createdAt] = LocalDateTime.now()
            it[NotificationsTable.readAt] = null
        }
    }

    fun purgeExpired(retentionDays: Long = notificationRetentionDays()): Int {
        val cutoff = LocalDateTime.now().minusDays(retentionDays.coerceIn(1, 3650))
        return transaction {
            NotificationsTable.deleteWhere {
                (NotificationsTable.type neq "direct_message") and (NotificationsTable.createdAt less cutoff)
            }
        }
    }

    fun startRetentionCleanup(application: Application) {
        cleanupScope.launch {
            while (isActive) {
                runCatching { purgeExpired() }
                    .onSuccess { removed -> if (removed > 0) application.environment.log.info("Purged {} expired notifications", removed) }
                    .onFailure { application.environment.log.error("Notification retention cleanup failed", it) }
                delay(24 * 60 * 60 * 1000L)
            }
        }
        application.monitor.subscribe(ApplicationStopping) { cleanupScope.cancel() }
    }


    private fun destinationFor(type: String, ticketId: Int?, relatedUserId: Int?): String? = when {
        type in setOf("colleague_added", "colleague_accepted", "colleague_request") && relatedUserId != null ->
            "/profile?id=$relatedUserId"
        type.startsWith("profile_photo_") -> "/profile#photoApprovalCard"
        type in setOf("account_approved", "account_rejected", "account_created", "role_changed", "account_state_changed") ->
            "/profile"
        type.startsWith("security_") -> "/security"
        type.startsWith("ticket_") && ticketId != null -> null // Portal-specific route is selected by the client.
        type.startsWith("knowledge_") -> null // Portal-specific route is selected by the client.
        else -> null
    }
}

internal fun notificationRetentionDays(
    deploymentEnvironment: String? = Env.get("DEPLOYMENT_ENV"),
    configuredDays: String? = Env.get("NOTIFICATION_RETENTION_DAYS")
): Long = configuredDays?.toLongOrNull()?.coerceIn(1, 3650)
    ?: if (deploymentEnvironment.equals("beta", ignoreCase = true)) 30L else 90L

internal fun ticketNotificationEventKey(ticketId: Int, eventType: String, occurrenceKey: String, userId: Int): String =
    "ticket:$ticketId:$eventType:$occurrenceKey:$userId".take(180)
