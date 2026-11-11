package com.mmtube.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmtube.app.bot.BotRuntime
import com.mmtube.app.bot.BotSecrets
import com.mmtube.app.bot.BotService
import com.mmtube.app.bot.TelegramApi
import com.mmtube.app.ui.components.FrostedCard
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hidden owner-only screen. It is not reachable from any menu — the owner
 * opens it by tapping the version line in Settings seven times.
 *
 * The token is typed here, validated with getMe(), then stored encrypted with
 * the Android Keystore. It is never part of the APK and never shown again.
 */
@Composable
fun OwnerBotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BotRuntime.status.collectAsStateWithLifecycle()

    var tokenInput by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(BotSecrets.token(context) != null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        message = if (granted) "✅ Notification ခွင့်ပြုပြီ"
        else "⚠️ Notification မခွင့်ပြုထားရင် Android က bot ကို သတ်နိုင်တယ်"
    }

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
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Column {
                Text(
                    text = "Owner Bot",
                    style = MmtubeType.HeadlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "သီးသန့် — သာမန် user တွေ မမြင်ရပါ",
                    style = MmtubeType.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        FrostedCard {
            Text("📌 ဒီစာမျက်နှာ အကြောင်း", style = MmtubeType.TitleMedium)
            Text(
                "ဒီစာမျက်နှာကို ဆက်တင် အောက်ခြေက ဗားရှင်းစာသားကို ၇ ချက်နှိပ်မှ ရောက်လာတယ်။ " +
                    "Menu ထဲမှာ မပေါ်ပါဘူး။ Bot token ကို APK ထဲ ထည့်မထားဘဲ " +
                    "ဒီဖုန်းရဲ့ Android Keystore နဲ့ encrypt လုပ်ပြီး သိမ်းတယ်။ " +
                    "ဒါကြောင့် app ကို တခြားသူ သုံးရင်လည်း token ကို မြင်ရမှာ မဟုတ်ပါဘူး။",
                style = MmtubeType.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        FrostedCard {
            Text("📊 အခြေအနေ", style = MmtubeType.TitleMedium)
            Text(
                buildString {
                    append(if (status.running) "🟢 လည်နေတယ်" else "⚪ ရပ်ထားတယ်")
                    status.username?.let { append(" · @").append(it) }
                    append("\n👤 Chat ID: ")
                    append(status.ownerChatId?.toString() ?: "မသတ်မှတ်ရသေးဘူး (/start ပို့ပါ)")
                    status.jobTitle?.let {
                        append("\n📥 ").append(it).append(" — ")
                        append(status.jobProgress.toInt()).append("%")
                    }
                    status.lastError?.let { append("\n⚠️ ").append(it) }
                },
                style = MmtubeType.BodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        FrostedCard {
            Text("🔑 Bot Token", style = MmtubeType.TitleMedium)
            Text(
                if (hasToken) "✅ Token သိမ်းထားပြီး (မြင်ရမှာ မဟုတ်ပါ)"
                else "BotFather (@BotFather) ကနေ /newbot လုပ်ပြီး ရတဲ့ token ကို ဒီမှာ ထည့်ပါ။",
                style = MmtubeType.LabelSmall,
                color = MmtubePalette.Aqua,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("123456789:AA…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            Button(
                onClick = {
                    if (busy) return@Button
                    val token = tokenInput.trim()
                    if (token.isBlank()) {
                        message = "Token ထည့်ပါ။"
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val me = TelegramApi(token).getMe()
                                BotSecrets.setToken(context, token)
                                BotSecrets.setBotUsername(context, me.optString("username"))
                                me.optString("username")
                            }
                        }
                        result.onSuccess { username ->
                            hasToken = true
                            tokenInput = ""
                            message = "✅ Token သိမ်းပြီး — @$username"
                        }.onFailure { error ->
                            message = "❌ Token မမှန်ပါ — ${error.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(if (busy) "စစ်ဆေးနေတယ်…" else "သိမ်းပြီး စစ်ဆေး")
            }
            if (hasToken) {
                TextButton(
                    onClick = {
                        BotService.stop(context)
                        BotSecrets.forget(context)
                        hasToken = false
                        tokenInput = ""
                        message = "Token ဖျက်ပြီ။"
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Token ဖျက်မယ်", color = MmtubePalette.Danger)
                }
            }
        }

        FrostedCard {
            Text("▶️ Bot ထိန်းချုပ်", style = MmtubeType.TitleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (BotSecrets.token(context) == null) {
                            message = "အရင် token ထည့်ပါ။"
                        } else {
                            BotSecrets.setEnabled(context, true)
                            BotService.start(context)
                            message = "Bot စတင်နေတယ်…"
                        }
                    },
                    enabled = !status.running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("စတင်")
                }
                OutlinedButton(
                    onClick = {
                        BotService.stop(context)
                        message = "Bot ရပ်လိုက်တယ်။"
                    },
                    enabled = status.running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ရပ်")
                }
            }
        }

        FrostedCard {
            Text("🛡️ တည်ငြိမ်မှု သတ်မှတ်ချက်များ", style = MmtubeType.TitleMedium)
            Text(
                "Bot ကို ၂၄ နာရီ လည်နေစေဖို့ ဒီ ၂ ခုကို လုပ်ထားပါ။",
                style = MmtubeType.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!notificationsGranted) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text("🔔 Notification ခွင့်ပြု")
                }
            } else {
                Text(
                    "✅ Notification ခွင့်ပြုပြီ",
                    style = MmtubeType.LabelSmall,
                    color = MmtubePalette.Success,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            OutlinedButton(
                onClick = {
                    val packageUri = Uri.parse("package:" + context.packageName)
                    val request = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        packageUri
                    )
                    val opened = runCatching { context.startActivity(request) }.isSuccess
                    if (!opened) {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("🔋 Battery optimization ကနေ ခြွင်းချက်ပေး")
            }
        }

        message?.let {
            Text(
                text = it,
                style = MmtubeType.BodyMedium,
                color = MmtubePalette.Aqua,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            "⚠️ Bot လည်နေဖို့ ဖုန်းကို ပိတ်မထားပါနဲ့၊ အားသွင်းထားပြီး " +
                "battery optimization ကနေ MMTube ကို ခြွင်းချက်ပေးထားပါ။ " +
                "ဖုန်း restart ရင် အလိုအလျောက် ပြန်လည်မယ် (ဖုန်းအမျိုးအစားပေါ်မူတည်တယ်)။",
            style = MmtubeType.LabelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}
