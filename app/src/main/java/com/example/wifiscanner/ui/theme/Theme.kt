package com.example.wifiscanner.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Appearance selected by the user.
 *
 * The app defaults to [Dark]: the deep-navy surfaces are the designed look,
 * while [Light] and [System] remain available from Settings.
 */
enum class ThemeMode { System, Light, Dark }

// ---------------------------------------------------------------------------
// Palette
//
// Dark mode is the primary design: near-black navy background, one step
// lighter surface for cards, mint as the single action colour and periwinkle
// as a secondary accent. Values are intentionally low-chroma so the content
// (device names, addresses) stays the brightest thing on screen.
// ---------------------------------------------------------------------------

private val Mint = Color(0xFF54E6C4)
private val MintDim = Color(0xFF0C4037)
private val Periwinkle = Color(0xFF98A7FF)
private val Amber = Color(0xFFFFC978)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00211A),
    primaryContainer = MintDim,
    onPrimaryContainer = Color(0xFFB6FFEF),
    inversePrimary = Color(0xFF006B58),
    secondary = Periwinkle,
    onSecondary = Color(0xFF0B1430),
    secondaryContainer = Color(0xFF232C55),
    onSecondaryContainer = Color(0xFFE1E5FF),
    tertiary = Amber,
    onTertiary = Color(0xFF2A1B00),
    tertiaryContainer = Color(0xFF3D2E10),
    onTertiaryContainer = Color(0xFFFFE8C2),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFE8EDF9),
    surface = Color(0xFF0C1322),
    onSurface = Color(0xFFE8EDF9),
    surfaceVariant = Color(0xFF151E32),
    onSurfaceVariant = Color(0xFFA6B2CB),
    outline = Color(0xFF3C4A66),
    outlineVariant = Color(0xFF212C43),
    error = Color(0xFFFF9A9A),
    onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF521919),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE8EDF9),
    inverseOnSurface = Color(0xFF11182A),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F4E3),
    onPrimaryContainer = Color(0xFF00211A),
    inversePrimary = Mint,
    secondary = Color(0xFF4453C4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E3FF),
    onSecondaryContainer = Color(0xFF0A1330),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3B3),
    onTertiaryContainer = Color(0xFF2A1B00),
    background = Color(0xFFF6F8FD),
    onBackground = Color(0xFF12182A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12182A),
    surfaceVariant = Color(0xFFEDF1F9),
    onSurfaceVariant = Color(0xFF4D586E),
    outline = Color(0xFFBCC6D8),
    outlineVariant = Color(0xFFE1E6F0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF12182A),
    inverseOnSurface = Color(0xFFF6F8FD),
    scrim = Color(0xFF000000),
)

// ---------------------------------------------------------------------------
// Typography & shapes
// ---------------------------------------------------------------------------

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * App theme.
 *
 * @param themeMode  what the user picked in Settings (dark by default).
 * @param dynamicColor Android 12+ wallpaper palette; off by default so the
 *                     designed mint/navy identity survives.
 */
@Composable
fun WifiScannerTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

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
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
