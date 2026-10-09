package com.example.wifiscanner.ui

import com.example.wifiscanner.model.NetworkDevice

/**
 * Immutable UI state for the main screen, modeled as a sealed hierarchy so
 * the Compose layer renders exactly one branch:
 *
 *  - [Idle]    — nothing scanned yet (first launch).
 *  - [Loading] — a scan is running; carries partial results so the list
 *                fills in live and the previous scan stays visible.
 *  - [Success] — the last scan finished; carries the full device list.
 *  - [Error]   — the scan could not run at all (Wi-Fi off / no network).
 *                Discovery methods that are merely *blocked* by the platform
 *                are reported through [note] instead, never as an error.
 */
sealed interface ScanUiState {

    /** No scan performed yet. */
    data object Idle : ScanUiState

    /**
     * Scan in progress.
     * @param phase    human-readable current strategy
     *                 ("SSDP/UPnP announcements…").
     * @param devices  partial results collected so far.
     * @param note     non-fatal advisory (degraded discovery), if any.
     */
    data class Loading(
        val phase: String,
        val devices: List<NetworkDevice> = emptyList(),
        val note: String? = null,
    ) : ScanUiState

    /**
     * Scan completed successfully.
     * @param devices       the devices found (possibly empty).
     * @param note          non-fatal advisory shown as a banner, e.g. that the
     *                      ARP table is hidden so MAC addresses may be missing.
     * @param elapsedMillis wall-clock duration of the scan, shown in the
     *                      statistics card.
     */
    data class Success(
        val devices: List<NetworkDevice>,
        val note: String? = null,
        val elapsedMillis: Long? = null,
    ) : ScanUiState

    /** Scan failed; [message] is already user-readable. */
    data class Error(val message: String) : ScanUiState
}
