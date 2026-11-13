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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.mmtube.app.R
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.components.GradientButton
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.mmtube.app.util.Formatters
import com.mmtube.app.util.copyText
import com.mmtube.app.data.PaymentSummary
import android.util.Base64

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
                    Icons.AutoMirrored.Rounded.ArrowBack,
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
                "1m" to "1 Month — 1,000 Ks",
                "3m" to "3 Months — 2,000 Ks",
                "6m" to "6 Months — 4,000 Ks",
                "12m" to "12 Months — 7,000 Ks"
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
        PaymentCard()
        PaymentProofCard(ui = ui, vm = vm, selected = selected, isActive = isActive)
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

private fun planAmountKs(plan: String): Int = when (plan) {
    "1m" -> 1000
    "3m" -> 2000
    "6m" -> 4000
    "12m" -> 7000
    else -> 0
}

private fun planLabel(plan: String): String = when (plan) {
    "1m" -> "1 Month"
    "3m" -> "3 Months"
    "6m" -> "6 Months"
    "12m" -> "12 Months"
    else -> plan
}

private fun compressImageToBase64(context: android.content.Context, uri: android.net.Uri): String? {
    return runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val bytes = input.readBytes()
        input.close()
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > 1280) {
            val factor = 1280f / longest
            bitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt(),
                (bitmap.height * factor).toInt(),
                true
            )
        }
        var quality = 72
        var encoded: String? = null
        while (quality > 0 && encoded == null) {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            val arr = out.toByteArray()
            if (arr.size <= 500_000 || quality <= 30) {
                encoded = Base64.encodeToString(arr, Base64.NO_WRAP)
            }
            quality -= 17
        }
        encoded
    }.getOrNull()
}

@Composable
private fun PaymentProofCard(
    ui: MainUiState,
    vm: MainViewModel,
    selected: String,
    isActive: Boolean
) {
    val context = LocalContext.current
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var imageBase64 by remember { mutableStateOf<String?>(null) }
    var imageAttached by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    val signedIn = ui.account.profile != null
    val latest = ui.latestPayment

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageBase64 = compressImageToBase64(context, uri)
            imageAttached = imageBase64 != null
            if (imageBase64 == null) {
                Toast.makeText(context, "ဓာတ်ပုံဖိုင်ပြဿနာ — နောက်တစ်ပုံ စမ်းပါ", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "ဓာတ်ပုံထည့်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.getString(R.string.payment_card_title),
            style = MmtubeType.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = context.getString(R.string.payment_card_desc),
            style = MmtubeType.BodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MmtubePalette.Aqua.copy(alpha = 0.12f),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = MmtubePalette.Aqua, modifier = Modifier.size(18.dp))
                Text(
                    text = context.getString(R.string.payment_plan_amount, planLabel(selected), planAmountKs(selected)),
                    style = MmtubeType.LabelLarge,
                    color = MmtubePalette.Aqua,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch == '+' } },
            label = { Text(context.getString(R.string.payment_phone_label), style = MmtubeType.BodyMedium) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            colors = OutlinedTextFieldDefaults.colors()
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(context.getString(R.string.payment_note_label), style = MmtubeType.BodyMedium) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = OutlinedTextFieldDefaults.colors()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { pickImage.launch("image/*") },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    text = context.getString(R.string.payment_pick_image),
                    style = MmtubeType.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            if (imageAttached) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MmtubePalette.Aqua, modifier = Modifier.size(22.dp))
            }
        }

        Button(
            onClick = {
                val payload = imageBase64
                if (payload == null) {
                    Toast.makeText(context, context.getString(R.string.payment_no_image), Toast.LENGTH_SHORT).show()
                    return@Button
                }
                confirmSubmit = true
            },
            enabled = !submitting && signedIn,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MmtubePalette.BlueDeep, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(
                text = if (submitting) context.getString(R.string.payment_submitting) else context.getString(R.string.payment_submit),
                style = MmtubeType.LabelLarge,
                color = Color.White
            )
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            title = { Text(context.getString(R.string.payment_confirm_title)) },
            text = { Text(context.getString(R.string.payment_confirm_body, planLabel(selected), planAmountKs(selected))) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmSubmit = false
                    val payload = imageBase64 ?: return@TextButton
                    submitting = true
                    vm.submitPayment(selected, phone, note, payload) { ok, error ->
                        submitting = false
                        if (ok) {
                            Toast.makeText(context, context.getString(R.string.payment_submit_ok), Toast.LENGTH_LONG).show()
                            imageBase64 = null
                            imageAttached = false
                            note = ""
                        } else {
                            Toast.makeText(context, context.getString(R.string.payment_submit_err, error ?: "?"), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(context.getString(R.string.payment_confirm_yes)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmSubmit = false }) {
                    Text(context.getString(android.R.string.cancel))
                }
            }
        )

        if (latest?.status == "pending") {
            TextButton(
                onClick = { vm.cancelMyPendingPayment() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Text(context.getString(R.string.payment_cancel_submit), color = MmtubePalette.Danger)
            }
        }
        latest?.let { p ->
            val (label, color) = when (p.status) {
                "approved" -> context.getString(R.string.payment_status_approved) to MmtubePalette.Success
                "rejected" -> context.getString(R.string.payment_status_rejected) to MmtubePalette.Danger
                else -> context.getString(R.string.payment_status_pending) to MmtubePalette.Amber
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = color,
                    modifier = Modifier.size(10.dp)
                ) {}
                Text(
                    text = context.getString(R.string.payment_latest_title) + ": " +
                        planLabel(p.plan) + " — " + p.amountKs + " Ks · " + label,
                    style = MmtubeType.LabelSmall,
                    color = color,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            p.adminNote?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    text = reason,
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PaymentCard() {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.getString(R.string.upgrade_payment_title),
            style = MmtubeType.TitleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "09989037775",
                    style = MmtubeType.HeadlineMedium.copy(letterSpacing = 2.sp),
                    color = MmtubePalette.Aqua
                )
                Text(
                    text = "Thu Khant Aung",
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                context.copyText("KBZPay", "09989037775")
                Toast.makeText(context, "KBZ Pay number copied", Toast.LENGTH_SHORT).show()
            }) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Copy",
                    tint = MmtubePalette.Blue
                )
            }
        }
        Image(
            painter = painterResource(R.drawable.kbzpay_qr),
            contentDescription = "KBZ Pay QR — Thu Khant Aung",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(14.dp))
                .padding(top = 12.dp)
        )
        Text(
            text = context.getString(R.string.upgrade_payment_desc),
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
