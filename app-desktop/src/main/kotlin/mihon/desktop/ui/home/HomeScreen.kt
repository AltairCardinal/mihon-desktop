package mihon.desktop.ui.home

import mihon.desktop.LocalDesktopUiDependencies

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.network.CloudflareChallenge
import mihon.desktop.network.ChallengeRecoveryAction
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.browse.BrowseTab
import mihon.desktop.ui.authors.AuthorsTab
import mihon.desktop.ui.cloudflare.CloudflareBypassDialog
import mihon.desktop.ui.cloudflare.DesktopChallengeHomeAction
import mihon.desktop.ui.cloudflare.DesktopChallengeHomeActionAdapter
import mihon.desktop.ui.cloudflare.DesktopChallengeLoginController
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.migration.MigrationSearchScreen
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.reader.ReaderModeState
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.settings.DownloadSettingsScreen
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.updates.UpdatesTab

class HomeScreen : Screen {

    @Composable
    override fun Content() {
        var activeChallenge by remember { mutableStateOf<CloudflareChallenge?>(null) }
        val dependencies = LocalDesktopUiDependencies.current
        val challengeManager = dependencies.cloudflareChallengeManager
        val notificationService = dependencies.notificationService
        val controller = remember(challengeManager, dependencies.challengeBrowserLoginBridge, dependencies.appPreferences) {
            DesktopChallengeLoginController(
                challengeManager,
                dependencies.challengeBrowserLoginBridge,
                dependencies.appPreferences,
            )
        }
        val challengeActions = remember(controller) { DesktopChallengeHomeActionAdapter(controller) }
        val scope = rememberCoroutineScope()
        var actionJob by remember { mutableStateOf<Job?>(null) }
        val snackbarHostState = remember { SnackbarHostState() }

        DisposableEffect(Unit) {
            onDispose { actionJob?.cancel() }
        }

        // Cloudflare challenges
        LaunchedEffect(Unit) {
            challengeManager.challenges.collect { challenge ->
                actionJob?.cancel()
                activeChallenge = challenge
            }
        }

        // In-app notifications
        LaunchedEffect(Unit) {
            notificationService.notifications.collect { notification ->
                val msg = if (notification.title.isNotEmpty()) {
                    "${notification.title}: ${notification.message}"
                } else {
                    notification.message
                }
                snackbarHostState.showSnackbar(message = msg)
            }
        }

        activeChallenge?.let { challenge ->
            val recoveryState by challenge.state.collectAsState()
            val uiState = controller.uiState(challenge, recoveryState)
            val runAction: (DesktopChallengeHomeAction) -> Unit = { action ->
                val block: suspend () -> Unit = {
                    val result = challengeActions.execute({ activeChallenge }, challenge, action)
                    if (result.dismiss) activeChallenge = null
                    result.feedback?.let { snackbarHostState.showSnackbar(message = it) }
                }
                val alongsideActiveRecovery = action == DesktopChallengeHomeAction.Close ||
                    action == DesktopChallengeHomeAction.Recover(ChallengeRecoveryIntent.Cancel) ||
                    (action is DesktopChallengeHomeAction.SubmitClearance &&
                        uiState.runningAction == ChallengeRecoveryAction.Browser)
                if (alongsideActiveRecovery) {
                    scope.launch { block() }
                } else {
                    actionJob?.cancel()
                    actionJob = scope.launch { block() }
                }
            }
            CloudflareBypassDialog(
                state = uiState,
                onIntent = { runAction(DesktopChallengeHomeAction.Recover(it)) },
                onCookieSubmit = { runAction(DesktopChallengeHomeAction.SubmitClearance(it)) },
                onClose = { runAction(DesktopChallengeHomeAction.Close) },
            )
        }

        // Create Navigator at top level for Screen navigation
        Navigator(LibraryTab) { navigator ->
            LaunchedEffect(dependencies.externalActionNavigator, navigator) {
                dependencies.externalActionNavigator.consumeSignals(navigator, snackbarHostState::showSnackbar)
            }
            // Create TabNavigator for tab navigation
            TabNavigator(LibraryTab) { tabNavigator ->
                // Observe test navigation requests for tabs
                LaunchedEffect(Unit) {
                    TestNavigationController.pendingTabNavigation.collect { targetScreen ->
                        if (targetScreen != null) {
                            val tab = TestNavigationController.getTabOrNull(targetScreen)
                            if (tab != null) {
                                tabNavigator.current = tab
                            }
                            TestNavigationController.clearPendingTabNavigation()
                        }
                    }
                }

                // Observe test navigation requests for screens
                LaunchedEffect(Unit) {
                    TestNavigationController.pendingScreenNavigation.collect { screen ->
                        if (screen != null) {
                            navigator.push(screen)
                            TestNavigationController.clearPendingScreenNavigation()
                        }
                    }
                }

                // Observe pending pop requests (from close_reader, etc.)
                LaunchedEffect(Unit) {
                    TestNavigationController.pendingPop.collect { shouldPop ->
                        if (shouldPop && navigator.size > 1) {
                            navigator.pop()
                            TestNavigationController.clearPendingPop()
                        }
                    }
                }

                Scaffold(
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(snackbarData = data)
                        }
                    },
                    bottomBar = {
                        // Hide bottom navigation bar when in reader mode
                        if (!ReaderModeState.isInReaderMode) {
                            NavigationBar {
                                TabNavigationItem(LibraryTab)
                                TabNavigationItem(UpdatesTab)
                                TabNavigationItem(HistoryTab)
                                TabNavigationItem(BrowseTab)
                                TabNavigationItem(AuthorsTab)
                                TabNavigationItem(MoreTab)
                            }
                        }
                    },
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    ) {
                        // Show pushed screen if navigator has more than root, otherwise show current tab
                        // This uses the SAME navigator that receives pushes from TestNavigationController
                        if (navigator.size > 1) {
                            CurrentScreen()
                        } else {
                            CurrentTab()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TabNavigationItem(tab: Tab) {
    val tabNavigator = LocalTabNavigator.current
    NavigationBarItem(
        selected = tabNavigator.current == tab,
        onClick = { tabNavigator.current = tab },
        icon = {
            tab.options.icon?.let { painter ->
                Icon(painter = painter, contentDescription = tab.options.title)
            }
        },
        label = { Text(tab.options.title) },
    )
}
