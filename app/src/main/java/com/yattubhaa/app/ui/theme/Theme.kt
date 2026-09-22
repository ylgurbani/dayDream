package com.yattubhaa.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Pure black on pure white (and the reverse) for maximum contrast; every button colour pair
// below is at least 7:1, the WCAG AAA threshold.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0A3D91),
    onPrimary = Color.White,
    secondary = Color(0xFF1B5E20),
    onSecondary = Color.White,
    error = Color(0xFFB00020),
    onError = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF1A1A1A),
    outline = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color.Black,
    secondary = Color(0xFFA5D6A7),
    onSecondary = Color.Black,
    error = Color(0xFFFF8A80),
    onError = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFF2F2F2),
    outline = Color.White,
)

// Base sizes are already large; the system font scale multiplies them further, so screens
// built on these styles must scroll rather than assume anything fits.
private val YattuTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun YattuTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = YattuTypography) {
        // Paint our own background and set the default text colour to match it, so text and
        // buttons can never end up on a background the theme did not choose.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground,
            content = content,
        )
    }
}
