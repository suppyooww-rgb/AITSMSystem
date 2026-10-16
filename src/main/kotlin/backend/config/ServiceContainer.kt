package backend.config

import backend.repository.AIConversationRepository
import backend.repository.AuditRepository
import backend.repository.DeviceRepository
import backend.repository.ColleagueRepository
import backend.repository.KnowledgeRepository
import backend.repository.TicketRepository
import backend.repository.UserRepository
import backend.repository.InvitationRepository
import backend.repository.PasswordResetRepository
import backend.repository.MonitoringRepository
import backend.queries.SystemSettingsQueries
import backend.queries.InventoryQueries
import backend.services.AIService
import backend.services.AssetDetectionService
import backend.services.AuthService
import backend.services.EmailService
import backend.services.InventoryService
import backend.services.ColleagueService
import backend.services.KnowledgeService
import backend.services.MonitoringService
import backend.services.ProfilePhotoService
import backend.services.NotificationService
import backend.services.SLAService
import backend.services.SlaAutomationService
import backend.services.SessionService
import backend.services.InvitationService
import backend.services.MfaService
import backend.services.ReauthenticationService
import backend.services.TicketService
import backend.services.ai.AIChatService
import backend.services.ai.AIConfigService
import backend.services.ai.PendingLocalAIProvider
import backend.services.ai.TicketAiAdvisor
import io.ktor.server.application.Application

class ServiceContainer(application: Application) {
    val auditRepo = AuditRepository()
    val userRepo = UserRepository()
    val invitationRepo = InvitationRepository()
    val passwordResetRepo = PasswordResetRepository()
    val colleagueRepo = ColleagueRepository()
    val ticketRepo = TicketRepository()
    val systemSettingsQueries = SystemSettingsQueries()
    val inventoryQueries = InventoryQueries()
    val assetDetectionService = AssetDetectionService(systemSettingsQueries)
    val deviceRepo = DeviceRepository(assetDetectionService)
    val monitoringRepository = MonitoringRepository()
    val knowledgeRepo = KnowledgeRepository()
    val aiConversationRepository = AIConversationRepository()

    val notificationService = NotificationService()
    val colleagueService = ColleagueService(colleagueRepo, auditRepo, notificationService)
    val emailService = EmailService.fromEnvironment()
    val sessionService = SessionService()
    val mfaService = MfaService(auditRepo)
    val reauthenticationService = ReauthenticationService()
    val authService = AuthService(userRepo, auditRepo, notificationService, emailService, sessionService, passwordResetRepo, mfaService)
    val invitationService = InvitationService(invitationRepo, userRepo, auditRepo, emailService, notificationService)
    val profilePhotoService = ProfilePhotoService(userRepo, auditRepo, notificationService)
    val ticketService = TicketService(ticketRepo, auditRepo, notificationService, userRepo)
    val inventoryService = InventoryService(inventoryQueries, assetDetectionService)
    val monitoringService = MonitoringService(monitoringRepository, assetDetectionService, inventoryService)
    val aiService = AIService()
    val aiConfigService = AIConfigService(application.environment.config, systemSettingsQueries)
    private val aiProvider = PendingLocalAIProvider()
    val aiChatService = AIChatService(aiProvider, aiConfigService, aiConversationRepository)
    val ticketAiAdvisor = TicketAiAdvisor(ticketService, knowledgeRepo, aiProvider, aiConfigService)
    val slaService = SLAService(auditRepo)
    val slaAutomationService = SlaAutomationService(ticketRepo, userRepo, notificationService)
    val knowledgeService = KnowledgeService(knowledgeRepo, auditRepo)
}
