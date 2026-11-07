package com.mmtube.admin

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AdminContainer(context: Context) {
    val appContext: Context = context.applicationContext

    private val firebaseApp: FirebaseApp? = AdminFirebaseBootstrap.init(appContext)
    val firestore: FirebaseFirestore? = firebaseApp?.let { FirebaseFirestore.getInstance(it) }
    val auth: FirebaseAuth? = firebaseApp?.let { FirebaseAuth.getInstance(it) }
}

class AdminApp : Application() {
    lateinit var container: AdminContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AdminContainer(this)
    }
}
