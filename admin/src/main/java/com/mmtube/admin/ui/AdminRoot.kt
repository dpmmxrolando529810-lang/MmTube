package com.mmtube.admin.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShieldMoon
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.BasicTextField
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mmtube.admin.AdminAuthState
import com.mmtube.admin.AdminUiState
import com.mmtube.admin.AdminUser
import com.mmtube.admin.AdminViewModel
import com.mmtube.admin.Plans
import com.mmtube.admin.R

private val Amber = Color(0xFF4CC4B0)
private val Aqua = Color(0xFF2FE3C0)
private val Danger = Color(0xFFFF6B81)
private val OnDark = Color(0xFFF2F5FF)
private val Muted = Color(0xFF8B97B5)

@Composable
fun AdminRoot(viewModel: AdminViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = Color(0xFF05070D),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        AdminBackdrop(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (val auth = ui.auth) {
                AdminAuthState.Loading -> CenteredLoading(LocalContext.current.getString(R.string.admin_loading))
                AdminAuthState.NotConfigured -> NotConfiguredScreen()
                AdminAuthState.SignedOut -> SignInScreen(
                    busy = ui.busy,
                    onSignIn = { email, password -> viewModel.signInWithEmail(email, password) }
                )
                AdminAuthState.Checking -> CenteredLoading(LocalContext.current.getString(R.string.admin_checking))
                is AdminAuthState.Denied -> DeniedScreen(
                    uid = auth.uid,
                    email = auth.email,
                    onRetry = viewModel::retryAdminCheck,
                    onSignOut = viewModel::signOut,
                    onCopy = { viewModel.copyText(auth.uid, "uid") }
                )
                is AdminAuthState.Ready -> Dashboard(ui = ui, vm = viewModel)
            }

            // Update-downloading pill — small amber line across the very top.
            if (ui.update.downloading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Update downloading v${ui.update.info?.version.orEmpty()} — ${ui.update.progress}%",
                            color = OnDark,
                            fontSize = 12.sp
                        )
                        LinearProgressIndicator(
                            progress = { ui.update.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = Amber,
                            trackColor = Muted,
                        )
                    }
                }
            }

            // Update ready → one-tap install (auto-launched too).
            val updateFile = ui.update.file
            if (updateFile != null && !ui.update.dismissed) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdate() },
                    containerColor = Color(0xFF05070D),
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.installUpdate() }) {
                            Text("Install now", color = Amber)
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { viewModel.dismissUpdate() }) {
                            Text("Later", color = Muted)
                        }
                    },
                    title = { Text("Update ready", color = OnDark, fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "MMTube Admin version ${ui.update.info?.version.orEmpty()} finished downloading. Android requires one tap on Install to complete.",
                            color = OnDark
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CenteredLoading(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AdminMark(size = 84.dp)
        CircularProgressIndicator(
            modifier = Modifier.padding(top = 26.dp).size(30.dp),
            strokeWidth = 3.dp,
            color = Amber
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

@Composable
private fun AdminMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(Brush.linearGradient(listOf(Amber, Color(0xFFFF8A3D)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.AdminPanelSettings,
            contentDescription = null,
            tint = Color(0xFF332000),
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

@Composable
private fun SignInScreen(
    busy: Boolean,
    onSignIn: (String, String) -> Unit
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AdminMark(size = 96.dp)
        Text(
            text = context.getString(R.string.admin_app_name),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = OnDark
            ),
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = context.getString(R.string.admin_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                capitalization = KeyboardCapitalization.None
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        )

        Button(
            onClick = { onSignIn(email, password) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .height(56.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = context.getString(R.string.admin_sign_in_btn),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            text = context.getString(R.string.admin_sign_in_note),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 14.dp)
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0x14FFB84D),
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
                Text(
                    text = context.getString(R.string.admin_secure_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }
    }
}

@Composable
private fun NotConfiguredScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Amber, modifier = Modifier.size(72.dp))
        Text(
            text = context.getString(R.string.admin_not_configured),
            style = MaterialTheme.typography.titleLarge,
            color = OnDark,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            text = context.getString(R.string.admin_not_configured_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        AdminCard(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            listOf(
                "FIREBASE_API_KEY",
                "FIREBASE_ADMIN_APP_ID",
                "FIREBASE_PROJECT_ID",
                "FIREBASE_SENDER_ID",
                "GOOGLE_WEB_CLIENT_ID"
            ).forEach {
                Text(
                    text = "•  $it",
                    fontSize = 12.sp,
                    color = Aqua,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DeniedScreen(
    uid: String,
    email: String?,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Danger.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.ShieldMoon, contentDescription = null, tint = Danger, modifier = Modifier.size(44.dp))
        }
        Text(
            text = context.getString(R.string.admin_denied_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = OnDark,
            modifier = Modifier.padding(top = 20.dp)
        )
        email?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
        Text(
            text = context.getString(R.string.admin_denied_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        AdminCard(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Text(
                text = context.getString(R.string.admin_uid),
                fontSize = 11.sp,
                color = Muted,
                letterSpacing = 1.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text(
                    text = uid,
                    fontSize = 12.sp,
                    color = Aqua,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = onCopy,
                    shape = CircleShape,
                    color = Aqua.copy(alpha = 0.15f)
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = context.getString(R.string.admin_copy_uid),
                        tint = Aqua,
                        modifier = Modifier.padding(10.dp).size(18.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                color = Amber,
                modifier = Modifier.height(50.dp)
            ) {
                Box(Modifier.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                    Text(context.getString(R.string.admin_retry), fontWeight = FontWeight.Bold, color = Color(0xFF332000))
                }
            }
            Surface(
                onClick = onSignOut,
                shape = RoundedCornerShape(14.dp),
                color = Color(0x1AFFFFFF),
                modifier = Modifier.height(50.dp)
            ) {
                Box(Modifier.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                    Text(context.getString(R.string.admin_sign_out), color = OnDark)
                }
            }
        }
    }
}
