package mihon.data.sync.runtime

import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.data.sync.http.rateLimitNotBeforeMillis

/** Persists and enforces GitHub's cooldown across every sync space bound to one account. */
internal class SyncAccountHttpRequestGate(
    private val store: SyncRunStore,
    private val accountId: Long,
    private val clock: () -> Long,
) : SyncHttpRequestGate {
    init {
        require(accountId > 0L) { "GitHub account id must be positive" }
    }

    override suspend fun beforeRequest() {
        val remaining = store.accountHttpNotBefore(accountId) - clock()
        if (remaining > 0L) {
            throw SyncHttpException(
                message = "GitHub account rate limit is still active",
                retryable = true,
                failureClass = SyncHttpFailureClass.RATE_LIMITED,
                retryAfterMillis = remaining,
            )
        }
    }

    override suspend fun afterResponse(response: SyncHttpResponse) {
        val deadline = response.rateLimitNotBeforeMillis(clock()) ?: return
        if (deadline > clock()) store.extendAccountHttpNotBefore(accountId, deadline)
    }
}
