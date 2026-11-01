package tachiyomi.domain.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticStage
import java.security.MessageDigest
import java.util.UUID

data class NativeFeedbackDraft(
    val title: String = "",
    val category: FeedbackCategory? = null,
    val description: String = "",
    val includeSummary: Boolean = true,
    val publicConsent: Boolean = false,
    val crashLog: String = "",
    val logConsent: Boolean = true,
    val screenshots: List<FeedbackScreenshot> = emptyList(),
)

enum class NativeFeedbackPhase {
    EDITING,
    READING_LOG,
    PROCESSING_SCREENSHOT,
    PICKING_SCREENSHOT,
    CREATING_SESSION,
    WAITING_VERIFICATION,
    SENDING,
    RECEIVED,
    FAILED,
}
enum class NativeFeedbackFailure {
    INVALID,
    LOG_INVALID,
    LOG_TOO_LARGE,
    LOG_READ_TOO_LARGE,
    LOG_UNAVAILABLE,
    PUBLIC_CONSENT,
    LOG_CONSENT,
    NETWORK,
    RATE_LIMIT,
    EXPIRED,
    CONFLICT,
    BROWSER,
    CANCELLED,
    IMAGE_INVALID,
    IMAGE_TOO_LARGE,
    IMAGE_LIMIT,
    V2_UNAVAILABLE,
    ATTACHMENT_CAPACITY,
    SCREENSHOTS_UNAVAILABLE,
}

data class NativeFeedbackState(
    val draft: NativeFeedbackDraft = NativeFeedbackDraft(),
    val phase: NativeFeedbackPhase = NativeFeedbackPhase.EDITING,
    val failure: NativeFeedbackFailure? = null,
    val receipt: String? = null,
    val pendingCategory: FeedbackCategory? = null,
    val screenshotProgress: FeedbackScreenshotProgress? = null,
    val capabilities: NativeFeedbackCapabilities? = null,
    val configLoading: Boolean = false,
    val capabilityFailure: NativeFeedbackFailure? = null,
) {
    val busy: Boolean get() = phase in setOf(
        NativeFeedbackPhase.READING_LOG,
        NativeFeedbackPhase.PROCESSING_SCREENSHOT,
        NativeFeedbackPhase.PICKING_SCREENSHOT,
        NativeFeedbackPhase.CREATING_SESSION,
        NativeFeedbackPhase.WAITING_VERIFICATION,
        NativeFeedbackPhase.SENDING,
    )
    val locked: Boolean get() = busy || phase == NativeFeedbackPhase.RECEIVED
}

data class NativeFeedbackSession(
    val sessionId: String,
    val secret: String,
    val expiresAt: Long,
    val verificationUrl: String,
) {
    override fun toString(): String = "NativeFeedbackSession([private])"
}
data class NativeFeedbackVerification(val verified: Boolean, val expiresAt: Long)
data class NativeFeedbackPayload(
    val submissionId: String,
    val title: String,
    val description: String,
    val summary: String,
    val crashLog: String,
    val category: FeedbackCategory? = null,
    val screenshots: List<FeedbackScreenshot> = emptyList(),
) {
    override fun toString(): String = "NativeFeedbackPayload([private])"
}
class NativeFeedbackException(val failure: NativeFeedbackFailure) : Exception("Feedback request failed")

data class NativeFeedbackCapabilities(val accepting: Boolean, val nativeV2: Boolean, val screenshots: Boolean)

interface NativeFeedbackService {
    suspend fun capabilities(): NativeFeedbackCapabilities = NativeFeedbackCapabilities(false, false, false)
    suspend fun createSession(contentHash: String): NativeFeedbackSession
    suspend fun pollSession(session: NativeFeedbackSession): NativeFeedbackVerification
    suspend fun submit(payload: NativeFeedbackPayload, session: NativeFeedbackSession): String
}

fun interface FeedbackLogReader {
    suspend fun read(): String
}

object NativeFeedbackContent {
    const val MAX_LOG_BYTES = 256 * 1024
    const val MAX_PREVIEW_BYTES = 4 * 1024 * 1024
    fun normalizeLog(value: String): String = value.replace("\r\n", "\n")
    fun trimText(value: String): String = value.trim { char ->
        char in '\u0009'..'\u000D' || char == '\u0020' || char == '\u00A0' || char == '\u1680' ||
            char in '\u2000'..'\u200A' || char == '\u2028' || char == '\u2029' || char == '\u202F' ||
            char == '\u205F' || char == '\u3000' || char == '\uFEFF'
    }
    fun logBytes(value: String): Int = normalizeLog(value).encodeToByteArray().size
    fun hash(payload: NativeFeedbackPayload): String {
        val category =
            payload.category ?: return hash(payload.title, payload.description, payload.summary, payload.crashLog)
        val fields = mutableListOf(
            "mihon-feedback-v2",
            category.id,
            category.publicTitle,
            trimText(payload.description),
            trimText(normalizeLog(payload.summary)),
            normalizeLog(payload.crashLog),
            payload.screenshots.size.toString(),
        )
        payload.screenshots.forEach { image ->
            fields += listOf(
                image.id,
                image.mime,
                image.width.toString(),
                image.height.toString(),
                image.byteLength.toString(),
                image.sha256,
            )
        }
        return hashParts(fields)
    }

    fun hash(title: String, description: String, summary: String, log: String): String {
        val parts = listOf(trimText(title), trimText(description), trimText(normalizeLog(summary)), normalizeLog(log))
        return hashParts(parts)
    }

    private fun hashParts(parts: List<String>): String {
        require(parts.all(::validUnicode))
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.encodeToByteArray()
            digest.update(bytes.size.toString().encodeToByteArray())
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index >= value.length || !value[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) {
                return false
            }
        }
        return true
    }

    fun validLog(value: String): Boolean = validUnicode(value) && value.none { char ->
        char == '\uFFFD' || (char.code in 0..31 && char !in "\t\n\r") || char.code in 127..159
    }

    fun validPayload(payload: NativeFeedbackPayload): Boolean =
        listOf(payload.title, payload.description, payload.summary, payload.crashLog).all(::validUnicode) &&
            payload.title.codePointCount(0, payload.title.length) in 5..120 &&
            payload.title.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
            payload.description.codePointCount(0, payload.description.length) in 20..6000 &&
            payload.description.none { (it.code in 0..31 && it !in "\t\n\r") || it.code == 127 } &&
            payload.summary.codePointCount(0, payload.summary.length) <= 1000 &&
            (
                (payload.category == null && payload.screenshots.isEmpty()) ||
                    payload.category?.publicTitle == payload.title
                ) &&
            payload.screenshots.size <= 2 && payload.screenshots.sumOf { it.byteLength } <= 1048576 &&
            payload.screenshots.map { it.id }.toSet().size == payload.screenshots.size
}

data class NativeFeedbackSnapshot(
    val draft: NativeFeedbackDraft,
    val submissionId: String,
    val logEdited: Boolean = false,
) {
    override fun toString(): String = "NativeFeedbackSnapshot([private])"
}

class FeedbackDraftMemory {
    var snapshot: NativeFeedbackSnapshot? = null
}

class NativeFeedbackController(
    private val service: NativeFeedbackService,
    private val logReader: FeedbackLogReader,
    private val summary: String,
    private val scope: CoroutineScope,
    private val openVerification: (String) -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val language: FeedbackLanguage = FeedbackLanguage.ENGLISH,
    private val screenshotProcessor: FeedbackScreenshotProcessor? = null,
    val diagnostics: FeedbackDiagnosticController? = null,
    initialSnapshot: NativeFeedbackSnapshot? = null,
) {
    fun snapshot(): NativeFeedbackSnapshot? = if (state.value.phase == NativeFeedbackPhase.RECEIVED) {
        null
    } else {
        NativeFeedbackSnapshot(
            state.value.draft.copy(screenshots = state.value.draft.screenshots.toList()),
            submissionId,
            diagnostics?.manuallyEdited == true,
        )
    }
    private val initialDraft = initialSnapshot?.draft ?: NativeFeedbackDraft()
    private val mutableState = MutableStateFlow(NativeFeedbackState(draft = initialDraft))
    val state: StateFlow<NativeFeedbackState> = mutableState
    private var submissionId = initialSnapshot?.submissionId ?: UUID.randomUUID().toString()

    @Volatile private var operation = 0
    private var job: Job? = null
    private var configJob: Job? = null

    init {
        diagnostics?.onContent = { text ->
            if (!state.value.locked) edit { it.copy(crashLog = NativeFeedbackContent.normalizeLog(text)) }
        }
        initialSnapshot?.draft?.category?.let { category ->
            diagnostics?.select(category)
            if (initialSnapshot.logEdited) diagnostics?.edited()
        }
    }

    private fun edit(contentChanged: Boolean = true, transform: (NativeFeedbackDraft) -> NativeFeedbackDraft) {
        if (state.value.locked) return
        val draft = transform(state.value.draft)
        if (draft == state.value.draft) return
        if (contentChanged) submissionId = UUID.randomUUID().toString()
        mutableState.update { current ->
            current.copy(
                draft = draft,
                phase = NativeFeedbackPhase.EDITING,
                failure = null,
                receipt = null,
                pendingCategory = null,
                screenshotProgress = null,
            )
        }
    }

    fun refreshCapabilities() {
        if (state.value.configLoading || state.value.locked) return
        mutableState.update { it.copy(configLoading = true, capabilityFailure = null) }
        configJob = scope.launch {
            try {
                val result = service.capabilities()
                mutableState.update {
                    it.copy(
                        capabilities = result,
                        configLoading = false,
                        capabilityFailure = if (!result.accepting ||
                            !result.nativeV2
                        ) {
                            NativeFeedbackFailure.V2_UNAVAILABLE
                        } else {
                            null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(configLoading = false, capabilityFailure = NativeFeedbackFailure.NETWORK)
                }
            }
        }
    }

    fun category(value: FeedbackCategory) {
        if (state.value.locked || value == state.value.draft.category) return
        val draft = state.value.draft
        val template = draft.category?.template(language).orEmpty()
        if (draft.description.isNotBlank() && draft.description != template) {
            mutableState.value = state.value.copy(pendingCategory = value)
        } else {
            applyCategory(value)
        }
    }

    fun confirmCategory(replace: Boolean) {
        if (state.value.locked) return
        val category = state.value.pendingCategory ?: return
        if (replace) applyCategory(category) else mutableState.value = state.value.copy(pendingCategory = null)
    }

    private fun applyCategory(category: FeedbackCategory) {
        edit {
            it.copy(
                category = category,
                title = category.publicTitle,
                description = category.template(language),
                crashLog = "",
            )
        }
        diagnostics?.select(category)
    }

    fun beginScreenshotSelection(): Int? {
        if (state.value.locked || state.value.draft.screenshots.size >= 2) return null
        val token = ++operation
        mutableState.value = state.value.copy(phase = NativeFeedbackPhase.PICKING_SCREENSHOT, failure = null)
        return token
    }

    fun finishScreenshotSelection(token: Int, inputs: List<FeedbackScreenshotInput>, failed: Boolean = false) {
        if (token != operation || state.value.phase != NativeFeedbackPhase.PICKING_SCREENSHOT) return
        mutableState.value = state.value.copy(phase = NativeFeedbackPhase.EDITING)
        if (failed) fail(NativeFeedbackFailure.IMAGE_INVALID) else addScreenshots(inputs)
    }

    fun addScreenshots(inputs: List<FeedbackScreenshotInput>) {
        if (state.value.locked || inputs.isEmpty()) return
        if (inputs.size + state.value.draft.screenshots.size > 2) {
            fail(NativeFeedbackFailure.IMAGE_LIMIT)
            return
        }
        val processor = screenshotProcessor ?: run {
            fail(NativeFeedbackFailure.IMAGE_INVALID)
            return
        }
        val token = ++operation
        mutableState.value = state.value.copy(phase = NativeFeedbackPhase.PROCESSING_SCREENSHOT, failure = null)
        job = scope.launch {
            try {
                val prepared = mutableListOf<FeedbackScreenshot>()
                inputs.forEachIndexed { index, input ->
                    prepared += processor.process(input) { stage ->
                        mutableState.update { current ->
                            if (token == operation && current.phase == NativeFeedbackPhase.PROCESSING_SCREENSHOT &&
                                stage != FeedbackImageStage.COMPLETE
                            ) {
                                current.copy(
                                    screenshotProgress = FeedbackScreenshotProgress(index + 1, inputs.size, stage),
                                )
                            } else {
                                current
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                }
                if (token == operation) {
                    submissionId = UUID.randomUUID().toString()
                    mutableState.value = state.value.copy(
                        phase = NativeFeedbackPhase.EDITING,
                        failure = null,
                        draft = state.value.draft.copy(screenshots = state.value.draft.screenshots + prepared),
                        screenshotProgress = FeedbackScreenshotProgress(
                            inputs.size,
                            inputs.size,
                            FeedbackImageStage.COMPLETE,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token ==
                    operation
                ) {
                    fail((error as? NativeFeedbackException)?.failure ?: NativeFeedbackFailure.IMAGE_INVALID)
                }
            }
        }
    }

    fun removeScreenshot(id: String) = edit {
        it.copy(screenshots = it.screenshots.filterNot { image -> image.id == id })
    }

    fun title(value: String) = edit { it.copy(title = value) }
    fun description(value: String) {
        if (state.value.locked || state.value.draft.description == value) return
        edit { it.copy(description = value) }
        for (category in FEEDBACK_ACTION_CATEGORIES) {
            if (DiagnosticEvents.enabled(category)) {
                DiagnosticEvents.record(
                    DiagnosticEvent(category, DiagnosticOperation.FEEDBACK, DiagnosticStage.SUCCESS, count = 1),
                )
            }
        }
    }
    fun includeSummary(value: Boolean) = edit { it.copy(includeSummary = value) }
    fun publicConsent(value: Boolean) = edit(false) { it.copy(publicConsent = value) }
    fun log(value: String) {
        if (state.value.locked) return
        diagnostics?.edited()
        edit { it.copy(crashLog = NativeFeedbackContent.normalizeLog(value)) }
    }
    fun logConsent(value: Boolean) = edit { it.copy(logConsent = value) }
    fun removeLog() {
        if (state.value.locked) return
        diagnostics?.remove()
        edit { it.copy(crashLog = "") }
    }

    fun readLog() {
        if (state.value.locked) return
        if (diagnostics != null) {
            diagnostics.refresh(explicit = true)
            return
        }
        val token = ++operation
        mutableState.value =
            state.value.copy(
                phase = NativeFeedbackPhase.READING_LOG,
                failure = null,
                draft = state.value.draft,
            )
        job = scope.launch {
            try {
                val text = NativeFeedbackContent.normalizeLog(logReader.read())
                currentCoroutineContext().ensureActive()
                if (text.isEmpty() ||
                    NativeFeedbackContent.logBytes(text) > NativeFeedbackContent.MAX_PREVIEW_BYTES
                ) {
                    throw NativeFeedbackException(NativeFeedbackFailure.LOG_UNAVAILABLE)
                }
                if (!NativeFeedbackContent.validLog(
                        text,
                    )
                ) {
                    throw NativeFeedbackException(NativeFeedbackFailure.LOG_INVALID)
                }
                if (token == operation) {
                    submissionId = UUID.randomUUID().toString()
                    mutableState.value =
                        state.value.copy(
                            draft = state.value.draft.copy(crashLog = text),
                            phase = NativeFeedbackPhase.EDITING,
                            failure = null,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token ==
                    operation
                ) {
                    fail((error as? NativeFeedbackException)?.failure ?: NativeFeedbackFailure.LOG_UNAVAILABLE)
                }
            }
        }
    }

    fun submit() {
        if (state.value.locked) return
        val draft = state.value.draft
        val payload =
            NativeFeedbackPayload(
                submissionId,
                draft.category?.publicTitle.orEmpty(),
                NativeFeedbackContent.trimText(draft.description),
                if (draft.includeSummary) {
                    NativeFeedbackContent.trimText(
                        NativeFeedbackContent.normalizeLog(summary),
                    )
                } else {
                    ""
                },
                if (draft.logConsent) NativeFeedbackContent.normalizeLog(draft.crashLog) else "",
                draft.category,
                draft.screenshots.toList(),
            )
        val validation = when {
            draft.category == null -> NativeFeedbackFailure.INVALID
            !FeedbackCategory.hasActualDescription(
                draft.description,
            ) -> NativeFeedbackFailure.INVALID
            !draft.publicConsent -> NativeFeedbackFailure.PUBLIC_CONSENT
            !NativeFeedbackContent.validPayload(payload) -> NativeFeedbackFailure.INVALID
            !NativeFeedbackContent.validLog(payload.crashLog) -> NativeFeedbackFailure.LOG_INVALID
            NativeFeedbackContent.logBytes(
                payload.crashLog,
            ) > NativeFeedbackContent.MAX_LOG_BYTES -> NativeFeedbackFailure.LOG_TOO_LARGE
            payload.crashLog.isNotEmpty() && !draft.logConsent -> NativeFeedbackFailure.LOG_CONSENT
            else -> null
        }
        if (validation != null) {
            fail(validation)
            return
        }
        val token = ++operation
        mutableState.value = state.value.copy(phase = NativeFeedbackPhase.CREATING_SESSION, failure = null)
        job = scope.launch {
            try {
                withTimeout(600_000) {
                    val capabilities = state.value.capabilities ?: service.capabilities()
                    mutableState.update { it.copy(capabilities = capabilities) }
                    if (!capabilities.accepting || !capabilities.nativeV2) {
                        throw NativeFeedbackException(NativeFeedbackFailure.V2_UNAVAILABLE)
                    }
                    if (payload.screenshots.isNotEmpty() && !capabilities.screenshots) {
                        throw NativeFeedbackException(NativeFeedbackFailure.SCREENSHOTS_UNAVAILABLE)
                    }
                    val session = service.createSession(
                        NativeFeedbackContent.hash(payload),
                    )
                    currentCoroutineContext().ensureActive()
                    try {
                        openVerification(session.verificationUrl)
                    } catch (
                        error: Exception,
                    ) {
                        throw NativeFeedbackException(NativeFeedbackFailure.BROWSER)
                    }
                    mutableState.value = state.value.copy(phase = NativeFeedbackPhase.WAITING_VERIFICATION)
                    while (true) {
                        delay(2000)
                        if (session.expiresAt <=
                            nowSeconds()
                        ) {
                            throw NativeFeedbackException(NativeFeedbackFailure.EXPIRED)
                        }
                        val verification = service.pollSession(session)
                        if (verification.expiresAt <=
                            nowSeconds()
                        ) {
                            throw NativeFeedbackException(NativeFeedbackFailure.EXPIRED)
                        }
                        if (verification.verified) break
                    }
                    currentCoroutineContext().ensureActive()
                    mutableState.value = state.value.copy(phase = NativeFeedbackPhase.SENDING)
                    val receipt = service.submit(payload, session)
                    currentCoroutineContext().ensureActive()
                    if (token ==
                        operation
                    ) {
                        mutableState.value =
                            state.value.copy(phase = NativeFeedbackPhase.RECEIVED, receipt = receipt)
                        withContext(NonCancellable) { diagnostics?.received() }
                    }
                }
            } catch (expired: TimeoutCancellationException) {
                if (token ==
                    operation
                ) {
                    fail(NativeFeedbackFailure.EXPIRED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token ==
                    operation
                ) {
                    fail((error as? NativeFeedbackException)?.failure ?: NativeFeedbackFailure.NETWORK)
                }
            }
        }
    }

    private fun fail(failure: NativeFeedbackFailure) {
        mutableState.value =
            state.value.copy(phase = NativeFeedbackPhase.FAILED, failure = failure, screenshotProgress = null)
    }

    fun cancel() {
        if (!state.value.busy) return
        operation++
        job?.cancel()
        fail(NativeFeedbackFailure.CANCELLED)
    }
}

private val FEEDBACK_ACTION_CATEGORIES = listOf(
    DiagnosticCategory.INTERFACE,
    DiagnosticCategory.FEATURE_REQUEST,
    DiagnosticCategory.OTHER,
)
