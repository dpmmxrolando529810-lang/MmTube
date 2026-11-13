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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mmtube.app.MainUiState
import com.mmtube.app.MainViewModel
import com.mmtube.app.R
import com.mmtube.app.data.AuthState
import com.mmtube.app.data.FirebaseConfig
import com.mmtube.app.data.Plans
import com.mmtube.app.ui.components.ConfirmDialog
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.components.GradientButton
import com.mmtube.app.ui.components.MmtubeLogo
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import com.mmtube.app.util.Formatters
import com.mmtube.app.util.findActivity

@Composable
fun AccountScreen(
    ui: MainUiState,
    vm: MainViewModel,
    onUpgrade: () -> Unit
) {
    val context = LocalContext.current
    var signOutConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var emailSignInEmail by remember { mutableStateOf("") }
    var emailSignInPassword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = context.getString(R.string.account_title),
                style = MmtubeType.DisplaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = context.getString(R.string.account_subtitle),
                style = MmtubeType.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (val auth = ui.account.auth) {
            is AuthState.Loading -> LoadingProfile()
            AuthState.SignedOut, is AuthState.Anonymous -> GuestAccountCard(
                busy = ui.accountBusy,
                firebaseConfigured = FirebaseConfig.isConfigured,
                email = emailSignInEmail,
                password = emailSignInPassword,
                onEmailChange = { emailSignInEmail = it },
                onPasswordChange = { emailSignInPassword = it },
                onEmailSignIn = { vm.signInWithEmail(emailSignInEmail, emailSignInPassword, createAccount = false) },
                onEmailCreate = { vm.signInWithEmail(emailSignInEmail, emailSignInPassword, createAccount = true) },
                onSignIn = {
                    context.findActivity()?.let { vm.signIn(it) }
                }
            )
            is AuthState.SignedIn -> SignedInCard(ui = ui, vm = vm, onUpgrade = onUpgrade)
            is AuthState.Error -> Text(
                text = auth.message,
                color = MmtubePalette.Danger,
                style = MmtubeType.BodyMedium
            )
        }

        ui.account.error?.takeIf { it.isNotBlank() && ui.account.isSignedIn }?.let { message ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = MmtubePalette.Danger, modifier = Modifier.size(16.dp))
                Text(message, style = MmtubeType.BodyMedium, color = MmtubePalette.Danger)
            }
        }

        if (ui.account.isSignedIn) {
            AccountActions(
                busy = ui.accountBusy,
                onRefresh = vm::refreshAccount,
                onSignOut = { signOutConfirm = true },
                onDelete = { deleteConfirm = true }
            )
        }

        Text(
            text = context.getString(R.string.account_admin_hint),
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }

    if (signOutConfirm) {
        ConfirmDialog(
            title = context.getString(R.string.account_sign_out_confirm),
            body = context.getString(R.string.account_sign_out),
            confirmLabel = context.getString(R.string.account_sign_out),
            onConfirm = {
                vm.signOut()
                signOutConfirm = false
            },
            onDismiss = { signOutConfirm = false }
        )
    }
    if (deleteConfirm) {
        ConfirmDialog(
            title = context.getString(R.string.account_delete_confirm),
            body = context.getString(R.string.account_delete_account_desc),
            confirmLabel = context.getString(R.string.account_delete_account),
            onConfirm = {
                vm.deleteAccountData()
                deleteConfirm = false
            },
            onDismiss = { deleteConfirm = false },
            danger = true
        )
    }
}

@Composable
private fun LoadingProfile() {
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(66.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Column {
                Box(Modifier.size(140.dp, 16.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Box(Modifier.size(96.dp, 12.dp).padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
}

@Composable
private fun GuestAccountCard(
    busy: Boolean,
    firebaseConfigured: Boolean,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onEmailSignIn: () -> Unit,
    onEmailCreate: () -> Unit,
    onSignIn: () -> Unit
) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MmtubePalette.Aqua.copy(alpha = 0.35f), MmtubePalette.Blue.copy(alpha = 0.35f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MmtubePalette.Aqua, modifier = Modifier.size(48.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = context.getString(R.string.account_guest),
                    style = MmtubeType.TitleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = context.getString(R.string.account_guest_desc),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (firebaseConfigured) {
                Text(
                    text = context.getString(R.string.account_email_hint),
                    style = MmtubeType.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onEmailSignIn,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (busy) "…" else context.getString(R.string.account_email_sign_in))
                    }
                    Button(
                        onClick = onEmailCreate,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (busy) "…" else context.getString(R.string.account_email_create))
                    }
                }
                GoogleSignInButton(busy = busy, onClick = onSignIn)
                Text(
                    text = context.getString(R.string.account_sign_in_note),
                    style = MmtubeType.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                CloudDisabledCard()
            }
        }
    }
}

@Composable
private fun CloudDisabledCard() {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MmtubePalette.Amber.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MmtubePalette.Amber, modifier = Modifier.size(26.dp))
            Column {
                Text(
                    text = context.getString(R.string.account_cloud_disabled),
                    style = MmtubeType.TitleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = context.getString(R.string.account_cloud_disabled_desc),
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GoogleSignInButton(busy: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Surface(
        onClick = { if (!busy) onClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDADCE0)),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFF4285F4))
            } else {
                Text(
                    text = "G",
                    style = MmtubeType.HeadlineMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
                    color = Color(0xFF4285F4)
                )
            }
            Text(
                text = context.getString(R.string.account_sign_in_google),
                style = MmtubeType.LabelLarge.copy(fontSize = 16.sp),
                color = Color(0xFF1F1F1F),
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun SignedInCard(
    ui: MainUiState,
    vm: MainViewModel,
    onUpgrade: () -> Unit
) {
    val context = LocalContext.current
    val auth = ui.account.auth as? AuthState.SignedIn
    val profile = ui.account.profile
    val usage = ui.account.usage
    val paid = profile?.isPaid == true
    val name = profile?.displayName?.takeIf { it.isNotBlank() } ?: auth?.displayName ?: "MMTube member"
    val email = profile?.email ?: auth?.email
    val photo = profile?.photoUrl ?: auth?.photoUrl

    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        ui.latestPayment?.let { p ->
            val (label, color) = when (p.status) {
                "approved" -> context.getString(R.string.payment_status_approved) to MmtubePalette.Success
                "rejected" -> context.getString(R.string.payment_status_rejected) to MmtubePalette.Danger
                "cancelled" -> "Cancelled" to MmtubePalette.Danger
                else -> context.getString(R.string.payment_status_pending) to MmtubePalette.Amber
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(
                        text = "ငွေပေးချေမှု: " + when (p.plan) {
                            "1m" -> "1 Month"
                            "3m" -> "3 Months"
                            "6m" -> "6 Months"
                            "12m" -> "12 Months"
                            else -> p.plan
                        } + " — " + label + " (" + p.amountKs.toString() + " Ks)",
                        style = MmtubeType.BodyMedium,
                        color = color,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MmtubePalette.Aqua, MmtubePalette.Blue))),
                contentAlignment = Alignment.Center
            ) {
                if (photo != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(photo).crossfade(true).build(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MmtubeType.HeadlineMedium,
                        color = Color(0xFF03211A)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MmtubeType.TitleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                email?.let {
                    Text(it, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (paid) Icons.Rounded.Verified else Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = if (paid) MmtubePalette.Aqua else MmtubePalette.Blue,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (paid) context.getString(R.string.account_plan_unlimited) else context.getString(R.string.account_plan_free),
                        style = MmtubeType.LabelLarge,
                        color = if (paid) MmtubePalette.Aqua else MmtubePalette.Blue
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        InfoRow(
            label = context.getString(R.string.account_user_id),
            value = profile?.userCode ?: "—",
            action = if (profile != null) Icons.Rounded.ContentCopy else null,
            onAction = if (profile != null) ({ vm.copyUserCode() }) else null,
            hint = context.getString(R.string.account_user_id_note)
        )
        InfoRow(
            label = context.getString(R.string.account_plan),
            value = if (paid) context.getString(R.string.account_plan_unlimited) else context.getString(R.string.account_plan_free),
            subValue = when {
                paid && profile?.planExpiresAt != null -> context.getString(R.string.account_plan_expires, Formatters.formatDate(profile.planExpiresAt))
                paid -> context.getString(R.string.account_plan_no_expiry)
                else -> null
            }
        )
        InfoRow(
            label = context.getString(R.string.account_member_since),
            value = Formatters.formatDate(profile?.createdAt),
            singleLineValue = true
        )
        InfoRow(
            label = context.getString(R.string.account_total_downloads),
            value = (usage?.totalDownloads ?: ui.quota?.totalDownloads ?: 0).toString()
        )

        if (!paid) {
            GradientButton(
                text = context.getString(R.string.account_upgrade),
                onClick = onUpgrade,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                icon = Icons.Rounded.Bolt
            )
        }
        PurchaseHistorySection(vm = vm)
    }
}

@Composable
private fun PurchaseHistorySection(vm: MainViewModel) {
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<com.mmtube.app.data.PurchaseRecord>?>(null) }
    LaunchedEffect(Unit) {
        vm.loadPurchaseHistory { records = it }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = context.getString(R.string.purchase_history_title),
            style = MmtubeType.TitleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (records == null) {
            Text(context.getString(R.string.loading), style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (records.orEmpty().isEmpty()) {
            Text(context.getString(R.string.purchase_history_empty), style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                records.orEmpty().take(6).forEach { record ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = MmtubePalette.Amber, modifier = Modifier.size(18.dp))
                        Text(
                            text = when (record.plan) {
                                Plans.M1 -> "1 Month"
                                Plans.M3 -> "3 Months"
                                Plans.M6 -> "6 Months"
                                Plans.M12 -> "12 Months"
                                else -> "Free"
                            },
                            style = MmtubeType.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp).weight(1f)
                        )
                        Text(
                            text = Formatters.formatDateBurmese(record.createdAt),
                            style = MmtubeType.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    subValue: String? = null,
    action: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onAction: (() -> Unit)? = null,
    hint: String? = null,
    singleLineValue: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MmtubeType.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MmtubeType.TitleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (singleLineValue) 1 else 2
            )
            subValue?.let {
                Text(it, style = MmtubeType.BodyMedium, color = MmtubePalette.Aqua)
            }
            hint?.let {
                Text(it, style = MmtubeType.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null && onAction != null) {
            IconButton(onClick = onAction) {
                Icon(action, contentDescription = null, tint = MmtubePalette.Blue)
            }
        }
    }
}

@Composable
private fun AccountActions(
    busy: Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    FrostedCard(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { if (!busy) onRefresh() },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(context.getString(R.string.account_refresh), style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MmtubePalette.Aqua)
            }
        }
        Surface(
            onClick = { if (!busy) onSignOut() },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(context.getString(R.string.account_sign_out), style = MmtubeType.TitleMedium, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onDelete, modifier = Modifier.padding(top = 4.dp).fillMaxWidth()) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MmtubePalette.Danger, modifier = Modifier.size(18.dp))
            Text(
                text = context.getString(R.string.account_delete_account),
                color = MmtubePalette.Danger,
                style = MmtubeType.LabelLarge,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}
