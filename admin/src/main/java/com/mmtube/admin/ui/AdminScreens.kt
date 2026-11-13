package com.mmtube.admin.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.Image
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

private fun decodeBase64Image(value: String, maxDim: Int = 720): Bitmap? = runCatching {
    val bytes = Base64.decode(value, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
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
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pending payments (${ui.pendingPayments.size})", color = OnDark, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = vm::loadPendingPayments) { Text("Refresh", color = Aqua) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TodayStatChip("ယနေ့ဝင်ငွေ", "${ui.revenueTodayKs} Ks")
            TodayStatChip("ယနေ့", "${ui.paymentsTodayCount} ခု")
            TodayStatChip("လစာ", ui.monthlyRevenue.lastOrNull()?.second?.let { it.toString() + " Ks" } ?: "0 Ks")
        }
        MonthlyRevenueChart(ui.monthlyRevenue)
        OutlinedTextField(
            value = ui.paymentSearchQuery,
            onValueChange = vm::updatePaymentSearch,
            placeholder = { Text("ဖုန်း / User ID ရှာရန်", color = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Aqua, unfocusedBorderColor = Color(0xFF26354E),
                focusedTextColor = OnDark, unfocusedTextColor = OnDark,
                cursorColor = Aqua,
                focusedContainerColor = FieldFill, unfocusedContainerColor = FieldFill
            )
        )
        if (ui.selectedPaymentIds.isNotEmpty()) {
            androidx.compose.material3.Button(
                onClick = vm::approveSelectedPayments,
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Aqua, contentColor = Color(0xFF03211A))
            ) {
                Text("ရွေးထား ${ui.selectedPaymentIds.size} ခု ကို အတည်ပြုရန်", fontWeight = FontWeight.Bold)
            }
        }
        NoteField(
            value = ui.adminNote,
            onValueChange = vm::setAdminNote,
            placeholder = "မှတ်ချက် (optional)"
        )
        if (ui.payingLoading) {
            LinearProgressIndicator(color = Amber, modifier = Modifier.fillMaxWidth())
        }
        val q = ui.paymentSearchQuery.trim().lowercase()
        val filtered = ui.pendingPayments.filter { payment ->
            q.isBlank() || listOfNotNull(payment.userCode, payment.buyerPhone, payment.email, payment.plan)
                .any { it.lowercase().contains(q) }
        }
        filtered.forEach { payment ->
            PaymentCard(
                payment = payment,
                vm = vm,
                adminNote = ui.adminNote,
                selected = payment.id in ui.selectedPaymentIds,
                onToggleSelected = { vm.togglePaymentSelection(payment.id) }
            )
        }
        if (!ui.payingLoading && filtered.isEmpty()) {
            Text("Pending payment မရှိသေးပါ", color = Muted)
        }
    }
}

@Composable
private fun TodayStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(10.dp), color = CardFill, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, color = Muted, fontSize = 10.sp)
            Text(value, color = Aqua, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun MonthlyRevenueChart(data: List<Pair<String, Long>>) {
    if (data.isEmpty()) return
    val maxAmount = data.maxOf { it.second }.coerceAtLeast(1L)
    val burmeseMonths = listOf("ဇန်", "ဖေ", "မတ်", "ဧပြီ", "မေ", "ဇွန်", "ဇူ", "သြ", "စက်", "အောက်", "နို", "ဒီ")
    Surface(shape = RoundedCornerShape(14.dp), color = CardFill, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("လစဉ် ဝင်ငွေ (Ks)", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                data.forEach { (key, amount) ->
                    val monthIndex = key.substringAfter('-').toIntOrNull()?.minus(1) ?: 0
                    val monthLabel = burmeseMonths.getOrElse(monthIndex.coerceIn(0, 11)) { key.takeLast(2) }
                    val fraction = (amount.toFloat() / maxAmount).coerceIn(0.05f, 1f)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("${amount / 1000}K", color = Aqua, fontSize = 9.sp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp)
                                .padding(top = 6.dp)
                                .background(Color(0x66131E33), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((84 * fraction).dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(Aqua)
                            )
                        }
                        Text(monthLabel, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
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
private fun PaymentCard(
    payment: PaymentRequest,
    vm: AdminViewModel,
    adminNote: String,
    selected: Boolean = false,
    onToggleSelected: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showFullImage by remember { mutableStateOf(false) }
    val bitmap = remember(payment.imageBase64) { decodeBase64Image(payment.imageBase64, maxDim = 720) }
    val fullBitmap = remember(showFullImage) { if (showFullImage) decodeBase64Image(payment.imageBase64, maxDim = 2000) else null }
    val expectedKs = planAmountAdmin(payment.plan)
    val amountOk = payment.amountKs == expectedKs.toLong()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            onToggleSelected?.let {
                Checkbox(checked = selected, onCheckedChange = { it() })
            }
            Text(payment.userCode, color = OnDark, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(Plans.label(payment.plan), color = Aqua, fontWeight = FontWeight.Bold)
        }
        Text(formatDateTimeMs(payment.createdAt), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        Text("Email: ${payment.email ?: "—"}", color = Muted, fontSize = 11.sp)
        Text("KBZ Pay sender: ${payment.buyerPhone ?: "—"}", color = Muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Amount: ${payment.amountKs} Ks", color = OnDark, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (amountOk) "✓ ${expectedKs} Ks — matches" else "✗ Mismatch (${expectedKs} Ks)",
                color = if (amountOk) Color(0xFF58E0A8) else Danger,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        payment.note?.let { note ->
            Text("Note: $note", color = Aqua, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Payment screenshot",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0C1322))
                    .padding(top = 8.dp)
                    .clickable { showFullImage = true }
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
    if (showFullImage && fullBitmap != null) {
        AlertDialog(
            onDismissRequest = { showFullImage = false },
            containerColor = Color(0xFF05070D),
            confirmButton = { TextButton(onClick = { showFullImage = false }) { Text("ပိတ်ရန်", color = Aqua) } },
            title = { Text("သက်သေပုံ — ${payment.userCode}", color = OnDark) },
            text = {
                Image(
                    bitmap = fullBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(420.dp)
                )
            }
        )
    }
}

private fun planAmountAdmin(plan: String): Int = when (plan) {
    "1m" -> 1000
    "3m" -> 2000
    "6m" -> 4000
    "12m" -> 7000
    else -> 0
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
            Row {
                if (log.source == "payment" && log.undoneAt == null) {
                    TextButton(onClick = { vm.refundFromLog(log) }) { Text("↩ ပြန်အမ်း", color = Danger, fontSize = 12.sp) }
                }
                if (log.undoneAt == null) {
                    TextButton(onClick = { vm.undoActivation(log) }) { Text("Undo", color = Amber, fontSize = 12.sp) }
                }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Switch(
                    checked = ui.announcementDryRun,
                    onCheckedChange = vm::setAnnouncementDryRun
                )
                Text(
                    "Dry-run (ကိုယ့်ကိုပဲ အစမ်းမြင်ရန်)",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp).weight(1f)
                )
            }
            androidx.compose.material3.Button(
                onClick = vm::publishAnnouncement,
                enabled = !ui.announcementSaving && ui.announcementText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Aqua, contentColor = Color(0xFF03211A))
            ) {
                Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(if (ui.announcementDryRun) "  ကိုယ်ပိုင်အစမ်းပို့ပါ" else "  ကြေညာချက် ပို့ပါ", fontWeight = FontWeight.Bold)
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
