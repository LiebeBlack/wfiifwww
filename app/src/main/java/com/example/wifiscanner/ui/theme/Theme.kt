package com.example.wifiscanner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// Brand palette — a cool "network tool" look that works in both modes.
// ---------------------------------------------------------------------------

private val TealPrimary = Color(0xFF00BFA5)
private val TealOnPrimary = Color(0xFF00332C)
private val BlueSecondary = Color(0xFF4FC3F7)

private val DarkColors = darkColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    secondary = BlueSecondary,
    background = Color(0xFF0B1220),
    surface = Color(0xFF111A2C),
    surfaceVariant = Color(0xFF1C2740),
    onBackground = Color(0xFFE6EAF2),
    onSurface = Color(0xFFE6EAF2),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    secondary = Color(0xFF0277BD),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF1F7),
)

/**
 * App theme: dynamic color (Android 12+) when available, brand palette
 * otherwise, with full dark/light support driven by the system.
 */
@Composable
fun WifiScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
