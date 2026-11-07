package com.mmtube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Dialog
import androidx.compose.material3.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mmtube.app.R
import com.mmtube.app.data.UpdateInfo
import com.mmtube.app.data.UpdateUi
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters

@Composable
fun UpdateDialog(
    state: UpdateUi,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onOpenPage: (UpdateInfo) -> Unit
) {
    val info = state.info ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(
        onDismissRequest = { if (!state.downloading) onLater() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !state.downloading)
    ) {
        FrostedCard(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(MmtubePalette.Aqua, MmtubePalette.Blue)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = Color(0xFF03211A), modifier = Modifier.size(30.dp))
                }
                Column {
                    Text(
                        text = if (state.file != null) context.getString(R.string.update_ready)
                        else context.getString(R.string.update_available_title),
                        style = MmtubeType.TitleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "MMTube ${info.version} • ${Formatters.formatBytes(info.sizeBytes)}",
                        style = MmtubeType.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                state.downloading -> {
                    Text(
                        text = context.getString(R.string.update_downloading, state.progress),
                        style = MmtubeType.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        color = MmtubePalette.Aqua,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                state.error != null -> {
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MmtubePalette.Danger, modifier = Modifier.size(18.dp))
                        Text(
                            text = state.error,
                            style = MmtubeType.BodyMedium,
                            color = MmtubePalette.Danger
                        )
                    }
                }
                info.notes.isNotBlank() -> {
                    Text(
                        text = info.notes.take(500),
                        style = MmtubeType.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onOpenPage(info) }, modifier = Modifier.weight(1f, fill = false)) {
                    Text("GitHub", color = MmtubePalette.Blue)
                }
                if (state.file != null) {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.AquaDeep, contentColor = Color.White)
                    ) { Text(context.getString(R.string.update_install)) }
                } else {
                    TextButton(onClick = onLater) {
                        Text(context.getString(R.string.update_later))
                    }
                    Button(
                        onClick = onDownload,
                        enabled = !state.downloading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.AquaDeep, contentColor = Color.White)
                    ) { Text(context.getString(R.string.update_download)) }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MmtubeType.TitleLarge) },
        text = { Text(body, style = MmtubeType.BodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) MmtubePalette.Danger else MmtubePalette.Aqua)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    )
}
