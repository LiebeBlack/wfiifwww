package com.example.wifiscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifiscanner.ui.ScanViewModel
import com.example.wifiscanner.ui.components.PermissionGate
import com.example.wifiscanner.ui.screens.MainScreen
import com.example.wifiscanner.ui.theme.WifiScannerTheme

/**
 * Single-activity host for the Compose UI.
 *
 * The composable tree is: theme -> [PermissionGate] -> [MainScreen]. The
 * gate shows a rationale and drives the system permission dialog; once
 * granted, [MainScreen] appears through normal recomposition.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WifiScannerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // ViewModel scoped to this Activity; survives rotation.
                    val viewModel: ScanViewModel = viewModel(factory = ScanViewModel.Factory)

                    PermissionGate {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
