package backend.services

import backend.config.ReauthGrantsTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

enum class ReauthScope(val wireValue: String) {
    ADMIN_ACCESS("admin-access"), MFA_CHANGE("mfa-change"), SECURITY_CONFIG("security-config"),
    SENSITIVE_EXPORT("sensitive-export"), ACCOUNT_DELETE("account-delete"), INVENTORY_DELETE("inventory-delete"), RECOVERY_CODES("recovery-codes");
    companion object { fun parse(value: String): ReauthScope? = entries.firstOrNull { it.wireValue == value } }
}

data class IssuedReauth(val token: String, val scope: ReauthScope, val expiresAt: LocalDateTime)

class ReauthenticationService {
    private val random = SecureRandom()
    fun issue(sessionId: UUID, userId: Int, scope: ReauthScope): IssuedReauth {
        val raw = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val now = LocalDateTime.now(); val expires = now.plusMinutes(5)
        transaction {
            ReauthGrantsTable.update({
                (ReauthGrantsTable.sessionId eq sessionId) and (ReauthGrantsTable.scope eq scope.wireValue) and ReauthGrantsTable.usedAt.isNull()
            }) { it[usedAt] = now }
            ReauthGrantsTable.insert {
                it[id] = UUID.randomUUID(); it[tokenHash] = SessionService.sha256(raw); it[ReauthGrantsTable.sessionId] = sessionId
                it[ReauthGrantsTable.userId] = userId; it[ReauthGrantsTable.scope] = scope.wireValue
                it[createdAt] = now; it[expiresAt] = expires; it[usedAt] = null
            }
        }
        return IssuedReauth(raw, scope, expires)
    }

    fun consume(sessionId: UUID, userId: Int, scope: ReauthScope, raw: String?, singleUse: Boolean = true): Boolean {
        if (raw.isNullOrBlank()) return false
        return transaction {
            val now = LocalDateTime.now()
            val row = ReauthGrantsTable.selectAll().where { ReauthGrantsTable.tokenHash eq SessionService.sha256(raw) }.singleOrNull()
                ?: return@transaction false
            if (row[ReauthGrantsTable.sessionId] != sessionId || row[ReauthGrantsTable.userId] != userId ||
                row[ReauthGrantsTable.scope] != scope.wireValue || row[ReauthGrantsTable.usedAt] != null || !row[ReauthGrantsTable.expiresAt].isAfter(now)) return@transaction false
            if (singleUse) ReauthGrantsTable.update({ ReauthGrantsTable.id eq row[ReauthGrantsTable.id] }) { it[usedAt] = now } == 1 else true
        }
    }
}
