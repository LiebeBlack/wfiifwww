package com.example.wifiscanner.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin repository over [DeviceLabelDao] — the only class the ViewModel talks
 * to for persistence. Centralizes the "empty label == delete" rule and the
 * MAC normalization so the DAO stays dumb.
 */
class DeviceLabelRepository(private val dao: DeviceLabelDao) {

    /** Reactive map of MAC -> label for cheap lookup during scan merges. */
    fun observeLabelMap(): Flow<Map<String, String>> =
        dao.observeAll().map { entities -> entities.associate { it.mac to it.label } }

    /**
     * Saves (or clears) the label for [mac].
     *
     * @param label trimmed user text; blank removes the stored row.
     * @param nowMs  timestamp to record as lastSeen (injected for tests).
     */
    suspend fun saveLabel(mac: String, label: String, nowMs: Long = System.currentTimeMillis()) {
        val normalized = normalizeMac(mac) ?: return
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            dao.delete(normalized)
        } else {
            dao.upsert(DeviceLabelEntity(mac = normalized, label = trimmed, lastSeen = nowMs))
        }
    }

    /** Stamps lastSeen for devices seen in the latest scan. */
    suspend fun touchSeen(macs: Collection<String>, nowMs: Long = System.currentTimeMillis()) {
        for (mac in macs) {
            val normalized = normalizeMac(mac) ?: continue
            val existing = dao.getByMac(normalized) ?: continue
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
    }
}
