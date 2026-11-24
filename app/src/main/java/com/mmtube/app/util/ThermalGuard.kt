package com.mmtube.app.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Reads the battery temperature and returns a fragment-count penalty. */
object ThermalGuard {

    fun celsius(context: Context): Float = runCatching {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = context.registerReceiver(null, filter)
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        tenths / 10f
    }.getOrDefault(0f)

    /** 0 when cool, up to 16 fragments removed when hot. */
    fun penalty(context: Context): Int = when {
        celsius(context) >= 45f -> 16
        celsius(context) >= 41f -> 8
        else -> 0
    }

    /** Optional rate limit in MB/s while the device is hot (0 = unlimited). */
    fun rateCapMb(context: Context): Int = when {
        celsius(context) >= 45f -> 6
        celsius(context) >= 41f -> 12
        else -> 0
    }

}
