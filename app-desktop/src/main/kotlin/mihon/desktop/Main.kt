package mihon.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDI
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.domain.ReaderModeMemoryCleaner
import mihon.desktop.source.LocalSourceScanService
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.theme.DesktopTheme
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Main entry point for Mihon Desktop application.
 * Supports test mode via command-line arguments:
 * - --test-mode: Enable test mode
 * - --headless: Run without UI (for automated testing)
 */
fun main(args: Array<String>) {
    // Install crash handler FIRST
    CrashHandler.install()

    // Parse test arguments
    val testArgs = TestArguments.parse(args)

    // Initialize DI
    initDesktopDI()

    runCatching {
        runBlocking {
            Injekt.get<ReaderModeMemoryCleaner>().clearNonFavoriteManga()
        }
    }

    // Start test mode if enabled
    if (testArgs.testMode) {
        TestMode.start(testArgs)
    }

    // Start background services
    Injekt.get<LibraryUpdateScheduler>().start()
    Injekt.get<LocalSourceScanService>().start()

    // Skip UI in headless mode
    if (testArgs.headless) {
        return
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Mihon Desktop $APP_VERSION",
            state = rememberWindowState(width = 1024.dp, height = 768.dp),
        ) {
            DesktopTheme {
                Navigator(HomeScreen()) { navigator ->
                    SlideTransition(navigator)
                }
            }
        }
    }
}
