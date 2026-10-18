package backend.services

import backend.models.Notification
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationContractTest {
    private val root = Path.of("src/main")

    @Test fun `notification response includes related employee and safe destination metadata`() {
        val encoded = Json.encodeToString(
            Notification(
                id = 9,
                userId = 2,
                title = "New colleague",
                message = "You were added by: Juan Dela Cruz",
                type = "colleague_added",
                relatedUserId = 7,
                relatedUserDisplayName = "Juan Dela Cruz",
                relatedUserProfileImageUrl = "/uploads/profile-photos/00000000-0000-0000-0000-000000000000.jpg",
                destination = "/profile?id=7",
                createdAt = "2026-10-11T09:49:20.450079"
            )
        )
        assertContains(encoded, "\"relatedUserId\":7")
        assertContains(encoded, "\"relatedUserDisplayName\":\"Juan Dela Cruz\"")
        assertContains(encoded, "\"relatedUserProfileImageUrl\"")
        assertContains(encoded, "\"destination\":\"/profile?id=7\"")
    }

    @Test fun `colleague events identify and link the responsible employee and remain idempotent`() {
        val source = Files.readString(root.resolve("kotlin/backend/services/ColleagueService.kt"))
        assertContains(source, "You were added by: \${actor.fullName}")
        assertContains(source, "\${actor.fullName} accepted your colleague request.")
        assertContains(source, "relatedUserId = actorId")
        assertContains(source, "eventKey = \"colleague:\${it.id}:added:\$recipientId\"")
        assertContains(source, "eventKey = \"colleague:\${result.id}:accepted:\${result.senderId}\"")
    }

    @Test fun `read operations are scoped to their owner and direct messages stay separate`() {
        val service = Files.readString(root.resolve("kotlin/backend/services/NotificationService.kt"))
        val routes = Files.readString(root.resolve("kotlin/backend/routes/NotificationRoutes.kt"))
        assertContains(service, "NotificationsTable.userId eq userId")
        assertContains(service, "NotificationsTable.type neq \"direct_message\"")
        assertContains(service, "NotificationsTable.isRead eq false")
        assertContains(routes, "service.belongsToUser(userId, id)")
        assertContains(routes, "HttpStatusCode.NotFound")
    }

    @Test fun `notification history is paginated and has environment specific retention`() {
        val routes = Files.readString(root.resolve("kotlin/backend/routes/NotificationRoutes.kt"))
        val client = Files.readString(root.resolve("resources/static/frontend/modules/common/js/notification-center.js"))
        val dashboard = Files.readString(root.resolve("resources/static/frontend/dashboard-admin/js/dashboard.js"))
        assertContains(routes, "pageSize")
        assertContains(routes, "service.page(userId, page, pageSize, status, type)")
        assertContains(client, "/api/notifications?page=1&pageSize=12")
        assertContains(client, "href = '/notifications'")
        assertContains(dashboard, "/api/notifications?page=1&pageSize=8")
        assertEquals(30L, notificationRetentionDays("beta", null))
        assertEquals(90L, notificationRetentionDays("production", null))
        assertEquals(45L, notificationRetentionDays("production", "45"))
    }

    @Test fun `ticket event deduplication remains recipient specific for multiple admins`() {
        val firstAdmin = ticketNotificationEventKey(14, "ticket_claimed", "claim-1", 2)
        val secondAdmin = ticketNotificationEventKey(14, "ticket_claimed", "claim-1", 3)
        assertFalse(firstAdmin == secondAdmin)
        assertContains(firstAdmin, ":2")
        assertContains(secondAdmin, ":3")
    }

    @Test fun `both shells use one accessible persistent notification client`() {
        val client = Files.readString(root.resolve("resources/static/frontend/modules/common/js/notification-center.js"))
        val application = Files.readString(root.resolve("kotlin/Application.kt"))
        val admin = Files.readString(root.resolve("resources/static/frontend/modules/sidebar/js/admin-shell.js"))
        val user = Files.readString(root.resolve("resources/static/frontend/modules/sidebar/js/user-shell.js"))
        assertContains(admin, "/modules/common/js/notification-center.js")
        assertContains(user, "/modules/common/js/notification-center.js")
        assertContains(client, "request('/api/notifications/read-all', { method: 'PATCH' })")
        assertContains(client, "global.aitsmFetchJson(url, options)")
        assertContains(client, "fetch('/api/auth/csrf-token'")
        assertContains(client, "'X-CSRF-Token': csrfToken")
        assertContains(client, "notification.relatedUserId")
        assertContains(client, "relatedUserProfileImageUrl")
        assertContains(client, "new Intl.DateTimeFormat")
        assertContains(client, "replaceChildren")
        assertFalse(client.contains("innerHTML"))
        assertTrue(client.contains("if (center.opening.has(notification.id)) return"))
        assertTrue(client.contains("if (center.markingAll) return"))
        assertContains(application, "allowMethod(HttpMethod.Patch)")
        assertContains(application, "allowMethod(HttpMethod.Put)")
        assertContains(application, "allowMethod(HttpMethod.Delete)")
    }

    @Test fun `legacy colleague notifications are safely backfilled`() {
        val migration = Files.readString(Path.of("db/migrations/V006__backfill_colleague_notification_actors.sql"))
        assertContains(migration, "n.related_user_id IS NULL")
        assertContains(migration, "ABS(EXTRACT(EPOCH FROM (n.created_at - cr.created_at))) <= 300")
        assertContains(migration, "'You were added by: ' || matched.actor_name")
        assertContains(migration, "WHERE match_rank = 1")
    }
}
