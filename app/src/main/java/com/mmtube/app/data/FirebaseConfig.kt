package com.mmtube.app.data

import com.mmtube.app.BuildConfig

object FirebaseConfig {
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
