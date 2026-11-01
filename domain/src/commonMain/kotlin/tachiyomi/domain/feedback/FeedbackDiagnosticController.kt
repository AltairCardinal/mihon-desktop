package tachiyomi.domain.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticState
import tachiyomi.core.common.feedback.DiagnosticStore
import tachiyomi.core.common.feedback.SafeCrashReportCodec
import java.time.Instant
import java.time.LocalDate

data class FeedbackDiagnosticState(
    val category: FeedbackCategory? = null,
    val dates: List<String> = emptyList(),
    val selectedDate: String? = null,
    val content: String = "",
    val reading: Boolean = false,
    val readFailed: Boolean = false,
    val noToday: Boolean = true,
    val recording: DiagnosticState = DiagnosticState(),
    val pendingRecording: FeedbackCategory? = null,
)

class FeedbackDiagnosticController(
    private val store: DiagnosticStore,
    private val crashReader: FeedbackLogReader,
    private val scope: CoroutineScope,
    private val zone: () -> java.time.ZoneId = { java.time.ZoneId.systemDefault() },
    private val today: () -> String = { LocalDate.now(zone()).toString() },
) {
    private val mutableState = MutableStateFlow(FeedbackDiagnosticState())
    val state: StateFlow<FeedbackDiagnosticState> = mutableState
    val recording: StateFlow<DiagnosticState> = store.state
    var onContent: ((String) -> Unit)? = null
    private var generation = 0
    private var readJob: Job? = null
    var manuallyEdited = false
        private set

    fun select(category: FeedbackCategory) {
        if (state.value.category == category) return
        generation++
        readJob?.cancel()
        manuallyEdited = false
        mutableState.value = state.value.copy(
            category = category,
            content = "",
            dates = emptyList(),
            selectedDate = today(),
            readFailed = false,
            noToday = true,
        )
        refresh()
    }

    fun refresh(date: String? = null, explicit: Boolean = false) {
        val category = state.value.category ?: return
        if (manuallyEdited && !explicit && date == null) return
        if (explicit || date != null) manuallyEdited = false
        val selected = date ?: today()
        val token = ++generation
        readJob?.cancel()
        mutableState.update { it.copy(reading = true, readFailed = false, selectedDate = selected) }
        readJob = scope.launch {
            try {
                store.flush()
                val diagnosticCategory = DiagnosticCategory.entries.single { it.id == category.id }
                val crashText = if (category == FeedbackCategory.STARTUP_CRASH) {
                    try {
                        crashReader.read()
                    } catch (error: NativeFeedbackException) {
                        if (error.failure == NativeFeedbackFailure.LOG_UNAVAILABLE) "" else throw error
                    }
                } else {
                    ""
                }
                val cutoff = LocalDate.parse(today()).minusDays(6).toString()
                val crashes = SafeCrashReportCodec.read(crashText).groupBy {
                    Instant.ofEpochMilli(it.atMillis).atZone(zone()).toLocalDate().toString()
                }.filterKeys { it >= cutoff && it <= today() }
                val dates = (store.dates(diagnosticCategory) + crashes.keys).distinct().sortedDescending()
                val debug = store.read(diagnosticCategory, selected)?.content.orEmpty()
                val crash = crashes[selected].orEmpty().joinToString("") { it.content }
                val content = listOf(crash, debug).filter { it.isNotEmpty() }.joinToString("\n")
                require(
                    NativeFeedbackContent.validLog(content) &&
                        NativeFeedbackContent.logBytes(content) <= NativeFeedbackContent.MAX_PREVIEW_BYTES,
                )
                if (token == generation) {
                    mutableState.update {
                        it.copy(
                            content = content,
                            dates = dates,
                            reading = false,
                            noToday = today() !in dates,
                            recording = store.state.value,
                        )
                    }
                    onContent?.invoke(content)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) mutableState.update { it.copy(reading = false, readFailed = true) }
            }
        }
    }

    fun edited() {
        manuallyEdited = true
        generation++
        readJob?.cancel()
        mutableState.update { it.copy(reading = false) }
    }

    fun remove() {
        edited()
        mutableState.update { it.copy(content = "") }
        onContent?.invoke("")
    }

    fun start() {
        val category = state.value.category ?: return
        val active = store.state.value.session
        if (active != null && active.category.id != category.id) {
            mutableState.update { it.copy(pendingRecording = category) }
        } else {
            launchStart(category, false)
        }
    }

    fun confirmStart(replace: Boolean) {
        val category = state.value.pendingRecording ?: return
        mutableState.update { it.copy(pendingRecording = null) }
        if (replace) launchStart(category, true)
    }

    private fun launchStart(category: FeedbackCategory, replace: Boolean) {
        scope.launch {
            store.start(DiagnosticCategory.entries.single { it.id == category.id })
            mutableState.update { it.copy(recording = store.state.value) }
        }
    }

    fun stop() {
        scope.launch {
            store.stop()
            mutableState.update { it.copy(recording = store.state.value) }
        }
    }
    suspend fun received(): Boolean {
        generation++
        readJob?.cancel()
        return store.stop().also {
            mutableState.update { value -> value.copy(recording = store.state.value, reading = false) }
        }
    }
}
