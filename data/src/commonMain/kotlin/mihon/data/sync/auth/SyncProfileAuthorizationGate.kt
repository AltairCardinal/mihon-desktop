package mihon.data.sync.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.data.sync.http.rateLimitNotBeforeMillis
import tachiyomi.core.common.preference.PreferenceStore

/** OAuth can be throttled before /user identifies an account; the profile retains that exact deadline. */
internal class SyncProfileAuthorizationGate(
    preferenceStore: PreferenceStore,
    private val clock: () -> Long,
    private val accountGate: suspend () -> SyncHttpRequestGate?,
) : SyncHttpRequestGate {
    private val mutex = Mutex()
    private val deadline = preferenceStore.getLong(KEY, 0)

    fun notBeforeMillis(): Long = deadline.get()

    override suspend fun beforeRequest() {
        val now = clock()
        val until = deadline.get()
        if (until > now) {
            throw SyncHttpException(
                code = 429,
                message = "authorization retry deadline has not elapsed",
                retryable = true,
                failureClass = SyncHttpFailureClass.RATE_LIMITED,
                retryAfterMillis = until - now,
            )
        }
        accountGate()?.beforeRequest()
    }

    override suspend fun afterResponse(response: SyncHttpResponse) {
        response.rateLimitNotBeforeMillis(clock())?.let { until ->
            mutex.withLock {
                if (until > deadline.get()) deadline.set(until)
            }
        }
        accountGate()?.afterResponse(response)
    }

    companion object {
        private const val KEY = "sync.oauth.http-not-before-v1"
    }
}
