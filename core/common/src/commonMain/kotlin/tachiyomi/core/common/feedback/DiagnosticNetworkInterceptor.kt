package tachiyomi.core.common.feedback

import okhttp3.Interceptor
import okhttp3.Response

data class DiagnosticRequestContext(
    val category: DiagnosticCategory,
    val operation: DiagnosticOperation = DiagnosticOperation.NETWORK,
)

class DiagnosticNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host == "mihon-feedback.mihon-altaircardinal.workers.dev") return chain.proceed(request)
        val context = request.tag(DiagnosticRequestContext::class.java)
            ?: DiagnosticRequestContext(DiagnosticCategory.SOURCE_NETWORK)
        val started = System.nanoTime()
        fun record(stage: DiagnosticStage, code: Int? = null) {
            val duration = ((System.nanoTime() - started) / 1_000_000).coerceIn(0, 86_400_000)
            DiagnosticEvents.record(DiagnosticEvent(context.category, context.operation, stage, code, duration))
            if (context.category != DiagnosticCategory.PERFORMANCE) {
                DiagnosticEvents.record(
                    DiagnosticEvent(DiagnosticCategory.PERFORMANCE, context.operation, stage, code, duration),
                )
            }
        }
        record(DiagnosticStage.START)
        return try {
            chain.proceed(request).also { record(DiagnosticStage.HTTP, it.code) }
        } catch (error: Exception) {
            record(if (chain.call().isCanceled()) DiagnosticStage.CANCELLED else DiagnosticStage.FAILURE)
            throw error
        }
    }
}
