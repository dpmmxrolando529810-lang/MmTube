package com.mmtube.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mmtube.app.ui.AppRoot

class MainActivity : AppCompatActivity() {

    private val container: AppContainer
        get() = (application as MMTubeApp).container

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()
        requestLegacyStoragePermission()

        setContent {
            AppRoot(
                viewModel = viewModel,
                onExit = { finishAffinity() }
            )
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    viewModel.pasteLink(text)
                }
            }
            Intent.ACTION_VIEW -> {
                val link = intent.dataString
                if (!link.isNullOrBlank()) {
                    viewModel.pasteLink(link)
                }
            }
            else -> {
                if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
                    viewModel.requestOpenDownloads()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
    }

    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val granted = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        private const val REQUEST_NOTIFICATIONS = 4101
        private const val REQUEST_STORAGE = 4102
    }
}
