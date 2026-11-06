package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.inspectSourceFailure
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Transports ordinary exceptions and non-fatal extension linkage failures from later in the
 * chain through OkHttp's IO callback. Fatal causes and unknown deep tails retain propagation.
 * Cancellation keeps its existing IO transport and its original cause, never compatibility.
 *
 * Must precede extension interceptors. Errors outside chain.proceed are outside this boundary.
 *
 * See https://square.github.io/okhttp/4.x/okhttp/okhttp3/-interceptor/
 */
class UncaughtExceptionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: Throwable) {
            val failure = e.inspectSourceFailure()
            failure.fatal?.let { throw it }
            if (!failure.complete) throw e
            if (failure.cancellation == null && failure.linkage != null) {
                throw if (e is SourceCompatibilityException) e else SourceCompatibilityException(e)
            }
            // Cancellation keeps the existing IO callback transport; source mapping recognizes
            // its original cause. Unrelated Errors remain host failures and propagate unchanged.
            if (e !is Exception && failure.cancellation == null) throw e
            if (e is IOException) {
                throw e
            } else {
                throw IOException(e)
            }
        }
    }
}
