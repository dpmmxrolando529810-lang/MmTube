package com.mmtube.admin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mmtube.admin.ActivationLog
import com.mmtube.admin.AdminUiState
import com.mmtube.admin.AdminViewModel
import com.mmtube.admin.PaymentRequest
import com.mmtube.admin.Plans
import com.mmtube.admin.AdminUser
import com.mmtube.admin.AdminTab
import androidx.compose.foundation.border
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber = Color(0xFFFFB84D)
private val Aqua = Color(0xFF2FE3C0)
private val Danger = Color(0xFFFF6B81)
private val OnDark = Color(0xFFF2F5FF)
private val Muted = Color(0xFF8B97B5)
private val FieldFill = Color(0x66131E33)
private val CardFill = Color(0x66131E33)
private val PanelFill = Color(0xFF141E33)

private fun fmtDate(ms: Long?): String = when (ms) {
    null, 0L -> "—"
    else -> runCatching {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("—")
}

private fun fmtDateTime(ms: Long?): String = when (ms) {
    null, 0L -> "—"
    else -> runCatching {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("—")
}

private fun decodeBase64Image(value: String): Bitmap? = runCatching {
    val bytes = Base64.decode(value, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

@Composable
private fun BoxDivider() {
    HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 1.dp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = PanelFill,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun PaymentsTab(ui: AdminUiState, vm: AdminViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pending payments — screenshot နဲ့ ငွေပြ ကို စစ်ဆေးပါ", color = OnDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        NoteField(
            value = ui.adminNote,
            onValueChange = vm::setAdminNote,
            placeholder = "မှတ်ချက် (optional)"
        )
        if (ui.payingLoading) {
            LinearProgressIndicator(color = Amber, modifier = Modifier.fillMaxWidth())
        }
        ui.pendingPayments.forEach { payment ->
            PaymentCard(payment = payment, vm = vm, adminNote = ui.adminNote)
        }
        if (!ui.payingLoading && ui.pendingPayments.isEmpty()) {
            Text("Pending payment မရှိသေးပါ", color = Muted)
        }
    }
}

@Composable
private fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Muted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Aqua,
            unfocusedBorderColor = Color(0xFF26354E),
            focusedTextColor = OnDark,
            unfocusedTextColor = OnDark,
            cursorColor = Aqua,
            focusedContainerColor = FieldFill,
            unfocusedContainerColor = FieldFill
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun PaymentCard(payment: PaymentRequest, vm: AdminViewModel, adminNote: String) {
    val context = LocalContext.current
    val bitmap = remember(payment.imageBase64) { decodeBase64Image(payment.imageBase64) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(payment.userCode, color = OnDark, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(Plans.label(payment.plan), color = Aqua, fontWeight = FontWeight.Bold)
        }
        Text(formatDateTimeMs(payment.createdAt), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Text("Email: ${payment.email ?: "—"}", color = Muted, fontSize = 11.sp)
        Text("KBZ Pay sender: ${payment.buyerPhone ?: "—"}", color = Muted, fontSize = 11.sp)
        Text("Amount: ${payment.amountKs} Ks", color = OnDark, fontSize = 12.sp)
        payment.note?.let { note ->
            Text("Note: $note", color = Aqua, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        bitmap?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Payment screenshot",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1322))
                    .padding(top = 8.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            Button(
                onClick = { vm.approvePayment(payment) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E3B2F), contentColor = Color(0xFF58E0A8)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Approve + Activate", fontWeight = FontWeight.Bold) }
            Button(
                onClick = { vm.rejectPayment(payment) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1520), contentColor = Color(0xFFFF6B81)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) { Text("Reject", fontWeight = FontWeight.Bold) }
        }
    }
}

private fun formatDateTimeMs(ms: Long): String = runCatching {
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
}.getOrDefault("")

@Composable
fun ExpiringTab(ui: AdminUiState, vm: AdminViewModel) {
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Paid plans — expires in ≤ 7 days (tap +1 to extend)", color = OnDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.loadExpiringUsers() }) { Text("Refresh", color = Aqua) }
        }
        if (ui.expiringLoading) {
            LinearProgressIndicator(color = Amber, modifier = Modifier.fillMaxWidth())
        }
        val expiring = ui.expiringUsers.filter { u ->
            val exp = u.planExpiresAt ?: Long.MAX_VALUE
            exp - now < 7L * 24L * 3600L * 1000L
        }
        if (expiring.isEmpty() && !ui.expiringLoading) {
            Text("သတ်မှတ်ထားသော အချိန်အတွင်း ကုန်မည့် user မရှိသေးပါ။", color = Muted)
        }
        expiring.forEach { user ->
            ExpiringRow(user, now, vm)
        }
    }
}

@Composable
private fun ExpiringRow(user: com.mmtube.admin.AdminUser, now: Long, vm: AdminViewModel) {
    val expiry = user.planExpiresAt ?: 0L
    val days = ((expiry - now) / 86_400_000L).toInt()
    val tone = when {
        days < 0 -> Danger
        days <= 1 -> Danger
        days <= 3 -> Amber
        else -> Aqua
    }
    val label = when {
        days < 0 -> "${-days} ရက် ကုန်ပြီး"
        days == 0 -> "ယနေ့ ကုန်မည်"
        else -> "$days ရက် ကျန်"
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PanelFill,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(user.userCode, color = OnDark, fontWeight = FontWeight.Bold)
                    Text(Plans.label(user.plan), color = Aqua, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(label, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(fmtDate(expiry), color = Muted, fontSize = 10.sp)
            }
            androidx.compose.material3.TextButton(onClick = { vm.quickExtend(user) }) {
                Text("+1 လ", color = Aqua, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun LogsTab(ui: AdminUiState, vm: AdminViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Activation history (newest first)", color = OnDark, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.loadLogs() }) { Text("Refresh", color = Aqua) }
        }
        if (ui.logsLoading) {
            LinearProgressIndicator(color = Amber, modifier = Modifier.fillMaxWidth())
        }
        if (ui.logs.isEmpty() && !ui.logsLoading) {
            Text("Activation မှတ်တမ်း မရှိသေးပါ။", color = Muted)
        }
        ui.logs.forEach { log ->
            LogRow(log, vm)
        }
    }
}

@Composable
private fun LogRow(log: ActivationLog, vm: AdminViewModel) {
    val tone = if (log.undoneAt != null) Muted else Aqua
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PanelFill,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(log.userCode, color = OnDark, fontWeight = FontWeight.Bold)
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0x1A2FE3C0)) {
                        Text(log.source, color = Aqua, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    if (log.undoneAt != null) {
                        Text("UNDO", color = Danger, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "${Plans.label(log.plan)}${log.amountKs?.let { "  ·  $it Ks" } ?: ""}  ·  ${fmtDateTime(log.createdAt)}",
                    color = Muted,
                    fontSize = 10.sp
                )
                log.note?.let { Text("Note: $it", color = Muted, fontSize = 10.sp) }
            }
            if (log.undoneAt == null) {
                TextButton(onClick = { vm.undoActivation(log) }) { Text("Undo", color = Amber, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun BroadcastTab(ui: AdminUiState, vm: AdminViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Notifications, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
                Text(" Broadcast announcement", color = OnDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(
                "Firestore `announcements/latest` ထဲ သိမ်းမယ် — app ထဲ banner အနေနဲ့ ပေါ်မယ် (user တိုင်းမြင်ရမယ်)",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = ui.announcementText,
                onValueChange = vm::setAnnouncementText,
                label = { Text("Message", color = Muted) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Aqua,
                    unfocusedBorderColor = Color(0xFF26354E),
                    focusedTextColor = OnDark,
                    unfocusedTextColor = OnDark,
                    cursorColor = Aqua,
                    focusedContainerColor = FieldFill,
                    unfocusedContainerColor = FieldFill
                ),
                shape = RoundedCornerShape(12.dp)
            )
            androidx.compose.material3.Button(
                onClick = vm::publishAnnouncement,
                enabled = !ui.announcementSaving && ui.announcementText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Aqua, contentColor = Color(0xFF03211A))
            ) {
                Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  ကြေညာချက် ပို့ပါ", fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
fun AdminTabRow(current: AdminTab, onSelect: (AdminTab) -> Unit) {
    val tabs = listOf(
        AdminTab.SEARCH to "Search",
        AdminTab.PAYMENTS to "Payments",
        AdminTab.EXPIRING to "Expiring",
        AdminTab.LOGS to "History",
        AdminTab.BROADCAST to "Broadcast"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEach { (tab, label) ->
            val selected = tab == current
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(999.dp),
                color = if (selected) Aqua.copy(alpha = 0.18f) else PanelFill,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Aqua else Color(0xFF26354E))
            ) {
                Text(
                    text = label,
                    color = if (selected) Aqua else Muted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
