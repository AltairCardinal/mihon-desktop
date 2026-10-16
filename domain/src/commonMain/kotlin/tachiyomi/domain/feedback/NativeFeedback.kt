package tachiyomi.domain.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.UUID

data class NativeFeedbackDraft(
    val title: String = "",
    val description: String = "",
    val includeSummary: Boolean = false,
    val publicConsent: Boolean = false,
    val crashLog: String = "",
    val logConsent: Boolean = false,
)

enum class NativeFeedbackPhase {
    EDITING,
    READING_LOG,
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
}

data class NativeFeedbackState(
    val draft: NativeFeedbackDraft = NativeFeedbackDraft(),
    val phase: NativeFeedbackPhase = NativeFeedbackPhase.EDITING,
    val failure: NativeFeedbackFailure? = null,
    val receipt: String? = null,
) {
    val busy: Boolean get() = phase in setOf(
        NativeFeedbackPhase.READING_LOG,
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
) {
    override fun toString(): String = "NativeFeedbackPayload([private])"
}
class NativeFeedbackException(val failure: NativeFeedbackFailure) : Exception("Feedback request failed")

interface NativeFeedbackService {
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
    fun hash(title: String, description: String, summary: String, log: String): String {
        val parts = listOf(trimText(title), trimText(description), trimText(normalizeLog(summary)), normalizeLog(log))
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
            payload.summary.codePointCount(0, payload.summary.length) <= 1000
}

class NativeFeedbackController(
    private val service: NativeFeedbackService,
    private val logReader: FeedbackLogReader,
    private val summary: String,
    private val scope: CoroutineScope,
    private val openVerification: (String) -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutableState = MutableStateFlow(NativeFeedbackState())
    val state: StateFlow<NativeFeedbackState> = mutableState
    private var submissionId = UUID.randomUUID().toString()
    private var operation = 0
    private var job: Job? = null

    private fun edit(contentChanged: Boolean = true, transform: (NativeFeedbackDraft) -> NativeFeedbackDraft) {
        if (state.value.locked) return
        if (contentChanged) submissionId = UUID.randomUUID().toString()
        mutableState.value = NativeFeedbackState(draft = transform(state.value.draft))
    }

    fun title(value: String) = edit { it.copy(title = value) }
    fun description(value: String) = edit { it.copy(description = value) }
    fun includeSummary(value: Boolean) = edit { it.copy(includeSummary = value) }
    fun publicConsent(value: Boolean) = edit(false) { it.copy(publicConsent = value) }
    fun log(value: String) = edit { it.copy(crashLog = NativeFeedbackContent.normalizeLog(value), logConsent = false) }
    fun logConsent(value: Boolean) = edit(false) { it.copy(logConsent = value && it.crashLog.isNotEmpty()) }
    fun removeLog() = log("")

    fun readLog() {
        if (state.value.locked) return
        val token = ++operation
        mutableState.value =
            state.value.copy(
                phase = NativeFeedbackPhase.READING_LOG,
                failure = null,
                draft = state.value.draft.copy(logConsent = false),
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
                        NativeFeedbackState(state.value.draft.copy(crashLog = text, logConsent = false))
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
                NativeFeedbackContent.trimText(draft.title),
                NativeFeedbackContent.trimText(draft.description),
                if (draft.includeSummary) {
                    NativeFeedbackContent.trimText(
                        NativeFeedbackContent.normalizeLog(summary),
                    )
                } else {
                    ""
                },
                NativeFeedbackContent.normalizeLog(draft.crashLog),
            )
        val validation = when {
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
                    val session = service.createSession(
                        NativeFeedbackContent.hash(
                            payload.title,
                            payload.description,
                            payload.summary,
                            payload.crashLog,
                        ),
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
        mutableState.value = state.value.copy(phase = NativeFeedbackPhase.FAILED, failure = failure)
    }

    fun cancel() {
        if (!state.value.busy) return
        operation++
        job?.cancel()
        fail(NativeFeedbackFailure.CANCELLED)
    }
}
