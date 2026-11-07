package com.mmtube.admin

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.mmtube.admin.BuildConfig

object AdminFirebaseConfig {
    val apiKey: String = BuildConfig.FIREBASE_API_KEY.trim()
    val appId: String = BuildConfig.FIREBASE_APP_ID.trim()
    val projectId: String = BuildConfig.FIREBASE_PROJECT_ID.trim()
    val senderId: String = BuildConfig.FIREBASE_SENDER_ID.trim()
    val webClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank()

    val canSignIn: Boolean
        get() = isConfigured && webClientId.isNotBlank()
}

object AdminFirebaseBootstrap {
    private const val TAG = "AdminFirebaseBootstrap"

    fun init(context: Context): FirebaseApp? {
        if (!AdminFirebaseConfig.isConfigured) return null
        return runCatching {
            FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(AdminFirebaseConfig.apiKey)
                    .setApplicationId(AdminFirebaseConfig.appId)
                    .setProjectId(AdminFirebaseConfig.projectId)
                    .apply {
                        if (AdminFirebaseConfig.senderId.isNotBlank()) {
                            setGcmSenderId(AdminFirebaseConfig.senderId)
                        }
                    }
                    .build()
            )
        }.onFailure {
            Log.e(TAG, "Firebase initialization failed", it)
        }.getOrNull()
    }
}
