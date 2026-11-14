package backend.services

import backend.models.InventoryAssetDetails
import backend.models.InventoryAssetListItem
import backend.models.InventoryFilters
import backend.models.InventoryStatsResponse
import backend.models.InventoryUpsertDetectedRequest
import backend.models.InventoryUpsertResponse
import backend.models.PaginatedResponse
import backend.models.PaginationMeta
import backend.queries.InventoryQueries
import backend.repository.AuditRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class InventoryService(
    private val inventoryQueries: InventoryQueries,
    private val assetDetectionService: AssetDetectionService,
    private val audit: AuditRepository
) {
    private val staleMinutes: Long = 30
    private val statusFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun listAssets(filters: InventoryFilters): PaginatedResponse<InventoryAssetListItem> {
        refreshAssetStatus()
        val collectorFilters = filters.copy(connectionSource = "agent")
        val page = inventoryQueries.getAll(collectorFilters)
        val pageSize = collectorFilters.pageSize.coerceIn(1, 100)
        val currentPage = collectorFilters.page.coerceAtLeast(1)
        return PaginatedResponse(
            data = page.items.map { record ->
                InventoryAssetListItem(
                    id = record.id,
                    deviceName = record.fullDeviceName ?: record.hostname ?: record.ipAddress ?: "Unknown",
                    hostname = record.hostname,
                    ipAddress = record.ipAddress,
                    manufacturer = record.manufacturer,
                    model = record.model,
                    processorName = record.processorName,
                    installedRam = record.installedRam,
                    storageSummary = formatStorageSummary(record.storageUsed, record.storageTotal, record.storageFree),
                    osSummary = listOfNotNull(record.osName, record.osEdition, record.osVersion).joinToString(" ").ifBlank { null },
                    status = record.status,
                    lastSeenAt = record.lastSeenAt?.format(statusFormatter),
                    assignedDepartment = record.assignedDepartment,
                    connectionSource = record.connectionSource
                )
            },
            meta = PaginationMeta(
                totalCount = page.total,
                pageSize = pageSize,
                offset = ((currentPage - 1) * pageSize).toLong(),
                currentPage = currentPage
            )
        )
    }

    fun getAssetDetails(id: Int): InventoryAssetDetails? {
        refreshAssetStatus()
        val record = inventoryQueries.getById(id)?.takeIf { it.connectionSource.equals("agent", ignoreCase = true) } ?: return null
        return InventoryAssetDetails(
            id = record.id,
            assetTag = record.assetTag,
            deviceName = record.fullDeviceName ?: record.hostname ?: record.ipAddress ?: "Unknown",
            hostname = record.hostname,
            fullDeviceName = record.fullDeviceName,
            manufacturer = record.manufacturer,
            model = record.model,
            processorName = record.processorName,
            processorSpeed = record.processorSpeed,
            installedRam = record.installedRam,
            usableRam = record.usableRam,
            ramSpeed = record.ramSpeed,
            gpuName = record.gpuName,
            gpuMemory = record.gpuMemory,
            storageTotal = record.storageTotal,
            storageUsed = record.storageUsed,
            storageFree = record.storageFree,
            storageBreakdownJson = record.storageBreakdownJson,
            systemType = record.systemType,
            osName = record.osName,
            osEdition = record.osEdition,
            osVersion = record.osVersion,
            osBuild = record.osBuild,
            installedOn = record.installedOn,
            domainOrWorkgroup = record.domainOrWorkgroup,
            deviceUuid = record.deviceUuid,
            productId = record.productId,
            penTouchSupport = record.penTouchSupport,
            ipAddress = record.ipAddress,
            macAddress = record.macAddress,
            connectionSource = record.connectionSource,
            status = record.status,
            assignedDepartment = record.assignedDepartment,
            assignedUser = record.assignedUser,
            notes = record.notes,
            lastSeenAt = record.lastSeenAt?.format(statusFormatter),
            createdAt = record.createdAt.format(statusFormatter),
            updatedAt = record.updatedAt.format(statusFormatter)
        )
    }

    fun upsertDetectedAsset(payload: InventoryUpsertDetectedRequest, saveSnapshot: Boolean = true): InventoryUpsertResponse {
        val normalized = normalizePayload(payload)
        validatePayload(normalized)

        val resolvedStatus = normalizeStatus(normalized.status, normalized.lastSeenAt)
        val computedStorageFree = normalized.storageFree ?: deriveStorageFree(normalized.storageTotal, normalized.storageUsed)

        val (record, created) = inventoryQueries.upsertAsset(
            payload = normalized,
            status = resolvedStatus,
            normalizedStorageFree = computedStorageFree
        )

        if (saveSnapshot) {
            inventoryQueries.saveSnapshot(record.id, json.encodeToString(normalized), normalized.connectionSource ?: "unknown")
        }

        return InventoryUpsertResponse(
            id = record.id,
            created = created,
            status = record.status,
            lastSeenAt = record.lastSeenAt?.format(statusFormatter),
            message = if (created) "Inventory asset created" else "Inventory asset updated"
        )
    }

    fun markStaleAssets() {
        val threshold = LocalDateTime.now().minusMinutes(staleMinutes)
        inventoryQueries.markAssetsStale(threshold)
    }

    fun refreshAssetStatus() {
        val threshold = LocalDateTime.now().minusMinutes(staleMinutes)
        inventoryQueries.markAssetsStale(threshold)
    }

    fun getInventoryStats(): InventoryStatsResponse {
        refreshAssetStatus()
        val raw = inventoryQueries.getStats("agent")
        return InventoryStatsResponse(
            total = raw.total.toInt(),
            online = raw.online.toInt(),
            offline = raw.offline.toInt(),
            stale = raw.stale.toInt(),
            bySource = raw.bySource.mapValues { it.value.toInt() }
        )
    }

    fun updateAssignment(id: Int, department: String?, assignedUser: String?): InventoryAssetDetails? {
        if (getAssetDetails(id) == null) return null
        inventoryQueries.updateAssignment(id, department?.trim(), assignedUser?.trim()) ?: return null
        return getAssetDetails(id)
    }

    fun updateNotes(id: Int, notes: String): InventoryAssetDetails? {
        if (getAssetDetails(id) == null) return null
        val sanitized = sanitizeNotes(notes)
        inventoryQueries.updateNotes(id, sanitized) ?: return null
        return getAssetDetails(id)
    }

    fun updateStatus(id: Int, status: String): InventoryAssetDetails? {
        if (getAssetDetails(id) == null) return null
        inventoryQueries.updateStatus(id, normalizeStatus(status, null)) ?: return null
        return getAssetDetails(id)
    }

    fun deleteAsset(id: Int, actorId: Int?): Boolean {
        val asset = inventoryQueries.getById(id) ?: return false
        val deleted = inventoryQueries.delete(id)
        if (deleted) {
            val identity = asset.fullDeviceName ?: asset.hostname ?: asset.ipAddress ?: "unknown device"
            audit.log(actorId, "Permanently deleted inventory asset #$id ($identity)", "inventory_assets", target = id.toString())
        }
        return deleted
    }

    fun exportAssetsCsv(filters: InventoryFilters): String {
        val rows = inventoryQueries.findAllForExport(filters.copy(connectionSource = "agent"))
        val header = listOf(
            "id", "deviceName", "hostname", "ipAddress", "manufacturer", "model", "processor", "ram", "storageTotal", "storageUsed", "storageFree", "os", "status", "lastSeenAt", "department", "assignedUser", "source"
        ).joinToString(",")
        val body = rows.joinToString("\n") { row ->
            listOf(
                row.id.toString(),
                row.fullDeviceName ?: row.hostname ?: "",
                row.hostname ?: "",
                row.ipAddress ?: "",
                row.manufacturer ?: "",
                row.model ?: "",
                row.processorName ?: "",
                row.installedRam ?: "",
                row.storageTotal ?: "",
                row.storageUsed ?: "",
                row.storageFree ?: "",
                listOfNotNull(row.osName, row.osEdition, row.osVersion).joinToString(" "),
                row.status,
                row.lastSeenAt?.format(statusFormatter) ?: "",
                row.assignedDepartment ?: "",
                row.assignedUser ?: "",
                row.connectionSource ?: ""
            ).joinToString(",") { value -> csvEscape(value) }
        }

        return "$header\n$body"
    }

    private fun normalizePayload(input: InventoryUpsertDetectedRequest): InventoryUpsertDetectedRequest {
        return input.copy(
            assetTag = input.assetTag.normalizedOrNull(),
            deviceName = input.deviceName.normalizedOrNull(),
            hostname = input.hostname.normalizedOrNull(),
            fullDeviceName = input.fullDeviceName.normalizedOrNull(),
            manufacturer = input.manufacturer.normalizedOrNull(),
            model = input.model.normalizedOrNull(),
            processorName = input.processorName.normalizedOrNull(),
            processorSpeed = input.processorSpeed.normalizedOrNull(),
            installedRam = input.installedRam.normalizedOrNull(),
            usableRam = input.usableRam.normalizedOrNull(),
            ramSpeed = input.ramSpeed.normalizedOrNull(),
            gpuName = input.gpuName.normalizedOrNull(),
            gpuMemory = input.gpuMemory.normalizedOrNull(),
            storageTotal = input.storageTotal.normalizedOrNull(),
            storageUsed = input.storageUsed.normalizedOrNull(),
            storageFree = input.storageFree.normalizedOrNull(),
            storageBreakdownJson = input.storageBreakdownJson.normalizedOrNull(),
            systemType = input.systemType.normalizedOrNull(),
            osName = input.osName.normalizedOrNull(),
            osEdition = input.osEdition.normalizedOrNull(),
            osVersion = input.osVersion.normalizedOrNull(),
            osBuild = input.osBuild.normalizedOrNull(),
            installedOn = input.installedOn.normalizedOrNull(),
            domainOrWorkgroup = input.domainOrWorkgroup.normalizedOrNull(),
            deviceUuid = normalizedInventoryUuid(input.deviceUuid),
            productId = input.productId.normalizedOrNull(),
            penTouchSupport = input.penTouchSupport.normalizedOrNull(),
            ipAddress = input.ipAddress.normalizedOrNull(),
            macAddress = normalizedInventoryMac(input.macAddress),
            connectionSource = normalizeSource(input.connectionSource),
            status = input.status?.trim(),
            assignedDepartment = input.assignedDepartment?.trim(),
            assignedUser = input.assignedUser?.trim(),
            notes = sanitizeNotes(input.notes),
            lastSeenAt = input.lastSeenAt?.trim()
        )
    }

    private fun validatePayload(payload: InventoryUpsertDetectedRequest) {
        val hasStableId = !payload.deviceUuid.isNullOrBlank() || !payload.hostname.isNullOrBlank() || !payload.ipAddress.isNullOrBlank()
        require(hasStableId) { "deviceUuid, hostname, or ipAddress is required" }

        payload.ipAddress?.takeIf { it.isNotBlank() }?.let { ip ->
            require(isValidIp(ip)) { "Invalid IP address format" }
            require(assetDetectionService.matches(ip) || payload.connectionSource.equals("manual", true) || payload.connectionSource.equals("agent", true)) {
                "IP address is outside allowed detection prefixes"
            }
        }
    }

    private fun normalizeSource(source: String?): String {
        val normalized = source?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "lan detected", "discovered", "lan", "passive" -> "lan-detected"
            "manual", "manually registered", "device-registry" -> "manual"
            "agent", "client-metrics", "reported" -> "agent"
            "" -> "lan-detected"
            else -> normalized
        }
    }

    private fun normalizeStatus(status: String?, lastSeenAt: String?): String {
        val normalized = status?.trim()?.lowercase().orEmpty()
        if (normalized in setOf("online", "offline", "stale")) return normalized
        return if (!lastSeenAt.isNullOrBlank()) "online" else "offline"
    }

    private fun isValidIp(value: String): Boolean = runCatching {
        InetAddress.getByName(value)
    }.isSuccess

    private fun parseBytesLike(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val number = Regex("""([0-9]+(?:\\.[0-9]+)?)""").find(value)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val unit = value.lowercase()
        return when {
            "tb" in unit -> number * 1024
            "gb" in unit -> number
            "mb" in unit -> number / 1024
            else -> number
        }
    }

    private fun deriveStorageFree(total: String?, used: String?): String? {
        val totalGb = parseBytesLike(total) ?: return null
        val usedGb = parseBytesLike(used) ?: return null
        val free = (totalGb - usedGb).coerceAtLeast(0.0)
        return "${"%.1f".format(free)} GB"
    }

    private fun formatStorageSummary(used: String?, total: String?, free: String?): String? {
        return when {
            !used.isNullOrBlank() && !total.isNullOrBlank() -> "$used / $total"
            !free.isNullOrBlank() && !total.isNullOrBlank() -> "$free free of $total"
            !total.isNullOrBlank() -> total
            else -> null
        }
    }

    private fun sanitizeNotes(notes: String?): String? {
        if (notes == null) return null
        return notes
            .replace(Regex("[\\u0000-\\u001F]"), " ")
            .trim()
            .take(4000)
            .ifBlank { null }
    }

    private fun csvEscape(value: String): String {
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }

    private fun String?.normalizedOrNull(): String? = this?.trim()?.ifBlank { null }

    private fun normalizedInventoryUuid(value: String?): String? = backend.repository.normalizedDeviceUuid(value)

    private fun normalizedInventoryMac(value: String?): String? = backend.repository.normalizedMac(value)?.chunked(2)?.joinToString("-")
}
