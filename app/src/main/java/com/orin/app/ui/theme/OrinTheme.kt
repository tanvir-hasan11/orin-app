package com.orin.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OrinPalette {
    val DarkBg = Color(0xFF191816)
    val DarkSurface = Color(0xFF1F1E1D)
    val DarkElevated = Color(0xFF2A2926)
    val DarkBorder = Color(0xFF363430)
    val DarkText = Color(0xFFF5F4EF)
    val DarkMuted = Color(0xFFA8A49B)
    val Terracotta = Color(0xFFD97757)

    val LightBg = Color(0xFFFAF9F5)
    val LightSurface = Color(0xFFF5F4EF)
    val LightElevated = Color(0xFFEFECE6)
    val LightBorder = Color(0xFFE2DDD3)
    val LightText = Color(0xFF1A1917)
    val LightMuted = Color(0xFF6B675E)
    val TerracottaLight = Color(0xFFC96442)

    val ObsidianBg = Color(0xFF0A0E17)
    val ObsidianSurface = Color(0xFF111827)
    val ObsidianElevated = Color(0xFF1F2937)
    val ObsidianBorder = Color(0xFF334155)
    val ObsidianText = Color(0xFFF8FAFC)
    val ObsidianMuted = Color(0xFF94A3B8)
    val ElectricCyan = Color(0xFF06B6D4)
    val AmberGold = Color(0xFFF59E0B)
}

private val OrinDarkScheme = darkColorScheme(
    primary = OrinPalette.Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A251E),
    onPrimaryContainer = Color(0xFFF7D6C8),
    secondary = OrinPalette.DarkMuted,
    onSecondary = OrinPalette.DarkBg,
    secondaryContainer = OrinPalette.DarkElevated,
    onSecondaryContainer = OrinPalette.DarkText,
    background = OrinPalette.DarkBg,
    onBackground = OrinPalette.DarkText,
    surface = OrinPalette.DarkBg,
    onSurface = OrinPalette.DarkText,
    surfaceContainerLow = OrinPalette.DarkSurface,
    surfaceContainer = OrinPalette.DarkSurface,
    surfaceContainerHigh = OrinPalette.DarkElevated,
    surfaceVariant = OrinPalette.DarkElevated,
    onSurfaceVariant = OrinPalette.DarkMuted,
    outline = OrinPalette.DarkBorder,
    outlineVariant = OrinPalette.DarkBorder,
)

private val OrinLightScheme = lightColorScheme(
    primary = OrinPalette.TerracottaLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DFD6),
    onPrimaryContainer = Color(0xFF3D1A0E),
    secondary = OrinPalette.LightMuted,
    onSecondary = Color.White,
    secondaryContainer = OrinPalette.LightElevated,
    onSecondaryContainer = OrinPalette.LightText,
    background = OrinPalette.LightBg,
    onBackground = OrinPalette.LightText,
    surface = OrinPalette.LightBg,
    onSurface = OrinPalette.LightText,
    surfaceContainerLow = OrinPalette.LightSurface,
    surfaceContainer = OrinPalette.LightSurface,
    surfaceContainerHigh = OrinPalette.LightElevated,
    surfaceVariant = OrinPalette.LightElevated,
    onSurfaceVariant = OrinPalette.LightMuted,
    outline = OrinPalette.LightBorder,
    outlineVariant = OrinPalette.LightBorder,
)

private val OrinObsidianScheme = darkColorScheme(
    primary = OrinPalette.ElectricCyan,
    onPrimary = Color(0xFF04131A),
    primaryContainer = Color(0xFF0E3A47),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = OrinPalette.AmberGold,
    onSecondary = Color(0xFF1F1300),
    secondaryContainer = OrinPalette.ObsidianElevated,
    onSecondaryContainer = OrinPalette.ObsidianText,
    tertiary = OrinPalette.AmberGold,
    background = OrinPalette.ObsidianBg,
    onBackground = OrinPalette.ObsidianText,
    surface = OrinPalette.ObsidianBg,
    onSurface = OrinPalette.ObsidianText,
    surfaceContainerLow = OrinPalette.ObsidianSurface,
    surfaceContainer = OrinPalette.ObsidianSurface,
    surfaceContainerHigh = OrinPalette.ObsidianElevated,
    surfaceVariant = OrinPalette.ObsidianElevated,
    onSurfaceVariant = OrinPalette.ObsidianMuted,
    outline = OrinPalette.ObsidianBorder,
    outlineVariant = OrinPalette.ObsidianBorder,
)

@Composable
fun OrinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeStyle: String = "warm",
    content: @Composable () -> Unit,
) {
    val scheme = when {
        themeStyle == "obsidian" -> OrinObsidianScheme
        darkTheme -> OrinDarkScheme
        else -> OrinLightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
