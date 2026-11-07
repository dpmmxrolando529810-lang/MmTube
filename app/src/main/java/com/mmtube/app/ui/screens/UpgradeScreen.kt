package com.mmtube.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmtube.app.MainUiState
import com.mmtube.app.MainViewModel
import com.mmtube.app.R
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.components.GradientButton
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters

@Composable
fun UpgradeScreen(
    ui: MainUiState,
    vm: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf("3m") }
    val profile = ui.account.profile
    val isActive = profile?.isPaid == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    androidx.compose.material.icons.Icons.Rounded.ArrowBack,
                    contentDescription = context.getString(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = context.getString(R.string.upgrade_title),
                style = MmtubeType.HeadlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        HeroCard(isActive = isActive, expiresAt = profile?.planExpiresAt)

        Column(Modifier.fillMaxWidth().selectableGroup()) {
            listOf(
                "1m" to context.getString(R.string.plan_1m),
                "3m" to context.getString(R.string.plan_3m),
                "6m" to context.getString(R.string.plan_6m),
                "12m" to context.getString(R.string.plan_12m)
            ).forEach { (key, label) ->
                PlanRow(
                    label = label,
                    selected = selected == key,
                    onClick = { selected = key }
                )
            }
        }

        BenefitsCard()
        UserIdCard(ui = ui, vm = vm)
        StepsCard()

        GradientButton(
            text = context.getString(R.string.upgrade_contact),
            onClick = vm::openSupport,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Bolt
        )
        Text(
            text = context.getString(R.string.upgrade_no_payment) + "\n" + context.getString(R.string.upgrade_price_note),
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun HeroCard(isActive: Boolean, expiresAt: Long?) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF1A1430),
                        Color(0xFF122041),
                        Color(0xFF0E2E35)
                    )
                )
            )
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
            .padding(22.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(MmtubePalette.Amber, Color(0xFFFF9D5C)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.WorkspacePremium,
                    contentDescription = null,
                    tint = Color(0xFF3A2200),
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = if (isActive) context.getString(R.string.upgrade_plan_active) else context.getString(R.string.upgrade_title),
                style = MmtubeType.DisplaySmall.copy(fontSize = 26.sp),
                color = Color(0xFFF6F8FF),
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                text = if (isActive && expiresAt != null) {
                    context.getString(R.string.quota_paid_desc, Formatters.formatDate(expiresAt))
                } else {
                    context.getString(R.string.upgrade_subtitle)
                },
                style = MmtubeType.BodyMedium,
                color = Color(0xB3E8EDFF),
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                modifier = Modifier.padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MmtubePalette.Amber, modifier = Modifier.size(16.dp))
                Text(
                    text = context.getString(R.string.plan_unlimited),
                    style = MmtubeType.LabelLarge,
                    color = MmtubePalette.Amber
                )
            }
        }
    }
}

@Composable
private fun PlanRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) MmtubePalette.AquaDeep.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.6.dp else 1.dp,
            if (selected) MmtubePalette.Aqua else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) MmtubePalette.Aqua.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    tint = if (selected) MmtubePalette.Aqua else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MmtubeType.TitleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = context.getString(R.string.plan_unlimited),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MmtubePalette.Aqua,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun BenefitsCard() {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        listOf(
            Icons.Rounded.HighQuality to context.getString(R.string.plan_max_quality),
            Icons.Rounded.MusicNote to context.getString(R.string.plan_no_watermark),
            Icons.Rounded.PhoneAndroid to context.getString(R.string.plan_all_devices)
        ).forEach { (icon, text) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MmtubePalette.Blue, modifier = Modifier.size(22.dp))
                Text(text, style = MmtubeType.BodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun UserIdCard(ui: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val code = ui.account.profile?.userCode
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.getString(R.string.upgrade_user_id),
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = code ?: context.getString(R.string.account_guest_id),
                style = MmtubeType.HeadlineMedium.copy(letterSpacing = 2.sp),
                color = if (code != null) MmtubePalette.Aqua else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (code != null) {
                IconButton(onClick = vm::copyUserCode) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = context.getString(R.string.upgrade_copy_id), tint = MmtubePalette.Blue)
                }
            }
        }
        Text(
            text = context.getString(R.string.upgrade_contact_note),
            style = MmtubeType.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun StepsCard() {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.getString(R.string.upgrade_how),
            style = MmtubeType.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        listOf(
            context.getString(R.string.upgrade_step_1),
            context.getString(R.string.upgrade_step_2),
            context.getString(R.string.upgrade_step_3)
        ).forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MmtubePalette.Blue.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", style = MmtubeType.LabelLarge, color = MmtubePalette.Blue)
                }
                Text(step, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
