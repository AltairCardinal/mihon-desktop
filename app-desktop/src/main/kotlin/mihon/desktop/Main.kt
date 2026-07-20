package mihon.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import mihon.desktop.di.initDesktopDI
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

    // Start test mode if enabled
    if (testArgs.testMode) {
        TestMode.start(testArgs)
    }

    val runtime = Injekt.get<DesktopAppRuntime>()
    val uiDependencies = DesktopUiDependencies.fromInjekt()
    runtime.start()

    if (runHeadlessMode(testArgs, runtime)) return

    application {
        Window(
            onCloseRequest = {
                runtime.close()
                exitApplication()
            },
            title = "Mihon Desktop $APP_VERSION",
            state = rememberWindowState(width = 1024.dp, height = 768.dp),
        ) {
            CompositionLocalProvider(LocalDesktopUiDependencies provides uiDependencies) {
                DesktopTheme {
                    Navigator(HomeScreen()) { navigator ->
                        SlideTransition(navigator)
                    }
                }
            }
        }
    }
}

internal fun runHeadlessMode(
    args: TestArguments,
    runtime: DesktopAppRuntime,
    awaitTestModeTermination: () -> Unit = TestMode::awaitTermination,
    stopTestMode: () -> Unit = TestMode::stop,
): Boolean {
    if (!args.headless) return false

    try {
        if (args.testMode) awaitTestModeTermination()
    } finally {
        if (args.testMode) stopTestMode()
        runtime.close()
    }
    return true
}
