package backend.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorInstallerContractTest {
    @Test fun `windows installer is delivered from a pinned verified release`() {
        val provider = Files.readString(Path.of("src/main/kotlin/backend/services/CollectorInstallerProvider.kt"))
        val route = Files.readString(Path.of("src/main/kotlin/backend/routes/MonitoringRoutes.kt"))
        val example = Files.readString(Path.of(".env.example"))
        val gitignore = Files.readString(Path.of(".gitignore"))
        assertContains(provider, "COLLECTOR_INSTALLER_URL")
        assertContains(provider, "COLLECTOR_INSTALLER_SHA256")
        assertContains(provider, "checksum verification failed")
        assertContains(route, "CollectorInstallerProvider.openStream()")
        assertContains(example, "releases/download/collector-v1.3.1/AITSMCollectorSetup.exe")
        assertContains(gitignore, "src/main/resources/collector/AITSMCollectorSetup.exe")
    }

    @Test fun `installer protects credentials and installs an automatic service`() {
        val source = Files.readString(Path.of("collector/windows/installer/Program.cs"))
        assertTrue(source.contains("CryptProtectData"))
        assertTrue(source.contains("private const int LocalMachine = 0x4"))
        assertTrue(source.contains("/inheritance:r"))
        assertTrue(source.contains("\"start=\", \"auto\""))
        assertTrue(source.contains("\"start=\", \"delayed-auto\""))
        assertTrue(source.contains("collector-status.json"))
        assertTrue(source.contains("consecutiveFailures"))
        assertTrue(source.contains("previousError"))
        assertTrue(source.contains("Switching this computer from"))
        assertTrue(source.contains("await Redeem(baseUrl, code)"))
        assertTrue(source.contains("existing.CollectorId.Equals(repairCollectorId"))
        assertTrue(source.contains("Registering this computer for the selected AITSM account"))
        assertTrue(source.contains("config.Scope == \"LAN\""))
        assertFalse(source.contains("Uninstall that collector before switching environments"))
        assertFalse(source.contains("Console.WriteLine(config.Token)"))
        assertFalse(source.contains("lastError, config.Token"))
    }

    @Test fun `self service enrollment remains owner and role scoped`() {
        val service = Files.readString(Path.of("src/main/kotlin/backend/services/MonitoringService.kt"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/MonitoringRoutes.kt"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/MonitoringRepository.kt"))
        assertTrue(service.contains("val scope = if (admin) requestedScope else \"SELF\""))
        assertTrue(service.contains("if (collector.scope != \"LAN\") return null"))
        assertTrue(service.contains("Self-device collectors may report exactly one device"))
        assertTrue(routes.contains("service.canDownloadPackage(request.enrollmentCode, actorId)"))
        assertTrue(routes.contains("service.canRepairCollector(request.repairCollectorId, actorId, call.userRole())"))
        assertTrue(routes.contains("service.revokeOwned(id, actorId)"))
        assertTrue(service.contains("repairCollectorId = repairCollectorId"))
        assertTrue(repository.contains("MonitoringCollectorsTable.scope eq \"LAN\""))
        assertTrue(repository.contains("No active company/LAN collector is enrolled."))
    }

    @Test fun `enrollment migration stores hashes and ownership without raw secrets`() {
        val migration = Files.readString(Path.of("db/migrations/V008__collector_self_service_enrollment.sql"))
        assertTrue(migration.contains("code_hash VARCHAR(64) NOT NULL UNIQUE"))
        assertTrue(migration.contains("requested_by INTEGER NOT NULL REFERENCES users(id)"))
        assertTrue(migration.contains("owner_user_id INTEGER REFERENCES users(id)"))
        assertFalse(migration.contains("raw_token"))
        assertFalse(migration.contains("enrollment_code VARCHAR"))
    }

    @Test fun `device identity migration is durable and unique`() {
        val migration = Files.readString(Path.of("db/migrations/V009__collector_diagnostics_and_device_identity.sql"))
        assertTrue(migration.contains("identity_key"))
        assertTrue(migration.contains("monitoring_devices_identity_unique_idx"))
        assertTrue(migration.contains("device_uuid"))
        assertTrue(migration.contains("agent_version"))
    }

    @Test fun `dynamic addresses are retained separately from canonical devices`() {
        val migration = Files.readString(Path.of("db/migrations/V011__canonical_devices_and_address_history.sql"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/MonitoringRepository.kt"))
        assertTrue(migration.contains("monitoring_device_addresses"))
        assertTrue(migration.contains("UNIQUE(device_id, ip_address)"))
        assertTrue(migration.contains("first_seen_at"))
        assertTrue(migration.contains("last_seen_at"))
        assertTrue(repository.contains("recordAddress"))
        assertTrue(repository.contains("collapseLegacyDeviceRows"))
        val adapterMigration = Files.readString(Path.of("db/migrations/V012__merge_stale_adapter_identity_into_uuid_device.sql"))
        assertTrue(adapterMigration.contains("COUNT(DISTINCT lower(device_uuid))"))
        assertTrue(adapterMigration.contains("monitoring_device_addresses"))
    }

    @Test fun `revoked collectors can be archived without deleting history`() {
        val migration = Files.readString(Path.of("db/migrations/V013__archive_revoked_collectors.sql"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/MonitoringRepository.kt"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/MonitoringRoutes.kt"))

        assertTrue(migration.contains("archived_at"))
        assertTrue(repository.contains("MonitoringCollectorsTable.revokedAt.isNotNull()"))
        assertTrue(repository.contains("MonitoringCollectorsTable.archivedAt.isNull()"))
        assertTrue(routes.contains("/collectors/{id}/archive"))
        assertFalse(repository.contains("MonitoringCollectorsTable.deleteWhere"))
    }

    @Test fun `local AI bridge is outbound authenticated LAN only and keeps ollama private`() {
        val source = Files.readString(Path.of("collector/windows/installer/Program.cs"))
        val routes = Files.readString(Path.of("src/main/kotlin/backend/routes/AIRoutes.kt"))
        val repository = Files.readString(Path.of("src/main/kotlin/backend/repository/AIBridgeRepository.kt"))
        val migration = Files.readString(Path.of("db/migrations/V014__secure_ollama_ai_bridge.sql"))

        assertTrue(source.contains("http://127.0.0.1:11434"))
        assertTrue(source.contains("AuthenticationHeaderValue(\"Bearer\", config.Token)"))
        assertTrue(source.contains("AiBridgeLoop.Run"))
        assertTrue(routes.contains("/api/ai/bridge/collector/jobs/next"))
        assertTrue(repository.contains("identity.scope != \"LAN\""))
        assertTrue(repository.contains("aiBridgeEnabled"))
        assertTrue(repository.contains("if (!request.model.isNullOrBlank())"))
        assertTrue(migration.contains("request_payload TEXT NOT NULL"))
        assertFalse(source.contains("0.0.0.0:11434"))
        assertFalse(source.contains("OLLAMA_HOST"))
    }
}
