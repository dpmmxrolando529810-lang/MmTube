package com.mmtube.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.mmtube.app.data.AccountRepository
import com.mmtube.app.data.AuthRepository
import com.mmtube.app.data.EngineManager
import com.mmtube.app.data.FirebaseBootstrap
import com.mmtube.app.data.HistoryStore
import com.mmtube.app.data.MediaExporter
import com.mmtube.app.data.MediaRepository
import com.mmtube.app.data.QuotaManager
import com.mmtube.app.data.SettingsStore
import com.mmtube.app.data.UpdateRepository
import com.mmtube.app.download.DownloadController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsStore(appContext)
    val history = HistoryStore(appContext)
    val engine = EngineManager(appContext)

    private val firebaseApp: FirebaseApp? = FirebaseBootstrap.init(appContext)
    private val firestore: FirebaseFirestore? = firebaseApp?.let {
        FirebaseFirestore.getInstance(it)
    }
    private val auth: FirebaseAuth? = firebaseApp?.let {
        FirebaseAuth.getInstance(it)
    }

    val authRepository = AuthRepository(appContext, auth)
    val account = AccountRepository(authRepository, firestore, scope)
    val quota = QuotaManager(authRepository, account, settings, firestore)
    val media = MediaRepository(engine)
    val exporter = MediaExporter(appContext)
    val downloads = DownloadController(
        context = appContext,
        scope = scope,
        engine = engine,
        settingsStore = settings,
        historyStore = history,
        quotaManager = quota,
        exporter = exporter
    )
    val updater = UpdateRepository(appContext)

    fun bootstrap() {
        engine.start(scope)
        scope.launch {
            val snapshot = settings.snapshot()
            engine.autoUpdateIfDue(snapshot, settings)
        }
    }
}

class MMTubeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.bootstrap()
    }
}
