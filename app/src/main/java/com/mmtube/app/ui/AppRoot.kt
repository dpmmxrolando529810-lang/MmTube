package com.mmtube.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mmtube.app.MainTab
import com.mmtube.app.MainViewModel
import com.mmtube.app.R
import com.mmtube.app.UiEvent
import com.mmtube.app.ui.components.AuroraBackground
import com.mmtube.app.ui.components.UpdateDialog
import com.mmtube.app.ui.components.rememberPressInteraction
import com.mmtube.app.ui.screens.AccountScreen
import com.mmtube.app.ui.screens.DownloadsScreen
import com.mmtube.app.ui.screens.HomeScreen
import com.mmtube.app.ui.screens.OwnerBotScreen
import com.mmtube.app.ui.screens.SettingsScreen
import com.mmtube.app.ui.screens.TermsScreen
import com.mmtube.app.ui.screens.UpgradeScreen
import com.mmtube.app.ui.theme.LocalMmtubeDark
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeTheme
import com.mmtube.app.ui.theme.MmtubeType

object Routes {
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
    const val ACCOUNT = "account"
    const val SETTINGS = "settings"
    const val UPGRADE = "upgrade"
    const val OWNER_BOT = "owner_bot"
}

private val MainTab.route: String
    get() = when (this) {
        MainTab.HOME -> Routes.HOME
        MainTab.DOWNLOADS -> Routes.DOWNLOADS
        MainTab.ACCOUNT -> Routes.ACCOUNT
    }

@Composable
fun AppRoot(
    viewModel: MainViewModel,
    onExit: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val appContext = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onAppForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MmtubeTheme(
        themeMode = ui.settings.theme,
        colorblindMode = ui.settings.colorblindMode,
        dynamicColor = ui.settings.dynamicColor,
        colorblindProfile = ui.settings.colorblindProfile
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val navController = rememberNavController()

        LaunchedEffect(Unit) {
            viewModel.events.collect { event ->
                when (event) {
                    is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
                    is UiEvent.OpenTab -> navController.navigate(event.tab.route) {
                        launchSingleTop = true
                        restoreState = true
                    }
                    UiEvent.OpenUpgrade -> navController.navigate(Routes.UPGRADE) {
                        launchSingleTop = true
                    }
                    UiEvent.Exit -> onExit()
                }
            }
        }

        if (!ui.termsAccepted) {
            TermsScreen(
                onAccept = viewModel::acceptTerms,
                onDecline = viewModel::declineTerms
            )
            return@MmtubeTheme
        }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route
        val showBottomBar = route == Routes.HOME || route == Routes.DOWNLOADS || route == Routes.ACCOUNT

        Box(Modifier.fillMaxSize()) {
            AuroraBackground(Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    snackbarHost = {
                        SnackbarHost(
                            snackbarHostState,
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(bottom = 8.dp)
                        )
                    },
                    bottomBar = {
                        if (showBottomBar) {
                            MmtubeBottomBar(
                                currentRoute = route,
                                onTab = { tab ->
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.padding(innerPadding),
                        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 16 } },
                        exitTransition = { fadeOut(tween(150)) },
                        popEnterTransition = { fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 16 } },
                        popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 16 } }
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                ui = ui,
                                vm = viewModel,
                                onSeeAll = { navController.navigate(Routes.DOWNLOADS) { launchSingleTop = true } },
                                onUpgrade = { navController.navigate(Routes.UPGRADE) },
                                onSettings = { navController.navigate(Routes.SETTINGS) }
                            )
                        }
                        composable(Routes.DOWNLOADS) {
                            DownloadsScreen(ui = ui, vm = viewModel)
                        }
                        composable(Routes.ACCOUNT) {
                            AccountScreen(
                                ui = ui,
                                vm = viewModel,
                                onUpgrade = { navController.navigate(Routes.UPGRADE) }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                ui = ui,
                                vm = viewModel,
                                onBack = { navController.popBackStack() },
                                onOpenOwner = {
                                    navController.navigate(Routes.OWNER_BOT) { launchSingleTop = true }
                                }
                            )
                        }
                        composable(Routes.OWNER_BOT) {
                            OwnerBotScreen(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.UPGRADE) {
                            UpgradeScreen(
                                ui = ui,
                                vm = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }

            if (ui.forceUpdate) {
                AlertDialog(
                    onDismissRequest = { /* blocking */ },
                    title = { Text(appContext.getString(R.string.update_required_title)) },
                    text = { Text(appContext.getString(R.string.update_required_body)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.openUpdatePage() }) {
                            Text(appContext.getString(R.string.update_required_action), color = MmtubePalette.Aqua)
                        }
                    }
                )
            }
            if (ui.showCelebration) {
                val celebrationContext = androidx.compose.ui.platform.LocalContext.current
                AlertDialog(
                    onDismissRequest = { viewModel.dismissCelebration() },
                    title = { Text(celebrationContext.getString(R.string.celebration_title)) },
                    text = { Text(celebrationContext.getString(R.string.celebration_body)) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissCelebration() }) {
                            Text("ရပြီ", color = MmtubePalette.Aqua)
                        }
                    },
                    containerColor = Color(0xFF0C3B2F)
                )
            }
            val clipboardLink = ui.clipboardLink
            if (clipboardLink != null) {
                AlertDialog(
                    onDismissRequest = viewModel::dismissClipboardLink,
                    title = { Text(appContext.getString(R.string.clipboard_title)) },
                    text = { Text(clipboardLink) },
                    confirmButton = {
                        TextButton(onClick = viewModel::useClipboardLink) {
                            Text(appContext.getString(R.string.clipboard_use), color = MmtubePalette.Aqua)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::dismissClipboardLink) {
                            Text(appContext.getString(R.string.action_cancel), color = MmtubePalette.Danger)
                        }
                    }
                )
            }
            val batchReport = ui.batchReport
            if (batchReport != null && (batchReport.failures.isNotEmpty() || batchReport.queued == 0)) {
                AlertDialog(
                    onDismissRequest = viewModel::dismissBatchReport,
                    title = { Text(appContext.getString(R.string.batch_report_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                appContext.getString(R.string.batch_report_queued, batchReport.queued)
                            )
                            batchReport.failures.take(8).forEach { failure ->
                                Text(
                                    "• " + failure.url.take(60) + " — " + failure.reason,
                                    style = MmtubeType.LabelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (batchReport.failures.size > 8) {
                                Text(
                                    appContext.getString(
                                        R.string.batch_report_more,
                                        batchReport.failures.size - 8
                                    ),
                                    style = MmtubeType.LabelSmall
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::dismissBatchReport) {
                            Text(appContext.getString(R.string.action_ok), color = MmtubePalette.Aqua)
                        }
                    }
                )
            }
            val duplicate = ui.duplicatePrompt
            if (duplicate != null) {
                val duplicateContext = androidx.compose.ui.platform.LocalContext.current
                AlertDialog(
                    onDismissRequest = viewModel::dismissDuplicate,
                    title = { Text(duplicateContext.getString(R.string.download_duplicate_title)) },
                    text = {
                        Text(
                            duplicateContext.getString(
                                R.string.download_duplicate_body,
                                duplicate.title
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::downloadAnyway) {
                            Text(duplicateContext.getString(R.string.download_duplicate_anyway), color = MmtubePalette.Aqua)
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = viewModel::openDuplicate) {
                                Text(duplicateContext.getString(R.string.download_duplicate_open), color = MmtubePalette.Aqua)
                            }
                            TextButton(onClick = viewModel::dismissDuplicate) {
                                Text(duplicateContext.getString(R.string.action_cancel), color = MmtubePalette.Danger)
                            }
                        }
                    }
                )
            }
            val whatsNew = ui.whatsNewVersion
            if (whatsNew != null) {
                WhatsNewDialog(version = whatsNew, onDismiss = viewModel::dismissWhatsNew)
            }
            val update = ui.update
            if (update.info != null && !update.dismissed) {
                UpdateDialog(
                    state = update,
                    onDownload = viewModel::downloadUpdate,
                    onInstall = viewModel::installUpdate,
                    onLater = viewModel::dismissUpdate,
                    onOpenPage = { viewModel.openUpdatePage() }
                )
            }
        }
    }
}

@Composable
private fun WhatsNewDialog(version: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = listOf(
        "App ထဲမှာတင် ဗီဒီယို ကြည့်နိုင်ပြီး ရပ်ခဲ့တဲ့နေရာက ဆက်ကြည့်နိုင်တယ် (speed / PiP ပါ)",
        "Clipboard ထဲမှာ link ရှိရင် app ဖွင့်တာနဲ့ ဒေါင်းမလားလို့ မေးတယ်",
        "မဒေါင်းခင် ဖိုင်အမည်ကို ကြိုတင်ပြင်နိုင်တယ်",
        "History နဲ့ settings ကို backup ထုတ်/ပြန်ထည့် လုပ်နိုင်တယ်",
        "Queue ကို အားလုံး pause/resume လုပ်နိုင်တယ်",
        "Batch link တွေမှာ မရတဲ့ link ကို အကြောင်းပြချက်နဲ့ ပြတယ်",
        "မှတ်တမ်းမှာ link ကူး/မျှဝေ၊ ဖိုင်အမည် ပြင်နိုင်တယ်",
        "Admin app မှာ EN / MY ဘာသာစကား ပြောင်းနိုင်တယ်"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.whats_new_title, version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Text("✦", color = MmtubePalette.Aqua, style = MmtubeType.LabelLarge)
                        Text(item, style = MmtubeType.BodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.whats_new_got_it), color = MmtubePalette.Aqua)
            }
        }
    )
}

@Composable
private fun MmtubeBottomBar(
    currentRoute: String?,
    onTab: (MainTab) -> Unit
) {
    val context = LocalContext.current
    val dark = LocalMmtubeDark.current
    val barColor = if (dark) Color(0xEE111B2C) else Color(0xF2FFFFFF)
    val outline = if (dark) Color(0x552FE3C0) else Color(0x330FA98D)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = barColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, outline),
            shadowElevation = 18.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomTab(
                    route = Routes.HOME,
                    currentRoute = currentRoute,
                    icon = Icons.Rounded.Home,
                    label = context.getString(R.string.nav_home),
                    onClick = { onTab(MainTab.HOME) },
                    modifier = Modifier.weight(1f)
                )
                BottomTab(
                    route = Routes.DOWNLOADS,
                    currentRoute = currentRoute,
                    icon = Icons.Rounded.Download,
                    label = context.getString(R.string.nav_downloads),
                    onClick = { onTab(MainTab.DOWNLOADS) },
                    modifier = Modifier.weight(1f)
                )
                BottomTab(
                    route = Routes.ACCOUNT,
                    currentRoute = currentRoute,
                    icon = Icons.Rounded.Person,
                    label = context.getString(R.string.nav_account),
                    onClick = { onTab(MainTab.ACCOUNT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BottomTab(
    route: String,
    currentRoute: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = currentRoute == route
    val interaction = rememberPressInteraction()
    val accent = MmtubePalette.Aqua
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = label,
            style = MmtubeType.LabelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
