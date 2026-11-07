package com.mmtube.admin

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mmtube.admin.ui.AdminRoot
import com.mmtube.admin.ui.AdminTheme

class AdminActivity : AppCompatActivity() {

    private val container: AdminContainer
        get() = (application as AdminApp).container

    private val viewModel: AdminViewModel by viewModels {
        AdminViewModel.factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AdminTheme {
                AdminRoot(viewModel = viewModel)
            }
        }
    }
}
