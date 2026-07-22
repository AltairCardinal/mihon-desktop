package mihon.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.desktop.di.initDesktopDI
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.platform.DesktopOpenUriEventPort
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopUriSchemeRegistrar
import mihon.desktop.platform.DesktopUriSchemeRegistration
import mihon.desktop.platform.AwtDesktopOpenUriEventPort
import mihon.desktop.platform.DesktopOpenUriInstallResult
import mihon.desktop.privacy.DesktopWindowPrivacyController
import mihon.desktop.security.DesktopAppLock
import mihon.desktop.security.DesktopAppLockLifecycle
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import mihon.desktop.test.state.TestState
import mihon.desktop.test.state.applicationState
import mihon.desktop.ui.ExternalActionNavigator
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.security.DesktopProtectedRoot
import mihon.desktop.ui.theme.DesktopTheme
import mihon.domain.platform.ExternalActionInput
import mihon.domain.security.SecureScreenPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Main entry point for Mihon Desktop application.
 * Supports test mode via command-line arguments:
 * - --test-mode: Enable test mode
 * - --headless: Run without UI (for automated testing)
 */
suspend fun main(args: Array<String>) {
    // Install crash handler FIRST
    CrashHandler.install()

    startProductionDesktopApplication(args)
}

internal suspend fun startProductionDesktopApplication(
    args: Array<String>,
    broker: DesktopExternalActionBroker = DesktopExternalActionBroker(DesktopPlatformPaths.current().instanceStateFile),
    registrar: DesktopUriSchemeRegistrar = DesktopUriSchemeRegistration(),
    reportRegistration: (DesktopUriSchemeRegistration.Result) -> Unit = ::reportUriSchemeRegistration,
    openUriEventPort: DesktopOpenUriEventPort = AwtDesktopOpenUriEventPort(),
    ownerIngressDependencies: (DesktopOwnerTransaction) -> DesktopOwnerIngressDependencies = { transaction ->
        initDesktopDI()
        val runtime = Injekt.get<DesktopAppRuntime>()
        transaction.registerRuntime(runtime)
        DesktopOwnerIngressDependencies(runtime, DesktopUiDependencies.fromInjekt())
    },
    ownerLifecycle: suspend (TestArguments, DesktopOwnerStartup) -> Unit = ::runProductionOwnerApplication,
): DesktopInstanceStartResult {
    val transaction = DesktopOwnerTransaction()
    var owner: DesktopOwnerStartup? = null
    return try {
        val result = startDesktopInstance(broker, desktopExternalActionRaw(args)) { electedBroker ->
            reportDesktopOwnerRegistration(registrar, reportRegistration)
            owner = prepareDesktopOwner(transaction, electedBroker, args, openUriEventPort, ownerIngressDependencies)
        }
        owner?.let { ownerLifecycle(TestArguments.parse(args), it) }
        result
    } catch (failure: Throwable) {
        owner?.closeAndJoin(failure) ?: transaction.closeAndJoin(failure)
        throw failure
    }
}

internal fun startDesktopApplication(
    args: Array<String>,
    broker: DesktopExternalActionBroker = DesktopExternalActionBroker(DesktopPlatformPaths.current().instanceStateFile),
    registrar: DesktopUriSchemeRegistrar = DesktopUriSchemeRegistration(),
    reportRegistration: (DesktopUriSchemeRegistration.Result) -> Unit = ::reportUriSchemeRegistration,
    openUriEventPort: DesktopOpenUriEventPort = AwtDesktopOpenUriEventPort(),
    ownerIngressDependencies: (DesktopOwnerTransaction) -> DesktopOwnerIngressDependencies = { transaction ->
        initDesktopDI()
        val runtime = Injekt.get<DesktopAppRuntime>()
        transaction.registerRuntime(runtime)
        DesktopOwnerIngressDependencies(runtime, DesktopUiDependencies.fromInjekt())
    },
    ownerContinuation: ((DesktopOwnerIngressDependencies) -> Unit)? = null,
    startOwnerApplication: (DesktopExternalActionBroker) -> Unit = { ownerBroker ->
        runOwnerApplication(args, TestArguments.parse(args), ownerBroker, openUriEventPort, ownerIngressDependencies, ownerContinuation)
    },
): DesktopInstanceStartResult {
    return startDesktopInstance(broker, desktopExternalActionRaw(args)) { ownerBroker ->
        reportDesktopOwnerRegistration(registrar, reportRegistration)
        startOwnerApplication(ownerBroker)
    }
}

internal data class DesktopOwnerIngressDependencies(
    val runtime: DesktopAppRuntime,
    val uiDependencies: DesktopUiDependencies,
)

private fun reportUriSchemeRegistration(result: DesktopUriSchemeRegistration.Result) {
    if (result is DesktopUriSchemeRegistration.Result.Configured) return
    System.err.println("Desktop URI scheme capability: ${result::class.simpleName}")
}

private fun runOwnerApplication(
    args: Array<String>,
    testArgs: TestArguments,
    broker: DesktopExternalActionBroker,
    openUriEventPort: DesktopOpenUriEventPort,
    ownerIngressDependencies: (DesktopOwnerTransaction) -> DesktopOwnerIngressDependencies,
    ownerContinuation: ((DesktopOwnerIngressDependencies) -> Unit)?,
) {
    val transaction = DesktopOwnerTransaction()
    val owner = try {
        prepareDesktopOwner(transaction, broker, args, openUriEventPort, ownerIngressDependencies)
    } catch (failure: Throwable) {
        transaction.close(failure)
        throw failure
    }
    checkNotNull(ownerContinuation) { "Synchronous startup is a test continuation seam; production uses suspend main" }
    try {
        ownerContinuation(owner.ingress)
    } finally {
        owner.runtime.close()
    }
}

private suspend fun runProductionOwnerApplication(
    testArgs: TestArguments,
    owner: DesktopOwnerStartup,
) {
    try {
        runProductionOwnerLifecycle(
            testArgs = testArgs,
            runtime = owner.runtime,
            appLock = owner.appLock,
            testState = applicationState,
            startTestMode = { if (testArgs.testMode) TestMode.start(testArgs) },
            awaitTestModeTermination = TestMode::awaitTermination,
            stopTestMode = TestMode::stop,
        ) { closeAndJoin ->
            application {
                val applicationScope = rememberCoroutineScope()
                Window(
                    onCloseRequest = {
                        applicationScope.launch {
                            try {
                                closeAndJoin()
                            } finally {
                                exitApplication()
                            }
                        }
                    },
                    title = "Mihon Desktop $APP_VERSION",
                    state = rememberWindowState(width = 1024.dp, height = 768.dp),
                ) {
                    BindDesktopWindowLifecycle(window, owner.appLock, owner.windowPrivacyController)
                    OwnerUiDependencies(owner.ingress) {
                        DesktopTheme {
                            DesktopProtectedRoot(owner.appLock) {
                                Navigator(HomeScreen()) { navigator ->
                                    SlideTransition(navigator)
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        owner.closeAndJoin(failure)
        throw failure
    }
}

internal data class DesktopOwnerStartup(
    val ingress: DesktopOwnerIngressDependencies,
    val appLock: DesktopAppLock,
    val windowPrivacyController: DesktopWindowPrivacyController,
) {
    val runtime: DesktopAppRuntime get() = ingress.runtime

    suspend fun closeAndJoin(primary: Throwable) {
        try {
            runtime.closeAndJoin()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
        }
    }
}

private fun prepareDesktopOwner(
    transaction: DesktopOwnerTransaction,
    broker: DesktopExternalActionBroker,
    args: Array<String>,
    openUriEventPort: DesktopOpenUriEventPort,
    ownerIngressDependencies: (DesktopOwnerTransaction) -> DesktopOwnerIngressDependencies,
): DesktopOwnerStartup {
    val ingress = ownerIngressDependencies(transaction)
    transaction.registerRuntime(ingress.runtime)
    val startup = DesktopOwnerStartup(
        ingress = ingress,
        appLock = Injekt.get(),
        windowPrivacyController = Injekt.get(),
    )
    try {
        submitDesktopExternalAction(args, ingress.uiDependencies.externalActionNavigator)
        initializeDesktopOwnerExternalActionIngress(broker, ingress.uiDependencies.externalActionNavigator, startup.runtime, openUriEventPort)
        return startup
    } catch (failure: Throwable) { throw failure }
}

internal class DesktopOwnerTransaction {
    private var runtime: DesktopAppRuntime? = null

    fun registerRuntime(value: DesktopAppRuntime) {
        check(runtime == null || runtime === value) { "A different owner runtime is already registered" }
        runtime = value
    }

    fun close(primary: Throwable) {
        try {
            runtime?.close()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
        }
    }

    suspend fun closeAndJoin(primary: Throwable) {
        try {
            runtime?.closeAndJoin()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
        }
    }
}

private fun reportDesktopOwnerRegistration(
    registrar: DesktopUriSchemeRegistrar,
    reportRegistration: (DesktopUriSchemeRegistration.Result) -> Unit,
) {
    val registrationResult = try {
        registrar.register()
    } catch (_: Exception) {
        DesktopUriSchemeRegistration.Result.Failed(DesktopUriSchemeRegistration.FailureReason.UNEXPECTED_FAILURE)
    }
    runCatching { reportRegistration(registrationResult) }
}

@Composable
internal fun OwnerUiDependencies(
    owner: DesktopOwnerIngressDependencies,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDesktopUiDependencies provides owner.uiDependencies, content = content)
}

internal fun bootstrapDesktopRuntime(
    runtime: DesktopAppRuntime,
    appLock: DesktopAppLock,
    testState: TestState,
    startTestMode: () -> Unit,
): DesktopRuntimeBootstrapSession {
    val session = DesktopRuntimeBootstrapSession(runtime, DesktopAppLockTestStateBinding(appLock, testState))
    return try {
        runtime.start()
        startTestMode()
        session
    } catch (failure: Throwable) {
        try {
            session.close()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

internal suspend fun runProductionOwnerLifecycle(
    testArgs: TestArguments,
    runtime: DesktopAppRuntime,
    appLock: DesktopAppLock,
    testState: TestState,
    startTestMode: () -> Unit,
    awaitTestModeTermination: () -> Unit,
    stopTestMode: () -> Unit,
    runApplication: suspend (closeAndJoin: suspend () -> Unit) -> Unit,
) {
    val session = DesktopRuntimeBootstrapSession(runtime, DesktopAppLockTestStateBinding(appLock, testState))
    try {
        runtime.start()
        startTestMode()
    } catch (failure: Throwable) {
        try {
            session.closeAndJoin()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    try {
        if (testArgs.headless) {
            try {
                if (testArgs.testMode) awaitTestModeTermination()
            } finally {
                try {
                    if (testArgs.testMode) stopTestMode()
                } finally {
                    session.closeAndJoin()
                }
            }
        } else {
            runApplication(session::closeAndJoin)
        }
    } finally {
        session.closeAndJoin()
    }
}

internal class DesktopRuntimeBootstrapSession(
    private val runtime: DesktopAppRuntime,
    private val lockStateBinding: DesktopAppLockTestStateBinding,
) : AutoCloseable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        var primaryFailure: Throwable? = null
        try {
            runtime.close()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        try {
            lockStateBinding.close()
        } catch (failure: Throwable) {
            val primary = primaryFailure
            if (primary == null) primaryFailure = failure else if (failure !== primary) primary.addSuppressed(failure)
        }
        primaryFailure?.let { throw it }
        closed = true
    }

    suspend fun closeAndJoin() {
        var primaryFailure: Throwable? = null
        try {
            close()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        try {
            runtime.awaitClosed()
        } catch (failure: Throwable) {
            val primary = primaryFailure
            if (primary == null) primaryFailure = failure else if (failure !== primary) primary.addSuppressed(failure)
        }
        primaryFailure?.let { throw it }
    }
}

internal class DesktopAppLockTestStateBinding(
    appLock: DesktopAppLock,
    private val testState: TestState,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            appLock.state.collect { state -> testState.setAppLocked(state.requiresUnlock) }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

internal class DesktopWindowPrivacyBinding(
    window: AwtWindow?,
    private val controller: DesktopWindowPrivacyController,
    securityPreferences: SecurityPreferences,
    appPreferences: DesktopAppPreferences,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        controller.attach(window)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(
                securityPreferences.secureScreen().changes(),
                appPreferences.incognitoMode.changes(),
            ) { mode, incognito -> mode to SecureScreenPolicy.isProtected(mode, incognito) }
                .distinctUntilChanged()
                .collect { (mode, shouldProtect) -> controller.applyProtection(mode, shouldProtect) }
        }
    }

    override fun close() {
        scope.cancel()
        controller.clearAndDetach()
    }
}

internal class DesktopWindowFocusListener(
    private val lifecycle: DesktopAppLockLifecycle,
) : WindowFocusListener {
    override fun windowGainedFocus(event: WindowEvent?) = lifecycle.onApplicationStarted()
    override fun windowLostFocus(event: WindowEvent?) = lifecycle.onApplicationStopped()
}

internal class DesktopWindowFocusRegistration(
    lifecycle: DesktopAppLockLifecycle,
    private val removeListener: (WindowFocusListener) -> Unit,
    addListener: (WindowFocusListener) -> Unit,
) : AutoCloseable {
    private val listener = DesktopWindowFocusListener(lifecycle)
    private var closed = false

    init {
        addListener(listener)
    }

    override fun close() {
        if (closed) return
        closed = true
        removeListener(listener)
    }
}

@Composable
private fun BindDesktopWindowFocus(window: AwtWindow, lifecycle: DesktopAppLockLifecycle) {
    DisposableEffect(window, lifecycle) {
        val registration = DesktopWindowFocusRegistration(
            lifecycle = lifecycle,
            removeListener = window::removeWindowFocusListener,
            addListener = window::addWindowFocusListener,
        )
        onDispose(registration::close)
    }
}

@Composable
internal fun BindDesktopWindowLifecycle(
    window: AwtWindow?,
    appLockLifecycle: DesktopAppLockLifecycle,
    windowPrivacyController: DesktopWindowPrivacyController,
) {
    window?.let { BindDesktopWindowFocus(it, appLockLifecycle) }
    BindDesktopWindowPrivacy(window, windowPrivacyController)
}

@Composable
private fun BindDesktopWindowPrivacy(window: AwtWindow?, controller: DesktopWindowPrivacyController) {
    DisposableEffect(window, controller) {
        val binding = DesktopWindowPrivacyBinding(
            window = window,
            controller = controller,
            securityPreferences = controller.securityPreferences,
            appPreferences = controller.appPreferences,
        )
        onDispose(binding::close)
    }
}

internal fun desktopExternalActionRaw(args: Array<String>): String? = args.firstOrNull { !it.startsWith("--") }

internal fun desktopExternalActionInput(args: Array<String>): ExternalActionInput? =
    desktopExternalActionRaw(args)?.let(ExternalActionInput::ViewUri)

internal fun submitDesktopExternalAction(args: Array<String>, navigator: mihon.desktop.ui.ExternalActionNavigator) {
    desktopExternalActionInput(args)?.let(navigator::submit)
}

internal fun submitDesktopExternalAction(raw: String, navigator: ExternalActionNavigator) {
    navigator.submit(ExternalActionInput.ViewUri(raw))
}

internal fun wireDesktopExternalActionBroker(
    broker: DesktopExternalActionBroker,
    navigator: ExternalActionNavigator,
) {
    broker.setActionConsumer { raw -> submitDesktopExternalAction(raw, navigator) }
}

internal fun wireDesktopOpenUriEvents(
    port: mihon.desktop.platform.DesktopOpenUriEventPort,
    navigator: ExternalActionNavigator,
) = port.install { raw -> submitDesktopExternalAction(raw, navigator) }

internal fun initializeDesktopOwnerExternalActionIngress(
    broker: DesktopExternalActionBroker,
    navigator: ExternalActionNavigator,
    runtime: DesktopAppRuntime,
    openUriEventPort: DesktopOpenUriEventPort,
) {
    wireDesktopExternalActionBroker(broker, navigator)
    runtime.attachInstanceBroker(broker)
    val openUriResult = wireDesktopOpenUriEvents(openUriEventPort, navigator)
    (openUriResult as? DesktopOpenUriInstallResult.Installed)?.let { runtime.attachCloseable(it.registration) }
}

internal fun runHeadlessMode(
    args: TestArguments,
    runtime: DesktopAppRuntime,
    awaitTestModeTermination: () -> Unit = TestMode::awaitTermination,
    stopTestMode: () -> Unit = TestMode::stop,
    closeRuntime: () -> Unit = runtime::close,
): Boolean {
    if (!args.headless) return false

    try {
        if (args.testMode) awaitTestModeTermination()
    } finally {
        try {
            if (args.testMode) stopTestMode()
        } finally {
            closeRuntime()
        }
    }
    return true
}
