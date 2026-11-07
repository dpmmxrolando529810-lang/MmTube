package com.mmtube.admin.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mmtube.admin.util.findActivity

private val AdminColors = darkColorScheme(
    primary = Color(0xFFFFB84D),
    onPrimary = Color(0xFF3A2500),
    primaryContainer = Color(0xFF4A320F),
    onPrimaryContainer = Color(0xFFFFDFA0),
    secondary = Color(0xFF2FE3C0),
    onSecondary = Color(0xFF03211A),
    secondaryContainer = Color(0xFF0F3B31),
    onSecondaryContainer = Color(0xFFAFFFF0),
    tertiary = Color(0xFF8AB4FF),
    onTertiary = Color(0xFF071B3C),
    error = Color(0xFFFF6B81),
    onError = Color(0xFF40000E),
    errorContainer = Color(0xFF451E27),
    onErrorContainer = Color(0xFFFFD3DB),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFF2F5FF),
    surface = Color(0xFF0C1322),
    onSurface = Color(0xFFE4E9F7),
    surfaceVariant = Color(0xFF131E33),
    onSurfaceVariant = Color(0xFF8B97B5),
    outline = Color(0xFF26354E),
    outlineVariant = Color(0xFF1B2740),
    scrim = Color(0xDD000000)
)

@Composable
fun AdminTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = AdminColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
