package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncCreationAttempt
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailure
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Headers.Companion.headersOf
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncSpaceDiscoveryContractTest {
    @Test
    fun `recovery rejects a polluted sync branch even when default bootstrap is intact`() = runTest {
        for (pollution in listOf("README", "tampered-bootstrap")) {
            MockWebServer().use { server ->
                server.start()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.url.encodedPath
                        val main = "a".repeat(40)
                        val sync = "b".repeat(40)
                        val mainBlob = "c".repeat(40)
                        val syncBlob = "d".repeat(40)
                        return when {
                            path == "/user" -> MockResponse(body = ACCOUNT)
                            path == "/user/installations" -> MockResponse(body = INSTALLATIONS)
                            path.endsWith("/repositories") ->
                                MockResponse(body = """{"repositories":[],"total_count":0}""")
                            path == "/repos/synthetic-user/mihon-sync" -> MockResponse(body = repositoryJson())
                            path.contains("/git/ref/") -> {
                                val sha = if (path.endsWith("/main")) main else sync
                                MockResponse(body = """{"object":{"sha":"$sha"}}""")
                            }
                            path.contains("/git/commits/") -> {
                                val sha = path.substringAfterLast('/')
                                MockResponse(body = """{"tree":{"sha":"$sha"}}""")
                            }
                            path.contains("/git/trees/") -> {
                                val isMain = path.endsWith(main)
                                val name = if (!isMain && pollution == "README") {
                                    "README.md"
                                } else {
                                    ".mihon-sync/bootstrap"
                                }
                                val sha = if (isMain) mainBlob else syncBlob
                                MockResponse(
                                    body = """{"truncated":false,"tree":[
                                        {"path":"$name","type":"blob","mode":"100644","sha":"$sha"}
                                    ]}""".trimIndent(),
                                )
                            }
                            path.contains("/git/blobs/") -> {
                                val content = if (path.endsWith(mainBlob)) "mihon-sync bootstrap" else "user content"
                                val encoded = content.encodeToByteArray().toByteString().base64()
                                MockResponse(body = """{"encoding":"base64","content":"$encoded"}""")
                            }
                            else -> MockResponse(code = 500)
                        }
                    }
                }
                var claimed = false
                val result = client(server).createOrResume(attempt().copy(submitted = true)) { claimed = true }
                assertEquals(SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED), result)
                assertTrue(!claimed)
                repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
            }
        }
    }

    @Test
    fun `multiple valid visible spaces are returned explicitly without choosing one`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            val first = repositoryJson().replace("mihon-sync", "sync-one")
            val second = repositoryJson().replace("mihon-sync", "sync-two").replace("\"id\":99", "\"id\":100")
            server.enqueue(MockResponse(body = """{"repositories":[$first,$second],"total_count":2}"""))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            enqueueSpace(server, SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("one", 1, "").descriptor))
            enqueueSpace(server, SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("two", 1, "").descriptor))
            val result = client(server).discover()
            assertTrue(result is SyncSpaceDiscovery.Multiple)
            assertEquals(
                setOf("one", "two"),
                (result as SyncSpaceDiscovery.Multiple).spaces.map { it.descriptor.spaceId }.toSet(),
            )
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `expired or revoked credential requests authorization without issuing network calls`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = GitHubSyncSpaceClient(OkHttpClient(), {
                throw GitHubAuthException(
                    GitHubAuthFailure(GitHubAuthFailureReason.REVOKED, "authorization required", false),
                )
            }, server.url("/").toString())
            assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED), client.discover())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `concurrent creation winner is adopted without replacing its protection choice`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val winner = SyncSpaceCrypto.create("winner-space", 1, "winner-password").descriptor
            var exists = false
            var posts = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/user" -> MockResponse(body = ACCOUNT)
                        path == "/user/installations" -> MockResponse(body = INSTALLATIONS)
                        path.endsWith("/repositories") -> MockResponse(body = """{"repositories":[],"total_count":0}""")
                        request.method == "POST" -> { exists = true; posts++; MockResponse(code = 422, body = "{}") }
                        !exists -> MockResponse(code = 404, body = "{}")
                        path == "/repos/synthetic-user/mihon-sync" -> MockResponse(body = repositoryJson(description = "Mihon sync setup:other-attempt-0001"))
                        path.contains("/git/ref/") -> MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}""")
                        path.contains("/git/commits/") -> MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}""")
                        path.contains("/git/trees/") -> MockResponse(body = """{"truncated":false,"tree":[{"path":".mihon-sync/space.json","type":"blob","mode":"100644","sha":"${"c".repeat(40)}"}]}""")
                        path.contains("/git/blobs/") -> MockResponse(body = """{"encoding":"base64","content":"${SyncSpaceDescriptorCodec.encode(winner).toByteString().base64()}"}""")
                        else -> error("unexpected request")
                    }
                }
            }
            val result = client(server).createOrResume(attempt()) {}
            assertTrue(result is SyncSpaceCreation.Existing)
            assertEquals(winner, (result as SyncSpaceCreation.Existing).space.descriptor)
            assertEquals(1, posts)
        }
    }

    @Test
    fun `interrupted initialization resumes only its own exact bootstrap tree`() = runTest {
        for (content in listOf("mihon-sync bootstrap", "unrelated user data")) {
            MockWebServer().use { server ->
                server.start()
                enqueueIdentity(server)
                server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
                server.enqueue(MockResponse(body = repositoryJson().replace("\"size\":0", "\"size\":1")))
                // The sync branch has not yet been created, but the default branch bootstrap exists.
                server.enqueue(MockResponse(code = 404, body = "{}"))
                server.enqueue(MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}"""))
                server.enqueue(MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}"""))
                server.enqueue(MockResponse(body = """{"truncated":false,"tree":[{"path":".mihon-sync","mode":"040000","type":"tree","sha":"${"d".repeat(40)}"},{"path":".mihon-sync/bootstrap","mode":"100644","type":"blob","sha":"${"c".repeat(40)}"}]}"""))
                server.enqueue(MockResponse(body = """{"encoding":"base64","content":"${content.encodeToByteArray().toByteString().base64()}"}"""))
                server.enqueue(MockResponse(code = 404, body = "{}"))
                val result = client(server).createOrResume(attempt().copy(submitted = true)) { }
                if (content == "mihon-sync bootstrap") {
                    assertTrue(result is SyncSpaceCreation.Ready)
                } else {
                    assertEquals(SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED), result)
                }
                repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
            }
        }
    }

    @Test
    fun `both protection modes are discovered through immutable commit tree and real descriptor codec`() = runTest {
        for (password in listOf("", " 空格🔒 ")) {
            MockWebServer().use { server ->
                server.start()
                val descriptor = SyncSpaceCrypto.create("fixture-space", 1, password).descriptor
                enqueueIdentity(server)
                server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
                server.enqueue(MockResponse(body = repositoryJson()))
                enqueueSpace(server, SyncSpaceDescriptorCodec.encode(descriptor))
                val result = client(server).discover()
                assertTrue(result is SyncSpaceDiscovery.Found)
                assertEquals(descriptor, (result as SyncSpaceDiscovery.Found).space.descriptor)
                assertEquals("a".repeat(40), result.space.head)
                val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
                assertTrue(paths.contains("/repos/synthetic-user/mihon-sync/git/commits/${"a".repeat(40)}"))
                assertTrue(paths.contains("/repos/synthetic-user/mihon-sync/git/blobs/${"c".repeat(40)}"))
            }
        }
    }

    @Test
    fun `pagination follows every repository page and rejects off-origin next link`() = runTest {
        for (external in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                enqueueIdentity(server)
                val next = if (external) {
                    "https://unrelated.example/steal"
                } else {
                    server.url("/user/installations/7/repositories?per_page=100&page=2").toString()
                }
                server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}""", headers = headersOf("Link", "<$next>; rel=\"next\"")))
                if (!external) {
                    server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
                    server.enqueue(MockResponse(code = 404, body = "{}"))
                }
                val result = client(server).discover()
                if (external) {
                    assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED), result)
                    assertEquals(3, server.requestCount)
                } else {
                    assertTrue(result is SyncSpaceDiscovery.NoVisibleSpace)
                    assertEquals(5, server.requestCount)
                }
            }
        }
    }

    @Test
    fun `old or unknown space and public repository never become creation candidates`() = runTest {
        for (old in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                enqueueIdentity(server)
                server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
                server.enqueue(MockResponse(body = repositoryJson()))
                if (old) {
                    server.enqueue(MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}"""))
                    server.enqueue(MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}"""))
                    server.enqueue(MockResponse(body = """{"truncated":false,"tree":[{"path":".mihon-sync/index/bootstrap/0/bootstrap.bin","type":"blob","mode":"100644","sha":"${"c".repeat(40)}"}]}"""))
                } else {
                    enqueueSpace(server, """{"spaceFormatVersion":999}""".encodeToByteArray())
                }
                assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.INCOMPATIBLE), client(server).discover())
                repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
            }
        }
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(body = repositoryJson().replace("\"private\":true", "\"private\":false")))
            assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.NAME_OCCUPIED), client(server).discover())
        }
    }

    @Test
    fun `discovery inherits production DNS rather than constructing an unrelated client`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            var resolutions = 0
            val production = OkHttpClient.Builder().dns {
                resolutions++
                listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }.build()
            val client = GitHubSyncSpaceClient(
                production,
                { "synthetic-token" },
                server.url("/").newBuilder().host("localhost").build().toString(),
            )
            assertTrue(client.discover() is SyncSpaceDiscovery.NoVisibleSpace)
            assertTrue(resolutions > 0)
        }
    }

    @Test
    fun `creation persists intent before POST and only creates the fixed private repository`() = runTest {
        MockWebServer().use { server ->
            server.start()
            var saved: SyncCreationAttempt? = null
            var created = false
            var body = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/user" -> MockResponse(body = ACCOUNT)
                    request.url.encodedPath == "/user/installations" -> MockResponse(body = INSTALLATIONS)
                    request.url.encodedPath.endsWith("/repositories") -> MockResponse(body = """{"repositories":[],"total_count":0}""")
                    request.method == "POST" -> {
                        check(saved?.submitted == true)
                        check(request.url.encodedPath == "/user/repos")
                        body = request.body!!.utf8()
                        created = true
                        MockResponse(code = 201, body = repositoryJson())
                    }
                    else -> MockResponse(code = 404, body = "{}")
                }
            }
            val result = client(server).createOrResume(attempt()) { saved = it }
            assertTrue(result is SyncSpaceCreation.Ready)
            assertTrue(created)
            assertTrue(body.contains("\"private\":true"))
            assertTrue(body.contains("\"name\":\"mihon-sync\""))
            assertTrue(body.contains("attempt-fixture-0001"))
            assertEquals(99L, saved?.repositoryId)
        }
    }

    @Test
    fun `unknown creation outcome is persisted and never blindly posted twice`() = runTest {
        MockWebServer().use { server ->
            server.start()
            var posts = 0
            var saved = attempt()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/user" -> MockResponse(body = ACCOUNT)
                    request.url.encodedPath == "/user/installations" -> MockResponse(body = INSTALLATIONS)
                    request.url.encodedPath.endsWith("/repositories") -> MockResponse(body = """{"repositories":[],"total_count":0}""" )
                    request.method == "POST" -> { posts++; MockResponse(code = 500, body = "{}") }
                    else -> MockResponse(code = 404, body = "{}")
                }
            }
            repeat(2) {
                assertEquals(
                    SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED),
                    client(server).createOrResume(saved) { saved = it },
                )
            }
            assertEquals(1, posts)
            assertTrue(saved.submitted)
        }
    }

    @Test
    fun `existing unrelated empty repository is never claimed or initialized`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(body = repositoryJson(description = "unrelated user repository")))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            val result = client(server).createOrResume(attempt()) { error("must not persist a creation request") }
            assertEquals(SyncSpaceCreation.Failed(SyncDiscoveryProblem.NAME_OCCUPIED), result)
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `matching pending marker can resume after a lost response without POST`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(body = repositoryJson()))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            var saved = attempt().copy(submitted = true)
            server.enqueue(MockResponse(code = 404, body = "{}"))
            val result = client(server).createOrResume(saved) { saved = it }
            assertTrue(result is SyncSpaceCreation.Ready)
            assertEquals(99L, saved.repositoryId)
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `selected empty installation returns no visible space without claiming global absence`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            val result = client(server).discover()
            assertTrue(result is SyncSpaceDiscovery.NoVisibleSpace)
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `account change and missing installation permissions fail before reading repositories`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = ACCOUNT))
            assertEquals(
                SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.ACCOUNT_CHANGED),
                client(server).discover(999),
            )
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server, permissions = """{"contents":"write","metadata":"read"}""")
            assertEquals(
                SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED),
                client(server).discover(),
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `HTTP failures and malformed bodies never turn into no visible space`() = runTest {
        for ((code, expected) in listOf(
            401 to SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
            403 to SyncDiscoveryProblem.AUTHORIZATION_REQUIRED,
            429 to SyncDiscoveryProblem.RATE_LIMITED,
            500 to SyncDiscoveryProblem.RETRYABLE,
            200 to SyncDiscoveryProblem.MALFORMED,
        )) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(code = code, body = "not-json"))
                assertEquals(SyncSpaceDiscovery.Failed(expected), client(server).discover())
            }
        }
    }

    private fun client(server: MockWebServer) = GitHubSyncSpaceClient(
        OkHttpClient(), { "synthetic-token" }, server.url("/").toString(),
    )

    private fun enqueueIdentity(
        server: MockWebServer,
        permissions: String = """{"administration":"write","contents":"write","metadata":"read"}""",
    ) {
        server.enqueue(MockResponse(body = ACCOUNT))
        server.enqueue(MockResponse(body = """{"installations":[{"id":7,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},"suspended_at":null,"repository_selection":"selected","permissions":$permissions}]}"""))
    }

    companion object {
        private const val ACCOUNT = """{"id":42,"login":"synthetic-user","type":"User"}"""
        private const val INSTALLATIONS = """{"installations":[{"id":7,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},"suspended_at":null,"repository_selection":"selected","permissions":{"administration":"write","contents":"write","metadata":"read"}}]}"""
    }

    private fun attempt() = SyncCreationAttempt(SyncGitHubAccount(42, "synthetic-user"), "attempt-fixture-0001")

    private fun enqueueSpace(server: MockWebServer, descriptor: ByteArray) {
        server.enqueue(MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}"""))
        server.enqueue(MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}"""))
        server.enqueue(MockResponse(body = """{"truncated":false,"tree":[{"path":".mihon-sync/space.json","type":"blob","mode":"100644","sha":"${"c".repeat(40)}"}]}"""))
        server.enqueue(MockResponse(body = """{"encoding":"base64","content":"${descriptor.toByteString().base64()}"}"""))
    }

    private fun repositoryJson(description: String = "Mihon sync setup:attempt-fixture-0001") =
        """{"id":99,"name":"mihon-sync","full_name":"synthetic-user/mihon-sync","owner":{"id":42,"login":"synthetic-user","type":"User"},"private":true,"permissions":{"push":true},"description":"$description","size":0,"default_branch":"main"}"""
}
