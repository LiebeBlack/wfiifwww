package com.example.wifiscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity: a user-defined physical location label for one device.
 *
 * Keyed by MAC because it is stable across DHCP renewals (IPs change, the
 * NIC's hardware address does not). The MAC is stored normalized uppercase
 * with colons so lookups are exact-match.
 *
 * @param mac      Normalized MAC, e.g. "AA:BB:CC:DD:EE:FF" (primary key).
 * @param label    User text, e.g. "Living Room". Empty string == unlabeled.
 * @param lastSeen Timestamp of the most recent scan that saw this device,
 *                 used for "last seen" display and cleanup of stale rows.
 */
@Entity(
    tableName = "device_labels",
    indices = [Index(value = ["lastSeen"])],
)
data class DeviceLabelEntity(
    @PrimaryKey val mac: String,
    val label: String,
    val lastSeen: Long = 0L,
)
