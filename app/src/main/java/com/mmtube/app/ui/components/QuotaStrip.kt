package com.mmtube.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import com.mmtube.app.R
import com.mmtube.app.data.AccountUi
import com.mmtube.app.data.Plans
import com.mmtube.app.data.QuotaState
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters

/** Live-updating clock used by countdown composables. */
@Composable
fun rememberNow(periodMs: Long = 1000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun QuotaStrip(
    quota: QuotaState?,
    account: AccountUi,
    onUpgrade: () -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val now = rememberNow()
    val profile = account.profile
    val paid = quota?.paid == true
    val guest = quota?.guest == true
    val disabled = quota?.disabled == true

    val accent = when {
        paid -> MmtubePalette.Aqua
        disabled -> MmtubePalette.Danger
        guest -> MmtubePalette.Amber
        else -> MmtubePalette.Blue
    }
    val icon = when {
        paid -> Icons.Rounded.Verified
        disabled -> Icons.Rounded.HourglassTop
        guest -> Icons.Rounded.Person
        else -> Icons.Rounded.Bolt
    }
    val title = when {
        paid -> context.getString(R.string.quota_paid_title)
        disabled -> context.getString(R.string.account_plan)
        guest -> context.getString(R.string.quota_guest_title)
        else -> context.getString(R.string.quota_free_title)
    }
    val paidDaysLeft = profile?.planExpiresAt?.let { ((it - now) / 86_400_000L).toInt().coerceAtLeast(0) }
    val subtitle = when {
        paid -> if (paidDaysLeft != null && paidDaysLeft > 0) {
            context.getString(R.string.quota_paid_days_left, paidDaysLeft)
        } else {
            context.getString(R.string.quota_paid_desc, Formatters.formatDate(profile?.planExpiresAt))
        }
        disabled -> context.getString(R.string.account_disabled_desc)
        guest -> context.getString(R.string.quota_guest_desc)
        quota == null -> context.getString(R.string.loading)
        else -> {
            val remaining = quota.remainingMs(now)
            if (quota.allowed) context.getString(R.string.quota_ready)
            else context.getString(R.string.quota_next, Formatters.formatCountdown(remaining))
        }
    }

    FrostedCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = accent.copy(alpha = 0.16f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(12.dp).size(24.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                paid -> {}
                guest -> Button(
                    onClick = onSignIn,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.AquaDeep, contentColor = androidx.compose.ui.graphics.Color.White)
                ) { Text(context.getString(R.string.quota_sign_in), style = MmtubeType.LabelLarge.copy(fontSize = 12.sp), maxLines = 1) }
                else -> Button(
                    onClick = onUpgrade,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.BlueDeep, contentColor = androidx.compose.ui.graphics.Color.White)
                ) { Text(context.getString(R.string.quota_upgrade), style = MmtubeType.LabelLarge, maxLines = 1) }
            }
        }
    }
}
