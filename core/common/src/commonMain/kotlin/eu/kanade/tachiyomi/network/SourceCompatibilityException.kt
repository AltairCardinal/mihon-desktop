package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** IO transport for an extension ABI failure; no source identity is inferred by the network layer. */
class SourceCompatibilityException(
    cause: Throwable,
    val phase: SourceOperationPhase?,
    val identity: SourceFailureIdentity?,
) : IOException(
    "Extension is incompatible with this application runtime" +
        (identity?.let { " [${it.diagnosticLabel()}, operation=${phase?.name ?: "unknown"}]" } ?: ""),
    cause,
) {
    constructor(cause: Throwable) : this(cause, null)
    constructor(cause: Throwable, phase: SourceOperationPhase?) : this(cause, phase, null)
}

/** Frozen host installation metadata; null means unknown, never inferred from a routing key or URL. */
data class SourceFailureIdentity(
    val sourceId: Long? = null,
    val extensionPackage: String? = null,
    val extensionName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
) {
    fun diagnosticLabel(): String =
        "source=${sourceId ?: "unknown"}, package=${extensionPackage.safeDiagnosticField()}, " +
            "name=${extensionName.safeDiagnosticField()}, version=${versionName.safeDiagnosticField()}, " +
            "versionCode=${versionCode ?: "unknown"}"
}

private fun String?.safeDiagnosticField(): String = this
    ?.take(160)
    ?.let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    ?.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }
    ?.joinToString("")
    ?.ifBlank { null }
    ?: "unknown"

/** Bind only a previously captured owner; do not resolve installation state while reporting failure. */
fun Throwable.withSourceFailureIdentity(identity: SourceFailureIdentity): Throwable {
    val inspection = inspectSourceFailure()
    inspection.fatal?.let { throw it }
    if (!inspection.complete) throw this
    inspection.cancellation?.let { throw it }
    if (inspection.linkage == null) return this
    val markers = inspection.causes.filterIsInstance<SourceCompatibilityException>()
    val frozen = markers.firstNotNullOfOrNull { it.identity } ?: identity
    if (this is SourceCompatibilityException && this.identity == frozen) return this
    return SourceCompatibilityException(this, markers.firstNotNullOfOrNull { it.phase }, frozen)
}

/** Stable operation labels; never put a query, URL, credential or guessed identity in these fields. */
enum class SourceOperationPhase {
    CONSTRUCTION,
    FACTORY,
    METADATA,
    FILTERS,
    POPULAR,
    LATEST,
    SEARCH,
    DETAILS,
    CHAPTERS,
    DETAILS_CHAPTERS,
    PAGE_LIST,
    IMAGE_URL,
    IMAGE_REQUEST,
    IMAGE_PARSE,
}

/** Only the supplied third-party call belongs inside this boundary, never host validation/storage. */
inline fun <T> guardSourceOperation(phase: SourceOperationPhase, block: () -> T): T = try {
    block()
} catch (error: Throwable) {
    val inspection = error.inspectSourceFailure()
    inspection.fatal?.let { throw it }
    if (!inspection.complete) throw error
    inspection.cancellation?.let { throw it }
    if (inspection.linkage != null) {
        val precisePhase = inspection.causes.filterIsInstance<SourceCompatibilityException>()
            .firstNotNullOfOrNull { it.phase }
        val identity = inspection.causes.filterIsInstance<SourceCompatibilityException>()
            .firstNotNullOfOrNull { it.identity }
        throw if (error is SourceCompatibilityException && error.phase != null && error.identity == identity) {
            error
        } else {
            SourceCompatibilityException(error, precisePhase ?: phase, identity)
        }
    }
    throw error
}

/** A bounded, identity-based view of causes shared by network and source error boundaries. */
data class SourceFailureInspection(
    val causes: List<Throwable>,
    val complete: Boolean,
) {
    val fatal: Throwable? get() = causes.firstOrNull { it is VirtualMachineError || it is ThreadDeath }
    val cancellation: CancellationException? get() = causes.filterIsInstance<CancellationException>().firstOrNull()
    val linkage: LinkageError? get() = causes.filterIsInstance<LinkageError>().firstOrNull()
}

/**
 * A cycle is exhausted once every distinct identity was inspected. A deep unknown tail is not
 * safe to classify: it might hide cancellation or a VM failure, so consumers preserve propagation.
 */
fun Throwable.inspectSourceFailure(): SourceFailureInspection {
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null) {
        if (causes.any { it === current }) return SourceFailureInspection(causes, complete = true)
        if (causes.size == MAX_SOURCE_FAILURE_CAUSES) return SourceFailureInspection(causes, complete = false)
        causes += current
        current = current.cause
    }
    return SourceFailureInspection(causes, complete = true)
}

private const val MAX_SOURCE_FAILURE_CAUSES = 64

/** For existing catch boundaries: preserve control/host errors without classifying the caught work as a source call. */
fun Throwable.rethrowSourceControlFailure() {
    val inspection = inspectSourceFailure()
    inspection.fatal?.let { throw it }
    if (!inspection.complete) throw this
    inspection.cancellation?.let { throw it }
    val markedSourceFailure = inspection.causes.any { it is SourceCompatibilityException }
    if (markedSourceFailure) return
    inspection.causes.firstOrNull { it is Error }?.let { throw it }
}
