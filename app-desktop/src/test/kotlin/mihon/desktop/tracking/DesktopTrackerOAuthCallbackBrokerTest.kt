package mihon.desktop.tracking

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class DesktopTrackerOAuthCallbackBrokerTest {
    @Test
    fun `query and fragment callbacks deliver only the matching provider state once`() = runTest {
        val states = ArrayDeque(listOf("mal-state", "ani-state"))
        val broker = DesktopTrackerOAuthCallbackBroker(states::removeFirst)

        val mal = broker.begin(DesktopTrackerOAuthProvider.MY_ANIME_LIST, Duration.ofSeconds(2))
        val malResult = async { mal.awaitCredential() }
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_STATE,
            broker.handle("mihon://myanimelist-auth?code=attacker&state=wrong").outcome,
        )
        assertFalse(malResult.isCompleted)
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.DELIVERED,
            broker.handle("mihon://myanimelist-auth?code=mal-code&state=mal-state").outcome,
        )
        assertEquals("mal-code", malResult.await())
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.DUPLICATE,
            broker.handle("mihon://myanimelist-auth?code=mal-code&state=mal-state").outcome,
        )

        val ani = broker.begin(DesktopTrackerOAuthProvider.ANI_LIST, Duration.ofSeconds(2))
        val aniResult = async { ani.awaitCredential() }
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.DELIVERED,
            broker.handle("mihon://anilist-auth#access_token=ani-token&state=ani-state").outcome,
        )
        assertEquals("ani-token", aniResult.await())
    }

    @Test
    fun `oauth shaped wrong host provider state and provider errors are consumed without secret exposure`() = runTest {
        val broker = DesktopTrackerOAuthCallbackBroker { "expected-state" }
        val session = broker.begin(DesktopTrackerOAuthProvider.SHIKIMORI, Duration.ofSeconds(2))
        val result = async { runCatching { session.awaitCredential() } }

        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_HOST,
            broker.handle("mihon://unknown-auth?code=host-secret&state=expected-state").outcome,
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_HOST,
            broker.handle("mihon://shikimori-auth.attacker.invalid?code=host-secret&state=expected-state").outcome,
        )
        val callbackShapedWrongHost =
            broker.handle("mihon://wrong?code=callback-secret&state=expected-state")
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_HOST,
            callbackShapedWrongHost.outcome,
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.HandleResult.NotOAuth,
            broker.handle("https://example.invalid/callback?code=ordinary&state=expected-state"),
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.HandleResult.NotOAuth,
            broker.handle("tachiyomi://manga?code=ordinary&state=expected-state"),
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.HandleResult.NotOAuth,
            broker.handle("mihon://search?state=expected-state"),
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_PROVIDER,
            broker.handle("mihon://bangumi-auth?code=provider-secret&state=expected-state").outcome,
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_STATE,
            broker.handle("mihon://shikimori-auth?code=state-secret&state=wrong").outcome,
        )
        assertFalse(result.isCompleted)

        val providerError = broker.handle(
            "mihon://shikimori-auth?error=access_denied&error_description=token-secret&state=expected-state",
        )
        assertEquals(DesktopTrackerOAuthCallbackBroker.Outcome.PROVIDER_ERROR, providerError.outcome)
        val failure = result.await().exceptionOrNull()
        assertTrue(failure is DesktopTrackerOAuthCallbackException)
        val diagnostic = listOf(
            callbackShapedWrongHost,
            providerError,
            failure,
            broker,
            session,
        ).joinToString()
        assertFalse(diagnostic.contains("callback-secret"))
        assertFalse(diagnostic.contains("token-secret"))
        assertFalse(diagnostic.contains("host-secret"))
        assertFalse(diagnostic.contains("provider-secret"))
        assertFalse(diagnostic.contains("state-secret"))

        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.STALE,
            broker.handle("mihon://shikimori-auth?code=late&state=expected-state").outcome,
        )
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.HandleResult.NotOAuth,
            broker.handle("tachiyomi://manga?url=ordinary"),
        )
    }

    @Test
    fun `code providers read query only while AniList reads fragment only`() = runTest {
        val states = ArrayDeque(listOf("mal-state", "ani-state"))
        val broker = DesktopTrackerOAuthCallbackBroker(states::removeFirst)

        val mal = broker.begin(DesktopTrackerOAuthProvider.MY_ANIME_LIST, Duration.ofSeconds(2))
        val malResult = async { mal.awaitCredential() }
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_STATE,
            broker.handle(
                "mihon://myanimelist-auth#code=fragment-code&state=mal-state&error=fragment-error",
            ).outcome,
        )
        assertFalse(malResult.isCompleted)
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.DELIVERED,
            broker.handle("mihon://myanimelist-auth?code=query-code&state=mal-state").outcome,
        )
        assertEquals("query-code", malResult.await())

        val ani = broker.begin(DesktopTrackerOAuthProvider.ANI_LIST, Duration.ofSeconds(2))
        val aniResult = async { ani.awaitCredential() }
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.REJECTED_STATE,
            broker.handle(
                "mihon://anilist-auth?access_token=query-token&state=ani-state&error=query-error",
            ).outcome,
        )
        assertFalse(aniResult.isCompleted)
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.DELIVERED,
            broker.handle("mihon://anilist-auth#access_token=fragment-token&state=ani-state").outcome,
        )
        assertEquals("fragment-token", aniResult.await())
    }

    @Test
    fun `cancel timeout and close clear the unique pending session`() = runTest {
        val states = ArrayDeque(listOf("cancelled", "timed-out", "closed", "replacement"))
        val broker = DesktopTrackerOAuthCallbackBroker(states::removeFirst)

        val cancelled = broker.begin(DesktopTrackerOAuthProvider.MY_ANIME_LIST, Duration.ofSeconds(2))
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { cancelled.awaitCredential() }
        waiter.cancelAndJoin()
        assertEquals(
            DesktopTrackerOAuthCallbackBroker.Outcome.STALE,
            broker.handle("mihon://myanimelist-auth?code=late&state=cancelled").outcome,
        )

        val timedOut = broker.begin(DesktopTrackerOAuthProvider.ANI_LIST, Duration.ofMillis(1))
        assertThrows(TimeoutCancellationException::class.java) {
            kotlinx.coroutines.runBlocking { timedOut.awaitCredential() }
        }

        val closed = broker.begin(DesktopTrackerOAuthProvider.BANGUMI, Duration.ofSeconds(2))
        assertThrows(IllegalStateException::class.java) {
            broker.begin(DesktopTrackerOAuthProvider.SHIKIMORI, Duration.ofSeconds(2))
        }
        closed.close()

        val replacement = broker.begin(DesktopTrackerOAuthProvider.SHIKIMORI, Duration.ofSeconds(2))
        replacement.close()
    }
}
