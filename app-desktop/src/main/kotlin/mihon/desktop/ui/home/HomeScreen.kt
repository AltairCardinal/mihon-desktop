package mihon.desktop.ui.home

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.network.ChallengeRecoveryAction
import mihon.desktop.network.ChallengeRecoveryIntent
import mihon.desktop.network.CloudflareChallenge
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.authors.AuthorDetailScreen
import mihon.desktop.ui.cloudflare.CloudflareBypassDialog
import mihon.desktop.ui.cloudflare.DesktopChallengeHomeAction
import mihon.desktop.ui.cloudflare.DesktopChallengeHomeActionAdapter
import mihon.desktop.ui.cloudflare.DesktopChallengeLoginController
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.library.LocalLibraryNavigationHost
import mihon.desktop.ui.reader.ReaderModeState
import tachiyomi.i18n.MR

class HomeScreen : Screen {

    @Composable
    override fun Content() {
        var activeChallenge by remember { mutableStateOf<CloudflareChallenge?>(null) }
        val dependencies = LocalDesktopUiDependencies.current
        val libraryNavigationHost = LocalLibraryNavigationHost.current
        val historyNavigationHost = mihon.desktop.ui.history.LocalHistoryNavigationHost.current
        val navigator = LocalNavigator.currentOrThrow
        val challengePort = dependencies.challengeUiPort
        val notificationService = dependencies.notificationService
        val authorDiscoveries by remember(dependencies.creatorArchive) {
            dependencies.creatorArchive?.observeUnread(100L) ?: flowOf(emptyList())
        }.collectAsState(emptyList())
        val controller =
            remember(challengePort, dependencies.challengeBrowserLoginBridge, dependencies.appPreferences) {
                DesktopChallengeLoginController(
                    challengePort,
                    dependencies.challengeBrowserLoginBridge,
                    dependencies.appPreferences,
                )
            }
        val challengeActions = remember(controller) { DesktopChallengeHomeActionAdapter(controller) }
        val scope = rememberCoroutineScope()
        var actionJob by remember { mutableStateOf<Job?>(null) }
        val snackbarHostState = remember { SnackbarHostState() }
        val externalActionFeedback = remember { ExternalActionFeedbackDispatcher() }

        DisposableEffect(externalActionFeedback) {
            onDispose {
                actionJob?.cancel()
                externalActionFeedback.close()
            }
        }

        LaunchedEffect(externalActionFeedback, snackbarHostState) {
            externalActionFeedback.consume { message -> snackbarHostState.showSnackbar(message) }
        }

        // Cloudflare challenges
        LaunchedEffect(Unit) {
            challengePort.challenges.collect { challenge ->
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
                val result = snackbarHostState.showSnackbar(
                    message = msg,
                    actionLabel = notification.creatorId?.let { MR.strings.action_open.localized() },
                )
                if (result == SnackbarResult.ActionPerformed) {
                    notification.creatorId?.let { navigator.push(AuthorDetailScreen(it)) }
                }
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
                    (
                        action is DesktopChallengeHomeAction.SubmitClearance &&
                            uiState.runningAction == ChallengeRecoveryAction.Browser
                        )
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
                dependencies.externalActionNavigator.consumeSignals(navigator) { message ->
                    externalActionFeedback.tryPublish(message)
                }
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

                HomeNavigationHost(
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        if (tabNavigator.current == LibraryTab && event.type == KeyEventType.KeyUp &&
                            !event.isCtrlPressed
                        ) {
                            libraryNavigationHost.onCtrlReleased()
                        }
                        false
                    },
                    current = tabNavigator.current,
                    onSelect = { tab ->
                        if (tabNavigator.current == tab) {
                            if (tab == LibraryTab) libraryNavigationHost.onReselect()
                            if (tab == mihon.desktop.ui.history.HistoryTab) historyNavigationHost.onReselect()
                        } else {
                            tabNavigator.current = tab
                        }
                    },
                    showNavigation = !ReaderModeState.isInReaderMode && navigator.size == 1,
                    badgeCount = authorDiscoveries.size,
                    snackbar = {
                        SnackbarHost(hostState = snackbarHostState) { data -> Snackbar(snackbarData = data) }
                    },
                ) {
                    if (navigator.size > 1) CurrentScreen() else CurrentTab()
                }
            }
        }
    }
}

internal class ExternalActionFeedbackDispatcher(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val messages = Channel<String>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun tryPublish(message: String): Boolean = messages.trySend(message).isSuccess

    suspend fun consume(showFeedback: suspend (String) -> Unit) {
        for (message in messages) {
            showFeedback(message)
        }
    }

    fun close() {
        messages.close()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
