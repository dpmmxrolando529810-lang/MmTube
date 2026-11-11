package com.mmtube.app.bot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owner-only bot settings.
 *
 * The Telegram token is **never** compiled into the APK. The owner pastes it
 * once on this device and it is encrypted with an AES-256 key that lives in
 * the Android Keystore (hardware-backed where available), so the stored blob
 * cannot be read on another device and is useless if the file is copied.
 */
object BotSecrets {

    private const val PREFS = "mmtube_bot_owner"
    private const val KEY_TOKEN = "token_enc"
    private const val KEY_OWNER = "owner_chat_id"
    private const val KEY_USERNAME = "bot_username"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OFFSET = "update_offset"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "mmtube_bot_token_key"
    private const val TAG = "BotSecrets"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun token(context: Context): String? {
        val stored = prefs(context).getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= 12) return@runCatching null
            val iv = raw.copyOfRange(0, 12)
            val body = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Could not decrypt the stored token", it) }.getOrNull()
    }

    fun setToken(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + body
        prefs(context).edit()
            .putString(KEY_TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun botUsername(context: Context): String? = prefs(context).getString(KEY_USERNAME, null)

    fun setBotUsername(context: Context, username: String?) {
        prefs(context).edit().putString(KEY_USERNAME, username).apply()
    }

    fun ownerChatId(context: Context): Long? =
        prefs(context).getLong(KEY_OWNER, 0L).takeIf { it != 0L }

    fun setOwnerChatId(context: Context, chatId: Long) {
        prefs(context).edit().putLong(KEY_OWNER, chatId).apply()
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun updateOffset(context: Context): Long = prefs(context).getLong(KEY_OFFSET, 0L)

    fun setUpdateOffset(context: Context, offset: Long) {
        prefs(context).edit().putLong(KEY_OFFSET, offset).apply()
    }

    /** Removes the token and everything tied to it, then stops the service. */
    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        }
    }
}
