package mihon.data.sync.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

@Serializable
enum class SyncDiagnosticStatus { OK, READ_FAILED, INCONSISTENT, UNAVAILABLE }

@Serializable
enum class SyncBindingDecode { OK, MISSING, UNSUPPORTED, READ_FAILED, UNKNOWN }

@Serializable
data class SyncDiagnosticEnvironment(
    val platform: String = "UNKNOWN",
    val appVersion: String = "UNKNOWN",
    val sourceRevision: String = "UNKNOWN",
    val releaseIdentity: String = "UNKNOWN",
    val build: String = "UNKNOWN",
    val releaseBuild: Boolean? = null,
)

@Serializable
data class SyncDiagnosticConnection(
    val hasActiveSpace: Boolean? = null,
    val exchangeEnabled: Boolean? = null,
    val storedBindingDecode: SyncBindingDecode = SyncBindingDecode.UNKNOWN,
    val panelConnectionEnabled: Boolean? = null,
    val panelUnsupportedFormat: Boolean? = null,
    val spaceAlias: String? = null,
    val generation: Long? = null,
)

@Serializable
data class SyncDiagnosticRun(
    val alias: String,
    val generation: Long,
    val state: String,
    val phase: String,
    val stopReason: String?,
    val attemptId: Long,
    val ownerPresent: Boolean,
    val confirmedItems: Long,
    val uploaded: Long,
    val downloaded: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastProgressAt: Long,
    val nextRetryAt: Long,
    val elapsedMillis: Long,
)

@Serializable
enum class SyncDiagnosticEventKind {
    OPEN,
    CLOSE,
    REFRESH_BEGIN,
    REFRESH_END,
    CONNECTION_CHANGED,
    RUN_CHANGED,
    COORDINATOR,
}

@Serializable
enum class SyncDiagnosticRefreshSource { OPEN, SUBSCRIPTION, COORDINATOR, ACTION, CAPTURE }

@Serializable
data class SyncDiagnosticEvent(
    val timeMillis: Long,
    val kind: SyncDiagnosticEventKind,
    val source: SyncDiagnosticRefreshSource? = null,
    val runAlias: String? = null,
    val state: String? = null,
    val attemptId: Long? = null,
    val ownerPresent: Boolean? = null,
    val failed: Boolean = false,
    val coordinatorRunning: Boolean? = null,
    val coordinatorTrigger: String? = null,
)

@Serializable
data class SyncDiagnosticSnapshot(
    val schemaVersion: Int = 1,
    val status: SyncDiagnosticStatus = SyncDiagnosticStatus.UNAVAILABLE,
    val environment: SyncDiagnosticEnvironment = SyncDiagnosticEnvironment(),
    val collectedAtMillis: Long = 0,
    val collectedAtUtc: String = "",
    val monotonicMillis: Long = 0,
    val processSession: String = "",
    val crossProcessComparable: Boolean = false,
    val diagnosticSessionExpiresAt: Long? = null,
    val previousSnapshotAlias: String? = null,
    val snapshotAlias: String = "",
    val connection: SyncDiagnosticConnection = SyncDiagnosticConnection(),
    val runSource: String = "UNKNOWN",
    val activeRun: SyncDiagnosticRun? = null,
    val latestRun: SyncDiagnosticRun? = null,
    val activeIsLatest: Boolean? = null,
    val coordinatorRunning: Boolean = false,
    val coordinatorTrigger: String? = null,
    val panelVisible: Boolean = false,
    val panelBusy: Boolean = false,
    val progressMatchesRun: Boolean? = null,
    val progressHold: String? = null,
    val scopeAlias: String? = null,
    val pendingTotal: Long? = null,
    val importRemaining: Long? = null,
    val startup: Boolean = false,
    val periodMinutes: Int = 0,
    val importPaused: Boolean = false,
    val schedulerObservation: String = "UNAVAILABLE_USE_PLATFORM_OBSERVATION",
    val lastRefreshSource: SyncDiagnosticRefreshSource? = null,
    val lastRefreshStartedAt: Long? = null,
    val lastRefreshEndedAt: Long? = null,
    val lastRefreshFailed: Boolean? = null,
    val lastRefreshError: SyncDiagnosticStatus? = null,
    val events: List<SyncDiagnosticEvent> = emptyList(),
    val truncated: Boolean = false,
) {
    fun json(): String = Json { encodeDefaults = true }.encodeToString(this)
}

internal data class SyncConnectionFacts(
    val projection: SyncConnection?,
    val exchangeEnabled: Boolean?,
    val decode: SyncBindingDecode,
)

/** Local read-only collection; no credential or event content is part of the report. */
class SyncDiagnostics internal constructor(
    private val runtime: SyncRuntime,
    directory: okio.Path?,
    private val environment: SyncDiagnosticEnvironment,
    private val clock: () -> Long,
    private val nanos: () -> Long = System::nanoTime,
) {
    private val store = directory?.let { SyncDiagnosticStore(it, clock = clock) }
    private var salt = randomSalt()
    private val process = java.util.UUID.randomUUID().toString()
    private var session: SyncDiagnosticSession? = store?.loadSession()
    private var sessionDeadlineNanos: Long? = session?.let {
        nanos() + (it.expiresAt - clock()).coerceIn(0, 86_400_000) * 1_000_000
    }
    private val events = ArrayDeque<SyncDiagnosticEvent>()
    private var lastConnection: SyncConnectionFacts? = null
    private var lastRun: Triple<String, Long, String?>? = null
    private var refreshSource: SyncDiagnosticRefreshSource? = null
    private var refreshStarted: Long? = null
    private var refreshEnded: Long? = null
    private var refreshFailed: Boolean? = null

    @Synchronized
    private fun alias(kind: String, value: String): String {
        val key = session?.salt ?: salt
        return kind + "-" + (kind + ":" + value).encodeUtf8().hmacSha256(key).hex().take(24)
    }

    @Synchronized
    private fun expireSession() {
        if (session?.expiresAt?.let { clock() >= it } == true ||
            sessionDeadlineNanos?.let { nanos() >= it } == true
        ) {
            runCatching { store?.endSession() }
            session = null
            sessionDeadlineNanos = null
            salt = randomSalt()
            events.clear()
            lastConnection = null
            lastRun = null
        }
    }

    @Synchronized
    fun beginSession(): Boolean = try {
        val target = checkNotNull(store)
        val deadline = nanos() + 86_400_000_000_000L
        session = target.beginSession()
        sessionDeadlineNanos = deadline
        events.clear()
        lastConnection = null
        lastRun = null
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun endSession(): Boolean = try {
        store?.endSession()
        session = null
        sessionDeadlineNanos = null
        salt = randomSalt()
        events.clear()
        lastConnection = null
        lastRun = null
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun record(kind: SyncDiagnosticEventKind, source: SyncDiagnosticRefreshSource? = null, failed: Boolean = false) {
        expireSession()
        val time = clock()
        if (kind == SyncDiagnosticEventKind.REFRESH_BEGIN) {
            refreshSource = source
            refreshStarted = time
            refreshEnded = null
            refreshFailed = null
        }
        if (kind == SyncDiagnosticEventKind.REFRESH_END) {
            refreshEnded = time
            refreshFailed = failed
        }
        append(SyncDiagnosticEvent(time, kind, source, failed = failed))
    }

    @Synchronized
    internal fun recordCoordinator(activity: mihon.domain.sync.runtime.SyncActivity) {
        expireSession()
        append(
            SyncDiagnosticEvent(
                clock(),
                SyncDiagnosticEventKind.COORDINATOR,
                SyncDiagnosticRefreshSource.COORDINATOR,
                coordinatorRunning = activity.running,
                coordinatorTrigger = activity.trigger?.name,
            ),
        )
    }

    @Synchronized
    internal fun observe(connection: SyncConnectionFacts, run: SyncRunSnapshot?) {
        expireSession()
        if (connection != lastConnection) {
            lastConnection = connection
            append(SyncDiagnosticEvent(clock(), SyncDiagnosticEventKind.CONNECTION_CHANGED))
        }
        val identity = run?.let { Triple(it.runId + ":" + it.state.name, it.attemptId, it.ownerSession) }
        if (identity != lastRun) {
            lastRun = identity
            append(
                SyncDiagnosticEvent(
                    clock(),
                    SyncDiagnosticEventKind.RUN_CHANGED,
                    runAlias = run?.let { alias("run", it.runId) },
                    state = run?.state?.name,
                    attemptId = run?.attemptId,
                    ownerPresent = run?.let { it.ownerSession != null },
                ),
            )
        }
    }

    private fun append(event: SyncDiagnosticEvent) {
        if (events.size == 128) events.removeFirst()
        events.addLast(event)
    }

    suspend fun capture(panel: SyncPanelState): SyncDiagnosticSnapshot = capture { panel }

    internal suspend fun capture(panel: () -> SyncPanelState): SyncDiagnosticSnapshot {
        val beforePanel = panel()
        val beforeActivity = runtime.coordinator.activity.value
        val first = readFacts()
        val second = readFacts()
        val afterPanel = panel()
        val afterActivity = runtime.coordinator.activity.value
        val failed = first == null || second == null || first.connection.decode == SyncBindingDecode.READ_FAILED ||
            second.connection.decode == SyncBindingDecode.READ_FAILED
        val panelMatches = !beforePanel.loaded || (
            beforePanel.connection == first?.connection?.projection &&
                beforePanel.run == (first?.active ?: first?.latest)
            )
        val consistent = first == second && beforeActivity == afterActivity && panelMatches &&
            beforePanel.connection == afterPanel.connection && beforePanel.run == afterPanel.run
        val status = when {
            failed -> SyncDiagnosticStatus.READ_FAILED
            !consistent -> SyncDiagnosticStatus.INCONSISTENT
            else -> SyncDiagnosticStatus.OK
        }
        val fact = first.takeIf { status == SyncDiagnosticStatus.OK }
        val selected = fact?.active ?: fact?.latest
        val now = clock()
        val progress = afterPanel.progress
        val matches = progress?.let { selected != null && it.scope.startsWith(selected.runId + ":") }
        val snapshot = synchronized(this) {
            expireSession()
            SyncDiagnosticSnapshot(
                status = status,
                environment = environment.safe(),
                collectedAtMillis = now,
                collectedAtUtc = java.time.Instant.ofEpochMilli(now).toString(),
                monotonicMillis = nanos() / 1_000_000,
                processSession = process,
                crossProcessComparable = session != null,
                diagnosticSessionExpiresAt = session?.expiresAt,
                previousSnapshotAlias = session?.previousAlias,
                snapshotAlias = alias("snapshot", java.util.UUID.randomUUID().toString()),
                connection = fact?.connection?.let { connection ->
                    SyncDiagnosticConnection(
                        hasActiveSpace = connection.projection != null,
                        exchangeEnabled = connection.exchangeEnabled,
                        storedBindingDecode = connection.decode,
                        panelConnectionEnabled = connection.projection?.enabled,
                        panelUnsupportedFormat = connection.projection?.unsupportedFormat,
                        spaceAlias = connection.projection?.let { alias("space", it.spaceId) },
                        generation = connection.projection?.generation,
                    )
                } ?: SyncDiagnosticConnection(
                    storedBindingDecode = if (failed) {
                        SyncBindingDecode.READ_FAILED
                    } else {
                        SyncBindingDecode.UNKNOWN
                    },
                ),
                runSource = when {
                    status != SyncDiagnosticStatus.OK -> "UNKNOWN"
                    fact?.active != null -> "ACTIVE"
                    fact?.latest != null -> "LATEST"
                    else -> "NONE"
                },
                activeRun = fact?.active?.safe(),
                latestRun = fact?.latest?.safe(),
                activeIsLatest = fact?.active?.let { it.runId == fact.latest?.runId },
                coordinatorRunning = afterActivity.running,
                coordinatorTrigger = afterActivity.trigger?.name,
                panelVisible = afterPanel.visible,
                panelBusy = afterPanel.busy,
                progressMatchesRun = matches.takeIf { status == SyncDiagnosticStatus.OK },
                progressHold = progress?.hold?.name.takeIf { matches == true && status == SyncDiagnosticStatus.OK },
                scopeAlias = progress?.let { alias("scope", it.scope) }.takeIf {
                    matches == true && status == SyncDiagnosticStatus.OK
                },
                pendingTotal = fact?.pending?.first,
                importRemaining = fact?.pending?.second,
                startup = runtime.preferences.startup.get(),
                periodMinutes = runtime.preferences.periodMinutes.get(),
                importPaused = runtime.preferences.importPaused.get(),
                lastRefreshSource = refreshSource,
                lastRefreshStartedAt = refreshStarted,
                lastRefreshEndedAt = refreshEnded,
                lastRefreshFailed = refreshFailed,
                lastRefreshError = SyncDiagnosticStatus.READ_FAILED.takeIf { refreshFailed == true },
                events = events.toList(),
            )
        }
        synchronized(this) {
            expireSession()
            session?.let { current ->
                // Persist only a salted association summary, never the full report or raw identity.
                val updated = current.copy(previousAlias = snapshot.snapshotAlias)
                try {
                    store?.saveSession(updated)
                    session = updated
                } catch (_: Exception) { }
            }
        }
        return snapshot
    }

    suspend fun export(snapshot: SyncDiagnosticSnapshot): String? = try {
        store?.export(snapshot)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private data class Facts(
        val connection: SyncConnectionFacts,
        val active: SyncRunSnapshot?,
        val latest: SyncRunSnapshot?,
        val pending: Pair<Long, Long>?,
    )

    private suspend fun readFacts(): Facts? = try {
        val connection = runtime.connectionFacts()
        val active = connection.projection?.let { runtime.runStore.active(it.spaceId, it.generation) }
        val latest = connection.projection?.let { runtime.runStore.latest(it.spaceId, it.generation) }
        val pending = connection.projection?.let { runtime.diagnosticPending(it) }
        Facts(connection, active, latest, pending)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun SyncRunSnapshot.safe() = SyncDiagnosticRun(
        alias("run", runId), generation, state.name, phase.name,
        stopReason?.uppercase(java.util.Locale.ROOT)?.let { if (it in STOP_REASONS) it else "OTHER" },
        attemptId, ownerSession != null, confirmedItems, uploaded, downloaded,
        createdAt, updatedAt, lastProgressAt, nextRetryAt, (updatedAt - createdAt).coerceAtLeast(0),
    )

    private fun SyncDiagnosticEnvironment.safe(): SyncDiagnosticEnvironment {
        fun String.publicValue() = takeIf { length <= 100 && matches(Regex("[A-Za-z0-9._+\\-]+")) } ?: "UNKNOWN"
        return copy(
            platform = platform.publicValue(),
            appVersion = appVersion.publicValue(),
            sourceRevision = sourceRevision.publicValue(),
            releaseIdentity = releaseIdentity.publicValue(),
            build = build.publicValue(),
        )
    }

    companion object {
        private val STOP_REASONS = setOf(
            "USER", "PROCESS_RESTART", "SUPERSEDED_BY_MANUAL_RETRY", "PENDING_DECISION", "PROJECTION_PENDING",
            "NETWORK", "RATE_LIMIT", "RETRY_EXHAUSTED", "CANCELLED", "PAUSED", "SYSTEM", "SOURCE_UNAVAILABLE",
        ) + mihon.domain.sync.runtime.SyncRunProblem.entries.map { it.name }
        internal fun randomSalt(): okio.ByteString = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            .toByteString()
    }
}
