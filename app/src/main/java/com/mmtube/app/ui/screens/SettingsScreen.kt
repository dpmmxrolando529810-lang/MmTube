package com.mmtube.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmtube.app.BuildConfig
import com.mmtube.app.MainUiState
import com.mmtube.app.MainViewModel
import com.mmtube.app.R
import com.mmtube.app.data.EngineState
import com.mmtube.app.data.FirebaseConfig
import com.mmtube.app.data.ThemeMode
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.copyText

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    ui: MainUiState,
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenOwner: () -> Unit = {}
) {
    val context = LocalContext.current
    var showLanguage by remember { mutableStateOf(false) }
    var showHelp1 by remember { mutableStateOf(false) }
    var showHelp2 by remember { mutableStateOf(false) }
    var showHelp3 by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showAutostart by remember { mutableStateOf(false) }
    var showVersionDetails by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var ownerTaps by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = context.getString(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column {
                Text(
                    text = context.getString(R.string.settings_title),
                    style = MmtubeType.HeadlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = context.getString(R.string.settings_subtitle),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(context.getString(R.string.settings_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        SettingsGroup(context.getString(R.string.settings_appearance), visibleIf = qMatch(query, listOf("appearance", "theme", "color", "dark", "light", "amoled", "dynamic", "material you", "language", "motion", "အရောင်", "အမှောင်", "ဘာသာ", "အရောင်စည်း", "dynamic color", "လှုပ်ရှား"))) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(context.getString(R.string.settings_theme), style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                ThemeSelector(selected = ui.settings.theme, onSelect = vm::setTheme)
            }
            SettingsClickRow(
                icon = Icons.Rounded.Language,
                label = context.getString(R.string.settings_language),
                value = languageLabel(context, ui.settings.language),
                onClick = { showLanguage = true }
            )
            SettingsSwitchRow(
                icon = Icons.Rounded.Stop,
                label = context.getString(R.string.settings_reduce_motion),
                description = context.getString(R.string.settings_reduce_motion_desc),
                checked = ui.reduceMotion,
                onChange = vm::setReduceMotion
            )
            SettingsSwitchRow(
                icon = Icons.Rounded.AutoAwesome,
                label = context.getString(R.string.settings_dynamic_color),
                description = context.getString(R.string.settings_dynamic_color_desc),
                checked = ui.settings.dynamicColor,
                onChange = vm::setDynamicColor
            )
            SettingsSwitchRow(
                icon = Icons.Rounded.Speed,
                label = context.getString(R.string.settings_colorblind),
                description = context.getString(R.string.settings_colorblind_desc),
                checked = ui.settings.colorblindMode,
                onChange = vm::setColorblindMode
            )
        }

        SettingsGroup(context.getString(R.string.settings_downloads), visibleIf = qMatch(query, listOf("download", "turbo", "မြန်", "မြန်ဆန်", "ဒေါင်း", "အရည်အသွေး", "quality"))) {
            SettingsSwitchRow(
                icon = Icons.Rounded.Speed,
                label = context.getString(R.string.settings_turbo),
                description = context.getString(R.string.settings_turbo_desc),
                checked = ui.settings.turbo,
                onChange = vm::setTurbo
            )
            SettingsValueRow(
                icon = Icons.Rounded.Download,
                label = context.getString(R.string.settings_quality),
                value = context.getString(R.string.settings_quality_desc)
            )
        }

        SettingsGroup(context.getString(R.string.settings_engine), visibleIf = qMatch(query, listOf("engine", "yt-dlp", "update", "အင်ဂျင်", "version", "update engine"))) {
            val engineValue = when (val e = ui.engine) {
                is EngineState.Ready -> e.version ?: context.getString(R.string.settings_engine_ready)
                is EngineState.Failed -> context.getString(R.string.settings_engine_failed_short)
                else -> context.getString(R.string.settings_engine_starting)
            }
            SettingsValueRow(
                icon = Icons.Rounded.VerifiedUser,
                label = context.getString(R.string.settings_engine_version),
                value = engineValue
            )
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = vm::updateEngine,
                    enabled = !ui.engineUpdating,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.BlueDeep, contentColor = Color.White)
                ) {
                    Text(
                        if (ui.engineUpdating) context.getString(R.string.settings_engine_updating)
                        else context.getString(R.string.settings_engine_update)
                    )
                }
                if (ui.engineUpdating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(start = 12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                        color = MmtubePalette.Aqua
                    )
                }
            }
            SettingsSwitchRow(
                icon = Icons.Rounded.Sync,
                label = context.getString(R.string.settings_auto_engine),
                description = context.getString(R.string.settings_auto_engine_desc),
                checked = ui.settings.autoEngineUpdate,
                onChange = vm::setAutoEngineUpdate
            )
        }

        SettingsGroup(context.getString(R.string.settings_updates), visibleIf = qMatch(query, listOf("update", "app update", "auto", "အပ်ဒိတ်", "self update"))) {
            SettingsSwitchRow(
                icon = Icons.Rounded.SystemUpdate,
                label = context.getString(R.string.settings_updates),
                description = context.getString(R.string.update_later_desc),
                checked = ui.settings.autoAppUpdate,
                onChange = vm::setAutoAppUpdate
            )
            Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                Button(
                    onClick = { vm.checkForUpdates(silent = false) },
                    enabled = !ui.update.checking,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.AquaDeep, contentColor = Color.White)
                ) {
                    Text(if (ui.update.checking) context.getString(R.string.settings_checking) else context.getString(R.string.settings_check_update))
                }
            }
            val updateInfo = ui.update.info
            if (updateInfo != null) {
                SettingsValueRow(
                    icon = Icons.Rounded.AutoAwesome,
                    label = context.getString(R.string.settings_update_ready, updateInfo.version),
                    value = "GitHub Releases"
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            // Hidden owner entry: seven taps on the Version row.
                            ownerTaps += 1
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            when {
                                ownerTaps >= 7 -> {
                                    ownerTaps = 0
                                    onOpenOwner()
                                }

                                ownerTaps >= 5 -> Toast.makeText(
                                    context,
                                    "နောက်ထပ် ${7 - ownerTaps} ချက်",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onLongClick = {
                            ownerTaps = 0
                            onOpenOwner()
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsIcon(Icons.Rounded.Info)
                Column(Modifier.weight(1f)) {
                    Text(
                        context.getString(R.string.settings_version),
                        style = MmtubeType.TitleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MmtubeType.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SettingsGroup(context.getString(R.string.settings_notifications), visibleIf = qMatch(query, listOf("notification", "notifications", "chime", "sound", "alert", "notify", "အကြောင်းကြား", "အသံ"))) {
            SettingsSwitchRow(
                icon = Icons.Rounded.Campaign,
                label = context.getString(R.string.notification_chime),
                description = context.getString(R.string.notification_chime_desc),
                checked = ui.settings.chimeEnabled,
                onChange = vm::setChimeEnabled
            )
            SettingsClickRow(
                icon = Icons.Rounded.NotificationsActive,
                label = context.getString(R.string.notification_permission),
                value = if (isNotificationsAllowed(context)) context.getString(R.string.notification_perm_granted) else context.getString(R.string.notification_perm_denied),
                onClick = {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                }
            )
            SettingsSwitchRow(
                icon = Icons.Rounded.Notifications,
                label = context.getString(R.string.settings_announcements),
                description = context.getString(R.string.settings_announcements_desc),
                checked = !ui.announcementsMuted,
                onChange = { vm.setAnnouncementsMuted(!it) }
            )
            SettingsSwitchRow(
                icon = Icons.Rounded.BugReport,
                label = context.getString(R.string.settings_crash_reports),
                description = context.getString(R.string.settings_crash_reports_desc),
                checked = ui.crashReportsEnabled,
                onChange = vm::setCrashReportsEnabled
            )
        }
        SettingsGroup(context.getString(R.string.settings_help), visibleIf = qMatch(query, listOf("help", "support", "faq", "ကူညီ", "help me", "autostart", "rom", "custom rom"))) {
            SettingsClickRow(
                icon = Icons.Rounded.Info,
                label = context.getString(R.string.help_download_failed),
                value = "",
                onClick = { showHelp1 = true }
            )
            SettingsClickRow(
                icon = Icons.Rounded.Download,
                label = context.getString(R.string.help_no_sound),
                value = "",
                onClick = { showHelp2 = true }
            )
            SettingsClickRow(
                icon = Icons.Rounded.Speed,
                label = context.getString(R.string.help_slow_quality),
                value = "",
                onClick = { showHelp3 = true }
            )
            SettingsClickRow(
                icon = Icons.Rounded.BatteryAlert,
                label = context.getString(R.string.help_autostart),
                value = "",
                onClick = { showAutostart = true }
            )
        }
        SettingsGroup(context.getString(R.string.settings_about), visibleIf = qMatch(query, listOf("about", "version", "ဗားရှင်း", "about mmtube"))) {
            SettingsValueRow(
                icon = Icons.Rounded.PhoneAndroid,
                label = context.getString(R.string.settings_about),
                value = context.getString(R.string.settings_about_desc)
            )
            SettingsClickRow(
                icon = Icons.Rounded.Description,
                label = context.getString(R.string.settings_source),
                value = BuildConfig.UPDATE_REPO.ifBlank { context.getString(R.string.settings_source_missing) },
                onClick = vm::openSource
            )
            SettingsSha1Row(sha1 = ui.sha1, onCopy = {
                context.copyText(context.getString(R.string.settings_sha1), ui.sha1)
            })
            SettingsValueRow(
                icon = Icons.Rounded.VerifiedUser,
                label = context.getString(R.string.settings_firebase),
                value = if (FirebaseConfig.isConfigured) context.getString(R.string.settings_firebase_on) else context.getString(R.string.settings_firebase_off),
                valueAccent = if (FirebaseConfig.isConfigured) MmtubePalette.Success else MmtubePalette.Amber
            )
            SettingsClickRow(
                icon = Icons.Rounded.PhoneAndroid,
                label = context.getString(R.string.version_details_title),
                value = "",
                onClick = { showVersionDetails = true }
            )
        }

        SettingsGroup(context.getString(R.string.settings_diagnostics), visibleIf = qMatch(query, listOf("diagnostics", "diagnostic", "ffmpeg", "ပြဿနာ", "ပြဿနာစစ်", "logs", "log", "partial", "အရှင်း", "အမှား"))) {
            SettingsClickRow(
                icon = Icons.Rounded.Description,
                label = context.getString(R.string.diagnostics_view_logs),
                value = "",
                onClick = vm::shareDiagnosticLog
            )
            SettingsClickRow(
                icon = Icons.Rounded.Send,
                label = context.getString(R.string.diagnostics_telegram),
                value = "",
                onClick = vm::sendDiagnosticToTelegram
            )
            SettingsClickRow(
                icon = Icons.Rounded.Share,
                label = context.getString(R.string.diagnostics_export_csv),
                value = "",
                onClick = vm::exportHistoryCsv
            )
            SettingsClickRow(
                icon = Icons.Rounded.DeleteOutline,
                label = context.getString(R.string.settings_reset_app_data),
                value = "",
                onClick = { showResetConfirm = true }
            )
            SettingsClickRow(
                icon = Icons.Rounded.DeleteOutline,
                label = context.getString(R.string.diagnostics_delete_partial),
                value = "",
                onClick = vm::deletePartialFiles
            )
            SettingsValueRow(
                icon = Icons.Rounded.VerifiedUser,
                label = context.getString(R.string.settings_diagnostics),
                value = context.getString(R.string.settings_diagnostics_desc)
            )
            Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                Button(
                    onClick = vm::runEngineDiagnostics,
                    enabled = !ui.engineUpdating,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.Amber.copy(alpha = 0.9f), contentColor = Color(0xFF3A2500))
                ) {
                    Text(if (ui.engineUpdating) context.getString(R.string.settings_diagnostics_running) else context.getString(R.string.settings_diagnostics_run))
                }
            }
        }

        SettingsGroup(context.getString(R.string.settings_legal), visibleIf = qMatch(query, listOf("legal", "terms", "privacy", "license", "လိုင်စင်", "မူဝါဒ"))) {
            SettingsValueRow(
                icon = Icons.Rounded.Shield,
                label = context.getString(R.string.settings_legal),
                value = context.getString(R.string.settings_legal_desc)
            )
            SettingsClickRow(
                icon = Icons.Rounded.Description,
                label = context.getString(R.string.settings_terms),
                value = "",
                onClick = { showTerms = true }
            )
            SettingsClickRow(
                icon = Icons.Rounded.PrivacyTip,
                label = context.getString(R.string.settings_privacy),
                value = "",
                onClick = { showPrivacy = true }
            )
            SettingsClickRow(
                icon = Icons.Rounded.Description,
                label = context.getString(R.string.settings_licenses),
                value = "",
                onClick = { showLicenses = true }
            )
        }

        Text(
            text = BuildConfig.VERSION_NAME.let { context.getString(R.string.settings_about_version, it) },
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        ownerTaps += 1
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        when {
                            ownerTaps >= 7 -> {
                                ownerTaps = 0
                                onOpenOwner()
                            }

                            ownerTaps >= 5 -> Toast.makeText(
                                context,
                                "နောက်ထပ် ${7 - ownerTaps} ချက်",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onLongClick = {
                        // Long press is a second, equally hidden shortcut.
                        ownerTaps = 0
                        onOpenOwner()
                    }
                )
                .padding(top = 14.dp, bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    if (showResetConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(context.getString(R.string.settings_reset_app_data)) },
            text = { Text(context.getString(R.string.settings_reset_app_data_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.resetAppData()
                    showResetConfirm = false
                }) {
                    Text(context.getString(R.string.storage_full_clean), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showResetConfirm = false }) {
                    Text(android.R.string.cancel.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
    if (showAutostart) {
        SimpleTextDialog(
            title = context.getString(R.string.help_autostart),
            body = context.getString(R.string.help_autostart_body),
            onDismiss = { showAutostart = false }
        )
    }
    if (showVersionDetails) {
        SimpleTextDialog(
            title = context.getString(R.string.version_details_title),
            body = context.getString(
                R.string.version_details_body,
                BuildConfig.VERSION_NAME,
                context.packageName,
                android.os.Build.VERSION.SDK_INT,
                BuildConfig.FIREBASE_PROJECT_ID
            ),
            onDismiss = { showVersionDetails = false }
        )
    }

    if (showLanguage) {
        LanguageDialog(
            selected = ui.settings.language,
            onSelect = {
                vm.setLanguage(it)
                showLanguage = false
            },
            onDismiss = { showLanguage = false }
        )
    }
    if (showTerms) {
        SimpleTextDialog(
            title = context.getString(R.string.terms_title),
            body = context.getString(R.string.terms_body),
            onDismiss = { showTerms = false }
        )
    }
    if (showPrivacy) {
        SimpleTextDialog(
            title = context.getString(R.string.settings_privacy),
            body = context.getString(R.string.settings_privacy_desc),
            onDismiss = { showPrivacy = false }
        )
    }
    if (showHelp1) {
        SimpleTextDialog(
            title = context.getString(R.string.help_download_failed),
            body = context.getString(R.string.help_download_failed_body),
            onDismiss = { showHelp1 = false }
        )
    }
    if (showHelp2) {
        SimpleTextDialog(
            title = context.getString(R.string.help_no_sound),
            body = context.getString(R.string.help_no_sound_body),
            onDismiss = { showHelp2 = false }
        )
    }
    if (showHelp3) {
        SimpleTextDialog(
            title = context.getString(R.string.help_slow_quality),
            body = context.getString(R.string.help_slow_quality_body),
            onDismiss = { showHelp3 = false }
        )
    }
    if (showLicenses) {
        SimpleTextDialog(
            title = context.getString(R.string.settings_licenses),
            body = context.getString(R.string.settings_licenses_desc),
            onDismiss = { showLicenses = false }
        )
    }
    ui.engineDiagnostics?.let { report ->
        DiagnosticsDialog(
            report = report,
            onCopy = { context.copyText(context.getString(R.string.settings_diagnostics_title), report) },
            onDismiss = vm::clearEngineDiagnostics
        )
    }
}

private fun languageLabel(context: android.content.Context, tag: String): String = when (tag) {
    "en" -> context.getString(R.string.language_english)
    "my" -> context.getString(R.string.language_burmese)
    else -> context.getString(R.string.language_system)
}

@Composable
private fun SettingsGroup(
    title: String,
    visibleIf: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    if (!visibleIf) return
    Column {
        Text(
            text = title.uppercase(),
            style = MmtubeType.LabelSmall.copy(letterSpacing = 1.4.sp),
            color = MmtubePalette.Aqua,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
        FrostedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsValueRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueAccent: Color? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(label, style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (value.isNotBlank()) {
                Text(
                    value,
                    style = MmtubeType.BodyMedium,
                    color = valueAccent ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsClickRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsIcon(icon)
            Column(Modifier.weight(1f)) {
                Text(label, style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (value.isNotBlank()) {
                    Text(value, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f)) {
            Text(label, style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsSha1Row(sha1: String, onCopy: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsIcon(Icons.Rounded.VerifiedUser)
            Column(Modifier.weight(1f)) {
                Text(context.getString(R.string.settings_sha1), style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(context.getString(R.string.settings_sha1_desc), style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = context.getString(R.string.action_copy), tint = MmtubePalette.Blue)
            }
        }
        Text(
            text = sha1,
            style = MmtubeType.LabelSmall.copy(letterSpacing = 0.5.sp),
            color = MmtubePalette.Aqua,
            modifier = Modifier.padding(start = 60.dp, top = 4.dp)
        )
    }
}

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MmtubePalette.Blue.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ThemeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            ThemeMode.SYSTEM to context.getString(R.string.theme_system),
            ThemeMode.LIGHT to context.getString(R.string.theme_light),
            ThemeMode.DARK to context.getString(R.string.theme_dark),
            ThemeMode.AMOLED to context.getString(R.string.theme_amoled)
        ).forEach { (mode, label) ->
            val isSelected = selected == mode
            Surface(
                onClick = { onSelect(mode) },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) MmtubePalette.AquaDeep else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(label, style = MmtubeType.LabelLarge)
                }
            }
        }
    }
}

@Composable
private fun LanguageDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val options = listOf(
        "" to context.getString(R.string.language_system),
        "en" to context.getString(R.string.language_english),
        "my" to context.getString(R.string.language_burmese)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.settings_language), style = MmtubeType.TitleLarge) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(tag) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioDot(selected == tag)
                        Text(label, style = MmtubeType.BodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (selected) MmtubePalette.AquaDeep else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(
                if (selected) Modifier else Modifier.background(Color.Transparent)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    report: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.settings_diagnostics_title), style = MmtubeType.TitleLarge) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    report,
                    style = MmtubeType.LabelSmall.copy(fontSize = 10.sp),
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(context.getString(R.string.settings_diagnostics_copy), color = MmtubePalette.Aqua) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_close)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun SimpleTextDialog(title: String, body: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MmtubeType.TitleLarge) },
        text = {
            Text(
                body,
                style = MmtubeType.BodyMedium,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.action_ok)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

private fun qMatch(query: String, keywords: List<String>): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return keywords.any { it.lowercase().contains(q) }
}

private fun isNotificationsAllowed(context: android.content.Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
