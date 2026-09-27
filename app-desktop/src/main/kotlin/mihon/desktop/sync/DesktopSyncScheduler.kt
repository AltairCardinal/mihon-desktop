package mihon.desktop.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.desktop.DesktopRuntimeService
import mihon.domain.sync.runtime.SyncCoordinator
import mihon.domain.sync.runtime.SyncPreferences
import mihon.domain.sync.runtime.SyncTrigger

class DesktopSyncScheduler(
    val coordinator: SyncCoordinator,
    private val preferences: SyncPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onStopped: suspend () -> Unit = {},
    private val resumeIfNeeded: suspend () -> Boolean = { false },
    private val recoveryDelayMillis: suspend () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
) : DesktopRuntimeService {
    private var job: Job? = null
    private val stopping = mutableListOf<Job>()

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            coroutineScope {
                launch {
                    var observedCompletion = coordinator.activity.value.completion
                    coordinator.activity.collect { activity ->
                        if (activity.completion == observedCompletion) return@collect
                        observedCompletion = activity.completion
                        if (activity.result?.problem == mihon.domain.sync.runtime.SyncRunProblem.NETWORK) {
                            val delayMillis = recoveryDelayMillis().coerceAtLeast(0L)
                            if (delayMillis > 0) delay(delayMillis)
                            try {
                                if (!resumeIfNeeded()) coordinator.synchronize(SyncTrigger.RECOVERY)
                            } catch (_: CancellationException) {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
                launch {
                    val resumed = resumeIfNeeded()
                    if (!resumed && preferences.startup.get()) coordinator.synchronize(SyncTrigger.STARTUP)
                }
                var observedPeriod: Int? = null
                preferences.periodMinutes.changes().distinctUntilChanged().collectLatest {
                    val minutes = preferences.intervalMinutes()
                    if (preferences.scheduleAnchor.get() == 0L || observedPeriod != null) {
                        preferences.scheduleAnchor.set(clock())
                    }
                    observedPeriod = minutes
                    if (minutes == 0) return@collectLatest
                    val interval = minutes * 60_000L
                    while (true) {
                        val now = clock()
                        // Wall-clock checks also handle a suspended laptop and backward clock adjustments.
                        val anchor = maxOf(
                            preferences.scheduleAnchor.get(),
                            preferences.lastAttempt.get(),
                            preferences.lastSuccess.get(),
                        ).coerceAtMost(now)
                        val remaining = (anchor + interval - now).coerceAtLeast(0)
                        if (remaining > 0) {
                            delay(minOf(remaining, 60_000))
                        } else {
                            try {
                                if (!resumeIfNeeded()) coordinator.synchronize(SyncTrigger.PERIODIC)
                            } catch (_: CancellationException) {
                                // Canceling a single exchange must not remove the device's periodic observer.
                                currentCoroutineContext().ensureActive()
                            }
                            preferences.scheduleAnchor.set(clock())
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun stop() {
        job?.let {
            it.cancel()
            stopping += it
        }
        job = null
    }

    override suspend fun awaitStopped() {
        val jobs = synchronized(this) { stopping.toList().also { stopping.clear() } }
        jobs.forEach { it.join() }
        coordinator.cancelAndJoin()
        onStopped()
    }
}
