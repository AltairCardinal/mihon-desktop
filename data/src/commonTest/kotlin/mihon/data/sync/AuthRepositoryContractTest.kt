package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubPrivateRepositorySelector
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthRepositoryContractTest {
    @Test
    fun `repository pages deduplicate real booleans and exclude public or read only repositories`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"installations":[{"id":1},{"id":1}]}"""))
            server.enqueue(
                MockResponse(
                    headers = headersOf(
                        "Link",
                        "<${server.url("/user/installations/1/repositories?page=2")}>; rel=\"next\"",
                    ),
                    body = """{"repositories":[
                        {"full_name":"owner/private","private":true,"permissions":{"push":true}},
                        {"full_name":"owner/public","private":false,"permissions":{"push":true}},
                        {"full_name":"owner/read-only","private":true,"permissions":{"push":false}},
                        {"full_name":"owner/string-private","private":"true","permissions":{"push":true}},
                        {"full_name":"owner/string-push","private":true,"permissions":{"push":"true"}}
                    ]}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse(
                    body = """{"repositories":[
                {"full_name":"owner/private","private":true,"permissions":{"push":true}},
                {"full_name":"owner/second","private":true,"permissions":{"admin":true}}
            ]}
                    """.trimIndent(),
                ),
            )
            val repositories = selector(server).select("sync")
            assertEquals(listOf("owner/private", "owner/second"), repositories.map { it.repository.fullName })
            assertEquals(3, server.requestCount)
            repeat(3) { assertEquals("Bearer synthetic-access", server.takeRequest().headers["Authorization"]) }
        }
    }

    @Test
    fun `pagination rejects a repeated page before making a duplicate request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val first = server.url("/user/installations?per_page=100&page=1")
            server.enqueue(
                MockResponse(
                    headers = headersOf("Link", "<$first>; rel=\"next\""),
                    body = """{"installations":[]}""",
                ),
            )
            val outcome = runCatching { selector(server).select("sync") }
            assertTrue(outcome.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `pagination accepts exactly one hundred complete pages but never requests page one hundred one`() = runTest {
        for (morePages in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                repeat(100) { page ->
                    val response = MockResponse.Builder().body("""{"installations":[]}""")
                    if (page < 99 || morePages) {
                        response.addHeader(
                            "Link",
                            "<${server.url("/user/installations?per_page=100&page=${page + 2}")}>; rel=\"next\"",
                        )
                    }
                    server.enqueue(response.build())
                }
                // An incorrect request beyond the limit also gets a response so it cannot hang the test.
                server.enqueue(MockResponse(body = """{"installations":[]}"""))
                val result = runCatching { selector(server).select("sync") }
                assertEquals(morePages, result.isFailure)
                if (!morePages) assertEquals(emptyList<Any>(), result.getOrThrow())
                assertEquals(100, server.requestCount)
            }
        }
    }

    @Test
    fun `pagination never forwards credentials to another host port or endpoint`() = runTest {
        for (differentHost in listOf(false, true)) {
            MockWebServer().use { server ->
                MockWebServer().use { foreign ->
                    server.start()
                    foreign.start()
                    val url = foreign.url("/user/installations?page=2").newBuilder()
                        .apply { if (differentHost) host("127.0.0.1") }
                        .build()
                    // A vulnerable implementation receives a complete response rather than hanging.
                    foreign.enqueue(MockResponse(body = """{"installations":[]}"""))
                    server.enqueue(
                        MockResponse(
                            headers = headersOf("Link", "<$url>; rel=\"next\""),
                            body = """{"installations":[]}""",
                        ),
                    )
                    val before = server.requestCount
                    val result = runCatching { selector(server).select("sync") }
                    assertTrue(result.isFailure, "Pagination accepted an unrelated endpoint")
                    assertEquals(before + 1, server.requestCount)
                    assertEquals(0, foreign.requestCount)
                }
            }
        }
    }

    @Test
    fun `pagination endpoint changes are rejected even on the same trusted host`() = runTest {
        for (path in listOf("/user/installations-evil?page=2", "/user/installations/999/repositories?page=2")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse(
                        headers = headersOf("Link", "<${server.url(path)}>; rel=\"next\""),
                        body = """{"installations":[]}""",
                    ),
                )
                server.enqueue(MockResponse(body = """{"installations":[]}"""))
                assertTrue(runCatching { selector(server).select("sync") }.isFailure)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `empty pages succeed while HTTP errors and malformed pages fail without reflecting data`() = runTest {
        val invalid = listOf(
            MockResponse(code = 401, body = SECRET),
            MockResponse(code = 403, body = SECRET),
            MockResponse(code = 429, body = SECRET),
            MockResponse(code = 500, body = SECRET),
            MockResponse(body = "malformed $SECRET"),
            MockResponse(body = """{"installations":"$SECRET"}"""),
            MockResponse(body = """{"installations":["$SECRET"]}"""),
            MockResponse(body = "{}"),
        )
        for (response in invalid) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(response)
                val result = runCatching { selector(server).select("sync") }
                assertTrue(result.isFailure)
                assertFalse(result.exceptionOrNull()!!.stackTraceToString().contains(SECRET))
            }
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"installations":[]}"""))
            assertEquals(emptyList<Any>(), selector(server).select("sync"))
        }
    }

    private fun selector(server: MockWebServer) = GitHubPrivateRepositorySelector(
        OkHttpClient(),
        { "synthetic-access" },
        server.url("/").toString(),
    )

    companion object {
        private const val SECRET = "synthetic-private-response"
    }
}
