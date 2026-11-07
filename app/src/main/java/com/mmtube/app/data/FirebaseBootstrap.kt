package com.mmtube.app.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseBootstrap {

    private const val TAG = "FirebaseBootstrap"

    fun init(context: Context): FirebaseApp? {
        if (!FirebaseConfig.isConfigured) return null
        return runCatching {
            FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(FirebaseConfig.apiKey)
                    .setApplicationId(FirebaseConfig.appId)
                    .setProjectId(FirebaseConfig.projectId)
                    .apply {
                        if (FirebaseConfig.senderId.isNotBlank()) {
                            setGcmSenderId(FirebaseConfig.senderId)
                        }
                    }
                    .build()
            )
        }.onFailure {
            Log.e(TAG, "Firebase initialization failed", it)
        }.getOrNull()
    }
}
