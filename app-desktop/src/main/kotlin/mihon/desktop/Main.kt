package mihon.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.desktop.di.initDesktopDI
import mihon.desktop.image.installDesktopImageLoader
import mihon.desktop.platform.AwtDesktopOpenUriEventPort
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.platform.DesktopOpenUriEventPort
import mihon.desktop.platform.DesktopOpenUriInstallResult
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.platform.DesktopTestProfile
import mihon.desktop.platform.DesktopUriSchemeRegistrar
import mihon.desktop.platform.DesktopUriSchemeRegistration
import mihon.desktop.privacy.DesktopWindowPrivacyController
import mihon.desktop.release.desktopExtensionRuntimeAcceptanceRequest
import mihon.desktop.release.executeDesktopExtensionRuntimeAcceptance
import mihon.desktop.security.DesktopAppLock
import mihon.desktop.security.DesktopAppLockLifecycle
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.test.TestArguments
import mihon.desktop.test.TestMode
import mihon.desktop.test.navigation.BindTestNavigationController
import mihon.desktop.test.state.TestState
import mihon.desktop.test.state.applicationState
import mihon.desktop.tracking.DesktopTrackerOAuthCallbackBroker
import mihon.desktop.ui.ExternalActionNavigator
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.security.DesktopProtectedRoot
import mihon.desktop.ui.settings.DesktopLocaleFeedbackHost
import mihon.desktop.ui.theme.DesktopTheme
import mihon.domain.platform.ExternalActionInput
import mihon.domain.security.SecureScreenPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import java.awt.Window as AwtWindow

/**
 * Main entry point for Mihon Desktop application.
 * Supports test mode via command-line arguments:
 * - --test-mode: Enable test mode
 * - --headless: Run without UI (for automated testing)
 */
suspend fun main(args: Array<String>) {
    DesktopTestProfile.configure(args)
    try {
        mihon.desktop.platform.DesktopRecoveryProfile.configure(args)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        mihon.desktop.sync.DesktopStartupRecovery.show(failure,
            args.firstOrNull { it.startsWith("--recovery-profile=") }?.substringAfter('='))
        return
    }
    // Profile selection must precede crash paths, preferences, the instance broker, and DI.
    CrashHandler.install()

    desktopExtensionRuntimeAcceptanceRequest(args)?.let { request ->
        val result = executeDesktopExtensionRuntimeAcceptance(request, APP_VERSION)
        exitProcess(if (result.success) 0 else 1)
    }

    startProductionDesktopApplication(args)
}

internal suspend fun startProductionDesktopApplication(
    args: Array<String>,
    onStartupFailure: suspend (Throwable) -> Unit = {
        val testArgs = TestArguments.parse(args)
        if (testArgs.testMode || testArgs.headless) throw it
        mihon.desktop.sync.DesktopStartupRecovery.show(it)
    },
    broker: DesktopExternalActionBroker = DesktopExternalActionBroker(
        (mihon.desktop.test.desktopTestProfile(args)?.paths ?: DesktopPlatformPaths.current()).instanceStateFile,
    ),
    registrar: DesktopUriSchemeRegistrar = DesktopUriSchemeRegistration(),
    reportRegistration: (DesktopUriSchemeRegistration.Result) -> Unit = ::reportUriSchemeRegistration,
    openUriEventPort: DesktopOpenUriEventPort = AwtDesktopOpenUriEventPort(),
    ownerIngressDependencies: suspend (DesktopOwnerTransaction) -> DesktopOwnerIngressDependencies = { transaction ->
        val profile = mihon.desktop.test.desktopTestProfile(args)
        val syncRepository = TestArguments.parse(args).syncRepository
        val syncScope = syncRepository?.let(mihon.data.sync.auth.SyncRepositoryScope::acceptance)
            ?: mihon.data.sync.auth.SyncRepositoryScope.Default
        if (profile == null) {
            initDesktopDI(syncScope)
        } else {
            initDesktopDI(profile.paths, profile.preferences(), syncScope)
        }
        val runtime = Injekt.get<DesktopAppRuntime>()
        transaction.registerRuntime(runtime)
        DesktopOwnerIngressDependencies(runtime, DesktopUiDependencies.fromInjekt())
    },
    startTestMode: (TestArguments) -> Unit = { testArgs -> if (testArgs.testMode) TestMode.start(testArgs) },
    awaitTestModeTermination: () -> Unit = TestMode::awaitTermination,
    stopTestMode: () -> Unit = TestMode::stop,
    runWindowEventLoop: suspend (DesktopOwnerStartup, DesktopApplicationCloseCoordinator) -> Unit =
        ::runDesktopComposeWindowEventLoop,
): DesktopInstanceStartResult {
    val transaction = DesktopOwnerTransaction()
    var owner: DesktopOwnerStartup? = null
    var electedOwner: DesktopExternalActionBroker? = null
    var lifecycleStarted = false
    val testArgs = TestArguments.parse(args)
    return try {
        val result = startDesktopInstance(broker, desktopExternalActionRaw(args)) { electedBroker ->
            if (testArgs.testProfile == null && mihon.desktop.test.desktopTestProfile(args) == null &&
                DesktopPlatformPaths.current(createDirectories = false).recoveryProfileRoot == null
            ) {
                reportDesktopOwnerRegistration(registrar, reportRegistration)
            }
            electedOwner = electedBroker
        }
        electedOwner?.let { electedBroker ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            owner = prepareDesktopOwner(transaction, electedBroker, args, openUriEventPort, ownerIngressDependencies)
        }
        owner?.let { startup ->
            lifecycleStarted = true
            runProductionOwnerLifecycle(
                testArgs = testArgs,
                runtime = startup.runtime,
                appLock = startup.appLock,
                testState = applicationState,
                startTestMode = { startTestMode(testArgs) },
                awaitTestModeTermination = awaitTestModeTermination,
                stopTestMode = stopTestMode,
                runApplication = { closeAndJoin ->
                    runDesktopWindowApplication(
                        closeAndJoin = closeAndJoin,
                        awaitTestModeTermination = awaitTestModeTermination.takeIf { testArgs.testMode },
                    ) { requestClose ->
                        runWindowEventLoop(startup, requestClose)
                    }
                },
            )
        }
        result
    } catch (failure: Throwable) {
        if (!lifecycleStarted) {
            withContext(NonCancellable) {
                try {
                    electedOwner?.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                transaction.closeAndJoin(failure)
            }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            onStartupFailure(failure)
            return DesktopInstanceStartResult.Failed(DesktopExternalActionBroker.Failure.OwnerStartFailed)
        }
        throw failure
    }
}

internal fun startDesktopApplication(
    args: Array<String>,
    broker: DesktopExternalActionBroker = DesktopExternalActionBroker(DesktopPlatformPaths.current().instanceStateFile),
    registrar: DesktopUriSchemeRegistrar = DesktopUriSchemeRegistration(),
    reportRegistration: (DesktopUriSchemeRegistration.Result) -> Unit = ::reportUriSchemeRegistration,
    startOwnerApplication: (DesktopExternalActionBroker) -> Unit,
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

internal suspend fun runDesktopWindowApplication(
    closeAndJoin: suspend () -> Unit,
    awaitTestModeTermination: (() -> Unit)? = null,
    runWindowEventLoop: suspend (requestClose: DesktopApplicationCloseCoordinator) -> Unit,
) {
    val closeCoordinator = DesktopApplicationCloseCoordinator(closeAndJoin, awaitTestModeTermination)
    runWindowEventLoop(closeCoordinator)
    closeCoordinator.throwTerminalFailure()
}

internal class DesktopApplicationCloseCoordinator(
    private val closeAndJoin: suspend () -> Unit,
    val awaitTestModeTermination: (() -> Unit)?,
) {
    private val closeRequested = AtomicBoolean()
    private val closeCompleted = CompletableDeferred<Unit>()
    private val applicationExitMutex = Mutex()
    private val failureLock = Any()
    private var terminalFailure: Throwable? = null
    private var applicationExited = false

    suspend fun stopForRecovery() {
        stopRuntime()
        throwTerminalFailure()
    }

    private suspend fun stopRuntime() {
        if (closeRequested.compareAndSet(false, true)) {
            runCatching { closeAndJoin() }
                .exceptionOrNull()
                ?.let(::recordFailure)
            closeCompleted.complete(Unit)
        } else {
            closeCompleted.await()
        }
    }

    suspend operator fun invoke(applicationExit: () -> Unit = {}) {
        stopRuntime()
        applicationExitMutex.withLock {
            if (!applicationExited) {
                try {
                    applicationExit()
                    applicationExited = true
                } catch (failure: Throwable) {
                    recordFailure(failure)
                }
            }
        }
    }

    suspend fun throwTerminalFailure() {
        if (!closeRequested.get()) return
        closeCompleted.await()
        val failure = applicationExitMutex.withLock { synchronized(failureLock) { terminalFailure } }
        failure?.let { throw it }
    }

    private fun recordFailure(failure: Throwable) {
        synchronized(failureLock) {
            terminalFailure = preserveLifecycleFailure(terminalFailure, failure)
        }
    }
}

internal suspend fun observeDesktopGuiTestModeTermination(
    awaitTermination: () -> Unit,
    requestClose: suspend () -> Unit,
) {
    runInterruptible(Dispatchers.IO, awaitTermination)
    requestClose()
}

private suspend fun runDesktopComposeWindowEventLoop(
    owner: DesktopOwnerStartup,
    requestClose: DesktopApplicationCloseCoordinator,
) {
    installDesktopImageLoader(owner.ingress.uiDependencies)
    application {
        val applicationScope = rememberCoroutineScope()
        val awaitTestModeTermination = requestClose.awaitTestModeTermination
        LaunchedEffect(awaitTestModeTermination) {
            if (awaitTestModeTermination != null) {
                observeDesktopGuiTestModeTermination(awaitTestModeTermination) {
                    requestClose { exitApplication() }
                }
            }
        }
        Window(
            onCloseRequest = {
                applicationScope.launch {
                    requestClose { exitApplication() }
                }
            },
            title = "Mihon Desktop $APP_VERSION",
            icon = androidx.compose.runtime.remember {
                androidx.compose.ui.graphics.painter.BitmapPainter(loadDesktopAppIcon())
            },
            state = rememberWindowState(width = 1024.dp, height = 768.dp),
        ) {
            BindDesktopWindowLifecycle(window, owner.appLock, owner.windowPrivacyController)
            mihon.desktop.test.ProvideDesktopSyncUiObservation(window) {
                OwnerUiDependencies(owner.ingress) {
                    CompositionLocalProvider(mihon.desktop.sync.LocalDesktopRecoveryRestart provides { root ->
                        requestClose.stopForRecovery()
                        val launched = mihon.desktop.platform.DesktopRecoveryLauncher.launch(root)
                        if (launched) requestClose { exitApplication() }
                        launched
                    }) {
                        DesktopTheme {
                            DesktopProtectedRoot(owner.appLock) {
                                Navigator(HomeScreen()) { navigator ->
                                    DesktopLocalizedNavigatorContent(owner.ingress.uiDependencies.localeAdapter, navigator)
                                }
                            }
                        }
                    }
                }
            }
        }
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

private suspend fun prepareDesktopOwner(
    transaction: DesktopOwnerTransaction,
    broker: DesktopExternalActionBroker,
    args: Array<String>,
    openUriEventPort: DesktopOpenUriEventPort,
    ownerIngressDependencies: suspend (DesktopOwnerTransaction) -> DesktopOwnerIngressDependencies,
): DesktopOwnerStartup {
    val ingress = ownerIngressDependencies(transaction).also { it.uiDependencies.localeAdapter.applyPersisted() }
    transaction.registerRuntime(ingress.runtime)
    val startup = DesktopOwnerStartup(
        ingress = ingress,
        appLock = Injekt.get(),
        windowPrivacyController = Injekt.get(),
    )
    try {
        submitDesktopExternalAction(
            args,
            ingress.uiDependencies.externalActionNavigator,
            ingress.uiDependencies.trackerOAuthCallbackBroker,
        )
        initializeDesktopOwnerExternalActionIngress(
            broker,
            ingress.uiDependencies.externalActionNavigator,
            startup.runtime,
            openUriEventPort,
            ingress.uiDependencies.trackerOAuthCallbackBroker,
        )
        return startup
    } catch (failure: Throwable) {
        throw failure
    }
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
    var failure: Throwable? = null
    var testModeStarted = false
    try {
        runtime.start()
        if (testArgs.testMode) {
            testModeStarted = true
            startTestMode()
        }
        if (testArgs.headless) {
            if (testArgs.testMode) awaitTestModeTermination()
        } else {
            runApplication(session::closeAndJoin)
        }
    } catch (lifecycleFailure: Throwable) {
        failure = lifecycleFailure
    }
    withContext(NonCancellable) {
        if (testModeStarted) {
            try {
                stopTestMode()
            } catch (cleanupFailure: Throwable) {
                failure = preserveLifecycleFailure(failure, cleanupFailure)
            }
        }
        try {
            session.closeAndJoin()
        } catch (cleanupFailure: Throwable) {
            failure = preserveLifecycleFailure(failure, cleanupFailure)
        }
    }
    failure?.let { throw it }
}

private fun preserveLifecycleFailure(primary: Throwable?, failure: Throwable): Throwable {
    if (primary == null) return failure
    if (failure !== primary) primary.addSuppressed(failure)
    return primary
}

internal class DesktopRuntimeBootstrapSession(
    private val closeRuntime: () -> Unit,
    private val awaitRuntime: suspend () -> Unit,
    private val closeBinding: () -> Unit,
) : AutoCloseable {
    constructor(runtime: DesktopAppRuntime, binding: DesktopAppLockTestStateBinding) :
        this(runtime::close, runtime::awaitClosed, binding::close)

    private var runtimeClosed = false
    private var runtimeAwaited = false
    private var bindingClosed = false

    @Synchronized
    override fun close() {
        if (runtimeClosed && bindingClosed) return
        var primaryFailure: Throwable? = null
        fun attempt(done: Boolean, action: () -> Unit): Boolean {
            if (done) return true
            return try {
                action()
                true
            } catch (failure: Throwable) {
                val primary = primaryFailure
                if (primary ==
                    null
                ) {
                    primaryFailure = failure
                } else if (failure !== primary) {
                    primary.addSuppressed(failure)
                }
                false
            }
        }
        runtimeClosed = attempt(runtimeClosed, closeRuntime)
        bindingClosed = attempt(bindingClosed, closeBinding)
        primaryFailure?.let { throw it }
    }

    suspend fun closeAndJoin() {
        var primaryFailure: Throwable? = null
        try {
            close()
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        if (!runtimeAwaited) {
            try {
                awaitRuntime()
                runtimeAwaited = true
            } catch (failure: Throwable) {
                val primary = primaryFailure
                if (primary ==
                    null
                ) {
                    primaryFailure = failure
                } else if (failure !== primary) {
                    primary.addSuppressed(failure)
                }
            }
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

internal fun submitDesktopExternalAction(
    args: Array<String>,
    navigator: mihon.desktop.ui.ExternalActionNavigator,
    oauthBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
) {
    desktopExternalActionRaw(args)?.let { submitDesktopExternalAction(it, navigator, oauthBroker) }
}

internal fun submitDesktopExternalAction(
    raw: String,
    navigator: ExternalActionNavigator,
    oauthBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
): DesktopTrackerOAuthCallbackBroker.HandleResult {
    val result = oauthBroker.handle(raw)
    if (result is DesktopTrackerOAuthCallbackBroker.HandleResult.NotOAuth) {
        navigator.submit(ExternalActionInput.ViewUri(raw))
    }
    return result
}

internal fun wireDesktopExternalActionBroker(
    broker: DesktopExternalActionBroker,
    navigator: ExternalActionNavigator,
    oauthBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
) {
    broker.setActionConsumer { raw -> submitDesktopExternalAction(raw, navigator, oauthBroker) }
}

internal fun wireDesktopOpenUriEvents(
    port: mihon.desktop.platform.DesktopOpenUriEventPort,
    navigator: ExternalActionNavigator,
    oauthBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
) = port.install { raw -> submitDesktopExternalAction(raw, navigator, oauthBroker) }

internal fun initializeDesktopOwnerExternalActionIngress(
    broker: DesktopExternalActionBroker,
    navigator: ExternalActionNavigator,
    runtime: DesktopAppRuntime,
    openUriEventPort: DesktopOpenUriEventPort,
    oauthBroker: DesktopTrackerOAuthCallbackBroker = DesktopTrackerOAuthCallbackBroker(),
) {
    wireDesktopExternalActionBroker(broker, navigator, oauthBroker)
    runtime.attachInstanceBroker(broker)
    val openUriResult = wireDesktopOpenUriEvents(openUriEventPort, navigator, oauthBroker)
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

@Composable
internal fun DesktopLocalizedNavigatorContent(
    localeAdapter: mihon.desktop.platform.DesktopLocaleAdapter,
    navigator: Navigator,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        BindTestNavigationController(navigator)
        localeAdapter.Provide { SlideTransition(navigator) }
        DesktopLocaleFeedbackHost(
            localeAdapter = localeAdapter,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}
