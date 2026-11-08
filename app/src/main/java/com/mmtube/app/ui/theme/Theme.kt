package com.mmtube.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mmtube.app.data.ThemeMode
import com.mmtube.app.util.findActivity

private val DarkColors = darkColorScheme(
    primary = MmtubePalette.Aqua,
    onPrimary = Color(0xFF03211A),
    primaryContainer = Color(0xFF0F3B31),
    onPrimaryContainer = MmtubePalette.Aqua,
    secondary = MmtubePalette.Blue,
    onSecondary = Color(0xFF0B1533),
    secondaryContainer = Color(0xFF1B2B52),
    onSecondaryContainer = Color(0xFFBCCFFF),
    tertiary = MmtubePalette.Violet,
    onTertiary = Color(0xFF241040),
    tertiaryContainer = Color(0xFF2C2148),
    onTertiaryContainer = Color(0xFFE3D1FF),
    error = MmtubePalette.Danger,
    onError = Color(0xFF3B0A15),
    errorContainer = Color(0xFF471E28),
    onErrorContainer = Color(0xFFFFD3DA),
    background = MmtubePalette.DarkBackground,
    onBackground = MmtubePalette.DarkOnBackground,
    surface = MmtubePalette.DarkSurface,
    onSurface = MmtubePalette.DarkOnSurface,
    surfaceVariant = MmtubePalette.DarkSurfaceHigh,
    onSurfaceVariant = MmtubePalette.DarkMuted,
    outline = MmtubePalette.DarkOutline,
    outlineVariant = MmtubePalette.DarkOutlineSoft,
    inverseSurface = Color(0xFFE7EEFB),
    inverseOnSurface = MmtubePalette.DarkSurface,
    inversePrimary = MmtubePalette.AquaDeep,
    scrim = Color(0xCC000000)
)

private val LightColors = lightColorScheme(
    primary = MmtubePalette.AquaDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC2F5E5),
    onPrimaryContainer = Color(0xFF04382C),
    secondary = MmtubePalette.BlueDeep,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E4FF),
    onSecondaryContainer = Color(0xFF0E2B6B),
    tertiary = MmtubePalette.Violet,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDEFF),
    onTertiaryContainer = Color(0xFF3A2457),
    error = Color(0xFFD92D4B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF561024),
    background = MmtubePalette.LightBackground,
    onBackground = MmtubePalette.LightOnBackground,
    surface = MmtubePalette.LightSurface,
    onSurface = MmtubePalette.LightOnSurface,
    surfaceVariant = MmtubePalette.LightSurfaceHigh,
    onSurfaceVariant = MmtubePalette.LightMuted,
    outline = MmtubePalette.LightOutline,
    outlineVariant = MmtubePalette.LightOutlineSoft,
    inverseSurface = Color(0xFF131E33),
    inverseOnSurface = Color(0xFFEDF2FC),
    inversePrimary = MmtubePalette.Aqua,
    scrim = Color(0x99000000)
)

val LocalMmtubeDark = staticCompositionLocalOf { true }

@Composable
fun MmtubeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() as? ComponentActivity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalMmtubeDark provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography.copy(
                displayLarge = MmtubeType.DisplayLarge,
                headlineLarge = MmtubeType.DisplaySmall,
                headlineMedium = MmtubeType.HeadlineMedium,
                titleLarge = MmtubeType.TitleLarge,
                titleMedium = MmtubeType.TitleMedium,
                bodyLarge = MmtubeType.BodyLarge,
                bodyMedium = MmtubeType.BodyMedium,
                labelLarge = MmtubeType.LabelLarge,
                labelSmall = MmtubeType.LabelSmall
            ),
            content = content
        )
    }
}
