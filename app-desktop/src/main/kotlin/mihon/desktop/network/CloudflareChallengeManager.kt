package mihon.desktop.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentLinkedQueue

class CloudflareChallengeManager {

    private val _challenges = MutableSharedFlow<CloudflareChallenge>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val challenges: SharedFlow<CloudflareChallenge> = _challenges

    private val recentChallenges = ConcurrentLinkedQueue<CloudflareChallenge>()

    fun emit(challenge: CloudflareChallenge) {
        recentChallenges.add(challenge)
        _challenges.tryEmit(challenge)
    }

    /** For testing: poll the most recent challenge without coroutines. */
    internal fun tryReceive(): CloudflareChallenge? = recentChallenges.poll()
}
