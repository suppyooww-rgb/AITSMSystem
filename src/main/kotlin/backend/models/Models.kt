package backend.models

import kotlinx.serialization.Serializable

@Serializable
enum class TicketStatus {
    OPEN,
    PENDING,
    RESOLVED,
    CLOSED
}

@Serializable
enum class UserRole {
    SUPERADMIN,
    ADMIN,
    IT_ADMIN,
    SUPPORT_AGENT,
    ASSET_MANAGER,
    AUDITOR,
    END_USER;

    fun asHeaderValue(): String = name.lowercase().replace('_', '-')

    companion object {
        fun from(value: String?): UserRole = when (value?.trim()?.lowercase()) {
            "superadmin" -> SUPERADMIN
            "admin" -> ADMIN
            "it_admin", "it-admin" -> IT_ADMIN
            "support_agent", "support-agent" -> SUPPORT_AGENT
            "asset_manager", "asset-manager" -> ASSET_MANAGER
            "auditor" -> AUDITOR
            else -> END_USER
        }
    }
}

@Serializable
enum class AccountState { INVITED, PENDING_VERIFICATION, PENDING_APPROVAL, ACTIVE, SUSPENDED, LOCKED, DISABLED }

@Serializable
data class PaginationMeta(
    val totalCount: Long,
    val pageSize: Int,
    val offset: Long,
    val currentPage: Int
)

@Serializable
data class PaginatedResponse<T>(
    val data: List<T>,
    val meta: PaginationMeta
)

@Serializable
data class User(
    val id: Int,
    val fullName: String,
    val email: String,
    val company: String,
    val department: String,
    val profilePhotoUrl: String? = null,
    val role: UserRole,
    val emailVerified: Boolean = false,
    val accountState: AccountState = AccountState.PENDING_VERIFICATION,
    val createdAt: String
)

@Serializable
enum class ProfilePhotoStatus { PENDING, APPROVED, REJECTED }

@Serializable
data class ProfilePhotoRequest(
    val id: Int,
    val userId: Int,
    val userName: String? = null,
    val userEmail: String? = null,
    val userRole: UserRole? = null,
    val photoUrl: String,
    val status: ProfilePhotoStatus,
    val submittedAt: String,
    val reviewedAt: String? = null,
    val reviewedBy: Int? = null
)

@Serializable data class ProfilePhotoReviewRequest(val approved: Boolean, val reason: String? = null)

@Serializable
data class RegisterRequest(
    val fullName: String,
    val email: String,
    val company: String = "",
    val department: String,
    val password: String,
    val confirmPassword: String,
    val eulaAccepted: Boolean,
    val eulaVersion: String = "1.0",
    val invitationToken: String = ""
)
@Serializable data class InvitationCreateRequest(val email: String, val role: UserRole = UserRole.END_USER)
@Serializable data class InvitationAcceptRequest(val token: String, val fullName: String, val password: String, val confirmPassword: String, val department: String, val eulaAccepted: Boolean, val eulaVersion: String = "1.0")
@Serializable data class InvitationView(val id: String, val email: String, val role: UserRole, val createdAt: String, val expiresAt: String, val acceptedAt: String? = null, val revokedAt: String? = null)
@Serializable data class InvitationInfo(val email: String, val role: UserRole)

@Serializable data class LoginRequest(val email: String, val password: String, val mfaCode: String? = null, val recoveryCode: String? = null)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class CompletePasswordResetRequest(val token: String, val newPassword: String, val confirmPassword: String)
@Serializable data class AuthResponse(val user: User, val mfaRequired: Boolean = false, val mfaEnrollmentRequired: Boolean = false)
@Serializable data class MfaConfirmRequest(val code: String)
@Serializable data class MfaLoginRequest(val code: String? = null, val recoveryCode: String? = null)
@Serializable data class ReauthRequest(val scope: String, val password: String? = null, val mfaCode: String? = null, val recoveryCode: String? = null)
@Serializable data class ReauthResponse(val token: String, val scope: String, val expiresAt: String)
@Serializable data class MfaEnrollmentResponse(val otpauthUri: String, val manualKey: String, val qrCodeDataUrl: String)
@Serializable data class MfaRecoveryResponse(val recoveryCodes: List<String>)
@Serializable data class SessionView(val id: String, val current: Boolean, val createdAt: String, val lastSeenAt: String, val expiresAt: String, val device: String?, val approximateIp: String?)
@Serializable data class RegistrationResponse(val message: String, val email: String, val devVerificationCode: String? = null)
@Serializable data class VerifyEmailRequest(val email: String, val code: String)
@Serializable data class ResendVerificationRequest(val email: String)
@Serializable data class RoleUpdateRequest(val role: UserRole)
@Serializable data class AccountStateUpdateRequest(val state: AccountState)

@Serializable data class ProfileUpdateRequest(val fullName: String, val company: String, val department: String)
@Serializable data class PasswordResetRequest(val newPassword: String, val confirmPassword: String)
@Serializable data class ChangeOwnPasswordRequest(val currentPassword: String, val newPassword: String, val confirmPassword: String)
@Serializable data class AssetIpPrefixesRequest(val prefixes: List<String>)
@Serializable data class AssetIpPrefixesResponse(val prefixes: List<String>, val message: String? = null)
@Serializable data class EmailApprovalRequest(val approved: Boolean)
@Serializable data class ExternalAccountApprovalRequest(val department: String, val role: UserRole)
@Serializable data class EmailVerificationUpdateRequest(val approved: Boolean)
@Serializable data class AdminEligibilityRequest(val targetEmail: String)
@Serializable data class AdminEligibilityResponse(val found: Boolean, val eligible: Boolean, val alreadyAdmin: Boolean, val targetUserId: Int? = null, val message: String)
@Serializable data class AdminSensitiveVerifyRequest(val password: String)
@Serializable data class AdminSensitiveVerifyResponse(
    val verified: Boolean,
    val verificationToken: String? = null,
    val reauthToken: String? = null,
    val expiresAt: String? = null,
    val message: String
)
@Serializable data class AdminGrantRequest(val targetEmail: String, val verificationToken: String)
@Serializable data class AdminGrantResponse(val success: Boolean, val user: User? = null, val message: String)
@Serializable data class UserActionResponse(val message: String, val user: User)
@Serializable enum class ColleagueRequestStatus { PENDING, ACCEPTED, DECLINED, CANCELLED }
@Serializable data class ColleagueRequest(val id: Int, val senderId: Int, val recipientId: Int, val status: ColleagueRequestStatus, val createdAt: String, val respondedAt: String? = null)
@Serializable data class Colleague(val id: Int, val fullName: String, val email: String, val department: String, val role: UserRole, val profilePhotoUrl: String? = null)
@Serializable data class ColleagueSearchResult(val colleague: Colleague, val relationship: ColleagueRequestStatus? = null, val requestedByCurrentUser: Boolean = false)
@Serializable data class DirectMessage(val id: Int, val senderId: Int, val recipientId: Int, val body: String, val sentAt: String, val readAt: String? = null)
@Serializable data class ConversationSummary(val colleague: Colleague, val lastMessage: DirectMessage? = null, val unreadCount: Long = 0)
@Serializable data class SendMessageRequest(val body: String)


@Serializable
data class Ticket(
    val id: Int,
    val userId: Int,
    val title: String,
    val description: String,
    val priority: String,
    val category: String,
    val status: TicketStatus,
    val assignedToUserId: Int? = null,
    val assignedTo: String? = null,
    val reviewedResolution: String? = null,
    val resolutionUpdatedBy: String? = null,
    val resolutionUpdatedAt: String? = null,
    val deviceId: Int? = null,
    val createdAt: String,
    val updatedAt: String,
    val slaResponseTargetMinutes: Int? = null,
    val slaResolutionTargetMinutes: Int? = null,
    val slaResponseDueAt: String? = null,
    val slaResolutionDueAt: String? = null,
    val slaFirstResponseAt: String? = null,
    val slaResolvedAt: String? = null,
    val slaResponseRemainingMinutes: Long? = null,
    val slaRemainingMinutes: Long? = null,
    val slaResponseState: String = "NOT_TRACKED",
    val slaResolutionState: String = "NOT_TRACKED",
    val slaReopenedCount: Int = 0,
    val overdue: Boolean = false,
    val slaRiskScore: Int = 0,
    val slaRiskLevel: String = "LOW",
    val slaRiskReasons: List<String> = emptyList()
)

@Serializable data class TicketRequest(val title: String, val description: String, val priority: String, val category: String, val deviceId: Int? = null)
@Serializable data class TicketStatusUpdate(
    val status: String,
    val note: String? = null,
    val userMessage: String? = null,
    val resolutionCategory: String? = null,
    val notifyRequester: Boolean = true
)
@Serializable data class TicketResolutionUpdate(val resolution: String)
@Serializable data class TicketAssignmentUpdate(val assigneeUserId: Int? = null, val note: String? = null)
@Serializable data class TicketHistoryEvent(
    val status: String,
    val previousStatus: String? = null,
    val note: String? = null,
    val userMessage: String? = null,
    val resolutionCategory: String? = null,
    val notifyRequester: Boolean = false,
    val updatedBy: String,
    val timestamp: String
)

@Serializable
data class Device(
    val id: Int,
    val deviceName: String,
    val ipAddress: String,
    val department: String,
    val assignedUser: String,
    val cpuUsage: Int?,
    val memoryUsage: Int?,
    val status: String,
    val lastSeen: String
)

@Serializable data class DeviceRequest(val deviceName: String, val ipAddress: String, val department: String, val assignedUser: String, val status: String)
@Serializable data class ClientMetricsRequest(val deviceName: String, val ipAddress: String, val department: String, val assignedUser: String, val cpuUsage: Int, val memoryUsage: Int, val status: String)

@Serializable
data class HostTelemetryDto(
    val cpuUsagePercent: Double,
    val memoryUsagePercent: Double,
    val totalMemoryBytes: Long,
    val usedMemoryBytes: Long,
    val hostname: String,
    val ipAddress: String,
    val timestamp: String
)

@Serializable data class CollectorEnrollmentRequest(val siteId: String, val name: String)
@Serializable data class CollectorPackageRequest(
    val siteId: String = "self-device",
    val name: String = "Windows device",
    val scope: String = "SELF",
    val repairCollectorId: String? = null
)
@Serializable data class CollectorPackageResponse(
    val enrollmentCode: String, val expiresAt: String, val scope: String,
    val environment: String = "production", val baseUrl: String = "",
    val repairCollectorId: String? = null
)
@Serializable data class CollectorInstallerDownloadRequest(val enrollmentCode: String, val repairCollectorId: String? = null)
@Serializable data class CollectorRedeemRequest(val enrollmentCode: String)
@Serializable data class CollectorRedeemResponse(
    val collectorId: String, val token: String, val scope: String, val privateCidrs: List<String>, val intervalSeconds: Int = 60
)
@Serializable data class CollectorEnrollmentResponse(
    val collectorId: String, val siteId: String, val name: String, val token: String, val enrolledAt: String
)
@Serializable data class CollectorView(
    val collectorId: String, val siteId: String, val name: String, val state: String,
    val enrolledAt: String, val lastSeenAt: String? = null, val revokedAt: String? = null,
    val archivedAt: String? = null,
    val scope: String = "LAN", val ownerUserId: Int? = null, val statusReason: String? = null,
    val agentVersion: String? = null, val serviceStartedAt: String? = null,
    val lastReportedError: String? = null, val consecutiveFailures: Int = 0,
    val aiBridgeEnabled: Boolean = false, val aiBridgeAvailable: Boolean = false,
    val aiBridgeLastSeenAt: String? = null, val aiBridgeModel: String? = null,
    val aiBridgeLastError: String? = null
)
@Serializable data class CollectorDeviceReport(
    val deviceKey: String, val hostname: String, val ipAddress: String, val macAddress: String? = null,
    val osDetails: String? = null, val reachable: Boolean = true, val telemetryAvailable: Boolean = false,
    val cpuUsagePercent: Double? = null, val memoryUsagePercent: Double? = null,
    val storageTotalBytes: Long? = null, val storageUsedBytes: Long? = null,
    val measurementAt: String? = null, val inventory: CollectorInventoryReport? = null
)
@Serializable data class CollectorInventoryReport(
    val fullDeviceName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val processorName: String? = null,
    val processorSpeed: String? = null,
    val installedRam: String? = null,
    val usableRam: String? = null,
    val ramSpeed: String? = null,
    val gpuName: String? = null,
    val gpuMemory: String? = null,
    val storageTotal: String? = null,
    val storageUsed: String? = null,
    val storageFree: String? = null,
    val storageBreakdownJson: String? = null,
    val systemType: String? = null,
    val osName: String? = null,
    val osEdition: String? = null,
    val osVersion: String? = null,
    val osBuild: String? = null,
    val installedOn: String? = null,
    val domainOrWorkgroup: String? = null,
    val deviceUuid: String? = null,
    val productId: String? = null,
    val penTouchSupport: String? = null
)
@Serializable data class CollectorReportRequest(
    val measuredAt: String, val devices: List<CollectorDeviceReport>,
    val discoveryRequestId: String? = null, val discoveryError: String? = null,
    val agentVersion: String? = null, val serviceStartedAt: String? = null,
    val previousError: String? = null, val consecutiveFailures: Int = 0
)
@Serializable data class CollectorReportResponse(val accepted: Boolean, val receivedAt: String, val devicesAccepted: Int)
@Serializable data class DiscoveryCommand(val requestId: String, val privateCidrs: List<String>, val requestedAt: String)
@Serializable data class DiscoveryRefreshResponse(val requestId: String, val status: String, val message: String)

@Serializable
 data class LanDeviceDto(
    val id: String,
    val hostname: String,
    val ipAddress: String,
    val reachable: Boolean,
    val telemetryAvailable: Boolean,
    val telemetrySourceType: String,
    val cpuUsagePercent: Double? = null,
    val memoryUsagePercent: Double? = null,
    val lastSeen: String,
    val measurementAt: String? = null,
    val receivedAt: String? = null,
    val stale: Boolean = false,
    val collectorId: String? = null,
    val macAddress: String? = null,
    val osDetails: String? = null,
    val storageTotalBytes: Long? = null,
    val storageUsedBytes: Long? = null,
    val identityKey: String? = null,
    val deviceUuid: String? = null,
    val observedIpAddresses: List<String> = emptyList()
)

@Serializable
 data class MonitoringSummaryDto(
    val totalDiscovered: Int,
    val monitoredDevices: Int,
    val telemetryAvailableDevices: Int,
    val hostTelemetry: HostTelemetryDto? = null,
    val timestamp: String? = null,
    val collectorState: String = "NOT_ENROLLED",
    val collectorLastSeenAt: String? = null,
    val discoveryState: String = "NEVER",
    val discoveryCompletedAt: String? = null,
    val serviceHealthCheckedAt: String
)

@Serializable
data class Notification(
    val id: Int,
    val userId: Int,
    val title: String,
    val message: String,
    val type: String,
    val relatedTicketId: Int? = null,
    val relatedUserId: Int? = null,
    val relatedUserDisplayName: String? = null,
    val relatedUserProfileImageUrl: String? = null,
    val destination: String? = null,
    val isRead: Boolean = false,
    val createdAt: String,
    val readAt: String? = null
)
@Serializable
data class NotificationPage(
    val items: List<Notification>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val totalPages: Int,
    val hasNext: Boolean
)
@Serializable data class SLA(val id: Int, val priority: String, val responseTime: Int, val resolutionTime: Int)
@Serializable data class SLAUpdateRequest(val responseTime: Int, val resolutionTime: Int)
@Serializable data class SLAAnalytics(
    val totalTickets: Int,
    val activeTickets: Int,
    val responseMeasured: Int,
    val responseMet: Int,
    val responseBreached: Int,
    val responseCompliancePercent: Double,
    val resolutionMeasured: Int,
    val resolutionMet: Int,
    val resolutionBreached: Int,
    val resolutionCompliancePercent: Double,
    val averageResponseMinutes: Double?,
    val averageResolutionMinutes: Double?,
    val activeHighRisk: Int,
    val activeBreaches: Int,
    val predictionSamples: Int,
    val highRiskPrecisionPercent: Double
)
@Serializable data class KnowledgeArticle(val id: Int, val title: String, val content: String, val category: String, val createdAt: String)
@Serializable data class KnowledgeRequest(val title: String, val content: String, val category: String)
@Serializable data class AuditLog(val id: Int, val userId: Int?, val action: String, val entity: String, val timestamp: String, val target: String? = null, val result: String = "SUCCESS", val requestId: String? = null, val sourceIp: String? = null, val userAgent: String? = null, val metadata: String? = null)

@Serializable data class TroubleshootingResponse(val suggestions: List<String>)
@Serializable data class AnalyticsResponse(val metric: String, val points: List<Map<String, String>>)
