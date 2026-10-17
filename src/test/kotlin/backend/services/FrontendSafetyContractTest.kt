package backend.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontendSafetyContractTest {
    @Test fun `ai interfaces share safe dom renderer`() {
        val renderer = Files.readString(Path.of("src/main/resources/static/frontend/modules/common/js/ai-renderer.js"))
        val standalone = Files.readString(Path.of("src/main/resources/static/frontend/ai-assistant/html/ai-assistant.html"))
        val createTicket = Files.readString(Path.of("src/main/resources/static/frontend/create-ticket/html/create-ticket.html"))
        assertTrue(renderer.contains("replaceChildren"))
        assertTrue(renderer.contains("noopener noreferrer"))
        assertFalse(renderer.contains("container.innerHTML"))
        assertTrue(standalone.contains("/modules/common/js/ai-renderer.js"))
        assertTrue(createTicket.contains("/modules/common/js/ai-renderer.js"))
        assertTrue(createTicket.contains("/ai-assistant/js/ai-assistant.js"))
        assertFalse(createTicket.contains("/create-ticket/js/ai-assistant.js"))
    }

    @Test fun `direct messages do not create or acknowledge bell notifications`() {
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/ColleagueService.kt"))
        val drawer = Files.readString(Path.of("src/main/resources/static/frontend/modules/common/js/chat-drawer.js"))
        assertFalse(service.contains("type = \"direct_message\""))
        assertFalse(drawer.contains("notifications/direct-messages"))
        assertTrue(service.contains("repository.markRead(actorId, colleagueId)"))
    }

    @Test fun `monitoring never labels cloud host metrics as lan telemetry`() {
        val monitoring = Files.readString(Path.of("src/main/kotlin/backend/services/MonitoringService.kt"))
        val frontend = Files.readString(Path.of("src/main/resources/static/frontend/monitoring/js/monitoring.js"))
        val page = Files.readString(Path.of("src/main/resources/static/frontend/monitoring/html/monitoring.html"))
        assertFalse(monitoring.contains("ManagementFactory"))
        assertFalse(monitoring.contains("discoverArpPeers"))
        assertTrue(frontend.contains("Last data received"))
        assertFalse(frontend.contains("Host CPU"))
        assertTrue(frontend.contains("waiting for LAN collector"))
        assertTrue(frontend.contains("['PENDING', 'IN_PROGRESS'].includes(discoveryState)"))
        assertTrue(page.contains("Scan LAN Now"))
        assertFalse(page.contains("Refresh Discovery"))
    }

    @Test fun `asset registration accepts normal IPv4 addresses in the browser`() {
        val frontend = Files.readString(Path.of("src/main/resources/static/frontend/assets/js/monitoring.js"))
        assertTrue(frontend.contains("/^(\\d{1,3}\\.){3}\\d{1,3}$/"))
        assertFalse(frontend.contains("/^(\\\\d{1,3}\\\\.){3}\\\\d{1,3}$/"))
    }

    @Test fun `end user ticket actions wait for session and always expose details`() {
        val frontend = Files.readString(Path.of("src/main/resources/static/frontend/tickets/js/tickets.js"))
        assertTrue(frontend.contains("await window.aitsmReady"))
        assertTrue(frontend.contains("View details"))
        assertTrue(frontend.contains("aria-label='Actions'>...</button>"))
        assertFalse(frontend.contains("if (!items.length) return '-'"))
    }

    @Test fun `account creation is presented only through verified invitations`() {
        val login = Files.readString(Path.of("src/main/resources/static/frontend/login/html/login.html"))
        val loginScript = Files.readString(Path.of("src/main/resources/static/frontend/login/js/auth.js"))
        val invitation = Files.readString(Path.of("src/main/resources/static/frontend/signup/html/signup.html"))
        val invitationScript = Files.readString(Path.of("src/main/resources/static/frontend/signup/js/auth.js"))
        assertFalse(login.contains("href='/signup'"))
        assertFalse(loginScript.contains("/api/auth/register"))
        assertTrue(invitation.contains("Accept your invitation"))
        assertTrue(invitation.contains("id='invitationAcceptanceForm' hidden"))
        assertTrue(invitationScript.contains("/api/auth/invitations/inspect"))
        assertTrue(invitationScript.contains("form.hidden = false"))
        assertFalse(invitationScript.contains("/api/auth/register"))
    }

    @Test fun `user administration masks passwords and never returns verified invites to pending`() {
        val frontend = Files.readString(Path.of("src/main/resources/static/frontend/user-management/js/user-management.js"))
        val page = Files.readString(Path.of("src/main/resources/static/frontend/user-management/html/user-management.html"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/AuthRoutes.kt"))

        assertTrue(frontend.contains("await requestReauthPassword()"))
        assertFalse(frontend.contains("prompt(`Verify your password"))
        assertFalse(frontend.contains("Mark email as pending"))
        assertTrue(page.contains("id='temporaryPassword' type='password'"))
        assertTrue(page.contains("id='temporaryPasswordConfirm' type='password'"))
        assertTrue(routes.contains("AdminSensitiveVerifyResponse("))
        assertTrue(routes.contains("Verified invitation accounts cannot be returned to pending status"))
    }

    @Test fun `ticket workspaces load every paginated ticket`() {
        val common = Files.readString(Path.of("src/main/resources/static/frontend/modules/common/js/app.js"))
        val pages = listOf(
            "src/main/resources/static/frontend/dashboard-admin/js/dashboard.js",
            "src/main/resources/static/frontend/dashboard-user/js/user-dashboard.js",
            "src/main/resources/static/frontend/ticket-management/js/admin-ticket-management.js",
            "src/main/resources/static/frontend/tickets/js/tickets.js",
            "src/main/resources/static/frontend/create-ticket/js/tickets.js"
        ).map { Files.readString(Path.of(it)) }

        assertTrue(common.contains("window.aitsmFetchAllPaginated = fetchAllPaginated"))
        assertTrue(common.contains("payload?.meta?.totalCount"))
        assertTrue(pages.all { it.contains("aitsmFetchAllPaginated('/api/tickets')") })
        assertTrue(pages.none { it.contains("/api/tickets?limit=500") })
    }

    @Test fun `new tickets notify every administrator portal role`() {
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/TicketService.kt"))
        assertTrue(service.contains("UserRole.ADMIN, UserRole.IT_ADMIN, UserRole.SUPERADMIN"))
    }

    @Test fun `admin operational feed refreshes live and limits the dashboard snapshot`() {
        val dashboard = Files.readString(Path.of("src/main/resources/static/frontend/dashboard-admin/js/dashboard.js"))
        val dashboardHtml = Files.readString(Path.of("src/main/resources/static/frontend/dashboard-admin/html/dashboard-admin.html"))
        val tickets = Files.readString(Path.of("src/main/resources/static/frontend/ticket-management/js/admin-ticket-management.js"))

        assertTrue(dashboard.contains("DASHBOARD_FEED_REFRESH_MS = 10 * 1000"))
        assertTrue(dashboard.contains("window.setInterval(refreshDashboardFeed, DASHBOARD_FEED_REFRESH_MS)"))
        assertTrue(dashboard.contains(".slice(0, 8)"))
        assertTrue(dashboardHtml.contains("id='adminOpsFeedStatus'"))
        assertTrue(dashboardHtml.contains("Older items remain available from the notification bell."))
        assertTrue(tickets.contains("View details"))
        assertTrue(tickets.contains("Legacy \${statusLabel(ticket.status)} ticket"))
    }

    @Test fun `ticket assistance is limited to active owned tickets with super admin override`() {
        val frontend = Files.readString(Path.of("src/main/resources/static/frontend/ticket-management/js/admin-ticket-management.js"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/AIRoutes.kt"))
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/TicketService.kt"))

        assertTrue(frontend.contains("const canGenerate = !isTerminal && canEdit"))
        assertTrue(frontend.contains("generateButton.classList.toggle('hidden', !canGenerate)"))
        assertTrue(routes.contains("ticket.status in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)"))
        assertTrue(routes.contains("actor.role != UserRole.SUPERADMIN && ticket.assignedToUserId != actor.id"))
        assertTrue(service.contains("existing.status !in setOf(TicketStatus.RESOLVED, TicketStatus.CLOSED)"))
    }

    @Test fun `device surfaces are collector only and inventory details are click through`() {
        val monitoring = Files.readString(Path.of("src/main/kotlin/backend/services/MonitoringService.kt"))
        val inventoryService = Files.readString(Path.of("src/main/kotlin/backend/services/InventoryService.kt"))
        val inventoryHtml = Files.readString(Path.of("src/main/resources/static/frontend/inventory/html/inventory.html"))
        val inventoryScript = Files.readString(Path.of("src/main/resources/static/frontend/inventory/js/inventory.js"))

        assertTrue(monitoring.contains("visibleCollectorDevices"))
        assertTrue(monitoring.contains("it.telemetryAvailable && it.inventory != null"))
        assertTrue(inventoryService.contains("filters.copy(connectionSource = \"agent\")"))
        assertTrue(inventoryScript.contains("class='inventory-device-row'"))
        assertTrue(inventoryScript.contains("openInventoryDetail"))
        assertTrue(inventoryScript.contains("formatDriveBreakdown(asset.storageBreakdownJson)"))
        assertTrue(inventoryScript.contains("aitsmEscapeHtml(name)"))
        assertTrue(inventoryHtml.contains("<th>Device</th><th>IP Address</th><th>Status</th><th>Last Seen</th><th>Department</th>"))
        assertFalse(inventoryHtml.contains("<th>Processor</th>"))
    }

    @Test fun `telemetry coverage treats higher values as healthy`() {
        val dashboard = Files.readString(Path.of("src/main/resources/static/frontend/dashboard-admin/js/dashboard.js"))
        assertTrue(dashboard.contains("label: 'Telemetry Coverage', value: telemetryCoverage, target: 'Above 70%', higherIsBetter: true"))
        assertTrue(dashboard.contains("metric.value >= 70 ? { tone: 'resolved', label: 'Stable' }"))
    }

    @Test fun `collector actions use a zoom friendly overflow menu`() {
        val settingsHtml = Files.readString(Path.of("src/main/resources/static/frontend/settings/html/settings.html"))
        val settingsScript = Files.readString(Path.of("src/main/resources/static/frontend/settings/js/settings.js"))
        val settingsStyles = Files.readString(Path.of("src/main/resources/static/frontend/settings/css/settings.css"))

        assertTrue(settingsHtml.contains("class='card collector-settings-card'"))
        assertTrue(settingsHtml.contains("class='collector-table'"))
        assertTrue(settingsScript.contains("collectorActionMenu(item, index)"))
        assertTrue(settingsScript.contains("aria-haspopup='menu'"))
        assertTrue(settingsScript.contains("archiveCollector"))
        assertTrue(settingsStyles.contains(".collector-table"))
        assertFalse(settingsStyles.contains("grid-column: 1 / -1"))
    }

    @Test fun `ollama bridge is explicit secure and only uses authenticated backend routes`() {
        val settingsHtml = Files.readString(Path.of("src/main/resources/static/frontend/settings/html/settings.html"))
        val settingsScript = Files.readString(Path.of("src/main/resources/static/frontend/settings/js/settings.js"))

        assertTrue(settingsHtml.contains("Local AI Bridge"))
        assertTrue(settingsHtml.contains("id='ollamaProviderHeading'>Ollama"))
        assertTrue(settingsHtml.contains("https://ollama.com/download/windows"))
        assertTrue(settingsHtml.contains("Checking bridge"))
        assertTrue(settingsHtml.contains("127.0.0.1:11434"))
        assertTrue(settingsHtml.contains("Never expose port 11434"))
        assertTrue(settingsHtml.contains("deterministic risk scoring"))
        assertFalse(settingsHtml.contains("Gemini"))
        assertTrue(settingsHtml.contains("aiTestBridgeBtn"))
        assertTrue(settingsScript.contains("ollama pull ${'$'}{model}"))
        assertTrue(settingsScript.contains("/api/ai/bridge/test"))
        assertTrue(settingsScript.contains("/api/ai/bridge/collectors/"))
        val aiRoutes = Files.readString(Path.of("src/main/kotlin/backend/routes/AIRoutes.kt"))
        assertTrue(aiRoutes.contains("AIBridgeTestResponse(true"))
        assertTrue(aiRoutes.contains("AIBridgeTestResponse(false"))
        assertFalse(aiRoutes.contains("mapOf(\"ok\" to true, \"message\""))
        assertFalse(settingsScript.contains("Gemini"))
        assertFalse(settingsScript.contains("fetch('http://127.0.0.1:11434"))
    }

    @Test fun `ai work does not block collector requests and fallback messages remain accurate`() {
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/AIRoutes.kt"))
        val chatService = Files.readString(Path.of("src/main/kotlin/backend/services/ai/AIChatService.kt"))

        assertTrue(routes.contains("withContext(Dispatchers.IO) { chatService.chat"))
        assertTrue(routes.contains("withContext(Dispatchers.IO) { bridgeService.testConnection() }"))
        assertTrue(chatService.contains("The company AI Bridge is online, but the local AI request did not finish in time"))
        assertFalse(chatService.contains("AI Bridge has not been installed"))
    }

    @Test fun `end user ticket creation uses the shared authenticated request client`() {
        val createTicket = Files.readString(Path.of("src/main/resources/static/frontend/create-ticket/js/tickets.js"))

        assertTrue(createTicket.contains("await window.aitsmReady"))
        assertTrue(createTicket.contains("window.aitsmFetchJson('/api/ai/ticket-insights'"))
        assertTrue(createTicket.contains("window.aitsmFetchJson('/api/tickets'"))
        assertFalse(createTicket.contains("headers: authHeaders()"))
    }
}
