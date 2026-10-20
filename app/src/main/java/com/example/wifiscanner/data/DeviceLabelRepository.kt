package com.example.wifiscanner.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin repository over [DeviceLabelDao] — the only class the ViewModel talks
 * to for persistence. Centralizes the "empty label == delete" rule and the
 * MAC/IP normalization so the DAO stays dumb.
 */
class DeviceLabelRepository(private val dao: DeviceLabelDao) {

    /** Reactive map of key (MAC or IP) -> label for cheap lookup during scan merges. */
    fun observeLabelMap(): Flow<Map<String, String>> =
        dao.observeAll().map { entities -> entities.associate { it.key to it.label } }

    /** Reactive map of key (MAC or IP) -> nickname for cheap lookup during scan merges. */
    fun observeNicknameMap(): Flow<Map<String, String>> =
        dao.observeAll().map { entities -> entities.associate { it.key to it.nickname } }

    /**
     * Saves (or clears) the label for a device identified by MAC or IP.
     *
     * @param mac optional MAC address (preferred if available)
     * @param ip  IP address (fallback if MAC is null)
     * @param label trimmed user text; blank removes the stored row.
     * @param nowMs  timestamp to record as lastSeen (injected for tests).
     */
    suspend fun saveLabel(mac: String?, ip: String, label: String, nowMs: Long = System.currentTimeMillis()) {
        val key = normalizeIdentifier(mac, ip) ?: return
        val keyType = if (mac != null) "MAC" else "IP"
        val trimmed = label.trim()
        val existing = dao.getByKey(key)
        if (trimmed.isEmpty() && (existing?.nickname?.isBlank() != false)) {
            dao.delete(key)
        } else {
            dao.upsert(
                DeviceLabelEntity(
                    key = key,
                    keyType = keyType,
                    label = trimmed,
                    nickname = existing?.nickname ?: "",
                    lastSeen = nowMs
                )
            )
        }
    }

    /**
     * Saves (or clears) the nickname for a device identified by MAC or IP.
     *
     * @param mac optional MAC address (preferred if available)
     * @param ip  IP address (fallback if MAC is null)
     * @param nickname trimmed user text; blank removes the nickname.
     * @param nowMs  timestamp to record as lastSeen (injected for tests).
     */
    suspend fun saveNickname(mac: String?, ip: String, nickname: String, nowMs: Long = System.currentTimeMillis()) {
        val key = normalizeIdentifier(mac, ip) ?: return
        val keyType = if (mac != null) "MAC" else "IP"
        val trimmed = nickname.trim()
        val existing = dao.getByKey(key)
        if (trimmed.isEmpty() && (existing?.label?.isBlank() != false)) {
            dao.delete(key)
        } else {
            dao.upsert(
                DeviceLabelEntity(
                    key = key,
                    keyType = keyType,
                    label = existing?.label ?: "",
                    nickname = trimmed,
                    lastSeen = nowMs
                )
            )
        }
    }

    /**
     * Saves both label and nickname for a device identified by MAC or IP in one transaction.
     *
     * @param mac optional MAC address (preferred if available)
     * @param ip  IP address (fallback if MAC is null)
     * @param label trimmed user text for location.
     * @param nickname trimmed user text for custom name.
     * @param nowMs  timestamp to record as lastSeen (injected for tests).
     */
    suspend fun saveLabelAndNickname(
        mac: String?,
        ip: String,
        label: String,
        nickname: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val key = normalizeIdentifier(mac, ip) ?: return
        val keyType = if (mac != null) "MAC" else "IP"
        val trimmedLabel = label.trim()
        val trimmedNickname = nickname.trim()
        if (trimmedLabel.isEmpty() && trimmedNickname.isEmpty()) {
            dao.delete(key)
        } else {
            dao.upsert(
                DeviceLabelEntity(
                    key = key,
                    keyType = keyType,
                    label = trimmedLabel,
                    nickname = trimmedNickname,
                    lastSeen = nowMs
                )
            )
        }
    }

    /** Stamps lastSeen for devices seen in the latest scan. */
    suspend fun touchSeen(identifiers: Collection<Pair<String?, String>>, nowMs: Long = System.currentTimeMillis()) {
        for ((mac, ip) in identifiers) {
            val key = normalizeIdentifier(mac, ip) ?: continue
            val existing = dao.getByKey(key) ?: continue
            dao.upsert(existing.copy(lastSeen = nowMs))
        }
    }

    /** Deletes labels untouched since [olderThanMillis] ago. */
    suspend fun pruneStale(olderThanMillis: Long) {
        dao.pruneStale(System.currentTimeMillis() - olderThanMillis)
    }

    companion object {
        /** Uppercases and colon-separates any common MAC format. */
        internal fun normalizeMac(mac: String): String? {
            val hex = mac.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            if (hex.length != 12) return null
            return hex.chunked(2).joinToString(":")
        }

        /**
         * Returns the best identifier for a device: MAC if available, otherwise IP.
         * MAC is preferred because it's stable across DHCP renewals.
         */
        internal fun normalizeIdentifier(mac: String?, ip: String): String? {
            // Prefer MAC if available and valid
            mac?.let { normalizeMac(it)?.let { return it } }
            // Fall back to IP
            if (isValidIp(ip)) return ip
            return null
        }

        /** Basic IP validation. */
        private fun isValidIp(ip: String): Boolean {
            val parts = ip.split('.')
            if (parts.size != 4) return false
            return parts.all { it.toIntOrNull() in 0..255 }
        }
    }
}
