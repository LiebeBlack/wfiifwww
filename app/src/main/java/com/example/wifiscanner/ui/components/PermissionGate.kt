package com.example.wifiscanner.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.wifiscanner.R
import com.example.wifiscanner.ui.SystemSettings

/**
 * The only runtime permission the scanner needs.
 *
 * Android gates Wi-Fi connection info (SSID/BSSID) and DHCP details behind
 * `ACCESS_FINE_LOCATION`, so it is required. `NEARBY_WIFI_DEVICES` is *not*
 * requested: the app does not use Wi-Fi Aware/RTT, and asking for an extra
 * permission that can be denied only made the gate fail unnecessarily on
 * Android 13+.
 */
fun requiredRuntimePermissions(): Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** True when every runtime scanner permission is granted. */
fun hasScannerPermissions(context: Context): Boolean =
    requiredRuntimePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Whether the system location switch is on. Needed in addition to the
 * permission: with location off, Android returns `<unknown ssid>` and the app
 * cannot show the network name.
 */
@Suppress("DEPRECATION")
fun isLocationServicesEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Wraps [content] with runtime permission handling for the scanner.
 *
 * - If permissions are granted, [content] renders normally.
 * - Otherwise a rationale screen is shown; the button launches the system
 *   permission flow once, then becomes a shortcut to the app settings page
 *   (Android does not show the dialog again after a permanent denial).
 * - The rationale also mentions the location switch, because granting the
 *   permission alone is not enough for the SSID.
 *
 * @param onPermissionResult optional callback with the final grant state.
 */
@Composable
fun PermissionGate(
    onPermissionResult: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(hasScannerPermissions(context))
    }
    var showRationale by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        granted = results.values.all { it }
        // After the first denial, flip to the "go to settings" explanation.
        showRationale = !granted
        onPermissionResult?.invoke(granted)
    }

    // Re-check on every return to the foreground: the user may have granted
    // the permission in system settings while this screen was in the back
    // stack, and showing the denial screen afterwards would be a dead end.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nowGranted = hasScannerPermissions(context)
        granted = nowGranted
        if (nowGranted) showRationale = false
    }

    if (granted) {
        content()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(
                if (showRationale) R.string.permission_denied_title
                else R.string.permission_rationale_title
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (showRationale) R.string.permission_denied_body
                else R.string.permission_rationale_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(26.dp))
        Button(onClick = {
            if (showRationale) {
                SystemSettings.openAppSettings(context)
            } else {
                launcher.launch(requiredRuntimePermissions())
            }
        }) {
            Text(
                stringResource(
                    if (showRationale) R.string.open_app_settings else R.string.grant_permission
                )
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.settings_privacy),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}
