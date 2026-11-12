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

    MmtubeTheme(ui.settings.theme, ui.settings.colorblindMode, ui.settings.dynamicColor) {
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
