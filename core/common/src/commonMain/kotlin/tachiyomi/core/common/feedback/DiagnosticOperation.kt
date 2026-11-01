package tachiyomi.core.common.feedback

import kotlinx.coroutines.CancellationException

inline fun <T> diagnosticOperation(
    category: DiagnosticCategory,
    operation: DiagnosticOperation,
    block: () -> T,
): T {
    val started = System.nanoTime()
    DiagnosticEvents.record(DiagnosticEvent(category, operation, DiagnosticStage.START))
    return try {
        block().also {
            DiagnosticEvents.record(
                DiagnosticEvent(
                    category,
                    operation,
                    DiagnosticStage.SUCCESS,
                    durationMs = ((System.nanoTime() - started) / 1_000_000).coerceIn(0, 86_400_000),
                ),
            )
        }
    } catch (error: Exception) {
        val stage = if (error is CancellationException) DiagnosticStage.CANCELLED else DiagnosticStage.FAILURE
        DiagnosticEvents.record(
            DiagnosticEvent(
                category,
                operation,
                stage,
                durationMs = ((System.nanoTime() - started) / 1_000_000).coerceIn(0, 86_400_000),
            ),
        )
        throw error
    }
}
