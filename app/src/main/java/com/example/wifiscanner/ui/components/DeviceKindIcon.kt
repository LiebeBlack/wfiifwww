package com.example.wifiscanner.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.wifiscanner.model.DeviceKind

/**
 * Picks the leading icon for an inferred [DeviceKind]. Recognition at a glance
 * matters more than accuracy here, so unidentified devices fall back to the
 * neutral computer icon.
 */
internal fun iconForKind(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.Self -> Icons.Filled.Smartphone
    DeviceKind.Router -> Icons.Filled.Router
    DeviceKind.Printer -> Icons.Filled.Print
    DeviceKind.Camera -> Icons.Filled.Videocam
    DeviceKind.Speaker -> Icons.Filled.Speaker
    DeviceKind.Tv -> Icons.Filled.Tv
    DeviceKind.MediaPlayer -> Icons.Filled.Cast
    DeviceKind.Nas -> Icons.Filled.Storage
    DeviceKind.Phone -> Icons.Filled.Smartphone
    DeviceKind.Computer -> Icons.Filled.Computer
    DeviceKind.Iot -> Icons.Filled.DevicesOther
    DeviceKind.Unknown -> Icons.Filled.Computer
}
