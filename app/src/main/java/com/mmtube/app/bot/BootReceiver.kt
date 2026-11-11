package com.mmtube.app.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the owner bot after a phone reboot, but only when the owner had
 * explicitly left it enabled. Normal app users never have a token, so nothing
 * is started for them.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!BotSecrets.isEnabled(context)) return
        if (BotSecrets.token(context).isNullOrBlank()) return
        runCatching { BotService.start(context) }
            .onFailure { Log.w("BootReceiver", "Could not restart the bot", it) }
    }
}
