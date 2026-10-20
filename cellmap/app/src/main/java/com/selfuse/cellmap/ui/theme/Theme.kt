package com.selfuse.cellmap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val UnicomBlue = Color(0xFF1565C0)
val TelecomRed = Color(0xFFC62828)
val PrivateYellow = Color(0xFFF9A825)
val SharedGreen = Color(0xFF2E7D32)
val RiskHigh = Color(0xFFB3261E)
val RiskMedium = Color(0xFFB26A00)
val RiskLow = Color(0xFF2E6B4F)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D47A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF00696E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1F7),
    onSecondaryContainer = Color(0xFF002022),
    tertiary = Color(0xFF6B5B00),
    surface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFFE1E8EF),
    onSurface = Color(0xFF182027),
    onSurfaceVariant = Color(0xFF414A52),
    outline = Color(0xFF717A82),
    error = RiskHigh
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF80D4DA),
    onSecondary = Color(0xFF00363A),
    secondaryContainer = Color(0xFF004F53),
    onSecondaryContainer = Color(0xFF9CF1F7),
    tertiary = Color(0xFFE0C64F),
    surface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFF28323C),
    onSurface = Color(0xFFE7EDF3),
    onSurfaceVariant = Color(0xFFC1CAD2),
    outline = Color(0xFF8B949C),
    error = Color(0xFFFFB4AB)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun CellMapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
