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
 *  - [Error]   — the scan failed (no Wi-Fi, permission denied, ...).
 */
sealed interface ScanUiState {

    /** No scan performed yet. */
    data object Idle : ScanUiState

    /**
     * Scan in progress.
     * @param phase    human-readable current phase ("Pinging subnet…").
     * @param devices  partial results collected so far.
     */
    data class Loading(
        val phase: String,
        val devices: List<NetworkDevice> = emptyList(),
    ) : ScanUiState

    /** Scan completed successfully. */
    data class Success(val devices: List<NetworkDevice>) : ScanUiState

    /** Scan failed; [message] is already user-readable. */
    data class Error(val message: String) : ScanUiState
}
