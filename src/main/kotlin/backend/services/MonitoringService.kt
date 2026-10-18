package backend.services

import backend.config.Env

import backend.models.CollectorDeviceReport
import backend.models.CollectorEnrollmentRequest
import backend.models.CollectorPackageRequest
import backend.models.CollectorRedeemResponse
import backend.models.CollectorReportRequest
import backend.models.CollectorReportResponse
import backend.models.DiscoveryRefreshResponse
import backend.models.InventoryUpsertDetectedRequest
import backend.models.MonitoringSummaryDto
import backend.models.normalizeCollectorMeasurement
import backend.repository.MonitoringRepository
import backend.security.RateLimiter
import backend.models.UserRole
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.util.UUID

class MonitoringService(
    private val repository: MonitoringRepository,
    private val assetDetectionService: AssetDetectionService,
    private val inventoryService: InventoryService,
    private val clock: Clock = Clock.systemUTC()
) {
    private val ingestionLimiter = RateLimiter(120, 60_000L, clock)

    fun enroll(request: CollectorEnrollmentRequest) = repository.enroll(
        request.copy(siteId = request.siteId.trim(), name = request.name.trim()).also {
            require(it.siteId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "Site id is invalid." }
            require(it.name.isNotBlank() && it.name.length <= 150) { "Collector name is required and must be 150 characters or fewer." }
        }
    )
    fun collectors() = repository.listCollectors()
    fun collectorsForUser(userId: Int) = repository.listCollectors(userId)
    fun collectorDiagnostics(rawId: String, ownerId: Int? = null): String? {
        val id = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return null
        val collector = (if (ownerId == null) collectors() else collectorsForUser(ownerId))
            .firstOrNull { it.collectorId == id.toString() } ?: return null
        val environment = Env.get("DEPLOYMENT_ENV")?.trim()?.lowercase() ?: "production"
        val baseUrl = Env.get("APP_BASE_URL")?.trim()?.removeSuffix("/").orEmpty()
        return buildString {
            appendLine("AITSM Collector Diagnostics")
            appendLine("Generated: ${clock.instant()}")
            appendLine("Environment: $environment")
            appendLine("Portal: $baseUrl")
            appendLine("Collector ID: ${collector.collectorId}")
            appendLine("Name: ${collector.name}")
            appendLine("Site: ${collector.siteId}")
            appendLine("Scope: ${collector.scope}")
            appendLine("State: ${collector.state}")
            appendLine("Reason: ${collector.statusReason.orEmpty()}")
            appendLine("Enrolled: ${collector.enrolledAt}")
            appendLine("Last report: ${collector.lastSeenAt ?: "Never"}")
            appendLine("Agent version: ${collector.agentVersion ?: "Not reported (legacy installer)"}")
            appendLine("Service started: ${collector.serviceStartedAt ?: "Not reported"}")
            appendLine("Consecutive failures: ${collector.consecutiveFailures}")
            appendLine("Last reported error: ${collector.lastReportedError ?: "None"}")
            appendLine()
            appendLine("Local Windows checks (run as Administrator):")
            appendLine("  sc.exe query AITSMCollector")
            appendLine("  Get-Content 'C:\\ProgramData\\AITSMCollector\\collector-status.json'")
            appendLine("  Get-Content 'C:\\ProgramData\\AITSMCollector\\collector.log' -Tail 100")
            appendLine("Run a newly downloaded installer on the same computer to repair the service and update the collector binary.")
            appendLine("This diagnostic intentionally contains no collector token or secret.")
        }
    }
    fun createPackage(userId: Int, role: UserRole, request: CollectorPackageRequest) = run {
        val requestedScope = request.scope.trim().uppercase()
        val admin = role in setOf(UserRole.ADMIN, UserRole.IT_ADMIN, UserRole.SUPERADMIN)
        val scope = if (admin) requestedScope else "SELF"
        require(scope in setOf("SELF", "LAN")) { "Collector scope must be SELF or LAN." }
        val site = request.siteId.trim().ifBlank { if (scope == "SELF") "self-device" else "company-lan" }
        val name = request.name.trim()
        require(site.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "Site id is invalid." }
        require(name.isNotBlank() && name.length <= 150) { "Collector name is required and must be 150 characters or fewer." }
        val repairCollectorId = request.repairCollectorId?.trim()?.takeIf { it.isNotBlank() }
        if (repairCollectorId != null) {
            require(canRepairCollector(repairCollectorId, userId, role, scope)) { "The selected collector is unavailable for repair." }
        }
        repository.createEnrollmentCode(userId, scope, site, name).copy(
            environment = Env.get("DEPLOYMENT_ENV")?.trim()?.lowercase() ?: "production",
            baseUrl = Env.get("APP_BASE_URL")?.trim()?.removeSuffix("/").orEmpty(),
            repairCollectorId = repairCollectorId
        )
    }
    fun redeemPackage(code: String): CollectorRedeemResponse? = repository.redeemEnrollmentCode(
        code.trim(), assetDetectionService.getConfiguredPrivateCidrs()
    )
    fun canDownloadPackage(code: String, userId: Int) = repository.enrollmentCodeBelongsTo(code.trim(), userId)
    fun canRepairCollector(rawId: String, userId: Int, role: UserRole, expectedScope: String? = null): Boolean {
        val id = runCatching { UUID.fromString(rawId) }.getOrNull()?.toString() ?: return false
        val admin = role in setOf(UserRole.ADMIN, UserRole.IT_ADMIN, UserRole.SUPERADMIN)
        val collector = (if (admin) collectors() else collectorsForUser(userId)).firstOrNull { it.collectorId == id }
            ?: return false
        return collector.state !in setOf("REVOKED", "ARCHIVED") &&
            (expectedScope == null || collector.scope == expectedScope)
    }
    fun revoke(rawId: String): Boolean = repository.revoke(UUID.fromString(rawId))
    fun archive(rawId: String): Boolean = repository.archive(UUID.fromString(rawId))
    fun revokeOwned(rawId: String, userId: Int): Boolean = repository.revokeOwned(UUID.fromString(rawId), userId)
    fun devices() = visibleCollectorDevices(deduplicatedLiveDevices(repository.devices()))
    fun lanDevices() = devices()
    fun lanPeerIps() = devices().map { it.ipAddress }.distinct().sorted()
    fun cpu() = devices().filter { it.telemetryAvailable }.map { mapOf("device" to it.hostname, "cpu" to (it.cpuUsagePercent?.toString() ?: "unavailable")) }
    fun alerts() = devices().filter { (it.cpuUsagePercent ?: 0.0) > 85 || (it.memoryUsagePercent ?: 0.0) > 90 }
        .map { mapOf("device" to it.hostname, "message" to "High resource usage reported by collector") }

    fun summary(): MonitoringSummaryDto {
        val devices = devices()
        val collectors = collectors().filter { it.state != "REVOKED" }
        val newest = collectors.maxByOrNull { it.lastSeenAt ?: it.enrolledAt }
        val discovery = repository.latestDiscovery()
        return MonitoringSummaryDto(
            totalDiscovered = devices.size, monitoredDevices = devices.count { it.reachable },
            telemetryAvailableDevices = devices.count { it.telemetryAvailable && !it.stale }, hostTelemetry = null,
            timestamp = devices.mapNotNull { it.receivedAt }.maxOrNull(), collectorState = newest?.state ?: "NOT_ENROLLED",
            collectorLastSeenAt = newest?.lastSeenAt, discoveryState = discovery.first,
            discoveryCompletedAt = discovery.second, serviceHealthCheckedAt = clock.instant().toString()
        )
    }

    fun requestDiscovery(): DiscoveryRefreshResponse {
        val (requestId, _) = repository.queueDiscovery()
        return DiscoveryRefreshResponse(requestId.toString(), "PENDING", "LAN scan was queued for the collector.")
    }
    fun nextCommand(rawToken: String): backend.models.DiscoveryCommand? {
        val collector = repository.authenticate(rawToken) ?: throw InvalidCollectorTokenException()
        if (collector.scope != "LAN") return null
        return repository.nextCommand(collector.id, assetDetectionService.getConfiguredPrivateCidrs())
    }
    fun ingest(rawToken: String, report: CollectorReportRequest): CollectorReportResponse {
        val collector = repository.authenticate(rawToken) ?: throw InvalidCollectorTokenException()
        if (!ingestionLimiter.allow(collector.id.toString())) throw CollectorRateLimitException()
        require(report.devices.size <= 1_000) { "A report may contain at most 1000 devices." }
        if (collector.scope == "SELF") require(report.devices.size == 1) { "Self-device collectors may report exactly one device." }
        val measuredAt = parseMeasurement(report.measuredAt)
        report.discoveryError?.let { require(it.length <= 500) { "Discovery error is too long." } }
        require((report.agentVersion?.length ?: 0) <= 40) { "Agent version is too long." }
        require((report.previousError?.length ?: 0) <= 500) { "Collector diagnostic error is too long." }
        require(report.consecutiveFailures in 0..100_000) { "Collector failure count is invalid." }
        report.devices.forEach { validateDevice(it, collector.scope) }
        val (receivedAt, count) = repository.saveReport(collector.id, report, measuredAt)
        report.devices.filter { it.telemetryAvailable && it.inventory != null }.forEach { device ->
            inventoryService.upsertDetectedAsset(device.toInventoryUpsert(), saveSnapshot = false)
        }
        return CollectorReportResponse(true, receivedAt.toString(), count)
    }

    private fun validateDevice(device: CollectorDeviceReport, scope: String) {
        require(device.deviceKey.isNotBlank() && device.deviceKey.length <= 200) { "Device key is invalid." }
        require(device.hostname.isNotBlank() && device.hostname.length <= 200) { "Hostname is invalid." }
        require(device.ipAddress.length <= 45 && isLiteralIp(device.ipAddress) &&
            (if (scope == "SELF") isPrivateIp(device.ipAddress) else assetDetectionService.matchesConfigured(device.ipAddress))) {
            "Device IP must be inside an authorized private range."
        }
        require((device.macAddress?.length ?: 0) <= 32 && (device.osDetails?.length ?: 0) <= 300) { "Device metadata is too long." }
        device.inventory?.let(::validateInventory)
        require(device.cpuUsagePercent == null || device.cpuUsagePercent in 0.0..100.0) { "CPU usage must be between 0 and 100." }
        require(device.memoryUsagePercent == null || device.memoryUsagePercent in 0.0..100.0) { "Memory usage must be between 0 and 100." }
        require((device.storageTotalBytes ?: 0) >= 0 && (device.storageUsedBytes ?: 0) >= 0) { "Storage values cannot be negative." }
        if (!device.telemetryAvailable) require(device.cpuUsagePercent == null && device.memoryUsagePercent == null && device.storageTotalBytes == null && device.storageUsedBytes == null && device.inventory == null) {
            "Uninstrumented peers cannot report detailed telemetry."
        }
        if (scope == "SELF") require(device.telemetryAvailable && device.inventory != null) {
            "Self-device collectors must report authenticated host inventory and telemetry."
        }
        device.measurementAt?.let(::parseMeasurement)
    }
    private fun validateInventory(inventory: backend.models.CollectorInventoryReport) {
        val fields = listOf(
            inventory.fullDeviceName to 255, inventory.manufacturer to 150, inventory.model to 180,
            inventory.processorName to 255, inventory.processorSpeed to 100, inventory.installedRam to 100,
            inventory.usableRam to 100, inventory.ramSpeed to 100, inventory.gpuName to 255,
            inventory.gpuMemory to 100, inventory.storageTotal to 100, inventory.storageUsed to 100,
            inventory.storageFree to 100, inventory.systemType to 180, inventory.osName to 180,
            inventory.osEdition to 180, inventory.osVersion to 120, inventory.osBuild to 120,
            inventory.installedOn to 120, inventory.domainOrWorkgroup to 160, inventory.deviceUuid to 160,
            inventory.productId to 160, inventory.penTouchSupport to 120
        )
        require(fields.all { (value, maxLength) -> (value?.length ?: 0) <= maxLength }) { "Inventory metadata is too long." }
        require((inventory.storageBreakdownJson?.length ?: 0) <= 8_000) { "Storage inventory is too long." }
    }
    private fun parseMeasurement(value: String): Instant {
        return normalizeCollectorMeasurement(value, clock.instant())
    }
    private fun isLiteralIp(value: String): Boolean = runCatching {
        val parsed = InetAddress.getByName(value)
        !value.any(Char::isLetter) && (parsed.hostAddress == value || value.contains(':'))
    }.getOrDefault(false)
    private fun isPrivateIp(value: String): Boolean = runCatching {
        val bytes = InetAddress.getByName(value).address
        bytes.size == 4 && ((bytes[0].toInt() and 0xff) == 10 ||
            ((bytes[0].toInt() and 0xff) == 172 && (bytes[1].toInt() and 0xff) in 16..31) ||
            ((bytes[0].toInt() and 0xff) == 192 && (bytes[1].toInt() and 0xff) == 168))
    }.getOrDefault(false)
}

internal fun preferredLiveDevicesByIp(devices: List<backend.models.LanDeviceDto>): Map<String, backend.models.LanDeviceDto> =
    deduplicatedLiveDevices(devices).associateBy { it.ipAddress }

internal fun deduplicatedLiveDevices(devices: List<backend.models.LanDeviceDto>): List<backend.models.LanDeviceDto> =
    devices.filter { it.telemetrySourceType != "HOST" }
        .groupBy { device ->
            normalizedDeviceIdentity(device.deviceUuid, device.macAddress) ?: device.identityKey ?: "ip:${device.ipAddress}"
        }
        .map { (_, readings) ->
            readings.firstOrNull { it.telemetryAvailable && !it.stale }
                ?: readings.firstOrNull { it.reachable && !it.stale }
                ?: readings.first()
        }

internal fun visibleCollectorDevices(devices: List<backend.models.LanDeviceDto>): List<backend.models.LanDeviceDto> =
    devices.filter { it.telemetryAvailable && it.telemetrySourceType == "COLLECTOR_AGENT" }

internal fun normalizedDeviceIdentity(deviceUuid: String?, macAddress: String?): String? =
    backend.repository.normalizedDeviceUuid(deviceUuid)?.let { "uuid:$it" }
        ?: backend.repository.normalizedMac(macAddress)?.let { "mac:$it" }

internal fun CollectorDeviceReport.toInventoryUpsert(): InventoryUpsertDetectedRequest {
    val details = inventory
    return InventoryUpsertDetectedRequest(
        deviceName = hostname,
        hostname = hostname,
        fullDeviceName = details?.fullDeviceName,
        manufacturer = details?.manufacturer,
        model = details?.model,
        processorName = details?.processorName,
        processorSpeed = details?.processorSpeed,
        installedRam = details?.installedRam,
        usableRam = details?.usableRam,
        ramSpeed = details?.ramSpeed,
        gpuName = details?.gpuName,
        gpuMemory = details?.gpuMemory,
        storageTotal = details?.storageTotal,
        storageUsed = details?.storageUsed,
        storageFree = details?.storageFree,
        storageBreakdownJson = details?.storageBreakdownJson,
        systemType = details?.systemType,
        osName = details?.osName ?: osDetails,
        osEdition = details?.osEdition,
        osVersion = details?.osVersion,
        osBuild = details?.osBuild,
        installedOn = details?.installedOn,
        domainOrWorkgroup = details?.domainOrWorkgroup,
        deviceUuid = details?.deviceUuid,
        productId = details?.productId,
        penTouchSupport = details?.penTouchSupport,
        ipAddress = ipAddress,
        macAddress = macAddress,
        connectionSource = if (inventory != null) "agent" else "lan-detected",
        status = if (reachable) "online" else "offline",
        lastSeenAt = measurementAt
    )
}

class CollectorRateLimitException : RuntimeException("Collector report rate limit exceeded.")
class InvalidCollectorTokenException : RuntimeException("Collector token is invalid or revoked.")
