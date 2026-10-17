package backend.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ProductionSchemaTest {
    @Test
    fun `production SQL covers every runtime table`() {
        val sql = buildString {
            appendLine(Files.readString(Path.of("db/schema.sql")))
            Files.list(Path.of("db/migrations")).use { migrations ->
                migrations.sorted().forEach { appendLine(Files.readString(it)) }
            }
        }.lowercase()

        val runtimeTables = setOf(
            "users", "invitations", "password_reset_tokens", "mfa_secrets", "mfa_recovery_codes",
            "reauth_grants", "auth_sessions", "security_events", "profile_photo_requests",
            "colleague_requests", "direct_messages", "eula_acceptance", "devices", "tickets",
            "ticket_history", "notifications", "sla_policies", "system_settings", "knowledge_articles",
            "audit_logs", "ai_conversation_messages", "inventory_assets", "inventory_asset_snapshots",
            "monitoring_collectors", "monitoring_devices", "monitoring_device_addresses", "monitoring_discovery_requests",
            "sla_risk_predictions", "ai_bridge_jobs"
        )

        runtimeTables.forEach { table ->
            assertTrue(
                Regex("create\\s+table\\s+if\\s+not\\s+exists\\s+$table\\b").containsMatchIn(sql),
                "Production SQL is missing table $table"
            )
        }
        assertTrue(
            Regex("alter\\s+table\\s+notifications[\\s\\S]*?related_user_id").containsMatchIn(sql) ||
                Regex("create\\s+table\\s+if\\s+not\\s+exists\\s+notifications[\\s\\S]*?related_user_id").containsMatchIn(sql),
            "Production SQL is missing notifications.related_user_id"
        )
        assertTrue(sql.contains("notifications_event_key_uidx"), "Production SQL is missing notification idempotency index")
        assertTrue(sql.contains("delete from notifications where type = 'direct_message'"), "Legacy direct-message bell rows are not removed")
    }
}
