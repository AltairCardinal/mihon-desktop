package mihon.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import mihon.desktop.di.initDesktopDI
import mihon.domain.easteregg.PigEasterEggController
import mihon.desktop.image.installDesktopImageLoader
import mihon.desktop.platform.AwtDesktopOpenUriEventPort
import mihon.desktop.platform.DesktopExternalActionBroker
import mihon.desktop.platform.DesktopMainWindow
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
import mihon.desktop.ui.settings.DesktopPigEasterEggOverlay
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
    mihon.desktop.startup.DesktopStartupTrace.mark("main-entry")
    mihon.desktop.startup.positionDesktopStartupSplash()
    // The classes the first user interface needs are read while the process starts.
    // The composition of that interface is the longest phase of the start-up, and it
    // can only begin once the dependency graph is ready. Reading the classes in
    // parallel with the platform set-up and the dependency graph therefore removes
    // them from the critical path of the first frame.
    mihon.desktop.startup.DesktopStartupClassWarmUp.startAsync()
    // The instance broker needs a random token before the window can be created, and
    // the entropy provider is expensive to initialize on Windows.
    Thread(
        { runCatching { mihon.desktop.platform.DesktopExternalActionBroker.warmUp() } },
        "mihon-startup-warmup",
    ).apply {
        isDaemon = true
        start()
    }
    mihon.desktop.startup.DesktopStartupTrace.attachLogDirectory(
        mihon.desktop.platform.DesktopPlatformPaths.current(createDirectories = false).logsDir,
    )
    mihon.desktop.startup.DesktopStartupTrace.mark("log-directory")
    DesktopTestProfile.configure(args)
    mihon.desktop.startup.DesktopStartupTrace.mark("test-profile")
    try {
        mihon.desktop.platform.DesktopRecoveryProfile.configure(args)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        mihon.desktop.sync.DesktopStartupRecovery.show(failure,
            args.firstOrNull { it.startsWith("--recovery-profile=") }?.substringAfter('='))
        return
    }
    mihon.desktop.startup.DesktopStartupTrace.mark("recovery-profile")
    // Profile selection must precede crash paths, preferences, the instance broker, and DI.
    CrashHandler.install()
    mihon.desktop.startup.DesktopStartupTrace.mark("crash-handler")

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
        val context = mihon.desktop.di.resolveDesktopStartupContext(profile, syncScope)
        initDesktopDI(context.paths, context.preferenceStore, context.syncRepositoryScope)
        val runtime = Injekt.get<DesktopAppRuntime>()
        transaction.registerRuntime(runtime)
        DesktopOwnerIngressDependencies(runtime, DesktopUiDependencies.fromInjekt())
    },
    windowGeometryStore: suspend () -> mihon.desktop.platform.DesktopWindowGeometryStore = {
        val profile = mihon.desktop.test.desktopTestProfile(args)
        mihon.desktop.di.desktopWindowGeometryStore(
            mihon.desktop.di.resolveDesktopStartupContext(profile).preferenceStore,
        )
    },
    startTestMode: (TestArguments) -> Unit = { testArgs -> if (testArgs.testMode) TestMode.start(testArgs) },
    awaitTestModeTermination: () -> Unit = TestMode::awaitTermination,
    stopTestMode: () -> Unit = TestMode::stop,
    runWindowEventLoop: suspend (mihon.desktop.startup.DesktopOwnerBootstrapController, DesktopApplicationCloseCoordinator) -> Unit =
        ::runDesktopComposeWindowEventLoop,
): DesktopInstanceStartResult {
    val transaction = DesktopOwnerTransaction()
    var electedOwner: DesktopExternalActionBroker? = null
    var ownerElected = false
    var lifecycleStarted = false
    val testArgs = TestArguments.parse(args)
    // The class warm up has to be in flight before the broker election: the election
    // opens the local socket and costs a noticeable part of the start-up, while the
    // classes are needed by the composition of the first user interface.
    mihon.desktop.startup.DesktopStartupClassWarmUp.startAsync()
    val bootstrap = mihon.desktop.startup.DesktopOwnerBootstrapController(
        testState = applicationState,
        geometryStore = windowGeometryStore(),
    )
    val bootstrapScope = CoroutineScope(
        SupervisorJob(kotlin.coroutines.coroutineContext[Job]) + Dispatchers.Default,
    )
    return try {
        val result = startDesktopInstance(broker, desktopExternalActionRaw(args)) { electedBroker ->
            mihon.desktop.startup.DesktopStartupTrace.mark("broker-owner")
            electedOwner = electedBroker
        }
        mihon.desktop.startup.DesktopStartupTrace.mark("broker-elected")
        electedOwner?.let { electedBroker ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ownerElected = true
            // The dependency graph is prepared in the background so the window can be
            // created while the graphics pipeline and the application classes warm up.
            bootstrapScope.launch {
                try {
                    // The URI scheme registration writes to the registry and is not
                    // needed for the window, so it never blocks the first frame.
                    if (testArgs.testProfile == null && mihon.desktop.test.desktopTestProfile(args) == null &&
                        DesktopPlatformPaths.current(createDirectories = false).recoveryProfileRoot == null
                    ) {
                        reportDesktopOwnerRegistration(registrar, reportRegistration)
                        mihon.desktop.startup.DesktopStartupTrace.mark("uri-registered")
                    }
                    val startup = prepareDesktopOwner(
                        transaction,
                        electedBroker,
                        args,
                        openUriEventPort,
                        ownerIngressDependencies,
                    )
                    startup.runtime.start()
                    installDesktopImageLoader(startup.ingress.uiDependencies)
                    mihon.desktop.startup.DesktopStartupTrace.mark("owner-ready")

                    bootstrap.publishReady(startup)
                } catch (failure: Throwable) {
                    bootstrap.publishFailure(failure)
                    withContext(NonCancellable) {
                        try {
                            electedBroker.close()
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                        try {
                            transaction.closeAndJoin(failure)
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                    // A failed dependency graph is a startup failure and keeps the
                    // recovery affordance that the user is used to. A cancellation is
                    // not a failure and must not open the recovery screen.
                    val escalates = if (failure is kotlinx.coroutines.CancellationException) {
                        true
                    } else {
                        try {
                            onStartupFailure(failure)
                            false
                        } catch (handlerFailure: Throwable) {
                            true
                        }
                    }
                    bootstrap.setFailureEscalates(escalates)
                    return@launch
                }
                // From here on the bootstrap session owns the runtime. Deferred work must
                // not close it a second time, and it must never fail the startup.
                runCatching { Injekt.get<mihon.desktop.di.DesktopPreparedFilesRecovery>().recover() }
                    .onFailure { System.err.println("Desktop prepared file recovery failed: ${it.message}") }
                runCatching { Injekt.get<mihon.desktop.download.DesktopDownloadManager>().start() }
                    .onFailure { System.err.println("Desktop download worker start failed: ${it.message}") }
            }
        }
        if (ownerElected) {
            lifecycleStarted = true
            runProductionOwnerLifecycle(
                testArgs = testArgs,
                bootstrap = bootstrap,
                startTestMode = { startTestMode(testArgs) },
                awaitTestModeTermination = awaitTestModeTermination,
                stopTestMode = stopTestMode,
                runApplication = { closeAndJoin ->
                    runDesktopWindowApplication(
                        closeAndJoin = closeAndJoin,
                        awaitTestModeTermination = awaitTestModeTermination.takeIf { testArgs.testMode },
                    ) { requestClose ->
                        runWindowEventLoop(bootstrap, requestClose)
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
    } finally {
        bootstrapScope.cancel()
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
    bootstrap: mihon.desktop.startup.DesktopOwnerBootstrapController,
    requestClose: DesktopApplicationCloseCoordinator,
) {
    mihon.desktop.startup.DesktopStartupTrace.mark("compose-application")
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
        DesktopMainWindow(
            geometryStore = bootstrap.geometryStore,
            onCloseRequest = {
                applicationScope.launch {
                    requestClose { exitApplication() }
                }
            },
            title = "Mihon Desktop $APP_VERSION",
            icon = androidx.compose.runtime.remember {
                androidx.compose.ui.graphics.painter.BitmapPainter(loadDesktopAppIcon())
            },
        ) {
            // The owner state is collected here and not in the application scope: the
            // window content is composed several hundred milliseconds after the
            // application block, and the dependency graph is usually ready in between.
            // Collecting it in the outer scope would keep the placeholder state that
            // was current at application start and force an extra frame before the
            // real user interface appears.
            val bootstrapState by bootstrap.state.collectAsState()
            // The first composition of the window content and the first frame that is
            // actually drawn are two different points in time. Both are recorded so the
            // graphics pipeline start-up can be told apart from the composition.
            androidx.compose.runtime.remember {
                mihon.desktop.startup.DesktopStartupTrace.mark("content-composition")
            }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                mihon.desktop.startup.DesktopStartupTrace.mark("first-frame")
                mihon.desktop.startup.DesktopStartupTrace
                    .writeTo(mihon.desktop.platform.DesktopPlatformPaths.current().logsDir)
            }
            mihon.desktop.startup.DesktopOwnerBootstrapContent(bootstrapState) { owner ->
                BindDesktopWindowLifecycle(window, owner.appLock, owner.windowPrivacyController)
                Box(modifier = Modifier.fillMaxSize()) {
                    mihon.desktop.test.ProvideDesktopSyncUiObservation(window) {
                        OwnerUiDependencies(owner.ingress) {
                            CompositionLocalProvider(
                                mihon.desktop.ui.reader.LocalReaderNativeWindow provides window,
                                mihon.desktop.sync.LocalDesktopRecoveryRestart provides { root ->
                                    requestClose.stopForRecovery()
                                    val launched = mihon.desktop.platform.DesktopRecoveryLauncher.launch(root)
                                    if (launched) requestClose { exitApplication() }
                                    launched
                                },
                            ) {
                                DesktopTheme {
                                    DesktopProtectedRoot(owner.appLock) {
                                        Navigator(HomeScreen()) { navigator ->
                                            DesktopLocalizedNavigatorContent(
                                                owner.ingress.uiDependencies.localeAdapter,
                                                navigator,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    DesktopPigEasterEggOverlay(PigEasterEggController.Default)
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
    bootstrap: mihon.desktop.startup.DesktopOwnerBootstrapController,
    startTestMode: () -> Unit,
    awaitTestModeTermination: () -> Unit,
    stopTestMode: () -> Unit,
    runApplication: suspend (closeAndJoin: suspend () -> Unit) -> Unit,
) {
    var failure: Throwable? = null
    var testModeStarted = false
    try {
        if (testArgs.testMode || testArgs.headless) {
            // Without a window there is nothing to show while the owner is prepared,
            // so the bootstrap has to finish before the run continues.
            bootstrap.awaitOwner()
        }
        // The owner preparation starts the runtime; a runtime that was prepared
        // elsewhere still has to be started by the lifecycle.
        bootstrap.readyRuntime()?.start()
        if (testArgs.testMode) {
            testModeStarted = true
            startTestMode()
        }
        if (testArgs.headless) {
            if (testArgs.testMode) awaitTestModeTermination()
        } else {
            runApplication {
                bootstrap.closeAndJoin(null)
            }
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
        // A dependency graph failure has to reach the caller even when the window was
        // closed before the graph finished.
        withTimeoutOrNull(1_000) {
            runCatching { bootstrap.awaitOwner() }
        }
        val bootstrapFailure = bootstrap.failure()
        if (bootstrapFailure != null) {
            // The startup failure handler decides whether the failure is raised again.
            withTimeoutOrNull(2_000) { bootstrap.awaitFailureDecision() }
            if (bootstrap.failureEscalates()) {
                failure = preserveLifecycleFailure(failure, bootstrapFailure)
            }
        }
        try {
            bootstrap.closeAndJoin(failure)
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
            try {
                appLock.state.collect { state -> testState.setAppLocked(state.requiresUnlock) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The binding only mirrors the lock state into the test surface. An
                // unavailable state must never take the application down, and it must
                // never escape as an uncaught exception of the application scope.
            }
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
