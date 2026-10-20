package com.example.wifiscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity: a user-defined physical location label and nickname for one device.
 *
 * Keyed by MAC when available (stable across DHCP renewals), falls back to IP
 * when MAC is not available. The MAC is stored normalized uppercase with colons.
 * IP-based labels are less stable but better than nothing.
 *
 * @param key      Normalized MAC or IP (primary key). Format: "AA:BB:CC:DD:EE:FF" or "192.168.1.1"
 * @param keyType  Either "MAC" or "IP" to distinguish the identifier type.
 * @param label    User text for location, e.g. "Living Room". Empty string == unlabeled.
 * @param nickname User text for custom device name/apodo, e.g. "Mi PC Principal".
 * @param lastSeen Timestamp of the most recent scan that saw this device,
 *                 used for "last seen" display and cleanup of stale rows.
 */
@Entity(
    tableName = "device_labels",
    indices = [Index(value = ["lastSeen"]), Index(value = ["keyType"])],
)
data class DeviceLabelEntity(
    @PrimaryKey val key: String,
    val keyType: String, // "MAC" or "IP"
    val label: String,
    val nickname: String = "",
    val lastSeen: Long = 0L,
)
