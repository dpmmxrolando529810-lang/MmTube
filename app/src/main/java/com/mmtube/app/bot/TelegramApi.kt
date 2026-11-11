package com.mmtube.app.bot

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramException(message: String) : Exception(message)

/**
 * Tiny Telegram Bot API client (long polling). Only the calls the owner bot
 * needs are implemented, so the app stays small and has no extra SDK.
 */
class TelegramApi(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val base = "https://api.telegram.org/bot$token/"

    private fun call(method: String, body: FormBody): JSONObject {
        val request = Request.Builder().url(base + method).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful || json == null || !json.optBoolean("ok")) {
                val description = json?.optString("description")
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                throw TelegramException(description)
            }
            return json
        }
    }

    fun getMe(): JSONObject {
        val result = call("getMe", FormBody.Builder().build()).getJSONObject("result")
        if (!result.optBoolean("is_bot", true)) throw TelegramException("This token is not a bot token")
        return result
    }

    fun getUpdates(offset: Long, timeoutSeconds: Int = 50): JSONArray {
        val body = FormBody.Builder()
            .add("offset", offset.toString())
            .add("timeout", timeoutSeconds.toString())
            .add("allowed_updates", "[\"message\",\"callback_query\"]")
            .build()
        return call("getUpdates", body).optJSONArray("result") ?: JSONArray()
    }

    fun sendMessage(chatId: Long, text: String, markup: JSONObject? = null): Int {
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("text", text.take(4000))
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .apply { if (markup != null) add("reply_markup", markup.toString()) }
            .build()
        return call("sendMessage", body)
            .optJSONObject("result")
            ?.optInt("message_id", 0)
            ?: 0
    }

    fun editMessage(chatId: Long, messageId: Int, text: String, markup: JSONObject? = null) {
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("message_id", messageId.toString())
            .add("text", text.take(4000))
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .apply { if (markup != null) add("reply_markup", markup.toString()) }
            .build()
        runCatching { call("editMessageText", body) }
            .onFailure { error ->
                // "message is not modified" is harmless (same progress text).
                val message = error.message.orEmpty()
                if (!message.contains("not modified", true)) throw error
            }
    }

    fun answerCallback(id: String, text: String? = null) {
        val body = FormBody.Builder()
            .add("callback_query_id", id)
            .apply { if (!text.isNullOrBlank()) add("text", text.take(190)) }
            .build()
        runCatching { call("answerCallbackQuery", body) }
    }

    fun sendChatAction(chatId: Long, action: String) {
        runCatching {
            call(
                "sendChatAction",
                FormBody.Builder().add("chat_id", chatId.toString()).add("action", action).build()
            )
        }
    }
}
