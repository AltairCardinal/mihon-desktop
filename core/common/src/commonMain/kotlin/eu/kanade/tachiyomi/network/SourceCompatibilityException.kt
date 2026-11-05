package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** IO transport for an extension ABI failure; no source identity is inferred by the network layer. */
class SourceCompatibilityException(cause: Throwable) : IOException(
    "Extension is incompatible with this application runtime",
    cause,
)

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
