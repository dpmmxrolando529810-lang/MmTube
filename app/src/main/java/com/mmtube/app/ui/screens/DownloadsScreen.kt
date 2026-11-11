package com.mmtube.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

@Composable
fun DownloadsScreen(
    ui: MainUiState,
    vm: MainViewModel
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }

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
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(16.dp))
                    Text(
                        text = context.getString(R.string.storage_used, Formatters.formatBytes(totalBytes)),
                        style = MmtubeType.LabelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (ui.history.isNotEmpty()) {
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
        item {
            FilterRow(selected = filter, onSelect = { filter = it })
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
private fun FilterRow(selected: Int, onSelect: (Int) -> Unit) {
    val context = LocalContext.current
    val options = listOf(
        context.getString(R.string.downloads_filter_all),
        context.getString(R.string.downloads_filter_video),
        context.getString(R.string.downloads_filter_audio)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            val isSelected = selected == index
            Surface(
                onClick = { onSelect(index) },
                shape = CircleShape,
                color = if (isSelected) MmtubePalette.AquaDeep else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
            ) {
                Text(
                    text = label,
                    style = MmtubeType.LabelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
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
