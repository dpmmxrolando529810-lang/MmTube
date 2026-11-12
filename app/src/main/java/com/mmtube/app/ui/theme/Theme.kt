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

private val AmoledColors = darkColorScheme(
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
    background = Color(0xFF000000),
    onBackground = MmtubePalette.DarkOnBackground,
    surface = Color(0xFF000000),
    onSurface = MmtubePalette.DarkOnSurface,
    surfaceVariant = Color(0xFF12171F),
    onSurfaceVariant = MmtubePalette.DarkMuted,
    outline = Color(0xFF2A323E),
    outlineVariant = Color(0xFF1E242E),
    inverseSurface = Color(0xFFE7EEFB),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = MmtubePalette.AquaDeep,
    scrim = Color(0xCC000000)
)

// Colorblind-safe palette (Okabe-Ito) — orange / blue / teal-green / vermillion.
private val ColorblindDark = darkColorScheme(
    primary = Color(0xFFE69F00),       // orange
    onPrimary = Color(0xFF2B1B00),
    primaryContainer = Color(0xFF3D2A00),
    onPrimaryContainer = Color(0xFFFFE0A2),
    secondary = Color(0xFF56B4E9),     // sky blue
    onSecondary = Color(0xFF062A3F),
    secondaryContainer = Color(0xFF0E2B3F),
    onSecondaryContainer = Color(0xFFCBE8FF),
    tertiary = Color(0xFF009E73),      // bluish green
    onTertiary = Color(0xFF003326),
    tertiaryContainer = Color(0xFF0F3326),
    onTertiaryContainer = Color(0xFFA8F0D2),
    error = Color(0xFFD55E00),         // vermillion
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF401600),
    onErrorContainer = Color(0xFFFFCCB8),
    background = Color(0xFF11161D),
    onBackground = Color(0xFFF2F5F9),
    surface = Color(0xFF161E28),
    onSurface = Color(0xFFE6ECF4),
    surfaceVariant = Color(0xFF1E2835),
    onSurfaceVariant = Color(0xFFAEBBCC),
    outline = Color(0xFF33404F),
    outlineVariant = Color(0xFF232C38),
    inverseSurface = Color(0xFFE6ECF4),
    inverseOnSurface = Color(0xFF11161D),
    inversePrimary = Color(0xFFE69F00),
    scrim = Color(0xCC000000)
)

private val ColorblindLight = lightColorScheme(
    primary = Color(0xFFB57600),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE1A4),
    onPrimaryContainer = Color(0xFF3E2A00),
    secondary = Color(0xFF0072B2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8FF),
    onSecondaryContainer = Color(0xFF062B43),
    tertiary = Color(0xFF00775B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA8F0D2),
    onTertiaryContainer = Color(0xFF003326),
    error = Color(0xFFD55E00),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDCC4),
    onErrorContainer = Color(0xFF2F1500),
    background = Color(0xFFFAFBFD),
    onBackground = Color(0xFF1B2330),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F2734),
    surfaceVariant = Color(0xFFE8ECF3),
    onSurfaceVariant = Color(0xFF515E70),
    outline = Color(0xFFB4BECB),
    outlineVariant = Color(0xFFDFE4EB),
    inverseSurface = Color(0xFF1B2330),
    inverseOnSurface = Color(0xFFF5F7FB),
    inversePrimary = Color(0xFFFFE1A4),
    scrim = Color(0x99000000)
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
    colorblindMode: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AMOLED -> true
    }
    val colorScheme = if (dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val context = androidx.compose.ui.platform.LocalContext.current
        if (darkTheme) androidx.compose.material3.dynamicDarkColorScheme(context)
        else androidx.compose.material3.dynamicLightColorScheme(context)
    } else when {
        colorblindMode -> if (darkTheme) ColorblindDark else ColorblindLight
        themeMode == ThemeMode.AMOLED -> AmoledColors
        else -> if (darkTheme) DarkColors else LightColors
    }

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
