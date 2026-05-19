package mihon.desktop.ui.home

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import mihon.desktop.domain.DesktopNotificationService
import mihon.desktop.network.CloudflareChallenge
import mihon.desktop.network.CloudflareChallengeManager
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.browse.BrowseTab
import mihon.desktop.ui.cloudflare.CloudflareBypassDialog
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.reader.ReaderModeState
import mihon.desktop.ui.updates.UpdatesTab
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HomeScreen : Screen {

    @Composable
    override fun Content() {
        var activeChallenge by remember { mutableStateOf<CloudflareChallenge?>(null) }
        val challengeManager = remember { Injekt.get<CloudflareChallengeManager>() }
        val notificationService = remember { Injekt.get<DesktopNotificationService>() }
        val snackbarHostState = remember { SnackbarHostState() }

        // Cloudflare challenges
        LaunchedEffect(Unit) {
            challengeManager.challenges.collect { challenge ->
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
            val networkHelper = remember { Injekt.get<mihon.desktop.platform.DesktopNetworkHelper>() }
            CloudflareBypassDialog(
                url = challenge.url,
                onCookieSubmit = { cookieValue ->
                    networkHelper.cookieJar.addManual(
                        challenge.url.toHttpUrl(),
                        "cf_clearance",
                        cookieValue,
                    )
                    challenge.resolved = true
                    challenge.latch.countDown()
                    activeChallenge = null
                },
                onCancel = {
                    challenge.latch.countDown()
                    activeChallenge = null
                },
            )
        }

        // Create TabNavigator at this level to share with test navigation
        TabNavigator(LibraryTab) { tabNavigator ->
            // Observe test navigation requests
            LaunchedEffect(Unit) {
                TestNavigationController.pendingNavigation.collect { targetScreen ->
                    if (targetScreen != null) {
                        val tab = TestNavigationController.getTabOrNull(targetScreen)
                        if (tab != null) {
                            tabNavigator.current = tab
                        }
                        TestNavigationController.clearPendingNavigation()
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
                    CurrentTab()
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
