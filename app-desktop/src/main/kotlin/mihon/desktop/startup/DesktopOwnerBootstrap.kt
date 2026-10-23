package mihon.desktop.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import mihon.desktop.DesktopAppLockTestStateBinding
import mihon.desktop.DesktopOwnerStartup
import mihon.desktop.DesktopRuntimeBootstrapSession
import mihon.desktop.security.DesktopAppLock

/**
 * Lifecycle of the owner bootstrap.
 *
 * The owner owns the dependency graph of the running application. Building that graph
 * takes long enough that the window would stay invisible for seconds, so the window is
 * created first and the owner is prepared while the graphics pipeline warms up.
 */
internal sealed interface DesktopOwnerBootstrapState {
    /** The dependency graph is still being built. */
    data object Preparing : DesktopOwnerBootstrapState

    /**
     * The owner is ready.
     *
     * [owner] carries the user interface dependencies and is absent when only the
     * runtime is prepared, which is the case for headless and test runs.
     */
    data class Ready(
        val runtime: mihon.desktop.DesktopAppRuntime,
        val appLock: DesktopAppLock,
        val owner: DesktopOwnerStartup?,
    ) : DesktopOwnerBootstrapState

    /** The dependency graph could not be built. */
    data class Failed(val failure: Throwable) : DesktopOwnerBootstrapState
}

/**
 * Holds the owner bootstrap state and lets the window observe the progress.
 */
internal class DesktopOwnerBootstrapController(
    private val testState: mihon.desktop.test.state.TestState,
    val geometryStore: mihon.desktop.platform.DesktopWindowGeometryStore? = null,
) {

    private val mutableState = MutableStateFlow<DesktopOwnerBootstrapState>(DesktopOwnerBootstrapState.Preparing)
    val state: StateFlow<DesktopOwnerBootstrapState> = mutableState.asStateFlow()

    private val ownerReady = CompletableDeferred<DesktopOwnerStartup?>()
    private val session = AtomicReference<DesktopRuntimeBootstrapSession?>(null)
    private val failureDecision = CompletableDeferred<Unit>()
    @Volatile
    private var failureEscalates = true

    /** Publishes a successfully prepared owner. */
    fun publishReady(owner: DesktopOwnerStartup) {
        if (mutableState.value is DesktopOwnerBootstrapState.Ready) return
        session.set(
            DesktopRuntimeBootstrapSession(
                owner.runtime,
                DesktopAppLockTestStateBinding(owner.appLock, testState),
            ),
        )
        mutableState.value = DesktopOwnerBootstrapState.Ready(owner.runtime, owner.appLock, owner)
        ownerReady.complete(owner)
        failureDecision.complete(Unit)
    }

    /**
     * Publishes a bootstrap failure.
     *
     * [escalates] tells whether the failure has to reach the caller. A failure that the
     * startup failure handler already presented does not need to be raised again.
     */
    fun publishFailure(failure: Throwable, escalates: Boolean = true) {
        if (mutableState.value is DesktopOwnerBootstrapState.Ready) return
        failureEscalates = escalates
        mutableState.value = DesktopOwnerBootstrapState.Failed(failure)
        ownerReady.completeExceptionally(failure)
    }

    /** Updates whether a failed bootstrap has to be raised to the caller. */
    fun setFailureEscalates(escalates: Boolean) {
        failureEscalates = escalates
        failureDecision.complete(Unit)
    }

    /** Suspends until the failure escalation of a failed bootstrap is known. */
    suspend fun awaitFailureDecision() {
        failureDecision.await()
    }

    /**
     * Publishes a prepared runtime without user interface dependencies.
     *
     * Headless and automated runs never render the user interface, so they only need
     * the runtime and the application lock.
     */
    fun publishRuntime(runtime: mihon.desktop.DesktopAppRuntime, appLock: DesktopAppLock) {
        if (mutableState.value is DesktopOwnerBootstrapState.Ready) return
        session.set(
            DesktopRuntimeBootstrapSession(
                runtime,
                DesktopAppLockTestStateBinding(appLock, testState),
            ),
        )
        mutableState.value = DesktopOwnerBootstrapState.Ready(runtime, appLock, null)
        ownerReady.complete(null)
        failureDecision.complete(Unit)
    }

    /** Publishes a bootstrap failure. */
    fun publishFailure(failure: Throwable) {
        if (mutableState.value is DesktopOwnerBootstrapState.Ready) return
        mutableState.value = DesktopOwnerBootstrapState.Failed(failure)
        ownerReady.completeExceptionally(failure)
    }

    /** Awaits the prepared owner; throws when the bootstrap failed. */
    suspend fun awaitOwner(): DesktopOwnerStartup? = ownerReady.await()

    /** The owner when it is already available, without suspending. */
    fun readyOwner(): DesktopOwnerStartup? = (mutableState.value as? DesktopOwnerBootstrapState.Ready)?.owner

    /** The failure of a failed bootstrap, when one happened. */
    fun failure(): Throwable? = (mutableState.value as? DesktopOwnerBootstrapState.Failed)?.failure

    /** Whether a failed bootstrap has to be raised to the caller. */
    fun failureEscalates(): Boolean = failureEscalates

    /** The runtime when the bootstrap already produced one, without suspending. */
    fun readyRuntime(): mihon.desktop.DesktopAppRuntime? =
        (mutableState.value as? DesktopOwnerBootstrapState.Ready)?.runtime

    /** Closes the runtime and the application lock binding of a prepared owner. */
    suspend fun closeAndJoin(primary: Throwable?) {
        // The window close handler and the lifecycle cleanup can run concurrently, so
        // the session is taken atomically and closed exactly once.
        val current = session.getAndSet(null) ?: return
        try {
            current.closeAndJoin()
        } catch (cleanup: Throwable) {
            if (primary != null) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            } else {
                throw cleanup
            }
        }
    }
}

/**
 * Renders the real user interface as soon as the owner is ready and a neutral
 * placeholder while the dependency graph is still being built.
 */
@Composable
internal fun DesktopOwnerBootstrapContent(
    state: DesktopOwnerBootstrapState,
    content: @Composable (DesktopOwnerStartup) -> Unit,
) {
    when (state) {
        DesktopOwnerBootstrapState.Preparing -> DesktopBootScreen()
        is DesktopOwnerBootstrapState.Ready -> {
            val owner = state.owner
            if (owner != null) {
                // The start of the real user interface composition and the moment it is
                // applied are recorded separately: the difference is the composition
                // itself, the gap before it is the wait for the graphics pipeline.
                androidx.compose.runtime.remember {
                    DesktopStartupTrace.mark("ui-compose-start")
                }
                LaunchedEffect(owner) { DesktopStartupTrace.mark("ui-ready") }
                content(owner)
            } else {
                DesktopBootScreen()
            }
        }
        is DesktopOwnerBootstrapState.Failed -> DesktopBootFailureScreen(state.failure)
    }
}

/** Placeholder shown while the application dependencies are being prepared. */
@Composable
internal fun DesktopBootScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Mihon",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Placeholder shown when the application dependencies could not be prepared. */
@Composable
internal fun DesktopBootFailureScreen(failure: Throwable) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Mihon could not start",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = failure.message ?: failure::class.simpleName.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
