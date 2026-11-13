package com.mmtube.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mmtube.app.BuildConfig
import com.mmtube.app.MainUiState
import com.mmtube.app.MainViewModel
import com.mmtube.app.R
import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.EngineState
import com.mmtube.app.data.HistoryItem
import com.mmtube.app.data.Platform
import com.mmtube.app.data.ProbeResult
import com.mmtube.app.data.ProbeUi
import com.mmtube.app.ui.components.ActiveDownloadCard
import com.mmtube.app.ui.components.CompletedDownloadCard
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.components.GradientButton
import com.mmtube.app.ui.components.MmtubeLogo
import com.mmtube.app.ui.components.PlatformBadge
import com.mmtube.app.ui.components.PulsingDot
import com.mmtube.app.ui.components.QuotaStrip
import com.mmtube.app.ui.components.SectionHeader
import com.mmtube.app.ui.theme.LocalMmtubeDark
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters
import com.mmtube.app.util.UrlUtils
import com.mmtube.app.util.findActivity

@Composable
fun HomeScreen(
    ui: MainUiState,
    vm: MainViewModel,
    onSeeAll: () -> Unit,
    onUpgrade: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    var showBatch by remember { mutableStateOf(false) }
    var batchText by remember { mutableStateOf("") }
    val storageFull = ui.storageFullPrompt
    Box(modifier = Modifier.fillMaxSize()) {
    androidx.compose.runtime.CompositionLocalProvider(com.mmtube.app.ui.components.LocalReduceMotion provides ui.reduceMotion) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header") {
            Header(ui = ui, onSettings = onSettings, onOpenInbox = vm::openInbox)
        }
        if (!ui.settings.onboardingSeen) {
            item(key = "onboarding") {
                OnboardingCard(onDismiss = vm::markOnboardingSeen)
            }
        }
        item(key = "hero") {
            HeroTitle()
        }
        item(key = "quota") {
            QuotaStrip(
                quota = ui.quota,
                account = ui.account,
                onUpgrade = onUpgrade,
                onSignIn = {
                    context.findActivity()?.let { vm.signIn(it) }
                },
                onRefresh = vm::refreshQuota
            )
        }
        if (ui.quota?.paid != true && (ui.quota?.totalDownloads ?: 0) >= 3) {
            item(key = "upsell") {
                PremiumUpsellCard(onUpgrade = onUpgrade)
            }
        }
        if (ui.showTrialBanner) {
            item(key = "trial_banner") {
                SimpleBanner(
                    text = context.getString(R.string.trial_banner_text, daysLeft(ui.account.profile?.trialEndsAt)),
                    actionLabel = context.getString(R.string.trial_banner_go),
                    onAction = onUpgrade
                )
            }
        }
        if (ui.freeLimitReached) {
            item(key = "free_limit") {
                SimpleBanner(
                    text = context.getString(R.string.free_limit_text),
                    actionLabel = context.getString(R.string.free_limit_go),
                    onAction = {
                        vm.dismissFreeLimitReached()
                        onUpgrade()
                    }
                )
            }
        }
        if (ui.announcementText != null) {
            item(key = "announcement") {
                AnnouncementBanner(
                    text = ui.announcementText!!,
                    onDismiss = vm::dismissAnnouncement
                )
            }
        }
        item(key = "link") {
            LinkCard(ui = ui, vm = vm, onBatch = { showBatch = true })
        }
        item(key = "engine") {
            EngineCard(ui = ui, onRetry = vm::retryEngine)
        }
        item(key = "preview") {
            PreviewSection(ui = ui, vm = vm, onUpgrade = onUpgrade)
        }
        item(key = "batch_dialog") {
            BatchPasteDialog(
                show = showBatch,
                onDismiss = { showBatch = false },
                text = batchText,
                onTextChange = { batchText = it },
                onStart = {
                    vm.batchStart(batchText)
                    batchText = ""
                    showBatch = false
                }
            )
        }
        item(key = "download") {
            DownloadSection(ui = ui, vm = vm)
        }
        item(key = "recent") {
            RecentSection(history = ui.history, onSeeAll = onSeeAll, onOpen = vm::openHistory)
        }
        item {
            Text(
                text = context.getString(R.string.platform_support),
                style = MmtubeType.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
    if (ui.showInbox) {
        InboxDialog(ui = ui, vm = vm, onDismiss = vm::dismissInbox)
    }
    }
    if (storageFull) {
        StorageFullDialog(
            onDismiss = vm::dismissStorageFullPrompt,
            onClean = vm::clearOldFilesFromStoragePrompt
        )
    }
}

@Composable
private fun InboxDialog(ui: MainUiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.inbox_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.inboxAnnouncements.isEmpty()) {
                    Text(context.getString(R.string.inbox_empty), style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ui.inboxAnnouncements.take(10).forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(10.dp)
                        ) {
                            Text(item.text, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                Formatters.formatDateBurmese(item.createdAt),
                                style = MmtubeType.LabelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { vm.markInboxShown() }) {
                Text(context.getString(R.string.inbox_mark_read), color = MmtubePalette.Aqua)
            }
        }
    )
}

@Composable
private fun StorageFullDialog(
    onDismiss: () -> Unit,
    onClean: () -> Unit
) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.error_no_space_title)) },
        text = { Text(context.getString(R.string.error_no_space_body)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onClean) {
                Text(context.getString(R.string.storage_full_clean))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(android.R.string.cancel.toString())
            }
        }
    )
}

@Composable
private fun AnnouncementBanner(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MmtubePalette.Amber.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MmtubePalette.Amber.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Rounded.Campaign,
                contentDescription = null,
                tint = MmtubePalette.Amber,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = text,
                style = MmtubeType.BodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = context.getString(R.string.announcement_dismiss), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun daysLeft(trialEndsAt: Long?): Int {
    if (trialEndsAt == null || trialEndsAt <= 0L) return 0
    return ((trialEndsAt - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0)
}

@Composable
private fun SimpleBanner(text: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MmtubePalette.Amber.copy(alpha = 0.16f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MmtubePalette.Amber.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = MmtubePalette.Amber, modifier = Modifier.size(22.dp))
            Text(text, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(actionLabel, color = MmtubePalette.Amber)
            }
        }
    }
}

@Composable
private fun Header(ui: MainUiState, onSettings: () -> Unit, onOpenInbox: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MmtubeLogo(size = 46.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                text = "MMTube",
                style = MmtubeType.HeadlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = androidx.compose.ui.platform.LocalContext.current.getString(R.string.brand_tagline),
                style = MmtubeType.LabelSmall,
                color = MmtubePalette.Aqua
            )
        }
        Box(Modifier.weight(1f))
        Box {
            IconButton(onClick = onOpenInbox) {
                Icon(
                    Icons.Rounded.Notifications,
                    contentDescription = androidx.compose.ui.platform.LocalContext.current.getString(R.string.inbox_title),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            if (ui.unreadAnnouncements > 0) {
                Surface(
                    shape = CircleShape,
                    color = MmtubePalette.Danger,
                    modifier = Modifier.size(18.dp).align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp)
                ) {
                    Text(
                        "${ui.unreadAnnouncements}",
                        color = Color.White,
                        style = MmtubeType.LabelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        IconButton(onClick = onSettings) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = androidx.compose.ui.platform.LocalContext.current.getString(R.string.action_settings),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun BatchPasteDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    onStart: () -> Unit
) {
    if (!show) return
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        FrostedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = LocalContext.current.getString(R.string.batch_title),
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = LocalContext.current.getString(R.string.batch_hint),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MmtubeType.BodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onStart() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                        .height(160.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.End)
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(
                            android.R.string.cancel.toString(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Button(
                        onClick = onStart,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MmtubePalette.AquaDeep
                        )
                    ) {
                        Text(LocalContext.current.getString(R.string.batch_start))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTitle() {
    val context = LocalContext.current
    val dark = LocalMmtubeDark.current
    Column {
        Text(
            text = context.getString(R.string.home_greeting).uppercase(),
            style = MmtubeType.LabelSmall.copy(letterSpacing = 1.6.sp),
            color = MmtubePalette.Aqua
        )
        val accent = if (dark) MmtubePalette.Aqua else MmtubePalette.AquaDeep
        val title = context.getString(R.string.home_title)
        val lines = title.split("\n", limit = 2)
        androidx.compose.material3.Text(
            text = buildAnnotatedString(lines, accent, MaterialTheme.colorScheme.onBackground),
            style = MmtubeType.DisplaySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
        )
        Text(
            text = context.getString(R.string.home_subtitle),
            style = MmtubeType.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun buildAnnotatedString(
    lines: List<String>,
    accent: Color,
    onBackground: Color
) = androidx.compose.ui.text.buildAnnotatedString {
    pushStyle(androidx.compose.ui.text.SpanStyle(color = onBackground))
    append(lines.getOrElse(0) { "" })
    pop()
    if (lines.size > 1) {
        append("\n")
        pushStyle(androidx.compose.ui.text.SpanStyle(brush = Brush.horizontalGradient(listOf(accent, MmtubePalette.Blue))))
        append(lines[1])
        pop()
    }
}

@Composable
private fun LinkCard(ui: MainUiState, vm: MainViewModel, onBatch: () -> Unit) {
    val context = LocalContext.current
    val analyzing = ui.probe is ProbeUi.Loading
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .height(56.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Link,
                contentDescription = null,
                tint = MmtubePalette.Aqua,
                modifier = Modifier.size(21.dp)
            )
            Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (ui.link.isBlank()) {
                    Text(
                        text = context.getString(R.string.link_hint),
                        style = MmtubeType.BodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = ui.link,
                    onValueChange = vm::onLinkChange,
                    singleLine = true,
                    textStyle = MmtubeType.BodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { vm.analyze() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (ui.link.isBlank()) {
                IconButton(onClick = onBatch) {
                    Icon(
                        Icons.Rounded.List,
                        contentDescription = context.getString(R.string.batch_title),
                        tint = MmtubePalette.Blue
                    )
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val pasted = clipboard.primaryClip?.let { clip ->
                        if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else null
                    }
                    pasted?.let(vm::pasteLink)
                }) {
                    Icon(
                        Icons.Rounded.ContentPaste,
                        contentDescription = context.getString(R.string.link_paste),
                        tint = MmtubePalette.Blue
                    )
                }
            } else {
                IconButton(onClick = vm::clearLink) {
                    Icon(
                        Icons.Rounded.Clear,
                        contentDescription = context.getString(R.string.link_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        val detected = ui.detected
        AnimatedVisibility(visible = detected != null) {
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                detected?.let {
                    PlatformBadge(it)
                    Text(
                        text = detectedLabel(context, it),
                        style = MmtubeType.LabelLarge.copy(fontSize = 12.sp),
                        color = MmtubePalette.Aqua
                    )
                }
            }
        }
        GradientButton(
            text = if (analyzing) context.getString(R.string.link_analyzing) else context.getString(R.string.link_analyze),
            onClick = vm::analyze,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            icon = Icons.Rounded.Bolt,
            loading = analyzing,
            enabled = ui.link.isNotBlank() || analyzing
        )
    }
}

private fun detectedLabel(context: android.content.Context, platform: Platform): String = when (platform) {
    Platform.YOUTUBE -> context.getString(R.string.detected_youtube)
    Platform.FACEBOOK -> context.getString(R.string.detected_facebook)
    Platform.TIKTOK -> context.getString(R.string.detected_tiktok)
    Platform.OTHER -> context.getString(R.string.detected_other)
}

@Composable
private fun EngineCard(ui: MainUiState, onRetry: () -> Unit) {
    val context = LocalContext.current
    when (val engine = ui.engine) {
        is EngineState.Ready -> Unit
        is EngineState.Failed -> FrostedCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Rounded.HourglassTop,
                    contentDescription = null,
                    tint = MmtubePalette.Danger,
                    modifier = Modifier.size(28.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(context.getString(R.string.engine_failed_title), style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(engine.message, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                TextButton(onClick = onRetry) { Text(context.getString(R.string.engine_retry), color = MmtubePalette.Aqua) }
            }
        }
        EngineState.Idle, EngineState.Initializing -> {
            val transition = rememberInfiniteTransition(label = "enginePulse")
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                label = "engineAlpha"
            )
            FrostedCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp,
                        color = MmtubePalette.Aqua.copy(alpha = alpha)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(context.getString(R.string.engine_preparing_title), style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = context.getString(R.string.engine_preparing_desc),
                            style = MmtubeType.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            color = MmtubePalette.Aqua,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingCard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.getString(R.string.onboarding_title),
            style = MmtubeType.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = context.getString(R.string.onboarding_body),
            style = MmtubeType.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.material3.Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(12.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MmtubePalette.AquaDeep),
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(context.getString(R.string.onboarding_got_it))
        }
    }
}

@Composable
private fun PremiumUpsellCard(onUpgrade: () -> Unit) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(MmtubePalette.Aqua, MmtubePalette.Blue)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.home_premium_title),
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = context.getString(R.string.home_premium_desc),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Button(
                onClick = onUpgrade,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MmtubePalette.AquaDeep,
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Text(context.getString(R.string.home_premium_cta), style = MmtubeType.LabelLarge)
            }
        }
    }
}

@Composable
private fun PreviewSection(
    ui: MainUiState,
    vm: MainViewModel,
    onUpgrade: () -> Unit
) {
    val context = LocalContext.current
    when (val probe = ui.probe) {
        ProbeUi.Idle -> Unit
        is ProbeUi.Loading -> LoadingPreviewCard(probe)
        is ProbeUi.Error -> ErrorPreviewCard(vm.errorText(probe.kind), vm::analyze)
        is ProbeUi.Success -> {
            val result = probe.result
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                VideoPreviewCard(result)
                QualitySelector(
                    selected = ui.mode,
                    audioOnly = result.audioOnly,
                    onSelect = vm::selectMode
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text(
                        text = context.getString(R.string.quality_source_note),
                        style = MmtubeType.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadAction(ui = ui, vm = vm, result = result, onUpgrade = onUpgrade)
            }
        }
    }
}

@Composable
private fun VideoPreviewCard(result: ProbeResult) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(172.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MmtubePalette.DarkSurfaceHigher, MmtubePalette.DarkSurfaceHigh)
                    )
                )
        ) {
            if (result.thumbnail != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(result.thumbnail).crossfade(true).build(),
                    contentDescription = result.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MmtubePalette.Aqua.copy(alpha = 0.7f),
                    modifier = Modifier.size(64.dp).align(Alignment.Center)
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0xCC000000)
                        )
                    )
            )
            PlatformBadge(result.platform, Modifier.padding(12.dp).align(Alignment.TopStart))
            if (result.durationSeconds > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xB3000000)
                ) {
                    Text(
                        text = Formatters.formatDuration(result.durationSeconds.toLong()),
                        style = MmtubeType.LabelLarge.copy(fontSize = 11.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        Text(
            text = result.title,
            style = MmtubeType.TitleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            modifier = Modifier.padding(top = 14.dp)
        )
        result.uploader?.let {
            Text(
                text = context.getString(R.string.media_by, it),
                style = MmtubeType.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatChipInline(
                text = if (result.audioOnly) context.getString(R.string.media_audio_only) else context.getString(
                    R.string.media_up_to,
                    Formatters.qualityLabel(result.maxHeight, result.maxFps)
                )
            )
            if (result.estimatedBytes > 0) {
                StatChipInline(text = "~${Formatters.formatBytes(result.estimatedBytes)}")
            }
        }
    }
}

@Composable
private fun StatChipInline(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    ) {
        Text(
            text = text,
            style = MmtubeType.LabelLarge.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun QualitySelector(
    selected: DownloadMode,
    audioOnly: Boolean,
    onSelect: (DownloadMode) -> Unit
) {
    val context = LocalContext.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.quality_title),
                style = MmtubeType.TitleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = context.getString(R.string.quality_source_short),
                style = MmtubeType.LabelSmall,
                color = MmtubePalette.Aqua
            )
        }
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QualityOption(DownloadMode.MAX, context.getString(R.string.quality_max), context.getString(R.string.quality_max_desc), selected, audioOnly, onSelect, Modifier.weight(2f))
            QualityOption(DownloadMode.MP3, context.getString(R.string.quality_mp3), context.getString(R.string.quality_mp3_desc), selected, audioOnly, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QualityOption(
    mode: DownloadMode,
    label: String,
    description: String?,
    selected: DownloadMode,
    audioOnly: Boolean,
    onSelect: (DownloadMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = !audioOnly || mode == DownloadMode.MP3
    val isSelected = selected == mode
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .then(
                if (isSelected) {
                    Modifier.background(Brush.linearGradient(listOf(MmtubePalette.Aqua, MmtubePalette.Blue)))
                } else {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                }
            )
            .then(
                if (enabled) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = androidx.compose.foundation.LocalIndication.current
                ) { onSelect(mode) } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (mode == DownloadMode.MP3) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (isSelected) Color(0xFF03211A) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(end = 0.dp)
                )
            }
            Text(
                text = label,
                style = MmtubeType.LabelLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    isSelected -> Color(0xFF03211A)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(start = if (mode == DownloadMode.MP3) 2.dp else 0.dp)
            )
        }
    }
}

@Composable
private fun DownloadAction(
    ui: MainUiState,
    vm: MainViewModel,
    result: ProbeResult,
    onUpgrade: () -> Unit
) {
    val context = LocalContext.current
    val quota = ui.quota
    val paid = quota?.paid == true
    val allowed = quota?.allowed == true
    val activeDownload = when (ui.download) {
        is DownloadState.Preparing, is DownloadState.Running,
        is DownloadState.Merging, is DownloadState.Saving -> true
        else -> false
    }
    val mode = ui.mode
    val label = vm.qualityLabel(result, mode)
    val buttonText = when {
        paid -> context.getString(R.string.download_unlimited)
        !allowed -> context.getString(R.string.download_limit_reached)
        mode == DownloadMode.MAX -> context.getString(R.string.download_button)
        else -> context.getString(R.string.download_button_quality, label)
    }
    GradientButton(
        text = buttonText,
        onClick = vm::startDownload,
        modifier = Modifier.fillMaxWidth(),
        icon = Icons.Rounded.Download,
        enabled = allowed && !activeDownload
    )
    when {
        paid -> Row(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MmtubePalette.Aqua, modifier = Modifier.size(15.dp))
            Text(
                text = context.getString(R.string.download_unlimited),
                style = MmtubeType.LabelSmall,
                color = MmtubePalette.Aqua,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        !allowed -> Column(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            val remaining = quota?.remainingMs(com.mmtube.app.ui.components.rememberNow()) ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.HourglassTop, contentDescription = null, tint = MmtubePalette.Amber, modifier = Modifier.size(15.dp))
                Text(
                    text = context.getString(R.string.quota_next, Formatters.formatCountdown(remaining)),
                    style = MmtubeType.LabelLarge,
                    color = MmtubePalette.Amber,
                    modifier = Modifier.padding(start = 6.dp).clickable(onClick = onUpgrade)
                )
            }
            TextButton(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                Text(context.getString(R.string.see_plan), color = MmtubePalette.Blue)
            }
        }
        else -> Unit
    }
}

@Composable
private fun LoadingPreviewCard(loading: ProbeUi.Loading) {
    val context = LocalContext.current
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(loading.startedAt) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - loading.startedAt) / 1000L).toInt()
            kotlinx.coroutines.delay(500)
        }
    }
    val stage = when {
        elapsed < 3 -> context.getString(R.string.analyze_stage_connecting)
        elapsed < 10 -> context.getString(R.string.analyze_stage_formats)
        else -> context.getString(R.string.analyze_stage_slow)
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "shimmerAlpha"
    )
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(172.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(0.8f)
                .height(20.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth(0.5f)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MmtubePalette.Aqua
            )
            Text(
                text = "$stage  ${elapsed}s",
                style = MmtubeType.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorPreviewCard(message: String, onRetry: () -> Unit) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MmtubePalette.Danger,
                modifier = Modifier.size(30.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = context.getString(R.string.analyze_failed),
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = context.getString(R.string.action_retry), tint = MmtubePalette.Aqua)
            }
        }
    }
}

@Composable
private fun DownloadSection(ui: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    ui.queuedTitle?.let { queuedTitle ->
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Rounded.HourglassTop,
                    contentDescription = null,
                    tint = MmtubePalette.Blue,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = context.getString(R.string.queued_banner, queuedTitle),
                    style = MmtubeType.LabelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
    when (val state = ui.download) {
        is DownloadState.Preparing,
        is DownloadState.Running,
        is DownloadState.Merging,
        is DownloadState.Saving -> ActiveDownloadCard(state = state, onCancel = vm::cancelDownload)

        is DownloadState.Completed -> CompletedDownloadCard(
            item = state.item,
            onOpen = { vm.openHistory(state.item) },
            onShare = { vm.shareHistory(state.item) },
            onNew = vm::dismissCompleted
        )

        is DownloadState.Paused -> {
            ActiveDownloadCard(
                state = state,
                onCancel = vm::cancelDownload,
                onResume = vm::resumeDownload
            )
        }
        is DownloadState.Failed -> {
            FrostedCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Rounded.HourglassTop,
                        contentDescription = null,
                        tint = MmtubePalette.Danger,
                        modifier = Modifier.size(28.dp).padding(end = 2.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = context.getString(R.string.download_status_failed),
                            style = MmtubeType.TitleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = vm.errorText(state.kind),
                            style = MmtubeType.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    text = detail.take(600),
                                    style = MmtubeType.LabelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                    TextButton(onClick = { vm.retryDownload() }) {
                        Text(context.getString(R.string.action_retry), color = MmtubePalette.Aqua)
                    }
                }
            }
        }

        DownloadState.Idle, DownloadState.Cancelled -> Unit
    }
}

@Composable
private fun RecentSection(
    history: List<HistoryItem>,
    onSeeAll: () -> Unit,
    onOpen: (HistoryItem) -> Unit
) {
    val context = LocalContext.current
    if (history.isEmpty()) {
        FrostedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.empty_recent),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    Column {
        SectionHeader(
            title = context.getString(R.string.section_recent),
            actionLabel = context.getString(R.string.section_see_all),
            onAction = onSeeAll,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(history.take(6), key = { it.id }) { item ->
                RecentCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun RecentCard(item: HistoryItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val audio = item.mode == "mp3" || item.mimeType.startsWith("audio")
    Column(
        modifier = Modifier
            .size(160.dp, 150.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
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
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(30.dp))
            }
        }
        Text(
            text = item.title,
            style = MmtubeType.LabelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = "${item.quality} • ${Formatters.formatBytes(item.sizeBytes)}",
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
