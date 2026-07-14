package mihon.desktop.platform

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class DesktopOAuthCallbackServer(
    private val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
    private val port: Int = 0,
    private val executorFactory: () -> ExecutorService = {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "desktop-oauth-callback").apply { isDaemon = true }
        }
    },
) : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    init {
        require(bindAddress.isLoopbackAddress) { "OAuth callback must bind to a loopback address" }
    }

    @Synchronized
    fun start(
        expectedState: String,
        timeout: Duration = Duration.ofMinutes(2),
    ): OAuthCallbackSession {
        require(expectedState.isNotBlank()) { "OAuth state must not be blank" }
        check(server == null) { "OAuth callback server is already running" }
        val result = CompletableDeferred<String>()
        val createdExecutor = executorFactory()
        val httpServer = try {
            HttpServer.create(InetSocketAddress(bindAddress, port), 0).also { created ->
                created.executor = createdExecutor
                created.createContext("/oauth/callback") { exchange -> handleCallback(exchange, expectedState, result) }
                created.start()
            }
        } catch (error: Throwable) {
            createdExecutor.shutdownNow()
            throw error
        }
        server = httpServer
        executor = createdExecutor
        val host = if (bindAddress is java.net.Inet6Address) "[${bindAddress.hostAddress}]" else bindAddress.hostAddress
        return OAuthCallbackSession(
            redirectUri = "http://$host:${httpServer.address.port}/oauth/callback",
            await = {
                try {
                    withTimeout(timeout.toMillis()) { result.await() }
                } finally {
                    close()
                }
            },
        )
    }

    private fun handleCallback(
        exchange: HttpExchange,
        expectedState: String,
        result: CompletableDeferred<String>,
    ) {
        val parameters = exchange.requestURI.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val pieces = part.split('=', limit = 2)
                decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
            }
        when {
            exchange.remoteAddress.address?.isLoopbackAddress != true -> exchange.respond(403, "Loopback callbacks only")
            parameters["state"] != expectedState -> exchange.respond(400, "Invalid OAuth state")
            parameters["error"] != null -> {
                result.completeExceptionally(IllegalStateException("OAuth authorization failed: ${parameters["error"]}"))
                exchange.respond(400, "Authorization failed; you may close this window")
            }
            parameters["code"].isNullOrBlank() -> exchange.respond(400, "Missing authorization code")
            else -> {
                result.complete(parameters.getValue("code"))
                exchange.respond(200, "Authorization complete; you may close this window")
            }
        }
    }

    @Synchronized
    override fun close() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

class OAuthCallbackSession internal constructor(
    val redirectUri: String,
    private val await: suspend () -> String,
) {
    suspend fun awaitCode(): String = await()
}
