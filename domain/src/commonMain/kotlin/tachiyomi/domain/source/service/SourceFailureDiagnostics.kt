package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceFailureIdentity
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import mihon.domain.error.AppError
import tachiyomi.core.common.feedback.safeCrashReport

const val SOURCE_COMPATIBILITY_LOG_TAG = "MihonExtension"

/** Records only frozen extension identity and a bounded, message-free original stack. */
fun AppError.recordSourceFailureDiagnostics(write: (String) -> Unit, atMillis: Long = System.currentTimeMillis()) {
    if (this !is AppError.ExtensionIncompatible) return
    val inspection = cause?.inspectSourceFailure()
    inspection?.fatal?.let { throw it }
    if (inspection?.complete == false) throw requireNotNull(cause)
    inspection?.cancellation?.let { throw it }
    val markers = inspection?.causes?.filterIsInstance<SourceCompatibilityException>().orEmpty()
    val identity = markers.firstNotNullOfOrNull { it.identity } ?: SourceFailureIdentity()
    val phase = markers.firstNotNullOfOrNull { it.phase }?.name ?: "unknown"
    val linkage = inspection?.linkage
    val kind = when (linkage) {
        is NoSuchMethodError -> "NoSuchMethodError"
        is NoClassDefFoundError -> "NoClassDefFoundError"
        is AbstractMethodError -> "AbstractMethodError"
        is NoSuchFieldError -> "NoSuchFieldError"
        is ExceptionInInitializerError -> "ExceptionInInitializerError"
        null -> "ExtensionIncompatible"
        else -> "LinkageError"
    }
    val original = linkage ?: cause
    val report = "${identity.diagnosticLabel()}, operation=$phase, kind=$kind\n" +
        (original?.let { safeCrashReport(it, atMillis).take(2048) } ?: "Stack unavailable\n")
    try {
        write(report)
    } catch (error: Exception) {
        // Ordinary diagnostic IO must not replace the failure being reported. Control and host
        // errors retain their existing propagation; this is not another extension call boundary.
        error.rethrowSourceControlFailure()
    }
}
