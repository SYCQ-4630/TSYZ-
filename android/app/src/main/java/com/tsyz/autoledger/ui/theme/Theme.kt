package com.tsyz.autoledger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = LedgerPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = LightSurfaceVariant,
    onPrimaryContainer = LightOnSurface,
    secondary = LedgerSecondary,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = LedgerTertiary,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = DangerRed,
    onError = androidx.compose.ui.graphics.Color.White
)

private val DarkColors = darkColorScheme(
    primary = LedgerPrimaryDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF07251C),
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkOnSurface,
    secondary = androidx.compose.ui.graphics.Color(0xFF9BC3E8),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF10202E),
    tertiary = androidx.compose.ui.graphics.Color(0xFFE0BE8A),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF2A1C07),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = androidx.compose.ui.graphics.Color(0xFFFFB4A8),
    onError = androidx.compose.ui.graphics.Color(0xFF3A0A03)
)

@Composable
fun AutoLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LedgerTypography,
        content = content
    )
}
