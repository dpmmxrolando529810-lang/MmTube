package com.mmtube.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mmtube.app.MainUiState
import com.mmtube.app.MainViewModel
import com.mmtube.app.R
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.HistoryItem
import com.mmtube.app.ui.components.ActiveDownloadCard
import com.mmtube.app.ui.components.ConfirmDialog
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.components.HistoryRow
import com.mmtube.app.ui.components.SectionHeader
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters
import sh.calvin.reorderable.ReorderableColumn
import androidx.compose.runtime.key

@Composable
fun DownloadsScreen(
    ui: MainUiState,
    vm: MainViewModel
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    var cleanupOldConfirm by remember { mutableStateOf(false) }

    val active = ui.download
    val hasActive = active is DownloadState.Preparing || active is DownloadState.Running ||
        active is DownloadState.Merging || active is DownloadState.Saving ||
        active is DownloadState.Paused

    val filtered = ui.history.filter { item ->
        when (filter) {
            1 -> !item.mimeType.startsWith("audio")
            2 -> item.mimeType.startsWith("audio")
            else -> true
        }
    }
    val totalBytes = ui.history.sumOf { it.sizeBytes }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = context.getString(R.string.downloads_title),
                    style = MmtubeType.DisplaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = context.getString(R.string.downloads_subtitle),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (hasActive && active != null) {
            item {
                SectionHeader(
                    title = context.getString(R.string.downloads_active),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 0.dp)
                )
            }
            item {
                ActiveDownloadCard(state = active, onCancel = vm::cancelDownload, onResume = vm::resumeDownload)
            }
        }
        if (ui.queue.isNotEmpty()) {
            item {
                SectionHeader(
                    title = context.getString(R.string.queue_section, ui.queue.size),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 0.dp)
                )
            }
            item {
                QueuedDownloadsCard(queue = ui.queue, vm = vm)
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Storage, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(16.dp))
                        Text(
                            text = context.getString(R.string.storage_used, Formatters.formatBytes(totalBytes)),
                            style = MmtubeType.LabelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (ui.history.isNotEmpty()) {
                        val audios = ui.history.count { it.mimeType.startsWith("audio") }
                        val videos = ui.history.size - audios
                        Text(
                            text = context.getString(R.string.download_stats, ui.history.size, videos, audios, Formatters.formatBytes(totalBytes)),
                            style = MmtubeType.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                        )
                    }
                }
                if (ui.history.isNotEmpty()) {
                    Column {
                        androidx.compose.material3.TextButton(onClick = { cleanupOldConfirm = true }) {
                            Text(context.getString(R.string.history_cleanup_old), color = MmtubePalette.Amber, style = MmtubeType.LabelSmall)
                        }
                        androidx.compose.material3.TextButton(onClick = { clearAllConfirm = true }) {
                            Text(
                                context.getString(R.string.history_clear_all),
                                color = MmtubePalette.Danger,
                                style = MmtubeType.LabelLarge
                            )
                        }
                    }
                }
            }
        }
        item {
            if (ui.history.isNotEmpty()) {
                WeeklyBarChart(history = ui.history)
            }
        }
        item {
            androidx.compose.material3.TabRow(
                selectedTabIndex = filter,
                containerColor = Color.Transparent,
                contentColor = MmtubePalette.Aqua
            ) {
                androidx.compose.material3.Tab(
                    selected = filter == 0,
                    onClick = { filter = 0 },
                    text = { Text(context.getString(R.string.downloads_filter_all)) }
                )
                androidx.compose.material3.Tab(
                    selected = filter == 1,
                    onClick = { filter = 1 },
                    text = { Text(context.getString(R.string.downloads_filter_video)) }
                )
                androidx.compose.material3.Tab(
                    selected = filter == 2,
                    onClick = { filter = 2 },
                    text = { Text(context.getString(R.string.downloads_filter_audio)) }
                )
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    title = context.getString(R.string.downloads_empty_title),
                    description = context.getString(R.string.downloads_empty_desc)
                )
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                HistoryRow(
                    item = item,
                    onOpen = { vm.openHistory(item) },
                    onShare = { vm.shareHistory(item) },
                    onDelete = { pendingDelete = item }
                )
            }
        }
    }

    if (cleanupOldConfirm) {
        ConfirmDialog(
            title = context.getString(R.string.history_cleanup_old),
            body = context.getString(R.string.history_cleanup_old_confirm),
            confirmLabel = context.getString(R.string.history_cleanup_old),
            onConfirm = {
                vm.clearOldHistory()
                cleanupOldConfirm = false
            },
            onDismiss = { cleanupOldConfirm = false },
            danger = true
        )
    }
    if (clearAllConfirm) {
        ConfirmDialog(
            title = context.getString(R.string.history_clear_all),
            body = context.getString(R.string.history_clear_all_confirm),
            confirmLabel = context.getString(R.string.action_delete),
            onConfirm = {
                vm.clearAllHistory()
                clearAllConfirm = false
            },
            onDismiss = { clearAllConfirm = false },
            danger = true
        )
    }
    pendingDelete?.let { item ->
        ConfirmDialog(
            title = context.getString(R.string.download_delete_title),
            body = context.getString(R.string.download_delete_desc),
            confirmLabel = context.getString(R.string.download_delete_confirm),
            onConfirm = {
                vm.deleteHistory(item)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
            danger = true
        )
    }
}

@Composable
private fun QueuedDownloadsCard(
    queue: List<com.mmtube.app.download.DownloadRequest>,
    vm: MainViewModel
) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        ReorderableColumn(
            list = queue,
            onSettle = { fromIndex, toIndex -> vm.moveQueueItem(fromIndex, toIndex) },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) { index, request, isDragging ->
            key(request.queueId) {
                ReorderableItem {
                    QueueRow(
                        index = index,
                        request = request,
                        dragging = isDragging,
                        dragHandle = {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle()
                            ) {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = context.getString(R.string.queue_drag_handle),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onRemove = { idx -> vm.removeFromQueue(idx) }
                    )
                }
            }
        }
        Text(
            text = context.getString(R.string.queue_drag_hint),
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun QueueRow(
    index: Int,
    request: com.mmtube.app.download.DownloadRequest,
    dragging: Boolean,
    dragHandle: @Composable () -> Unit,
    onRemove: (Int) -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dragging) MmtubePalette.Blue.copy(alpha = 0.18f) else Color(0xFF141E33),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (dragging) MmtubePalette.Aqua else Color(0xFF26354E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            dragHandle()
            Column(Modifier.weight(1f)) {
                Text(
                    text = request.title,
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "#${index + 1} · ${request.qualityLabel}",
                    style = MmtubeType.LabelSmall,
                    color = MmtubePalette.Aqua
                )
            }
            IconButton(
                onClick = { onRemove(index) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Rounded.Clear,
                    contentDescription = context.getString(R.string.queue_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WeeklyBarChart(history: List<HistoryItem>) {
    val context = LocalContext.current
    val days = remember {
        (0..6).map { offset ->
            val dayStart = java.util.Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis() - offset * 24L * 3600 * 1000
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            dayStart
        }.reversed()
    }
    val counts = days.map { day -> history.count { item ->
        val c = java.util.Calendar.getInstance().apply { timeInMillis = item.createdAt }
        c.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR) &&
            c.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR)
    } }
    val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val burmeseDays = listOf("တနင်္ဂ", "စနေ", "သောကြာ", "ကြာသပ", "ဗုဒ္ဓ", "အင်္ဂါ", "တနင်္လာ")
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Text(context.getString(R.string.week_chart_title), style = MmtubeType.LabelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(84.dp)) {
            val slotW = size.width / counts.size
            counts.forEachIndexed { index, count ->
                val barH = (count.toFloat() / maxCount) * (size.height - 12f)
                val x = index * slotW + slotW * 0.25f
                drawRect(
                    color = androidx.compose.ui.graphics.Color(0xFF2FE3C0),
                    topLeft = androidx.compose.ui.geometry.Offset(x, size.height - barH),
                    size = androidx.compose.ui.geometry.Size(slotW * 0.5f, barH)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEach { day ->
                Text(burmeseDays[(day.get(java.util.Calendar.DAY_OF_WEEK) + 4) % 7], style = MmtubeType.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, description: String) {
    FrostedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.FolderOff, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(36.dp))
            }
            Text(title, style = MmtubeType.TitleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description,
                style = MmtubeType.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
