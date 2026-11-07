package com.mmtube.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mmtube.admin.AdminAuthState
import com.mmtube.admin.AdminUiState
import com.mmtube.admin.AdminUser
import com.mmtube.admin.AdminViewModel
import com.mmtube.admin.Plans
import com.mmtube.admin.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Amber = Color(0xFFFFB84D)
private val Aqua = Color(0xFF2FE3C0)
private val Danger = Color(0xFFFF6B81)
private val OnDark = Color(0xFFF2F5FF)
private val Muted = Color(0xFF8B97B5)
private val FieldFill = Color(0x66131E33)

private sealed interface ConfirmAction {
    data class Activate(val plan: String) : ConfirmAction
    data object Free : ConfirmAction
    data object Reset : ConfirmAction
    data class Disabled(val value: Boolean) : ConfirmAction
}

@Composable
fun Dashboard(ui: AdminUiState, vm: AdminViewModel) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    val adminEmail = (ui.auth as? AdminAuthState.Ready)?.email

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { HeaderRow(email = adminEmail, onSignOut = vm::signOut) }
            item { SearchCard(ui = ui, vm = vm) }
            item {
                when {
                    ui.searching -> LoadingUserCard()
                    ui.user != null -> UserDetailCard(
                        user = ui.user,
                        busy = ui.busy,
                        onRefresh = vm::refreshUser,
                        onCopyCode = { vm.copyText(ui.user.userCode, "User ID") },
                        onActivate = { confirm = ConfirmAction.Activate(it) },
                        onFree = { confirm = ConfirmAction.Free },
                        onReset = { confirm = ConfirmAction.Reset },
                        onDisabled = { confirm = ConfirmAction.Disabled(it) }
                    )
                    ui.searched -> NotFoundCard()
                    else -> Unit
                }
            }
            item { RecentActionsCard(ui = ui) }
            item { SecureNote() }
        }
        if (ui.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = Amber,
                trackColor = Color.Transparent
            )
        }
    }

    confirm?.let { action ->
        val user = ui.user
        val body = when (action) {
            is ConfirmAction.Activate -> context.getString(R.string.admin_confirm_activate, Plans.label(action.plan), user?.userCode ?: "")
            ConfirmAction.Free -> context.getString(R.string.admin_confirm_free, user?.userCode ?: "")
            ConfirmAction.Reset -> context.getString(R.string.admin_confirm_reset, user?.userCode ?: "")
            is ConfirmAction.Disabled -> if (action.value) {
                context.getString(R.string.admin_confirm_disable, user?.userCode ?: "")
            } else {
                context.getString(R.string.admin_confirm_enable, user?.userCode ?: "")
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(context.getString(R.string.admin_confirm_title), color = OnDark, fontWeight = FontWeight.Bold) },
            text = { Text(body, color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        is ConfirmAction.Activate -> vm.activatePlan(action.plan)
                        ConfirmAction.Free -> vm.setFree()
                        ConfirmAction.Reset -> vm.resetCooldown()
                        is ConfirmAction.Disabled -> vm.setDisabled(action.value)
                    }
                    confirm = null
                }) {
                    Text(
                        context.getString(R.string.admin_apply),
                        color = if (action is ConfirmAction.Disabled && action.value) Danger else Aqua,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(context.getString(R.string.admin_cancel), color = Muted)
                }
            },
            containerColor = Color(0xFF141E33)
        )
    }
}

@Composable
private fun HeaderRow(email: String?, onSignOut: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Amber, Color(0xFFFF8A3D)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, tint = Color(0xFF332000), modifier = Modifier.size(26.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = context.getString(R.string.admin_app_name),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OnDark
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Amber,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = context.getString(R.string.admin_badge),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF332000),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            email?.let {
                Text(it, fontSize = 11.sp, color = Muted, maxLines = 1)
            }
        }
        IconButton(onClick = onSignOut) {
            Icon(Icons.Rounded.Logout, contentDescription = context.getString(R.string.admin_sign_out), tint = Muted)
        }
    }
}

@Composable
private fun SearchCard(ui: AdminUiState, vm: AdminViewModel) {
    val context = LocalContext.current
    AdminCard(modifier = Modifier.fillMaxWidth()) {
        AdminSectionLabel(context.getString(R.string.admin_search_by_code))
        Row(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(FieldFill)
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Aqua, modifier = Modifier.size(21.dp))
            BasicTextField(
                value = ui.query,
                onValueChange = vm::updateQuery,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = OnDark,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.5.sp
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            if (ui.query.isNotBlank()) {
                TextButton(onClick = { vm.updateQuery("") }) {
                    Text("×", fontSize = 22.sp, color = Muted)
                }
            }
        }
        Surface(
            onClick = { if (!ui.searching) vm.search() },
            shape = RoundedCornerShape(14.dp),
            color = Amber,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ui.searching) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = Color(0xFF332000)
                    )
                    Text(
                        text = context.getString(R.string.admin_searching),
                        color = Color(0xFF332000),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF332000))
                    Text(
                        text = context.getString(R.string.admin_search_button),
                        color = Color(0xFF332000),
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }
        Text(
            text = context.getString(R.string.admin_search_help),
            fontSize = 11.sp,
            color = Muted,
            modifier = Modifier.padding(top = 10.dp)
        )
        if (ui.searched && !ui.searching && ui.user == null) {
            Text(
                text = context.getString(R.string.admin_not_found),
                fontSize = 13.sp,
                color = Amber,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun LoadingUserCard() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "loadingCard")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(800),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "loadingAlpha"
    )
    AdminCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x33131E33).copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(0.7f)
                .height(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x33131E33).copy(alpha = alpha))
        )
    }
}

@Composable
private fun NotFoundCard() {
    val context = LocalContext.current
    AdminCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.getString(R.string.admin_not_found),
            color = Amber,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun UserDetailCard(
    user: AdminUser,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCopyCode: () -> Unit,
    onActivate: (String) -> Unit,
    onFree: () -> Unit,
    onReset: () -> Unit,
    onDisabled: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val paid = user.isPaid
    val expired = Plans.isPaid(user.plan) && (user.planExpiresAt ?: 0L) <= now
    val planLabel = when {
        expired -> context.getString(R.string.admin_plan_expired)
        Plans.isPaid(user.plan) -> Plans.label(user.plan)
        else -> context.getString(R.string.admin_plan_free)
    }
    val planAccent = if (paid) Amber else Aqua
    val cooldownActive = (user.nextAllowedAt ?: 0L) > now

    AdminCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Aqua, Color(0xFF0E6B5C)))),
                contentAlignment = Alignment.Center
            ) {
                if (user.photoUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(user.photoUrl).crossfade(true).build(),
                        contentDescription = user.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Text(
                        text = (user.displayName ?: user.email ?: "?").take(1).uppercase(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF03211A)
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = user.displayName ?: context.getString(R.string.admin_unknown),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = OnDark,
                    maxLines = 1
                )
                Text(user.email ?: "—", fontSize = 11.sp, color = Muted, maxLines = 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 3.dp)
                ) {
                    Text(user.userCode, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Aqua)
                    IconButton(onClick = onCopyCode, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = Muted, modifier = Modifier.size(15.dp))
                    }
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = context.getString(R.string.admin_refresh), tint = Muted)
            }
        }

        if (user.disabled) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Danger.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.DisabledByDefault, contentDescription = null, tint = Danger, modifier = Modifier.size(18.dp))
                    Text(
                        text = context.getString(R.string.admin_disabled),
                        color = Danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0x1AFFFFFF), modifier = Modifier.padding(vertical = 12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            AdminStat(
                value = planLabel,
                label = context.getString(R.string.admin_plan),
                accent = planAccent
            )
            AdminStat(
                value = user.totalDownloads.toString(),
                label = context.getString(R.string.admin_total_downloads),
                accent = OnDark
            )
            AdminStat(
                value = if (cooldownActive) context.getString(R.string.admin_cooldown_active) else context.getString(R.string.admin_ready),
                label = context.getString(R.string.admin_next_download),
                accent = if (cooldownActive) Amber else Aqua
            )
        }
        Text(
            text = if (paid && user.planExpiresAt != null) {
                "${context.getString(R.string.admin_expires)} ${formatDate(user.planExpiresAt)}"
            } else if (expired && user.planExpiresAt != null) {
                context.getString(R.string.admin_expired_note)
            } else {
                context.getString(R.string.admin_free_limit)
            },
            fontSize = 11.sp,
            color = if (expired) Amber else Muted,
            modifier = Modifier.padding(top = 10.dp)
        )

        AdminSectionLabel(context.getString(R.string.admin_set_plan), modifier = Modifier.padding(top = 16.dp))
        Row(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(Plans.M1, Plans.M3).forEach { plan ->
                PlanActivateButton(plan, onActivate, Modifier.weight(1f))
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(Plans.M6, Plans.M12).forEach { plan ->
                PlanActivateButton(plan, onActivate, Modifier.weight(1f))
            }
        }
        Text(
            text = context.getString(R.string.admin_plan_extended_note),
            fontSize = 10.sp,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp)
        )

        Row(
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                label = context.getString(R.string.admin_set_free),
                accent = OnDark,
                modifier = Modifier.weight(1f),
                onClick = onFree
            )
            ActionButton(
                label = context.getString(R.string.admin_reset_cooldown),
                accent = Amber,
                modifier = Modifier.weight(1f),
                onClick = onReset
            )
        }
        ActionButton(
            label = if (user.disabled) context.getString(R.string.admin_enable) else context.getString(R.string.admin_disable),
            accent = if (user.disabled) Aqua else Danger,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = { onDisabled(!user.disabled) }
        )
    }
}

@Composable
private fun PlanActivateButton(
    plan: String,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val label = when (plan) {
        Plans.M1 -> context.getString(R.string.admin_plan_1m)
        Plans.M3 -> context.getString(R.string.admin_plan_3m)
        Plans.M6 -> context.getString(R.string.admin_plan_6m)
        else -> context.getString(R.string.admin_plan_12m)
    }
    Surface(
        onClick = { onActivate(plan) },
        shape = RoundedCornerShape(16.dp),
        color = Amber.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.2.dp, Amber.copy(alpha = 0.55f)),
        modifier = modifier.height(52.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Amber)
            Text(context.getString(R.string.admin_unlimited), fontSize = 9.sp, color = Muted)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier.height(46.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
private fun RecentActionsCard(ui: AdminUiState) {
    val context = LocalContext.current
    AdminCard(modifier = Modifier.fillMaxWidth()) {
        AdminSectionLabel(context.getString(R.string.admin_recent_actions))
        if (ui.actions.isEmpty()) {
            Text(
                text = context.getString(R.string.admin_no_actions),
                fontSize = 12.sp,
                color = Muted,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                ui.actions.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Aqua,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(action.text, fontSize = 12.sp, color = OnDark, modifier = Modifier.weight(1f))
                        Text(formatTime(action.at), fontSize = 10.sp, color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SecureNote() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(14.dp))
        Text(
            text = context.getString(R.string.admin_secure_note),
            fontSize = 10.sp,
            color = Muted,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

private fun formatDate(millis: Long?): String {
    if (millis == null) return "—"
    return runCatching {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }.getOrDefault("—")
}

private fun formatTime(millis: Long): String = runCatching {
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
}.getOrDefault("")
