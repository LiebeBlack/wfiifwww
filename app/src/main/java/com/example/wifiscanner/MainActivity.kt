package com.example.wifiscanner

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifiscanner.ui.AppSettings
import com.example.wifiscanner.ui.ScanViewModel
import com.example.wifiscanner.ui.components.PermissionGate
import com.example.wifiscanner.ui.screens.AppRoot
import com.example.wifiscanner.ui.theme.ThemeMode
import com.example.wifiscanner.ui.theme.WifiScannerTheme

/**
 * Single-activity host for the Compose UI.
 *
 * The composable tree is: settings -> theme -> [PermissionGate] -> [AppRoot].
 * The permission gate shows a rationale and drives the system dialog; once
 * granted, the shell appears through normal recomposition.
 *
 * The theme is driven by the persisted [AppSettings] snapshot, so changing the
 * appearance in Settings re-themes every screen immediately. The status and
 * navigation bar icon colours follow the chosen mode too, otherwise light mode
 * would keep the white icons from `enableEdgeToEdge()`'s dark default.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appContext = LocalContext.current.applicationContext
            val settings = remember { AppSettings(appContext) }
            val uiSettings by settings.state.collectAsStateWithLifecycle()

            val darkTheme = when (uiSettings.themeMode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> isSystemInDarkTheme()
            }

            WifiScannerTheme(
                themeMode = uiSettings.themeMode,
                dynamicColor = uiSettings.dynamicColor,
            ) {
                val view = LocalView.current
                SideEffect {
                    val window = view.context.findActivity()?.window ?: return@SideEffect
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // ViewModel scoped to this Activity; survives rotation.
                    val viewModel: ScanViewModel = viewModel(factory = ScanViewModel.Factory)

                    PermissionGate {
                        AppRoot(viewModel = viewModel, settings = settings)
                    }
                }
            }
        }
    }
}

/** Unwraps the Activity from a Compose view's (possibly wrapped) context. */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
