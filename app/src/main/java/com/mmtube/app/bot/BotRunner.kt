package com.mmtube.app.bot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.mmtube.app.MMTubeApp
import com.mmtube.app.data.DownloadMode
import com.mmtube.app.data.DownloadState
import com.mmtube.app.data.ProbeResult
import com.mmtube.app.download.DownloadRequest
import com.mmtube.app.util.Formatters
import com.mmtube.app.util.UrlUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Owner-only Telegram bot running inside the app process.
 *
 * The bot is a remote control for this phone: send a link, it downloads at the
 * source's maximum quality and saves the file into the phone gallery. Nothing
 * is uploaded back to Telegram, so the 50 MB bot limit does not apply.
 */
class BotRunner(private val context: Context) {

    private val container = (context.applicationContext as MMTubeApp).container
    private val api = TelegramApi(
        BotSecrets.token(context) ?: throw TelegramException("Bot token is missing")
    )

    private val pending = LinkedHashMap<String, ProbeResult>()
    private val pendingLock = Any()

    suspend fun run() = coroutineScope {
        val me = withContext(Dispatchers.IO) { api.getMe() }
        val username = me.optString("username")
        BotSecrets.setBotUsername(context, username)
        BotSecrets.setEnabled(context, true)
        BotRuntime.update {
            it.copy(
                running = true,
                username = username,
                ownerChatId = BotSecrets.ownerChatId(context),
                lastError = null
            )
        }
        Log.i(TAG, "Bot @$username started (long polling)")

        var offset = BotSecrets.updateOffset(context)
        while (isActive) {
            val updates = try {
                withContext(Dispatchers.IO) { api.getUpdates(offset, 50) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "getUpdates failed", error)
                BotRuntime.update { it.copy(lastError = error.message) }
                delay(5_000L)
                continue
            }
            for (index in 0 until updates.length()) {
                val update = updates.optJSONObject(index) ?: continue
                offset = update.optLong("update_id", 0L) + 1L
                BotSecrets.setUpdateOffset(context, offset)
                try {
                    handleUpdate(update)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Could not handle update", error)
                    BotRuntime.update { it.copy(lastError = error.message) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- updates

    private suspend fun handleUpdate(update: JSONObject) {
        update.optJSONObject("callback_query")?.let {
            handleCallback(it)
            return
        }
        update.optJSONObject("message")?.let { handleMessage(it) }
    }

    private suspend fun handleMessage(message: JSONObject) {
        val chatId = message.optJSONObject("chat")?.optLong("id") ?: return
        val text = message.optString("text").trim()
        if (text.isEmpty()) return
        if (!ensureOwner(chatId)) return
        BotRuntime.update { it.copy(lastActivityAt = System.currentTimeMillis()) }

        when {
            text.startsWith("/start") || text.startsWith("/help") -> sendWelcome(chatId)
            text.startsWith("/status") -> sendStatus(chatId)
            else -> analyzeAndOffer(chatId, text)
        }
    }

    private suspend fun handleCallback(callback: JSONObject) {
        val callbackId = callback.optString("id")
        val data = callback.optString("data")
        val message = callback.optJSONObject("message")
        val chatId = message?.optJSONObject("chat")?.optLong("id") ?: 0L
        val messageId = message?.optInt("message_id") ?: 0

        if (chatId == 0L || !ensureOwner(chatId)) {
            api.answerCallback(callbackId)
            return
        }
        api.answerCallback(callbackId)

        val parts = data.split(":")
        if (parts.size != 3 || parts[0] != "dl") return
        val probe = synchronized(pendingLock) { pending.remove(parts[2]) }
        if (probe == null) {
            api.editMessage(chatId, messageId, "⌛ Session ကုန်သွားပြီ — link ပြန်ပို့ပါ။")
            return
        }
        if (container.downloads.isActive()) {
            api.editMessage(chatId, messageId, "⏳ တစ်ခု ဒေါင်းနေတယ် — ပြီးမှ ထပ်စမ်းပါ။")
            return
        }

        val mode = if (parts[1] == "mp3") DownloadMode.MP3 else DownloadMode.MAX
        val request = DownloadRequest(
            url = probe.url,
            title = probe.title,
            platform = probe.platform,
            mode = mode,
            thumbnail = probe.thumbnail,
            durationSeconds = probe.durationSeconds,
            qualityLabel = labelFor(probe, mode)
        )
        api.editMessage(
            chatId,
            messageId,
            "📥 <b>${escapeHtml(probe.title)}</b>\nဒေါင်းနေတယ်… (ဖုန်း gallery ထဲ သိမ်းမယ်)"
        )
        BotRuntime.update { it.copy(lastActivityAt = System.currentTimeMillis()) }
        container.downloads.start(request)
        trackJob(chatId, messageId, probe, mode)
    }

    // ------------------------------------------------------------- analysing

    private suspend fun analyzeAndOffer(chatId: Long, text: String) {
        val url = UrlUtils.normalize(text)
        if (url == null) {
            reply(
                chatId,
                "🔗 Link မမှန်ပါ။\nYouTube / Facebook / TikTok link ကို ကူးထည့်ပါ။"
            )
            return
        }
        val statusId = reply(chatId, "⏳ Link စစ်ဆေးနေတယ်…")
        api.sendChatAction(chatId, "typing")

        container.media.probe(url).fold(
            onSuccess = { probe ->
                val nonce = UUID.randomUUID().toString().take(8)
                synchronized(pendingLock) {
                    if (pending.size > 20) pending.remove(pending.keys.first())
                    pending[nonce] = probe
                }
                val quality = if (probe.maxHeight > 0) "${probe.maxHeight}p" else "Audio"
                val size = Formatters.formatBytes(probe.estimatedBytes)
                val keyboard = JSONObject().put(
                    "inline_keyboard",
                    JSONArray().put(
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("text", "⬇ $quality Download")
                                    .put("callback_data", "dl:max:$nonce")
                            )
                            .put(
                                JSONObject()
                                    .put("text", "🎵 MP3")
                                    .put("callback_data", "dl:mp3:$nonce")
                            )
                    )
                )
                val caption = buildString {
                    append("<b>").append(escapeHtml(probe.title)).append("</b>\n")
                    probe.uploader?.takeIf { it.isNotBlank() }?.let {
                        append("👤 ").append(escapeHtml(it)).append(" · ")
                    }
                    append("⏱ ").append(Formatters.formatDuration(probe.durationSeconds.toLong()))
                    append("\n📺 အမြင့်ဆုံး: <b>").append(quality).append("</b>")
                    if (size != "—") append(" · ~").append(size)
                    append("\n\nရွေးပါ —")
                }
                if (statusId != 0) {
                    api.editMessage(chatId, statusId, caption, keyboard)
                } else {
                    api.sendMessage(chatId, caption, keyboard)
                }
            },
            onFailure = { error ->
                Log.w(TAG, "Probe failed", error)
                val message = "❌ Link စစ်လို့မရပါ။\n" +
                    "Video ကို ဖွင့်ကြည့်လို့ရသေးလား စစ်ပါ၊ ဒါမှမဟုတ် နောက်မှ ထပ်စမ်းပါ။"
                if (statusId != 0) api.editMessage(chatId, statusId, message) else reply(chatId, message)
            }
        )
    }

    // ------------------------------------------------------------- downloads

    private suspend fun trackJob(
        chatId: Long,
        messageId: Int,
        probe: ProbeResult,
        mode: DownloadMode
    ) = coroutineScope {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMTube:botDownload")
            .apply { setReferenceCounted(false); acquire(SIX_HOURS) }
        val wifiLock = runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MMTube:botWifi")
                .apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        BotRuntime.update {
            it.copy(jobTitle = probe.title, jobProgress = 0f, jobLabel = "ဒေါင်းနေတယ်")
        }

        try {
            var lastEdit = 0L
            val progressJob = launch {
                container.downloads.state.collect { state ->
                    val now = System.currentTimeMillis()
                    when (state) {
                        is DownloadState.Running -> {
                            BotRuntime.update {
                                it.copy(jobProgress = state.progress, jobLabel = "ဒေါင်းနေတယ်")
                            }
                            if (now - lastEdit > 6_000L) {
                                lastEdit = now
                                val percent = state.progress.toInt().coerceIn(0, 100)
                                val text = buildString {
                                    append("📥 <b>").append(escapeHtml(probe.title)).append("</b>\n")
                                    append(percent).append("%")
                                    val total = Formatters.formatBytes(state.totalBytes)
                                    if (total != "—") append(" · ").append(total)
                                    val speed = Formatters.formatBytes(state.speedBytes)
                                    if (speed != "—") append(" · ").append(speed).append("/s")
                                }
                                api.editMessage(chatId, messageId, text)
                            }
                        }

                        is DownloadState.Merging -> {
                            BotRuntime.update { it.copy(jobLabel = "ပေါင်းစပ်နေတယ်") }
                            if (now - lastEdit > 4_000L) {
                                lastEdit = now
                                api.editMessage(
                                    chatId,
                                    messageId,
                                    "🧩 <b>${escapeHtml(probe.title)}</b>\nVideo + audio ပေါင်းစပ်နေတယ်…"
                                )
                            }
                        }

                        is DownloadState.Saving -> {
                            BotRuntime.update { it.copy(jobLabel = "သိမ်းနေတယ်") }
                            api.editMessage(
                                chatId,
                                messageId,
                                "💾 <b>${escapeHtml(probe.title)}</b>\nGallery ထဲ သိမ်းနေတယ်…"
                            )
                        }

                        else -> Unit
                    }
                }
            }

            val terminal = withTimeoutOrNull(SIX_HOURS) {
                container.downloads.state.first {
                    it is DownloadState.Completed ||
                        it is DownloadState.Failed ||
                        it is DownloadState.Cancelled
                }
            }
            progressJob.cancel()

            when (terminal) {
                is DownloadState.Completed -> {
                    val item = terminal.item
                    val size = Formatters.formatBytes(item.sizeBytes)
                    val folder = if (item.mode == DownloadMode.MP3.key) "Music/MMTube" else "Movies/MMTube"
                    val text = buildString {
                        append("✅ <b>သိမ်းပြီး</b>\n")
                        append(escapeHtml(item.title)).append("\n")
                        append(item.quality).append(" · ").append(size).append("\n")
                        if (item.savedToGallery) {
                            append("📂 Gallery → ").append(folder)
                        } else {
                            append("📂 App ထဲမှာ သိမ်းထားတယ် (Gallery ခွင့်ပြုချက် မရလို့)")
                        }
                    }
                    api.editMessage(chatId, messageId, text)
                }

                is DownloadState.Failed -> {
                    val detail = terminal.detail?.takeIf { it.isNotBlank() }
                    val text = buildString {
                        append("❌ ဒေါင်းလို့မရပါ။\n")
                        if (detail != null) append(escapeHtml(detail.take(200)))
                    }
                    api.editMessage(chatId, messageId, text)
                }

                is DownloadState.Cancelled -> {
                    api.editMessage(chatId, messageId, "⏹ ဒေါင်းတာ ရပ်သွားပြီ။")
                }

                null -> {
                    container.downloads.cancel()
                    api.editMessage(
                        chatId,
                        messageId,
                        "⌛ အချိန်ကြာလွန်းလို့ ရပ်လိုက်တယ် — နောက်မှ ထပ်စမ်းပါ။"
                    )
                }

                else -> Unit
            }
        } finally {
            runCatching { wakeLock.release() }
            runCatching { wifiLock?.release() }
            BotRuntime.update {
                it.copy(jobTitle = null, jobProgress = 0f, jobLabel = null)
            }
        }
    }

    // ------------------------------------------------------------- messaging

    private suspend fun reply(chatId: Long, text: String): Int =
        withContext(Dispatchers.IO) { api.sendMessage(chatId, text) }

    private suspend fun sendWelcome(chatId: Long) {
        reply(
            chatId,
            buildString {
                append("👋 <b>MMTube Bot</b>\n\n")
                append("YouTube / Facebook / TikTok link တစ်ခု ပို့ပါ — ")
                append("အမြင့်ဆုံး quality (4K အထိ) နဲ့ ဒေါင်းပြီး ")
                append("<b>ဒီဖုန်းရဲ့ Gallery</b> ထဲ သိမ်းပေးမယ်။\n\n")
                append("• ဖိုင်အရွယ် ကန့်သတ်မရှိ (ဖုန်းထဲ သိမ်းတာမို့)\n")
                append("• 4K ဖိုင်ဆို မိနစ်အနည်းငယ် ကြာနိုင်တယ်\n")
                append("• ဒီ bot ကို သတ်မှတ်ထားတဲ့ chat ပဲ သုံးနိုင်တယ်\n\n")
                append("/status — bot ရဲ့ အခြေအနေ")
            }
        )
    }

    private suspend fun sendStatus(chatId: Long) {
        val status = BotRuntime.status.value
        val engine = container.engine.state.value
        val text = buildString {
            append("🤖 Bot: ")
            append(if (status.username != null) "@${status.username}" else "—")
            append("\n🟢 ").append(if (status.running) "လည်နေတယ်" else "ရပ်ထားတယ်")
            append("\n👤 Chat ID: ").append(status.ownerChatId ?: "—")
            append("\n⚙️ Engine: ").append(
                when (engine) {
                    is com.mmtube.app.data.EngineState.Ready -> "Ready ${engine.version ?: ""}"
                    is com.mmtube.app.data.EngineState.Failed -> "Failed"
                    else -> "Initializing"
                }
            )
            status.lastError?.let { append("\n⚠️ ").append(escapeHtml(it.take(150))) }
        }
        reply(chatId, text)
    }

    private suspend fun ensureOwner(chatId: Long): Boolean {
        val owner = BotSecrets.ownerChatId(context)
        if (owner == null) {
            BotSecrets.setOwnerChatId(context, chatId)
            BotRuntime.update { it.copy(ownerChatId = chatId) }
            reply(
                chatId,
                "🔐 ဒီ bot ကို သင့် chat အတွက်ပဲ သတ်မှတ်လိုက်ပြီ။\n" +
                    "အခြားသူတွေ ဒီ bot ကို သုံးလို့ မရတော့ပါ။\n\n" +
                    "Link တစ်ခု ပို့ကြည့်ပါ 👇"
            )
            return true
        }
        if (owner != chatId) {
            reply(chatId, "🔒 ဒီ bot က သီးသန့်ပါ။ အသုံးပြုခွင့် မရှိပါ။")
            return false
        }
        return true
    }

    private fun labelFor(probe: ProbeResult, mode: DownloadMode): String =
        if (mode == DownloadMode.MP3) "MP3"
        else Formatters.qualityLabel(probe.maxHeight, probe.maxFps)

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private const val TAG = "BotRunner"
        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
    }
}
