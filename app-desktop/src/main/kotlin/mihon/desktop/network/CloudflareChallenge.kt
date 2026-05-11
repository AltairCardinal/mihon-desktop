package mihon.desktop.network

import java.util.concurrent.CountDownLatch

class CloudflareChallenge(
    val url: String,
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var resolved: Boolean = false,
)
