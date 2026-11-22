package com.example

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.navigation.AppRoute
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.GameDetailsScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.StreamPlayerScreen
import com.example.ui.screens.SupportScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.ShubhankarCloudTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShubhankarCloudTheme {
                ShubhankarAppShell(viewModel = viewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewModel.inputBus.onKeyEvent(event)
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.inputBus.onGenericMotionEvent(event)
        return if (handled) true else super.onGenericMotionEvent(event)
    }
}

private data class TopTab(
    val route: AppRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TOP_TABS = listOf(
    TopTab(AppRoute.Home, "Home", Icons.Rounded.Home, Icons.Outlined.Home, "tab_home"),
    TopTab(AppRoute.Library, "Library", Icons.Rounded.SportsEsports, Icons.Outlined.SportsEsports, "tab_library"),
    TopTab(AppRoute.Wallet, "Wallet", Icons.Rounded.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet, "tab_wallet"),
    TopTab(AppRoute.History, "History", Icons.Rounded.History, Icons.Outlined.History, "tab_history"),
    TopTab(AppRoute.Profile, "Profile", Icons.Rounded.Person, Icons.Outlined.Person, "tab_profile")
)

@Composable
fun ShubhankarAppShell(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val touchPlacements by viewModel.touchPlacements.collectAsStateWithLifecycle()
    val streamTelemetry by viewModel.webRtcManager.telemetry.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isBootstrapping, uiState.isAuthenticated) {
        if (!uiState.isBootstrapping) {
            val target = if (uiState.isAuthenticated) AppRoute.Home else AppRoute.Auth
            val currentlyOnSplash = currentDest?.hasRoute<AppRoute.Splash>() == true
            val currentlyOnAuth = currentDest?.hasRoute<AppRoute.Auth>() == true
            if (currentlyOnSplash || (uiState.isAuthenticated && currentlyOnAuth)) {
                navController.navigate(target) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        uiState.statusBannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val isTopLevelRoute = TOP_TABS.any { tab -> currentDest?.hasRoute(tab.route::class) == true }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = if (currentDest?.hasRoute<AppRoute.StreamPlayer>() == true) {
                WindowInsets(0, 0, 0, 0)
            } else {
                WindowInsets.safeDrawing
            },
            bottomBar = {
                if (isTopLevelRoute && !useNavigationRail) {
                    NavigationBar {
                        TOP_TABS.forEach { tab ->
                            val selected = currentDest?.hasRoute(tab.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(AppRoute.Home) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isTopLevelRoute && useNavigationRail) {
                    NavigationRail {
                        TOP_TABS.forEach { tab ->
                            val selected = currentDest?.hasRoute(tab.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(AppRoute.Home) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = AppRoute.Splash,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<AppRoute.Splash> {
                        SplashScreen()
                    }
                    composable<AppRoute.Auth> {
                        AuthScreen(
                            isBusy = uiState.isAuthBusy,
                            authError = uiState.authError,
                            serverReachable = uiState.serverReachable,
                            onlineNodes = uiState.serverHealth?.onlineGpuNodes ?: 0,
                            currentServerUrl = settings.serverBaseUrl,
                            onLogin = viewModel::login,
                            onRegister = viewModel::register,
                            onGoogleToken = viewModel::signInWithGoogleToken,
                            onAuthError = viewModel::setAuthError,
                            onSaveServerUrl = viewModel::updateServerUrl
                        )
                    }
                    composable<AppRoute.Home> {
                        HomeDashboardScreen(
                            uiState = uiState,
                            networkStatus = networkStatus,
                            onNavigateToWallet = { navController.navigate(AppRoute.Wallet) },
                            onNavigateToLibrary = { navController.navigate(AppRoute.Library) },
                            onNavigateToSettings = { navController.navigate(AppRoute.Settings) },
                            onGameSelected = { game -> navController.navigate(AppRoute.GameDetail(game.id)) },
                            onClaimDailyReward = viewModel::claimDailyReward,
                            onRefresh = viewModel::refreshAllUserData
                        )
                    }
                    composable<AppRoute.Library> {
                        LibraryScreen(
                            games = uiState.games,
                            searchQuery = uiState.searchQuery,
                            selectedCategory = uiState.selectedCategory,
                            isSyncing = uiState.isSyncingGames,
                            onSearchChange = viewModel::updateSearch,
                            onCategoryChange = viewModel::updateCategory,
                            onGameClick = { game -> navController.navigate(AppRoute.GameDetail(game.id)) },
                            onRetrySync = viewModel::syncGames
                        )
                    }
                    composable<AppRoute.GameDetail> { entry ->
                        val route = entry.toRoute<AppRoute.GameDetail>()
                        val game = uiState.games.find { it.id == route.gameId }
                        val balance = uiState.wallet?.balance ?: uiState.user?.coinBalance ?: 0
                        GameDetailsScreen(
                            game = game,
                            coinBalance = balance,
                            settings = settings,
                            isStartingSession = uiState.isStartingSession,
                            onBack = { navController.popBackStack() },
                            onOpenWallet = { navController.navigate(AppRoute.Wallet) },
                            onStartGame = { hours, preset, codec ->
                                if (game != null) {
                                    viewModel.startCloudGameSession(game, hours, preset, codec) {
                                        navController.navigate(AppRoute.StreamPlayer)
                                    }
                                }
                            }
                        )
                    }
                    composable<AppRoute.Wallet> {
                        WalletScreen(
                            wallet = uiState.wallet,
                            fallbackBalance = uiState.user?.coinBalance ?: 0,
                            isBusy = uiState.isWalletBusy,
                            onClaimDailyReward = viewModel::claimDailyReward
                        )
                    }
                    composable<AppRoute.History> {
                        HistoryScreen(
                            history = uiState.sessionHistory,
                            onRefresh = viewModel::refreshHistory
                        )
                    }
                    composable<AppRoute.Profile> {
                        ProfileScreen(
                            user = uiState.user,
                            coinBalance = uiState.wallet?.balance ?: uiState.user?.coinBalance ?: 0,
                            gpuNodes = uiState.gpuNodes,
                            onOpenSettings = { navController.navigate(AppRoute.Settings) },
                            onOpenSupport = { navController.navigate(AppRoute.Support) },
                            onSignOut = {
                                viewModel.logout {
                                    navController.navigate(AppRoute.Auth) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            }
                        )
                    }
                    composable<AppRoute.Support> {
                        SupportScreen(
                            tickets = uiState.supportTickets,
                            onBack = { navController.popBackStack() },
                            onSubmitTicket = viewModel::submitSupportTicket
                        )
                    }
                    composable<AppRoute.Settings> {
                        SettingsScreen(
                            settings = settings,
                            onBack = { navController.popBackStack() },
                            onSaveServerUrl = viewModel::updateServerUrl,
                            onQualitySelected = viewModel::updateQualityPreset,
                            onCodecSelected = viewModel::updateCodec,
                            onLowMemoryToggle = viewModel::updateLowMemoryMode,
                            onHapticToggle = viewModel::updateHapticFeedback,
                            onTelemetryToggle = viewModel::updateShowTelemetry,
                            onResetTouchLayout = viewModel::resetTouchControls
                        )
                    }
                    composable<AppRoute.StreamPlayer> {
                        StreamPlayerScreen(
                            session = uiState.activeSession,
                            remainingSeconds = uiState.remainingSessionSeconds,
                            warningBanner = uiState.sessionWarningBanner,
                            telemetry = streamTelemetry,
                            settings = settings,
                            touchPlacements = touchPlacements,
                            webRtcManager = viewModel.webRtcManager,
                            inputBus = viewModel.inputBus,
                            onSaveButtonPosition = viewModel::saveTouchControlPosition,
                            onExitStream = {
                                viewModel.endActiveSession {
                                    navController.popBackStack()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
