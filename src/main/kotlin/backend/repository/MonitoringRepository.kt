package backend.repository

import backend.config.DiscoveryRequestsTable
import backend.config.CollectorEnrollmentCodesTable
import backend.config.MonitoringCollectorsTable
import backend.config.MonitoringDeviceAddressesTable
import backend.config.MonitoringDevicesTable
import backend.models.CollectorDeviceReport
import backend.models.CollectorEnrollmentRequest
import backend.models.CollectorEnrollmentResponse
import backend.models.CollectorPackageResponse
import backend.models.CollectorRedeemResponse
import backend.models.CollectorReportRequest
import backend.models.CollectorView
import backend.models.DiscoveryCommand
import backend.models.LanDeviceDto
import backend.models.normalizeCollectorMeasurement
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

object CollectorToken {
    private val random = SecureRandom()
    fun issue(id: UUID): String {
        val secret = ByteArray(32).also(random::nextBytes)
        return "$id.${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"
    }
    fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun collectorId(raw: String): UUID? = runCatching { UUID.fromString(raw.substringBefore('.')) }.getOrNull()
    fun matches(raw: String, expectedHash: String): Boolean = MessageDigest.isEqual(
        hash(raw).toByteArray(Charsets.US_ASCII), expectedHash.toByteArray(Charsets.US_ASCII)
    )
}

data class CollectorIdentity(val id: UUID, val scope: String, val ownerUserId: Int?)

class MonitoringRepository(private val clock: Clock = Clock.systemUTC()) {
    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    private fun iso(value: LocalDateTime?): String? = value?.toInstant(ZoneOffset.UTC)?.toString()

    fun enroll(request: CollectorEnrollmentRequest): CollectorEnrollmentResponse = transaction {
        val id = UUID.randomUUID()
        val token = CollectorToken.issue(id)
        val enrolledAt = now()
        MonitoringCollectorsTable.insert {
            it[MonitoringCollectorsTable.id] = id
            it[siteId] = request.siteId
            it[name] = request.name
            it[scope] = "LAN"
            it[ownerUserId] = null
            it[tokenHash] = CollectorToken.hash(token)
            it[MonitoringCollectorsTable.enrolledAt] = enrolledAt
            it[lastSeenAt] = null
            it[revokedAt] = null
        }
        CollectorEnrollmentResponse(id.toString(), request.siteId, request.name, token, iso(enrolledAt)!!)
    }

    fun createEnrollmentCode(userId: Int, scopeValue: String, site: String, collectorName: String): CollectorPackageResponse = transaction {
        val id = UUID.randomUUID()
        val code = CollectorToken.issue(id)
        val created = now()
        val expires = created.plusMinutes(10)
        CollectorEnrollmentCodesTable.insert {
            it[CollectorEnrollmentCodesTable.id] = id
            it[codeHash] = CollectorToken.hash(code)
            it[requestedBy] = userId
            it[scope] = scopeValue
            it[siteId] = site
            it[name] = collectorName
            it[createdAt] = created
            it[expiresAt] = expires
            it[redeemedAt] = null
            it[collectorId] = null
        }
        CollectorPackageResponse(code, iso(expires)!!, scopeValue)
    }

    fun redeemEnrollmentCode(rawCode: String, privateCidrs: List<String>): CollectorRedeemResponse? = transaction {
        val codeId = CollectorToken.collectorId(rawCode) ?: return@transaction null
        val row = CollectorEnrollmentCodesTable.selectAll().where { CollectorEnrollmentCodesTable.id eq codeId }.singleOrNull()
            ?: return@transaction null
        if (row[CollectorEnrollmentCodesTable.redeemedAt] != null || row[CollectorEnrollmentCodesTable.expiresAt].isBefore(now()) ||
            !CollectorToken.matches(rawCode, row[CollectorEnrollmentCodesTable.codeHash])) return@transaction null

        val collectorId = UUID.randomUUID()
        val token = CollectorToken.issue(collectorId)
        val redeemed = now()
        val claimed = CollectorEnrollmentCodesTable.update({
            (CollectorEnrollmentCodesTable.id eq codeId) and CollectorEnrollmentCodesTable.redeemedAt.isNull()
        }) {
            it[redeemedAt] = redeemed
        }
        if (claimed != 1) return@transaction null
        MonitoringCollectorsTable.insert {
            it[id] = collectorId
            it[siteId] = row[CollectorEnrollmentCodesTable.siteId]
            it[name] = row[CollectorEnrollmentCodesTable.name]
            it[scope] = row[CollectorEnrollmentCodesTable.scope]
            it[ownerUserId] = row[CollectorEnrollmentCodesTable.requestedBy]
            it[tokenHash] = CollectorToken.hash(token)
            it[enrolledAt] = redeemed
            it[lastSeenAt] = null
            it[revokedAt] = null
        }
        CollectorEnrollmentCodesTable.update({ CollectorEnrollmentCodesTable.id eq codeId }) {
            it[CollectorEnrollmentCodesTable.collectorId] = collectorId
        }
        CollectorRedeemResponse(collectorId.toString(), token, row[CollectorEnrollmentCodesTable.scope], privateCidrs)
    }

    fun enrollmentCodeBelongsTo(rawCode: String, userId: Int): Boolean = transaction {
        val codeId = CollectorToken.collectorId(rawCode) ?: return@transaction false
        val row = CollectorEnrollmentCodesTable.selectAll().where {
            (CollectorEnrollmentCodesTable.id eq codeId) and (CollectorEnrollmentCodesTable.requestedBy eq userId)
        }.singleOrNull() ?: return@transaction false
        row[CollectorEnrollmentCodesTable.redeemedAt] == null && row[CollectorEnrollmentCodesTable.expiresAt].isAfter(now()) &&
            CollectorToken.matches(rawCode, row[CollectorEnrollmentCodesTable.codeHash])
    }

    fun authenticate(rawToken: String): CollectorIdentity? = transaction {
        val id = CollectorToken.collectorId(rawToken) ?: return@transaction null
        val row = MonitoringCollectorsTable.selectAll().where { MonitoringCollectorsTable.id eq id }.singleOrNull()
            ?: return@transaction null
        if (row[MonitoringCollectorsTable.revokedAt] != null || row[MonitoringCollectorsTable.archivedAt] != null ||
            !CollectorToken.matches(rawToken, row[MonitoringCollectorsTable.tokenHash])) null
        else CollectorIdentity(id, row[MonitoringCollectorsTable.scope], row[MonitoringCollectorsTable.ownerUserId])
    }

    fun revoke(id: UUID): Boolean = transaction {
        MonitoringCollectorsTable.update({ (MonitoringCollectorsTable.id eq id) and MonitoringCollectorsTable.revokedAt.isNull() }) {
            it[revokedAt] = now()
        } > 0
    }

    fun archive(id: UUID): Boolean = transaction {
        MonitoringCollectorsTable.update({
            (MonitoringCollectorsTable.id eq id) and MonitoringCollectorsTable.revokedAt.isNotNull() and
                MonitoringCollectorsTable.archivedAt.isNull()
        }) { it[archivedAt] = now() } > 0
    }

    fun listCollectors(ownerId: Int? = null, includeArchived: Boolean = false): List<CollectorView> = transaction {
        val instantNow = clock.instant()
        val query = MonitoringCollectorsTable.selectAll().let { base ->
            when {
                ownerId != null && !includeArchived -> base.where {
                    (MonitoringCollectorsTable.ownerUserId eq ownerId) and MonitoringCollectorsTable.archivedAt.isNull()
                }
                ownerId != null -> base.where { MonitoringCollectorsTable.ownerUserId eq ownerId }
                !includeArchived -> base.where { MonitoringCollectorsTable.archivedAt.isNull() }
                else -> base
            }
        }
        query.orderBy(MonitoringCollectorsTable.enrolledAt, SortOrder.DESC).map { row ->
            val lastSeen = row[MonitoringCollectorsTable.lastSeenAt]
            val state = when {
                row[MonitoringCollectorsTable.archivedAt] != null -> "ARCHIVED"
                row[MonitoringCollectorsTable.revokedAt] != null -> "REVOKED"
                lastSeen == null -> "NEVER_CONNECTED"
                lastSeen.toInstant(ZoneOffset.UTC).isAfter(instantNow.minusSeconds(120)) -> "ONLINE"
                lastSeen.toInstant(ZoneOffset.UTC).isAfter(instantNow.minusSeconds(600)) -> "STALE"
                else -> "OFFLINE"
            }
            val statusReason = when (state) {
                "ARCHIVED" -> "Archived from the active collector list. Historical records are retained."
                "REVOKED" -> "Credential revoked. This collector can no longer submit telemetry."
                "NEVER_CONNECTED" -> "Enrollment completed, but no telemetry report has been received."
                "ONLINE" -> "Telemetry received within the last two minutes."
                "STALE" -> "No telemetry received for more than two minutes."
                else -> "No telemetry received for more than ten minutes. Check the Windows service, network access, and embedded environment address."
            }
            CollectorView(row[MonitoringCollectorsTable.id].toString(), row[MonitoringCollectorsTable.siteId], row[MonitoringCollectorsTable.name], state,
                iso(row[MonitoringCollectorsTable.enrolledAt])!!, iso(lastSeen), iso(row[MonitoringCollectorsTable.revokedAt]),
                iso(row[MonitoringCollectorsTable.archivedAt]),
                row[MonitoringCollectorsTable.scope], row[MonitoringCollectorsTable.ownerUserId], statusReason,
                row[MonitoringCollectorsTable.agentVersion], iso(row[MonitoringCollectorsTable.serviceStartedAt]),
                row[MonitoringCollectorsTable.lastError], row[MonitoringCollectorsTable.consecutiveFailures],
                row[MonitoringCollectorsTable.aiBridgeEnabled], row[MonitoringCollectorsTable.aiBridgeAvailable],
                iso(row[MonitoringCollectorsTable.aiBridgeLastSeenAt]), row[MonitoringCollectorsTable.aiBridgeModel],
                row[MonitoringCollectorsTable.aiBridgeLastError])
        }
    }

    fun revokeOwned(id: UUID, ownerId: Int): Boolean = transaction {
        MonitoringCollectorsTable.update({
            (MonitoringCollectorsTable.id eq id) and (MonitoringCollectorsTable.ownerUserId eq ownerId) and MonitoringCollectorsTable.revokedAt.isNull()
        }) { it[revokedAt] = now() } > 0
    }

    fun saveReport(collectorId: UUID, report: CollectorReportRequest, measurement: Instant): Pair<Instant, Int> = transaction {
        val received = now()
        MonitoringCollectorsTable.update({ (MonitoringCollectorsTable.id eq collectorId) and MonitoringCollectorsTable.revokedAt.isNull() }) {
            it[lastSeenAt] = received
            it[agentVersion] = report.agentVersion?.take(40)
            it[serviceStartedAt] = parseAgentTimestamp(report.serviceStartedAt)
            it[lastError] = report.previousError?.replace(Regex("[\\r\\n]+"), " ")?.take(500)
            it[consecutiveFailures] = report.consecutiveFailures.coerceIn(0, 100_000)
        }
        report.devices.forEach { device -> upsertDevice(collectorId, device, measurement, received) }
        report.discoveryRequestId?.let { rawId ->
            val requestId = runCatching { UUID.fromString(rawId) }.getOrNull()
            if (requestId != null) {
                DiscoveryRequestsTable.update({ (DiscoveryRequestsTable.id eq requestId) and (DiscoveryRequestsTable.collectorId eq collectorId) }) {
                    it[status] = if (report.discoveryError.isNullOrBlank()) "COMPLETED" else "FAILED"
                    it[completedAt] = received
                    it[errorMessage] = report.discoveryError?.take(500)
                }
            }
        }
        received.toInstant(ZoneOffset.UTC) to report.devices.size
    }

    private fun org.jetbrains.exposed.sql.Transaction.upsertDevice(collectorId: UUID, device: CollectorDeviceReport, fallbackMeasurement: Instant, received: LocalDateTime) {
        val uuid = normalizedDeviceUuid(device.inventory?.deviceUuid)
        val mac = normalizedMac(device.macAddress)
        val collectorKey = "collector:$collectorId:${device.deviceKey.lowercase()}"
        val preferredIdentity = uuid?.let { "uuid:$it" } ?: mac?.let { "mac:$it" } ?: collectorKey
        val identityCandidates = listOfNotNull(uuid?.let { "uuid:$it" }, mac?.let { "mac:$it" }, collectorKey).distinct()
        val identityMatches = MonitoringDevicesTable.selectAll().where {
            (MonitoringDevicesTable.identityKey inList identityCandidates) or
                ((MonitoringDevicesTable.collectorId eq collectorId) and (MonitoringDevicesTable.deviceKey eq device.deviceKey))
        }.orderBy(MonitoringDevicesTable.receivedAt, SortOrder.DESC).toList()
        val hostnameUuids = if (uuid != null && device.hostname.isNotBlank()) {
            MonitoringDevicesTable.selectAll().where { MonitoringDevicesTable.hostname eq device.hostname }
                .mapNotNull { normalizedDeviceUuid(it[MonitoringDevicesTable.deviceUuid]) }
                .distinct()
        } else emptyList()
        val hostnameCanBelongToIncomingUuid = uuid != null &&
            (hostnameUuids.isEmpty() || hostnameUuids.singleOrNull() == uuid)
        val legacyHostnameMatches = if ((uuid != null || mac != null) && device.telemetryAvailable && device.hostname.isNotBlank()) {
            MonitoringDevicesTable.selectAll().where { MonitoringDevicesTable.hostname eq device.hostname }
                .filter { row ->
                    row[MonitoringDevicesTable.telemetryAvailable] &&
                        row[MonitoringDevicesTable.deviceUuid] == null &&
                        if (uuid != null) hostnameCanBelongToIncomingUuid && row[MonitoringDevicesTable.lastSeenAt].isBefore(received.minusMinutes(10))
                        else row[MonitoringDevicesTable.identityKey]?.startsWith("legacy:") == true
                }
        } else emptyList()
        val matches = (identityMatches + legacyHostnameMatches)
            .distinctBy { it[MonitoringDevicesTable.id] }
            .sortedByDescending { it[MonitoringDevicesTable.receivedAt] }
        val existing = matches.firstOrNull { it[MonitoringDevicesTable.identityKey] == preferredIdentity } ?: matches.firstOrNull()
        if (existing != null) {
            val canonicalId = existing[MonitoringDevicesTable.id]
            matches.filterNot { it[MonitoringDevicesTable.id] == canonicalId }.forEach { duplicate ->
                recordAddress(
                    deviceId = canonicalId,
                    collectorId = duplicate[MonitoringDevicesTable.collectorId],
                    ipAddress = duplicate[MonitoringDevicesTable.ipAddress],
                    macAddress = duplicate[MonitoringDevicesTable.macAddress],
                    firstSeenAt = duplicate[MonitoringDevicesTable.receivedAt],
                    lastSeenAt = duplicate[MonitoringDevicesTable.lastSeenAt]
                )
                val duplicateId = duplicate[MonitoringDevicesTable.id]
                MonitoringDevicesTable.deleteWhere { id eq duplicateId }
            }
        }
        val measured = runCatching {
            normalizeCollectorMeasurement(device.measurementAt ?: fallbackMeasurement.toString(), fallbackMeasurement)
        }.getOrDefault(fallbackMeasurement)
        val values: MonitoringDevicesTable.(org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) -> Unit = { statement ->
            statement[MonitoringDevicesTable.collectorId] = collectorId
            statement[MonitoringDevicesTable.deviceKey] = device.deviceKey
            statement[identityKey] = preferredIdentity
            statement[deviceUuid] = uuid
            statement[hostname] = device.hostname
            statement[ipAddress] = device.ipAddress
            statement[macAddress] = device.macAddress
            statement[osDetails] = device.osDetails
            statement[reachable] = device.reachable
            statement[telemetryAvailable] = device.telemetryAvailable
            statement[cpuUsagePercent] = device.cpuUsagePercent?.toBigDecimal()
            statement[memoryUsagePercent] = device.memoryUsagePercent?.toBigDecimal()
            statement[storageTotalBytes] = device.storageTotalBytes
            statement[storageUsedBytes] = device.storageUsedBytes
            statement[measurementAt] = LocalDateTime.ofInstant(measured, ZoneOffset.UTC)
            statement[receivedAt] = received
            statement[lastSeenAt] = received
        }
        val canonicalId = if (existing == null) {
            MonitoringDevicesTable.insert {
                values(MonitoringDevicesTable, it)
            }[MonitoringDevicesTable.id]
        } else {
            existing[MonitoringDevicesTable.id].also { existingId ->
                MonitoringDevicesTable.update({ MonitoringDevicesTable.id eq existingId }) { values(MonitoringDevicesTable, it) }
            }
        }
        recordAddress(canonicalId, collectorId, device.ipAddress, device.macAddress, received, received)
    }

    private fun org.jetbrains.exposed.sql.Transaction.recordAddress(
        deviceId: Int,
        collectorId: UUID,
        ipAddress: String,
        macAddress: String?,
        firstSeenAt: LocalDateTime,
        lastSeenAt: LocalDateTime
    ) {
        val existing = MonitoringDeviceAddressesTable.selectAll().where {
            (MonitoringDeviceAddressesTable.deviceId eq deviceId) and
                (MonitoringDeviceAddressesTable.ipAddress eq ipAddress)
        }.singleOrNull()
        if (existing == null) {
            MonitoringDeviceAddressesTable.insert {
                it[MonitoringDeviceAddressesTable.deviceId] = deviceId
                it[MonitoringDeviceAddressesTable.collectorId] = collectorId
                it[MonitoringDeviceAddressesTable.ipAddress] = ipAddress
                it[MonitoringDeviceAddressesTable.macAddress] = macAddress
                it[MonitoringDeviceAddressesTable.firstSeenAt] = firstSeenAt
                it[MonitoringDeviceAddressesTable.lastSeenAt] = lastSeenAt
                it[observationCount] = 1
            }
        } else {
            MonitoringDeviceAddressesTable.update({ MonitoringDeviceAddressesTable.id eq existing[MonitoringDeviceAddressesTable.id] }) {
                it[MonitoringDeviceAddressesTable.collectorId] = collectorId
                it[MonitoringDeviceAddressesTable.macAddress] = macAddress ?: existing[MonitoringDeviceAddressesTable.macAddress]
                it[MonitoringDeviceAddressesTable.firstSeenAt] = minOf(existing[MonitoringDeviceAddressesTable.firstSeenAt], firstSeenAt)
                it[MonitoringDeviceAddressesTable.lastSeenAt] = maxOf(existing[MonitoringDeviceAddressesTable.lastSeenAt], lastSeenAt)
                it[observationCount] = existing[MonitoringDeviceAddressesTable.observationCount] + 1
            }
        }
    }

    fun devices(): List<LanDeviceDto> = transaction {
        val staleBefore = clock.instant().minusSeconds(600)
        val observedAddresses = MonitoringDeviceAddressesTable.selectAll()
            .orderBy(MonitoringDeviceAddressesTable.lastSeenAt, SortOrder.DESC)
            .groupBy { it[MonitoringDeviceAddressesTable.deviceId] }
            .mapValues { (_, rows) -> rows.map { it[MonitoringDeviceAddressesTable.ipAddress] }.distinct() }
        val devices = MonitoringDevicesTable.selectAll().orderBy(MonitoringDevicesTable.receivedAt, SortOrder.DESC).map { row ->
            val received = row[MonitoringDevicesTable.receivedAt]
            val stale = received.toInstant(ZoneOffset.UTC).isBefore(staleBefore)
            LanDeviceDto(
                id = "collector-device-${row[MonitoringDevicesTable.id]}", hostname = row[MonitoringDevicesTable.hostname],
                ipAddress = row[MonitoringDevicesTable.ipAddress], reachable = row[MonitoringDevicesTable.reachable] && !stale,
                telemetryAvailable = row[MonitoringDevicesTable.telemetryAvailable],
                telemetrySourceType = if (row[MonitoringDevicesTable.telemetryAvailable]) "COLLECTOR_AGENT" else "LAN_OBSERVED",
                cpuUsagePercent = row[MonitoringDevicesTable.cpuUsagePercent]?.toDouble(), memoryUsagePercent = row[MonitoringDevicesTable.memoryUsagePercent]?.toDouble(),
                lastSeen = iso(row[MonitoringDevicesTable.lastSeenAt])!!, measurementAt = iso(row[MonitoringDevicesTable.measurementAt]),
                receivedAt = iso(received), stale = stale, collectorId = row[MonitoringDevicesTable.collectorId].toString(),
                macAddress = row[MonitoringDevicesTable.macAddress], osDetails = row[MonitoringDevicesTable.osDetails],
                storageTotalBytes = row[MonitoringDevicesTable.storageTotalBytes], storageUsedBytes = row[MonitoringDevicesTable.storageUsedBytes],
                identityKey = row[MonitoringDevicesTable.identityKey], deviceUuid = row[MonitoringDevicesTable.deviceUuid],
                observedIpAddresses = observedAddresses[row[MonitoringDevicesTable.id]].orEmpty()
            )
        }
        collapseLegacyDeviceRows(devices)
    }

    fun queueDiscovery(): Pair<UUID, UUID> = transaction {
        val collector = MonitoringCollectorsTable.selectAll().where {
            MonitoringCollectorsTable.revokedAt.isNull() and
                MonitoringCollectorsTable.archivedAt.isNull() and
                (MonitoringCollectorsTable.scope eq "LAN")
        }
            .orderBy(MonitoringCollectorsTable.lastSeenAt, SortOrder.DESC_NULLS_LAST).limit(1).singleOrNull()
            ?: error("No active company/LAN collector is enrolled.")
        val requestId = UUID.randomUUID()
        DiscoveryRequestsTable.insert {
            it[id] = requestId
            it[collectorId] = collector[MonitoringCollectorsTable.id]
            it[status] = "PENDING"
            it[requestedAt] = now()
            it[completedAt] = null
            it[errorMessage] = null
        }
        requestId to collector[MonitoringCollectorsTable.id]
    }

    fun nextCommand(collectorId: UUID, cidrs: List<String>): DiscoveryCommand? = transaction {
        MonitoringCollectorsTable.update({ MonitoringCollectorsTable.id eq collectorId }) { it[lastSeenAt] = now() }
        val row = DiscoveryRequestsTable.selectAll().where {
            (DiscoveryRequestsTable.collectorId eq collectorId) and (DiscoveryRequestsTable.status eq "PENDING")
        }.orderBy(DiscoveryRequestsTable.requestedAt).limit(1).singleOrNull() ?: return@transaction null
        DiscoveryRequestsTable.update({ DiscoveryRequestsTable.id eq row[DiscoveryRequestsTable.id] }) { it[status] = "IN_PROGRESS" }
        DiscoveryCommand(row[DiscoveryRequestsTable.id].toString(), cidrs, iso(row[DiscoveryRequestsTable.requestedAt])!!)
    }

    fun latestDiscovery(): Triple<String, String?, String?> = transaction {
        val row = DiscoveryRequestsTable.selectAll().orderBy(DiscoveryRequestsTable.requestedAt, SortOrder.DESC).limit(1).singleOrNull()
            ?: return@transaction Triple("NEVER", null, null)
        Triple(row[DiscoveryRequestsTable.status], iso(row[DiscoveryRequestsTable.completedAt]), row[DiscoveryRequestsTable.errorMessage])
    }

    private fun parseAgentTimestamp(value: String?): LocalDateTime? = value?.let { raw ->
        runCatching { Instant.parse(raw) }.getOrNull()?.takeIf { instant ->
            val current = clock.instant()
            !instant.isAfter(current.plusSeconds(300)) && !instant.isBefore(current.minusSeconds(31_536_000))
        }?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
    }
}

internal fun normalizedDeviceUuid(value: String?): String? = value?.trim()?.lowercase()
    ?.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }
    ?.takeUnless { it == "00000000-0000-0000-0000-000000000000" || it == "ffffffff-ffff-ffff-ffff-ffffffffffff" }

internal fun normalizedMac(value: String?): String? = value?.filter(Char::isLetterOrDigit)?.uppercase()
    ?.takeIf { it.length == 12 && it.all { character -> character in '0'..'9' || character in 'A'..'F' } }
    ?.takeUnless { it == "000000000000" || it == "FFFFFFFFFFFF" }

internal fun collapseLegacyDeviceRows(devices: List<LanDeviceDto>): List<LanDeviceDto> {
    val stableByHostname = devices.filter { it.telemetryAvailable }
        .groupBy { it.hostname.trim().lowercase() }
        .mapValues { (_, rows) -> rows.mapNotNull(::stableDeviceIdentity).distinct() }
    val uuidByHostname = devices.filter { it.telemetryAvailable }
        .groupBy { it.hostname.trim().lowercase() }
        .mapValues { (_, rows) -> rows.mapNotNull(::uuidDeviceIdentity).distinct() }
    return devices.groupBy { device ->
        val hostname = device.hostname.trim().lowercase()
        val stable = stableDeviceIdentity(device)
        val soleUuid = uuidByHostname[hostname].orEmpty().singleOrNull()
        when {
            !device.telemetryAvailable || hostname.isBlank() -> stable ?: device.identityKey ?: "${device.collectorId}:${device.ipAddress}"
            soleUuid != null && (uuidDeviceIdentity(device) != null || (device.deviceUuid == null && device.stale)) -> soleUuid
            stable != null -> stable
            stableByHostname[hostname].orEmpty().size == 1 -> stableByHostname.getValue(hostname).single()
            stableByHostname[hostname].orEmpty().isEmpty() -> "legacy-host:$hostname"
            else -> device.identityKey ?: "${device.collectorId}:${device.ipAddress}"
        }
    }.values.map { rows ->
        val latest = rows.maxBy { it.receivedAt ?: it.lastSeen }
        latest.copy(observedIpAddresses = (rows.flatMap { it.observedIpAddresses } + rows.map { it.ipAddress }).distinct())
    }.sortedByDescending { it.receivedAt ?: it.lastSeen }
}

private fun stableDeviceIdentity(device: LanDeviceDto): String? =
    uuidDeviceIdentity(device)
        ?: normalizedMac(device.macAddress)?.let { "mac:$it" }
        ?: device.identityKey?.takeIf { it.startsWith("uuid:") || it.startsWith("mac:") }

private fun uuidDeviceIdentity(device: LanDeviceDto): String? =
    normalizedDeviceUuid(device.deviceUuid)?.let { "uuid:$it" }
        ?: device.identityKey?.takeIf { it.startsWith("uuid:") }
