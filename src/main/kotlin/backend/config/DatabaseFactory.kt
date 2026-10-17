package backend.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction

object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val fullName = varchar("full_name", 150)
    val email = varchar("email", 200).uniqueIndex()
    val company = varchar("company", 150)
    val department = varchar("department", 120)
    val profilePhotoUrl = varchar("profile_photo_url", 500).nullable()
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 20).index()
    val emailVerified = bool("email_verified").default(false)
    val accountState = varchar("account_state", 32).default("PENDING_VERIFICATION").index()
    val verificationCode = varchar("verification_code", 64).nullable()
    val verificationExpiresAt = datetime("verification_expires_at").nullable()
    val verificationAttempts = integer("verification_attempts").default(0)
    val verificationSentAt = datetime("verification_sent_at").nullable()
    val failedLoginAttempts = integer("failed_login_attempts").default(0)
    val lockedUntil = datetime("locked_until").nullable()
    val createdAt = datetime("created_at").index()
    override val primaryKey = PrimaryKey(id)
}

object InvitationsTable : Table("invitations") {
    val id = uuid("id")
    val email = varchar("email", 200).index()
    val intendedRole = varchar("intended_role", 32)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val inviterUserId = integer("inviter_user_id").references(UsersTable.id)
    val createdAt = datetime("created_at").index()
    val expiresAt = datetime("expires_at").index()
    val acceptedAt = datetime("accepted_at").nullable()
    val revokedAt = datetime("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PasswordResetTokensTable : Table("password_reset_tokens") {
    val id = uuid("id")
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at").index()
    val usedAt = datetime("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object MfaSecretsTable : Table("mfa_secrets") {
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val encryptedSecret = text("encrypted_secret")
    val enabled = bool("enabled").default(false)
    val lastUsedStep = long("last_used_step").nullable()
    val createdAt = datetime("created_at")
    val confirmedAt = datetime("confirmed_at").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object MfaRecoveryCodesTable : Table("mfa_recovery_codes") {
    val id = uuid("id")
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val codeHash = varchar("code_hash", 100)
    val usedAt = datetime("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ReauthGrantsTable : Table("reauth_grants") {
    val id = uuid("id")
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val sessionId = uuid("session_id").index()
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val scope = varchar("scope", 80).index()
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at").index()
    val usedAt = datetime("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SessionsTable : Table("auth_sessions") {
    val id = uuid("id")
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val csrfHash = varchar("csrf_hash", 64)
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val createdAt = datetime("created_at").index()
    val lastSeenAt = datetime("last_seen_at").index()
    val expiresAt = datetime("expires_at").index()
    val revokedAt = datetime("revoked_at").nullable().index()
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = varchar("user_agent", 500).nullable()
    val deviceLabel = varchar("device_label", 120).nullable()
    val mfaComplete = bool("mfa_complete").default(false)
    override val primaryKey = PrimaryKey(id)
}

object SecurityEventsTable : Table("security_events") {
    val id = long("id").autoIncrement()
    val actorUserId = integer("actor_user_id").nullable().index()
    val target = varchar("target", 200).nullable().index()
    val action = varchar("action", 100).index()
    val result = varchar("result", 20).index()
    val requestId = varchar("request_id", 80).nullable().index()
    val sourceIp = varchar("source_ip", 45).nullable()
    val userAgent = varchar("user_agent", 300).nullable()
    val metadata = text("metadata").nullable()
    val createdAt = datetime("created_at").index()
    override val primaryKey = PrimaryKey(id)
}

object ProfilePhotoRequestsTable : Table("profile_photo_requests") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val photoUrl = varchar("photo_url", 500)
    val status = varchar("status", 20).index()
    val submittedAt = datetime("submitted_at").index()
    val reviewedAt = datetime("reviewed_at").nullable()
    val reviewedBy = integer("reviewed_by").references(UsersTable.id).nullable().index()
    override val primaryKey = PrimaryKey(id)
}

object ColleagueRequestsTable : Table("colleague_requests") {
    val id = integer("id").autoIncrement()
    val senderId = integer("sender_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val recipientId = integer("recipient_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val status = varchar("status", 20).index()
    val createdAt = datetime("created_at").index()
    val respondedAt = datetime("responded_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object DirectMessagesTable : Table("direct_messages") {
    val id = integer("id").autoIncrement()
    val senderId = integer("sender_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val recipientId = integer("recipient_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val body = varchar("body", 2000)
    val sentAt = datetime("sent_at").index()
    val readAt = datetime("read_at").nullable().index()
    override val primaryKey = PrimaryKey(id)
}

object EulaAcceptanceTable : Table("eula_acceptance") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val eulaVersion = varchar("eula_version", 20)
    val acceptedAt = datetime("accepted_at")
    override val primaryKey = PrimaryKey(id)
}

object DevicesTable : Table("devices") {
    val id = integer("id").autoIncrement()
    val deviceName = varchar("device_name", 150)
    val ipAddress = varchar("ip_address", 45)
    val department = varchar("department", 120)
    val assignedUser = varchar("assigned_user", 150)
    val cpuUsage = integer("cpu_usage")
    val memoryUsage = integer("memory_usage")
    val status = varchar("status", 50).index()
    val lastSeen = datetime("last_seen")
    override val primaryKey = PrimaryKey(id)
}

object MonitoringCollectorsTable : Table("monitoring_collectors") {
    val id = uuid("id")
    val siteId = varchar("site_id", 100).index()
    val name = varchar("name", 150)
    val scope = varchar("scope", 20).default("LAN")
    val ownerUserId = integer("owner_user_id").references(UsersTable.id, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val enrolledAt = datetime("enrolled_at")
    val lastSeenAt = datetime("last_seen_at").nullable().index()
    val revokedAt = datetime("revoked_at").nullable().index()
    val archivedAt = datetime("archived_at").nullable().index()
    val agentVersion = varchar("agent_version", 40).nullable()
    val serviceStartedAt = datetime("service_started_at").nullable()
    val lastError = varchar("last_error", 500).nullable()
    val consecutiveFailures = integer("consecutive_failures").default(0)
    val aiBridgeEnabled = bool("ai_bridge_enabled").default(false).index()
    val aiBridgeAvailable = bool("ai_bridge_available").default(false)
    val aiBridgeLastSeenAt = datetime("ai_bridge_last_seen_at").nullable().index()
    val aiBridgeModel = varchar("ai_bridge_model", 150).nullable()
    val aiBridgeLastError = varchar("ai_bridge_last_error", 500).nullable()
    override val primaryKey = PrimaryKey(id)
}

object AiBridgeJobsTable : Table("ai_bridge_jobs") {
    val id = uuid("id")
    val collectorId = uuid("collector_id").references(MonitoringCollectorsTable.id, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val status = varchar("status", 20).index()
    val model = varchar("model", 150)
    val requestPayload = text("request_payload")
    val responsePayload = text("response_payload").nullable()
    val errorMessage = varchar("error_message", 500).nullable()
    val createdAt = datetime("created_at").index()
    val claimedAt = datetime("claimed_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val expiresAt = datetime("expires_at").index()
    override val primaryKey = PrimaryKey(id)
}

object CollectorEnrollmentCodesTable : Table("collector_enrollment_codes") {
    val id = uuid("id")
    val codeHash = varchar("code_hash", 64).uniqueIndex()
    val requestedBy = integer("requested_by").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val scope = varchar("scope", 20)
    val siteId = varchar("site_id", 100)
    val name = varchar("name", 150)
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at").index()
    val redeemedAt = datetime("redeemed_at").nullable().index()
    val collectorId = uuid("collector_id").references(MonitoringCollectorsTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    override val primaryKey = PrimaryKey(id)
}

object MonitoringDevicesTable : Table("monitoring_devices") {
    val id = integer("id").autoIncrement()
    val collectorId = uuid("collector_id").references(MonitoringCollectorsTable.id, onDelete = ReferenceOption.CASCADE).index()
    val deviceKey = varchar("device_key", 200)
    val identityKey = varchar("identity_key", 220).nullable().index()
    val deviceUuid = varchar("device_uuid", 160).nullable().index()
    val hostname = varchar("hostname", 200)
    val ipAddress = varchar("ip_address", 45).index()
    val macAddress = varchar("mac_address", 32).nullable()
    val osDetails = varchar("os_details", 300).nullable()
    val reachable = bool("reachable")
    val telemetryAvailable = bool("telemetry_available")
    val cpuUsagePercent = decimal("cpu_usage_percent", 5, 2).nullable()
    val memoryUsagePercent = decimal("memory_usage_percent", 5, 2).nullable()
    val storageTotalBytes = long("storage_total_bytes").nullable()
    val storageUsedBytes = long("storage_used_bytes").nullable()
    val measurementAt = datetime("measurement_at").nullable()
    val receivedAt = datetime("received_at").index()
    val lastSeenAt = datetime("last_seen_at").index()
    init { uniqueIndex(collectorId, deviceKey) }
    override val primaryKey = PrimaryKey(id)
}

object MonitoringDeviceAddressesTable : Table("monitoring_device_addresses") {
    val id = long("id").autoIncrement()
    val deviceId = integer("device_id").references(MonitoringDevicesTable.id, onDelete = ReferenceOption.CASCADE).index()
    val collectorId = uuid("collector_id").references(MonitoringCollectorsTable.id, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val ipAddress = varchar("ip_address", 45)
    val macAddress = varchar("mac_address", 32).nullable()
    val firstSeenAt = datetime("first_seen_at")
    val lastSeenAt = datetime("last_seen_at").index()
    val observationCount = long("observation_count").default(1)
    init { uniqueIndex(deviceId, ipAddress) }
    override val primaryKey = PrimaryKey(id)
}

object DiscoveryRequestsTable : Table("monitoring_discovery_requests") {
    val id = uuid("id")
    val collectorId = uuid("collector_id").references(MonitoringCollectorsTable.id, onDelete = ReferenceOption.CASCADE).index()
    val status = varchar("status", 20).index()
    val requestedAt = datetime("requested_at")
    val completedAt = datetime("completed_at").nullable()
    val errorMessage = varchar("error_message", 500).nullable()
    override val primaryKey = PrimaryKey(id)
}

object TicketsTable : Table("tickets") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val title = varchar("title", 200)
    val description = text("description")
    val priority = varchar("priority", 20)
    val category = varchar("category", 80)
    val status = varchar("status", 30).index()
    val assignedToUserId = integer("assigned_to_user_id").references(UsersTable.id, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val assignedTo = varchar("assigned_to", 150).nullable()
    val reviewedResolution = text("reviewed_resolution").nullable()
    val resolutionUpdatedBy = varchar("resolution_updated_by", 150).nullable()
    val resolutionUpdatedAt = datetime("resolution_updated_at").nullable()
    val slaResponseTargetMinutes = integer("sla_response_target_minutes").nullable()
    val slaResolutionTargetMinutes = integer("sla_resolution_target_minutes").nullable()
    val slaResponseDueAt = datetime("sla_response_due_at").nullable().index()
    val slaResolutionDueAt = datetime("sla_resolution_due_at").nullable().index()
    val slaFirstResponseAt = datetime("sla_first_response_at").nullable()
    val slaResolvedAt = datetime("sla_resolved_at").nullable()
    val slaResponseWarningLevel = integer("sla_response_warning_level").default(0)
    val slaResolutionWarningLevel = integer("sla_resolution_warning_level").default(0)
    val slaResponseBreachedAt = datetime("sla_response_breached_at").nullable()
    val slaResolutionBreachedAt = datetime("sla_resolution_breached_at").nullable()
    val slaReopenedCount = integer("sla_reopened_count").default(0)
    val deviceId = integer("device_id").references(DevicesTable.id, onDelete = ReferenceOption.CASCADE).nullable().index()
    val createdAt = datetime("created_at").index()
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object TicketHistoryTable : Table("ticket_history") {
    val id = integer("id").autoIncrement()
    val ticketId = integer("ticket_id").references(TicketsTable.id, onDelete = ReferenceOption.CASCADE).index()
    val status = varchar("status", 30).index()
    val previousStatus = varchar("previous_status", 30).nullable()
    val workNote = text("work_note").nullable()
    val userMessage = text("user_message").nullable()
    val resolutionCategory = varchar("resolution_category", 80).nullable()
    val notifyRequester = bool("notify_requester").default(false)
    val updatedBy = varchar("updated_by", 150)
    val timestamp = datetime("timestamp").index()
    override val primaryKey = PrimaryKey(id)
}

object SlaRiskPredictionsTable : Table("sla_risk_predictions") {
    val id = long("id").autoIncrement()
    val ticketId = integer("ticket_id").references(TicketsTable.id, onDelete = ReferenceOption.CASCADE).index()
    val score = integer("score")
    val level = varchar("level", 20)
    val reasons = text("reasons")
    val createdAt = datetime("created_at").index()
    init { uniqueIndex(ticketId, level) }
    override val primaryKey = PrimaryKey(id)
}

object NotificationsTable : Table("notifications") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val title = varchar("title", 180).default("Notification")
    val message = text("message")
    val type = varchar("type", 40)
    val relatedTicketId = integer("related_ticket_id").nullable().index()
    val relatedUserId = integer("related_user_id").references(UsersTable.id, onDelete = ReferenceOption.SET_NULL).nullable().index()
    val eventKey = varchar("event_key", 180).nullable().uniqueIndex()
    val isRead = bool("is_read").default(false).index()
    val createdAt = datetime("created_at").index()
    val readAt = datetime("read_at").nullable().index()
    override val primaryKey = PrimaryKey(id)
}

object SLAPoliciesTable : Table("sla_policies") {
    val id = integer("id").autoIncrement()
    val priority = varchar("priority", 20)
    val responseTime = integer("response_time")
    val resolutionTime = integer("resolution_time")
    override val primaryKey = PrimaryKey(id)
}

object SystemSettingsTable : Table("system_settings") {
    val id = integer("id").autoIncrement()
    val key = varchar("key", 120).uniqueIndex()
    val value = text("value")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object KnowledgeArticlesTable : Table("knowledge_articles") {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 200)
    val content = text("content")
    val category = varchar("category", 100)
    val createdAt = datetime("created_at").index()
    override val primaryKey = PrimaryKey(id)
}

object AuditLogsTable : Table("audit_logs") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").nullable().index()
    val action = varchar("action", 160)
    val entity = varchar("entity", 80)
    val target = varchar("target", 200).nullable().index()
    val result = varchar("result", 20).default("SUCCESS").index()
    val requestId = varchar("request_id", 80).nullable().index()
    val sourceIp = varchar("source_ip", 45).nullable()
    val userAgent = varchar("user_agent", 300).nullable()
    val metadata = text("metadata").nullable()
    val timestamp = datetime("timestamp").index()
    override val primaryKey = PrimaryKey(id)
}

object AIConversationMessagesTable : Table("ai_conversation_messages") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id, onDelete = ReferenceOption.CASCADE).index()
    val sessionId = varchar("session_id", 128).index()
    val role = varchar("role", 32)
    val content = text("content")
    val createdAt = datetime("created_at").index()
    override val primaryKey = PrimaryKey(id)
}

object InventoryAssetsTable : Table("inventory_assets") {
    val id = integer("id").autoIncrement()
    val assetTag = varchar("asset_tag", 120).nullable().uniqueIndex()
    val hostname = varchar("hostname", 200).nullable().index()
    val fullDeviceName = varchar("full_device_name", 255).nullable()
    val manufacturer = varchar("manufacturer", 150).nullable()
    val model = varchar("model", 180).nullable()
    val processorName = varchar("processor_name", 255).nullable()
    val processorSpeed = varchar("processor_speed", 100).nullable()
    val installedRam = varchar("installed_ram", 100).nullable()
    val usableRam = varchar("usable_ram", 100).nullable()
    val ramSpeed = varchar("ram_speed", 100).nullable()
    val gpuName = varchar("gpu_name", 255).nullable()
    val gpuMemory = varchar("gpu_memory", 100).nullable()
    val storageTotal = varchar("storage_total", 100).nullable()
    val storageUsed = varchar("storage_used", 100).nullable()
    val storageFree = varchar("storage_free", 100).nullable()
    val storageBreakdownJson = text("storage_breakdown_json").nullable()
    val systemType = varchar("system_type", 180).nullable()
    val osName = varchar("os_name", 180).nullable()
    val osEdition = varchar("os_edition", 180).nullable()
    val osVersion = varchar("os_version", 120).nullable()
    val osBuild = varchar("os_build", 120).nullable()
    val installedOn = varchar("installed_on", 120).nullable()
    val domainOrWorkgroup = varchar("domain_or_workgroup", 160).nullable()
    val deviceUuid = varchar("device_uuid", 160).nullable().index()
    val productId = varchar("product_id", 160).nullable()
    val penTouchSupport = varchar("pen_touch_support", 120).nullable()
    val ipAddress = varchar("ip_address", 45).nullable().index()
    val macAddress = varchar("mac_address", 80).nullable()
    val connectionSource = varchar("connection_source", 80).nullable().index()
    val status = varchar("status", 50).index()
    val assignedDepartment = varchar("assigned_department", 150).nullable().index()
    val assignedUser = varchar("assigned_user", 180).nullable().index()
    val notes = text("notes").nullable()
    val lastSeenAt = datetime("last_seen_at").nullable().index()
    val createdAt = datetime("created_at").index()
    val updatedAt = datetime("updated_at").index()
    override val primaryKey = PrimaryKey(id)
}

object InventoryAssetSnapshotsTable : Table("inventory_asset_snapshots") {
    val id = integer("id").autoIncrement()
    val assetId = integer("asset_id").references(InventoryAssetsTable.id, onDelete = ReferenceOption.CASCADE).index()
    val snapshotSource = varchar("snapshot_source", 80)
    val payloadJson = text("payload_json")
    val createdAt = datetime("created_at").index()
    override val primaryKey = PrimaryKey(id)
}

object DatabaseFactory {
    fun init() {
        val production = Env.get("APP_ENV")?.equals("production", ignoreCase = true) == true
        fun databaseSetting(name: String, developmentDefault: String): String = Env.get(name)
            ?: if (!production) developmentDefault
            else error("$name is required in production. Set the PostgreSQL connection values before starting AITSM.")

        // Preserve the original local setup for existing developer installations. Production
        // still requires explicit environment settings and never falls back to credentials.
        val dbUrl = databaseSetting("DB_URL", "jdbc:postgresql://localhost:5432/aitsm_db")
        val dbUser = databaseSetting("DB_USER", "postgres")
        val dbPassword = databaseSetting("DB_PASSWORD", "root")

        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            // Web requests commonly touch the same session and notification rows concurrently.
            // READ_COMMITTED provides the required statement-level consistency without aborting
            // independent parallel page-load requests as serialization conflicts.
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        Database.connect(HikariDataSource(config))
        if (!production) transaction {
            exec("ALTER TABLE IF EXISTS devices DROP CONSTRAINT IF EXISTS devices_assigned_user_fkey")
            exec("ALTER TABLE IF EXISTS ticket_history DROP CONSTRAINT IF EXISTS ticket_history_updated_by_fkey")
            exec("""
                DO ${'$'}${'$'}
                BEGIN
                    IF to_regclass('public.ai_conversation_messages') IS NOT NULL THEN
                        ALTER TABLE ai_conversation_messages ADD COLUMN IF NOT EXISTS user_id INTEGER REFERENCES users(id) ON DELETE CASCADE;
                        DELETE FROM ai_conversation_messages WHERE user_id IS NULL;
                        ALTER TABLE ai_conversation_messages ALTER COLUMN user_id SET NOT NULL;
                    END IF;
                END
                ${'$'}${'$'};
            """.trimIndent())

            SchemaUtils.createMissingTablesAndColumns(
                UsersTable,
                SessionsTable,
                SecurityEventsTable,
                InvitationsTable,
                PasswordResetTokensTable,
                MfaSecretsTable,
                MfaRecoveryCodesTable,
                ReauthGrantsTable,
                ProfilePhotoRequestsTable,
                ColleagueRequestsTable,
                DirectMessagesTable,
                EulaAcceptanceTable,
                DevicesTable,
                MonitoringCollectorsTable,
                AiBridgeJobsTable,
                CollectorEnrollmentCodesTable,
                MonitoringDevicesTable,
                MonitoringDeviceAddressesTable,
                DiscoveryRequestsTable,
                TicketsTable,
                TicketHistoryTable,
                SlaRiskPredictionsTable,
                NotificationsTable,
                SLAPoliciesTable,
                SystemSettingsTable,
                KnowledgeArticlesTable,
                AuditLogsTable,
                AIConversationMessagesTable,
                InventoryAssetsTable,
                InventoryAssetSnapshotsTable
            )
            exec("""
                UPDATE monitoring_devices
                SET identity_key = CASE
                  WHEN mac_address IS NOT NULL AND length(regexp_replace(mac_address, '[^0-9A-Fa-f]', '', 'g')) = 12
                    THEN 'mac:' || upper(regexp_replace(mac_address, '[^0-9A-Fa-f]', '', 'g'))
                  ELSE 'legacy:' || id::text
                END
                WHERE identity_key IS NULL
            """.trimIndent())
            exec("""
                DELETE FROM monitoring_devices older
                USING monitoring_devices newer
                WHERE older.id <> newer.id
                  AND older.identity_key = newer.identity_key
                  AND (older.received_at < newer.received_at OR (older.received_at = newer.received_at AND older.id < newer.id))
            """.trimIndent())
            exec("CREATE UNIQUE INDEX IF NOT EXISTS monitoring_devices_identity_unique_idx ON monitoring_devices(identity_key) WHERE identity_key IS NOT NULL")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS profile_photo_requests_one_pending_per_user ON profile_photo_requests(user_id) WHERE status = 'PENDING'")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS colleague_requests_pair_idx ON colleague_requests (LEAST(sender_id, recipient_id), GREATEST(sender_id, recipient_id))")
            exec("CREATE INDEX IF NOT EXISTS ai_conversation_messages_owner_session_idx ON ai_conversation_messages(user_id, session_id, created_at)")
            exec("UPDATE users SET account_state = CASE WHEN email_verified THEN 'ACTIVE' ELSE 'PENDING_VERIFICATION' END WHERE account_state IS NULL OR account_state = ''")
            exec("UPDATE users SET account_state = 'ACTIVE' WHERE email_verified = TRUE AND account_state = 'PENDING_VERIFICATION'")
        }
    }
}
