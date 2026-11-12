package com.mmtube.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mmtube.app.R
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.HistoryItem
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters

@Composable
fun ActiveDownloadCard(
    state: DownloadState,
    onCancel: () -> Unit,
    onResume: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val title = when (state) {
        is DownloadState.Preparing -> state.title
        is DownloadState.Running -> state.title
        is DownloadState.Merging -> state.title
        is DownloadState.Saving -> state.title
        is DownloadState.Paused -> state.title
        else -> context.getString(R.string.download_status_preparing)
    }
    val isRunning = state is DownloadState.Running
    val isPaused = state is DownloadState.Paused
    val rawProgress = when {
        isRunning -> (state as DownloadState.Running).progress
        isPaused -> (state as DownloadState.Paused).progress
        else -> null
    }
    val progress = rawProgress?.div(100f)
    val statusText = when (state) {
        is DownloadState.Preparing -> context.getString(R.string.download_status_preparing)
        is DownloadState.Running -> "${Formatters.progressPercent(state.progress)}%"
        is DownloadState.Merging -> context.getString(R.string.download_status_merging)
        is DownloadState.Saving -> context.getString(R.string.download_status_saving)
        is DownloadState.Paused -> context.getString(R.string.download_status_paused)
        else -> ""
    }
    FrostedCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(MmtubePalette.Aqua.copy(alpha = 0.25f), MmtubePalette.Blue.copy(alpha = 0.25f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (progress == null && !isPaused) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = MmtubePalette.Aqua
                    )
                } else {
                    Text(
                        text = statusText,
                        style = MmtubeType.LabelLarge,
                        color = if (isPaused) MmtubePalette.Amber else MmtubePalette.Aqua
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = context.getString(R.string.downloads_active),
                        style = MmtubeType.LabelSmall,
                        color = MmtubePalette.Aqua,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    PulsingDot(MmtubePalette.Aqua, Modifier.padding(start = 6.dp, bottom = 2.dp), size = 7.dp)
                }
                Text(
                    text = title,
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (state is DownloadState.Running) {
                    val meta = buildList {
                        if (state.etaSeconds >= 0) add(context.getString(R.string.download_eta, Formatters.formatEta(state.etaSeconds)))
                        if (state.speedBytes > 0) add(context.getString(R.string.download_speed, Formatters.formatBytes(state.speedBytes)))
                        if (state.totalBytes > 0) add("/ ${Formatters.formatBytes(state.totalBytes)}")
                    }.joinToString("  •  ")
                    Text(meta, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (isPaused) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = context.getString(R.string.download_resume), tint = MmtubePalette.Aqua)
                }
            } else if (isRunning) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.HourglassTop, contentDescription = context.getString(R.string.download_pause), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = context.getString(R.string.action_cancel), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (progress != null) {
            LinearBar(progress, Modifier.fillMaxWidth().padding(top = 12.dp))
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = MmtubePalette.Aqua,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun CompletedDownloadCard(
    item: HistoryItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { visible = true }
    val checkScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "check"
    )
    FrostedCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .scale(checkScale)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MmtubePalette.Success.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MmtubePalette.Success, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.download_status_complete),
                    style = MmtubeType.LabelSmall,
                    color = MmtubePalette.Success
                )
                Text(
                    text = item.title,
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                Text(
                    text = buildString {
                        append(context.getString(R.string.download_quality_label, item.quality, Formatters.formatBytes(item.sizeBytes)))
                        if (item.downloadDurationMs > 0) {
                            append(" · ").append(Formatters.formatEta(item.downloadDurationMs / 1000))
                        }
                    },
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionChip(context.getString(R.string.action_open), Icons.Rounded.FolderOpen, onOpen, filled = true)
            ActionChip(context.getString(R.string.action_share), Icons.Rounded.Share, onShare, filled = false)
            ActionChip(context.getString(R.string.download_new), Icons.Rounded.DownloadForOffline, onNew, filled = false)
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    filled: Boolean
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (filled) MmtubePalette.AquaDeep else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (filled) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MmtubeType.LabelLarge.copy(fontSize = 12.sp))
        }
    }
}

@Composable
fun HistoryRow(
    item: HistoryItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audio = item.mode == "mp3" || item.mimeType.startsWith("audio")
    FrostedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(92.dp, 66.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpen)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(MmtubePalette.DarkSurfaceHigh, MmtubePalette.DarkSurfaceHigher)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (audio) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MmtubePalette.Aqua, modifier = Modifier.size(30.dp))
                } else if (item.thumbnail != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(item.thumbnail).crossfade(true).build(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(92.dp, 66.dp)
                    )
                } else {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(30.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = context.getString(
                        R.string.download_quality_label,
                        item.quality,
                        Formatters.formatBytes(item.sizeBytes)
                    ),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = Formatters.formatDate(item.createdAt),
                    style = MmtubeType.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = context.getString(R.string.action_open), tint = MmtubePalette.Aqua)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = context.getString(R.string.action_share), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = context.getString(R.string.action_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
