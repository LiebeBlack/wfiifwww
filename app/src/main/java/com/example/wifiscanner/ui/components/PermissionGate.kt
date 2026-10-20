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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * The runtime permissions the scanner needs.
 *
 * Android gates Wi-Fi connection info (SSID/BSSID) and DHCP details behind
 * `ACCESS_FINE_LOCATION`, so it is required. `NEARBY_WIFI_DEVICES` is declared
 * in the manifest with `neverForLocation`, which makes it an install-time
 * permission: it is deliberately *not* in this array, because asking for it at
 * runtime would let a denial block the scanner for no functional gain.
 *
 * `READ_BASIC_PHONE_STATE` is likewise install-time and needs no request.
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
 * - Otherwise a rationale screen offers the system permission dialog plus a
 *   reduced mode ("continue without the network name"): the LAN scan itself
 *   never needs the location permission, so a denial is not a dead end.
 * - After a denial the screen offers the app settings and a retry, because
 *   Android stops showing the dialog after a permanent denial.
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
    // Counts dialogs shown by this process. After the second denial Android
    // stops showing the dialog, so the settings shortcut becomes primary.
    var attempts by remember { mutableStateOf(0) }
    // The location permission is only needed for the SSID. Scanning the LAN
    // itself works without it, so a denial must not make the whole app
    // unusable: this lets the user continue in reduced mode.
    var bypassed by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Read the real grant state instead of trusting the result map: an
        // automatic denial can arrive as an *empty* map, and `empty.all { }`
        // is true — which would wrongly unlock the app with no permission.
        granted = hasScannerPermissions(context)
        // After a denial, flip to the "go to settings" explanation.
        showRationale = !granted
        onPermissionResult?.invoke(granted)
    }

    // Re-check on every return to the foreground: the user may have granted
    // the permission in system settings while this screen was in the back
    // stack, and showing the denial screen afterwards would be a dead end.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nowGranted = hasScannerPermissions(context)
        granted = nowGranted
        if (nowGranted) {
            showRationale = false
            attempts = 0
        }
    }

    if (granted || bypassed) {
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
        if (showRationale) {
            // Two exits: retry the system dialog (it may still be shown) or go
            // straight to the app settings, which always works.
            Button(onClick = { SystemSettings.openAppSettings(context) }) {
                Text(stringResource(R.string.open_app_settings))
            }
            if (attempts < 2) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    attempts += 1
                    launcher.launch(requiredRuntimePermissions())
                }) {
                    Text(stringResource(R.string.permission_grant_again))
                }
            }
        } else {
            Button(onClick = {
                attempts += 1
                launcher.launch(requiredRuntimePermissions())
            }) {
                Text(stringResource(R.string.grant_permission))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Reduced mode is always available: the LAN scan does not need the
        // location permission, only the network name does.
        TextButton(onClick = { bypassed = true }) {
            Text(stringResource(R.string.permission_continue_without))
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
