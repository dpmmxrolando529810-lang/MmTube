package com.mmtube.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmtube.app.data.Platform
import com.mmtube.app.ui.theme.LocalMmtubeDark
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType

/** Animated aurora backdrop used behind every screen. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val dark = LocalMmtubeDark.current
    // Static background: no per-frame redraws — much cheaper on low-end devices.
    val drift = 0.5f
    val baseTop = if (dark) MmtubePalette.DarkBackgroundTop else MmtubePalette.LightBackgroundTop
    val baseBottom = if (dark) MmtubePalette.DarkBackground else MmtubePalette.LightBackground
    val glowA = if (dark) MmtubePalette.Aqua.copy(alpha = 0.22f) else MmtubePalette.Aqua.copy(alpha = 0.35f)
    val glowB = if (dark) MmtubePalette.Blue.copy(alpha = 0.20f) else MmtubePalette.Blue.copy(alpha = 0.28f)
    val glowC = if (dark) MmtubePalette.Violet.copy(alpha = 0.14f) else MmtubePalette.Violet.copy(alpha = 0.22f)

    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(baseTop, baseBottom)))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowA, Color.Transparent),
                    center = Offset(w * (0.08f + 0.22f * drift), h * 0.06f),
                    radius = w * 0.85f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowB, Color.Transparent),
                    center = Offset(w * (0.92f - 0.20f * drift), h * 0.24f),
                    radius = w * 0.75f
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowC, Color.Transparent),
                    center = Offset(w * (0.35f + 0.30f * drift), h * 0.78f),
                    radius = w * 0.95f
                )
            )
        }
        content()
    }
}

/** The MMTube wordmark: aqua-to-blue squircle with a play glyph and download tick. */
@Composable
fun MmtubeLogo(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = size * 0.22f,
                shape = shape,
                spotColor = MmtubePalette.Aqua.copy(alpha = 0.45f),
                ambientColor = MmtubePalette.Blue.copy(alpha = 0.35f)
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(MmtubePalette.Aqua, MmtubePalette.Blue),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(size * 0.66f)) {
            val w = this.size.width
            val h = this.size.height
            // Play triangle
            val triangle = Path().apply {
                moveTo(w * 0.30f, h * 0.16f)
                lineTo(w * 0.30f, h * 0.58f)
                lineTo(w * 0.76f, h * 0.37f)
                close()
            }
            drawPath(triangle, Color.White.copy(alpha = 0.96f))
            // Download baseline
            drawLine(
                color = Color.White.copy(alpha = 0.96f),
                start = Offset(w * 0.26f, h * 0.76f),
                end = Offset(w * 0.74f, h * 0.76f),
                strokeWidth = h * 0.085f
            )
        }
    }
}

/** Press-scale wrapper for interactive cards and buttons. */
@Composable
fun PressScale(
    pressed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "pressScale"
    )
    Box(modifier.scale(scale)) { content() }
}

@Composable
fun rememberPressInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

/** Primary gradient call-to-action. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    val dark = LocalMmtubeDark.current
    val gradient = if (enabled) {
        Brush.horizontalGradient(listOf(MmtubePalette.Aqua, MmtubePalette.Blue))
    } else {
        Brush.horizontalGradient(
            listOf(
                (if (dark) MmtubePalette.DarkSurfaceHigher else MmtubePalette.LightSurfaceHigher),
                (if (dark) MmtubePalette.DarkSurfaceHigher else MmtubePalette.LightSurfaceHigher)
            )
        )
    }
    Surface(
        onClick = { if (enabled && !loading) onClick() },
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        enabled = enabled && !loading
    ) {
        Box(
            modifier = Modifier
                .background(gradient)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (loading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.2.dp,
                        color = if (enabled) Color(0xFF03211A) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) Color(0xFF03211A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Text(
                    text = text,
                    style = MmtubeType.LabelLarge.copy(fontSize = 15.sp),
                    color = if (enabled) Color(0xFF03211A) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** Section header with optional action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MmtubeType.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MmtubeType.LabelLarge,
                color = MmtubePalette.Aqua,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            )
        }
    }
}

/** Frosted panel surface used for cards across the app. */
@Composable
fun FrostedCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    border: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val dark = LocalMmtubeDark.current
    val fill = if (dark) MmtubePalette.DarkSurface.copy(alpha = 0.88f) else MmtubePalette.LightSurface.copy(alpha = 0.92f)
    val outline = if (dark) MmtubePalette.DarkOutlineSoft else MmtubePalette.LightOutlineSoft
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(
                if (border) Modifier.border(1.dp, outline, shape) else Modifier
            )
            .padding(18.dp),
        content = content
    )
}

/** Small platform badge (YT / FB / TT / WEB). */
@Composable
fun PlatformBadge(platform: Platform, modifier: Modifier = Modifier) {
    val (label, colors) = when (platform) {
        Platform.YOUTUBE -> "YT" to listOf(Color(0xFFFF4E45), Color(0xFFB31217))
        Platform.FACEBOOK -> "FB" to listOf(Color(0xFF4E9CFF), Color(0xFF1559C7))
        Platform.TIKTOK -> "TT" to listOf(Color(0xFF25F4EE), Color(0xFFFE2C55))
        Platform.OTHER -> "WEB" to listOf(MmtubePalette.Blue, MmtubePalette.AquaDeep)
    }
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MmtubeType.LabelSmall.copy(fontSize = 10.sp, letterSpacing = 0.6.sp),
            color = Color.White
        )
    }
}

/** Thin rounded progress track. */
@Composable
fun LinearBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    active: Boolean = true
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .background(track)
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(height)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(MmtubePalette.Aqua, MmtubePalette.Blue)))
            )
        }
    }
}

@Composable
fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, style = MmtubeType.LabelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MmtubeType.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val LocalReduceMotion = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 9.dp
) {
    val appReduce = LocalReduceMotion.current
    if (appReduce) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.75f))
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

@Immutable
data class ShimmerBrush(val color: Color, val highlight: Color)
