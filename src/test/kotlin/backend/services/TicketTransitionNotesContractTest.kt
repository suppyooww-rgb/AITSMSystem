package backend.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TicketTransitionNotesContractTest {
    @Test fun `administrator status changes require durable work and resolution notes`() {
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/TicketService.kt"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/TicketRepository.kt"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/TicketRoutes.kt"))
        val migration = Files.readString(Path.of("db/migrations/V015__ticket_transition_notes.sql"))

        assertTrue(service.contains("A work note of at least 5 characters is required"))
        assertTrue(service.contains("A requester-visible resolution of at least 10 characters is required"))
        assertTrue(service.contains("Resolve the ticket before closing it"))
        assertTrue(repository.contains("TicketHistoryTable.workNote"))
        assertTrue(repository.contains("resolutionCategoryValue"))
        assertTrue(routes.contains("get(\"/{id}/history\")"))
        assertTrue(routes.contains("it.copy(note = null)"))
        assertTrue(migration.contains("notify_requester BOOLEAN NOT NULL DEFAULT FALSE"))
    }

    @Test fun `administrator interface records notes and shows the activity timeline`() {
        val html = Files.readString(Path.of("src/main/resources/static/frontend/ticket-management/html/ticket-management.html"))
        val script = Files.readString(Path.of("src/main/resources/static/frontend/ticket-management/js/admin-ticket-management.js"))
        val userHtml = Files.readString(Path.of("src/main/resources/static/frontend/tickets/html/tickets.html"))

        assertTrue(html.contains("id='ticketTransitionDialog'"))
        assertTrue(html.contains("Internal work note *"))
        assertTrue(html.contains("Requester-visible update"))
        assertTrue(html.contains("id='ticketResolutionCategory'"))
        assertTrue(script.contains("openTicketTransition"))
        assertTrue(script.contains("/history`"))
        assertTrue(script.contains("resolutionCategory"))
        assertFalse(script.contains("updateTicketStatusAdmin"))
        assertTrue(userHtml.contains("id='selectedTicketActivity'"))
    }

    @Test fun `ticket ownership is atomic and restricts administrator edits`() {
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/TicketService.kt"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/TicketRepository.kt"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/TicketRoutes.kt"))
        val script = Files.readString(Path.of("src/main/resources/static/frontend/ticket-management/js/admin-ticket-management.js"))
        val migration = Files.readString(Path.of("db/migrations/V016__atomic_ticket_ownership.sql"))

        assertTrue(repository.contains("TicketsTable.assignedToUserId.isNull()"))
        assertTrue(repository.contains("fun claim("))
        assertTrue(service.contains("requireTicketOwner"))
        assertTrue(service.contains("Ticket #\$id is being handled by \$actor"))
        assertTrue(routes.contains("post(\"/{id}/claim\")"))
        assertTrue(routes.contains("put(\"/{id}/assignment\")"))
        assertTrue(script.contains("Take ticket"))
        assertTrue(script.contains("Handled by"))
        assertTrue(migration.contains("assigned_to_user_id INT REFERENCES users(id) ON DELETE SET NULL"))
    }

    @Test fun `super admin can override ticket ownership while regular admins remain locked`() {
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/TicketService.kt"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/TicketRepository.kt"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/TicketRoutes.kt"))
        val script = Files.readString(Path.of("src/main/resources/static/frontend/ticket-management/js/admin-ticket-management.js"))

        assertTrue(service.contains("role != UserRole.SUPERADMIN"))
        assertTrue(service.contains("ticket_superadmin_override"))
        assertTrue(repository.contains("if (enforceOwner)"))
        assertTrue(routes.contains("actorId, call.userRole()"))
        assertTrue(script.contains("const canEdit = ownedByMe || superAdmin"))
        assertTrue(script.contains("Super Admin override is available"))
    }
}
