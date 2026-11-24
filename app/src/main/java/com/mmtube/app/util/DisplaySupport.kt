package com.mmtube.app.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** Whether this phone can actually show HDR (otherwise prefer bright SDR). */
object DisplaySupport {

    fun canRenderHdr(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val manager = context.getSystemService(DisplayManager::class.java)
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            val capabilities = display.hdrCapabilities ?: return false
            capabilities.supportedHdrTypes.isNotEmpty()
        }.getOrDefault(false)
    }
}
