package mihon.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import mihon.desktop.di.initDesktopDI
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import mihon.desktop.ui.ExternalActionNavigator
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.theme.DesktopTheme
import mihon.domain.platform.ExternalActionInput
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

    val testArgs = TestArguments.parse(args)
    val broker = DesktopExternalActionBroker(DesktopPlatformPaths.current().instanceStateFile)
    val rawAction = desktopExternalActionRaw(args)
    startDesktopInstance(broker, rawAction) { ownerBroker ->
        runOwnerApplication(args, testArgs, ownerBroker)
    }
}

private fun runOwnerApplication(
    args: Array<String>,
    testArgs: TestArguments,
    broker: DesktopExternalActionBroker,
) {
    initDesktopDI()
    // Start test mode if enabled
    if (testArgs.testMode) {
        TestMode.start(testArgs)
    }

    val runtime = Injekt.get<DesktopAppRuntime>()
    val uiDependencies = DesktopUiDependencies.fromInjekt()
    wireDesktopExternalActionBroker(broker, uiDependencies.externalActionNavigator)
    submitDesktopExternalAction(args, uiDependencies.externalActionNavigator)
    runtime.attachInstanceBroker(broker)
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

internal fun desktopExternalActionRaw(args: Array<String>): String? = args.firstOrNull { !it.startsWith("--") }

internal fun desktopExternalActionInput(args: Array<String>): ExternalActionInput? =
    desktopExternalActionRaw(args)?.let(ExternalActionInput::ViewUri)

internal fun submitDesktopExternalAction(args: Array<String>, navigator: mihon.desktop.ui.ExternalActionNavigator) {
    desktopExternalActionInput(args)?.let(navigator::submit)
}

internal fun wireDesktopExternalActionBroker(
    broker: DesktopExternalActionBroker,
    navigator: ExternalActionNavigator,
) {
    broker.setActionConsumer { raw -> navigator.submit(ExternalActionInput.ViewUri(raw)) }
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
        try {
            if (args.testMode) stopTestMode()
        } finally {
            runtime.close()
        }
    }
    return true
}
