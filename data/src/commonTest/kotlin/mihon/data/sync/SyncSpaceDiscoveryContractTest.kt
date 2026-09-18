@file:Suppress("ktlint:standard:max-line-length")

package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncCreationAttempt
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailure
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
                                MockResponse(body = """{"repositories":[${repositoryJson()}],"total_count":1}""")
                            path == "/repos/synthetic-user/mihon-sync" -> MockResponse(body = repositoryJson())
                            path == "/repos/synthetic-user/mihon-sync/git/matching-refs/" -> MockResponse(
                                body = """[
                                    {"ref":"refs/heads/main","object":{"sha":"$main"}},
                                    {"ref":"refs/heads/mihon-sync-v1","object":{"sha":"$sync"}}
                                ]
                                """.trimIndent(),
                            )
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
                                    ]}
                                    """.trimIndent(),
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
                val expectedProblem = if (pollution == "README") {
                    SyncDiscoveryProblem.CREATION_UNCONFIRMED
                } else {
                    SyncDiscoveryProblem.INCOMPATIBLE
                }
                assertEquals(SyncSpaceCreation.Failed(expectedProblem), result)
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
    fun `renamed old v2 space does not hide a verified fixed-name empty repository`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val descriptor = SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("archived-space", 1, "").descriptor)
            val fixed = repositoryJson()
            val renamed = repositoryJson()
                .replace("mihon-sync", "mihon-sync-archive")
                .replace("\"id\":99", "\"id\":100")
                .replace("\"size\":0", "\"size\":1")
            val blob = "c".repeat(40)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/user" -> MockResponse(body = ACCOUNT)
                        path == "/user/installations" -> MockResponse(body = INSTALLATIONS)
                        path == "/user/installations/7/repositories" -> MockResponse(
                            body = """{"repositories":[$fixed,$renamed],"total_count":2}""",
                        )
                        path == "/repos/synthetic-user/mihon-sync" -> MockResponse(body = fixed)
                        path == "/repos/synthetic-user/mihon-sync-archive/git/ref/heads/mihon-sync-v1" ->
                            MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}""")
                        path == "/repos/synthetic-user/mihon-sync/git/ref/heads/mihon-sync-v1" ->
                            MockResponse(code = 404, body = "{}")
                        path.contains("mihon-sync-archive/git/commits/") ->
                            MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}""")
                        path.contains("mihon-sync-archive/git/trees/") -> MockResponse(
                            body = """{"truncated":false,"tree":[{"path":".mihon-sync/space.json","type":"blob","mode":"100644","sha":"$blob"}]}""",
                        )
                        path.contains("mihon-sync-archive/git/blobs/") -> MockResponse(
                            body = """{"encoding":"base64","content":"${descriptor.toByteString().base64()}"}""",
                        )
                        path.endsWith("/branches") || path.endsWith("/tags") -> MockResponse(body = "[]")
                        path.contains("/git/matching-refs/") ->
                            MockResponse(code = 409, body = """{"message":"Git Repository is empty."}""")
                        path.endsWith("/git/ref/heads/main") -> MockResponse(code = 404, body = "{}")
                        path.endsWith("/contents/") -> MockResponse(
                            code = 404,
                            body = """{"message":"This repository is empty."}""",
                        )
                        else -> MockResponse(code = 500, body = "unexpected request: $path")
                    }
                }
            }

            val result = client(server).discover()

            assertTrue(result is SyncSpaceDiscovery.EmptyRepository)
            assertEquals("mihon-sync", (result as SyncSpaceDiscovery.EmptyRepository).candidate.repository.name)
        }
    }

    @Test
    fun `create recheck prefers fixed-name empty repository over one renamed old space`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val descriptor = SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("archived-space", 1, "").descriptor)
            val fixed = repositoryJson()
            val renamed = repositoryJson()
                .replace("mihon-sync", "mihon-sync-archive")
                .replace("\"id\":99", "\"id\":100")
                .replace("\"size\":0", "\"size\":1")
            val blob = "c".repeat(40)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/user" -> MockResponse(body = ACCOUNT)
                        path == "/user/installations" -> MockResponse(body = INSTALLATIONS)
                        path == "/user/installations/7/repositories" -> MockResponse(
                            body = """{"repositories":[$fixed,$renamed],"total_count":2}""",
                        )
                        path == "/repos/synthetic-user/mihon-sync" -> MockResponse(body = fixed)
                        path == "/repos/synthetic-user/mihon-sync-archive/git/ref/heads/mihon-sync-v1" ->
                            MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}""")
                        path == "/repos/synthetic-user/mihon-sync/git/ref/heads/mihon-sync-v1" ->
                            MockResponse(code = 404, body = "{}")
                        path.contains("mihon-sync-archive/git/commits/") ->
                            MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}""")
                        path.contains("mihon-sync-archive/git/trees/") -> MockResponse(
                            body = """{"truncated":false,"tree":[{"path":".mihon-sync/space.json","type":"blob","mode":"100644","sha":"$blob"}]}""",
                        )
                        path.contains("mihon-sync-archive/git/blobs/") -> MockResponse(
                            body = """{"encoding":"base64","content":"${descriptor.toByteString().base64()}"}""",
                        )
                        path.endsWith("/branches") || path.endsWith("/tags") -> MockResponse(body = "[]")
                        path.contains("/git/matching-refs/") ->
                            MockResponse(code = 409, body = """{"message":"Git Repository is empty."}""")
                        path.endsWith("/git/ref/heads/main") -> MockResponse(code = 404, body = "{}")
                        path.endsWith("/contents/") -> MockResponse(
                            code = 404,
                            body = """{"message":"This repository is empty."}""",
                        )
                        else -> MockResponse(code = 500, body = "unexpected request: $path")
                    }
                }
            }

            var persisted: SyncCreationAttempt? = null
            val result = client(server).createOrResume(attempt()) { persisted = it }

            assertEquals(
                SyncSpaceCreation.Ready(
                    mihon.domain.sync.transport.SyncRepository("synthetic-user", "mihon-sync", "mihon-sync-v1"),
                    99,
                    "main",
                ),
                result,
            )
            assertEquals(99L, persisted?.repositoryId)
        }
    }

    @Test
    fun `notes refs prevent a repository from being classified as empty`() = runTest {
        emptyRepositoryServer(
            allRefsCode = 200,
            allRefsBody = """[{"ref":"refs/notes/commits","object":{"sha":"${"a".repeat(40)}"}}]""",
        ).use { server ->
            assertEquals(
                SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.NAME_OCCUPIED),
                client(server).discover(),
            )
        }
    }

    @Test
    fun `installed fixed target followed by direct metadata 404 is retryable not missing access`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    paths += path
                    return when (path) {
                        "/user" -> MockResponse(body = ACCOUNT)
                        "/user/installations" -> MockResponse(body = INSTALLATIONS)
                        "/user/installations/7/repositories" -> MockResponse(
                            body = """{"repositories":[${repositoryJson()}],"total_count":1}""",
                        )
                        "/repos/synthetic-user/mihon-sync" -> MockResponse(code = 404, body = "{}")
                        else -> MockResponse(code = 500, body = "request must stop after inconsistent target 404")
                    }
                }
            }

            val result = client(server).discover()

            assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.RETRYABLE), result)
            assertEquals(
                listOf(
                    "/user",
                    "/user/installations",
                    "/user/installations/7/repositories",
                    "/repos/synthetic-user/mihon-sync",
                ),
                paths,
            )
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
    fun `both protection modes are discovered through immutable commit tree and real descriptor codec`() = runTest {
        for (password in listOf("", " 空格🔒 ")) {
            MockWebServer().use { server ->
                server.start()
                val descriptor = SyncSpaceCrypto.create("fixture-space", 1, password).descriptor
                enqueueIdentity(server)
                val existing = repositoryJson().replace("\"size\":0", "\"size\":1")
                server.enqueue(MockResponse(body = """{"repositories":[$existing],"total_count":1}"""))
                server.enqueue(MockResponse(body = existing))
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
                server.enqueue(
                    MockResponse(
                        body = """{"repositories":[],"total_count":0}""",
                        headers = headersOf("Link", "<$next>; rel=\"next\""),
                    ),
                )
                if (!external) {
                    server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
                    server.enqueue(MockResponse(code = 404, body = "{}"))
                }
                val result = client(server).discover()
                if (external) {
                    assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED), result)
                    assertEquals(3, server.requestCount)
                } else {
                    assertEquals(
                        SyncSpaceDiscovery.NeedsRepositoryAccess(SyncGitHubAccount(42, "synthetic-user")),
                        result,
                    )
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
                val occupied = repositoryJson().replace("\"size\":0", "\"size\":1")
                server.enqueue(MockResponse(body = """{"repositories":[$occupied],"total_count":1}"""))
                server.enqueue(MockResponse(body = occupied))
                if (old) {
                    server.enqueue(MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}"""))
                    server.enqueue(MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}"""))
                    server.enqueue(
                        MockResponse(
                            body = """{"truncated":false,"tree":[{"path":".mihon-sync/index/bootstrap/0/bootstrap.bin","type":"blob","mode":"100644","sha":"${"c".repeat(
                                40,
                            )}"}]}""",
                        ),
                    )
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
            val publicRepository = repositoryJson().replace("\"private\":true", "\"private\":false")
            server.enqueue(MockResponse(body = """{"repositories":[$publicRepository],"total_count":1}"""))
            server.enqueue(MockResponse(body = publicRepository))
            assertEquals(
                SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE),
                client(server).discover(),
            )
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
            assertEquals(
                SyncSpaceDiscovery.NeedsRepositoryAccess(SyncGitHubAccount(42, "synthetic-user")),
                client.discover(),
            )
            assertTrue(resolutions > 0)
        }
    }

    @Test
    fun `user-created fixed empty repository is rechecked read-only before setup advances`() = runTest {
        emptyRepositoryServer().use { server ->
            var saved: SyncCreationAttempt? = null
            val result = client(server).createOrResume(attempt()) { saved = it }

            assertTrue(result is SyncSpaceCreation.Ready)
            val ready = result as SyncSpaceCreation.Ready
            assertEquals("mihon-sync", ready.repository.name)
            assertEquals("mihon-sync-v1", ready.repository.branch)
            assertEquals(99L, ready.repositoryId)
            assertEquals("main", ready.defaultBranch)
            assertEquals(99L, saved?.repositoryId)
            assertEquals(false, saved?.submitted)
            val requests = List(server.requestCount) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" })
            assertFalse(requests.any { it.url.encodedPath == "/user/repos" })
        }
    }

    @Test
    fun `legacy submitted attempt remains unconfirmed on read-only recheck`() = runTest {
        emptyRepositoryServer().use { server ->
            val legacy = attempt().copy(submitted = true, repositoryId = 99)
            var persistCalls = 0

            repeat(2) {
                assertEquals(
                    SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED),
                    client(server).createOrResume(legacy) { persistCalls++ },
                )
            }

            assertEquals(0, persistCalls)
            val requests = List(server.requestCount) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" })
            assertFalse(requests.any { it.url.encodedPath == "/user/repos" })
        }
    }

    @Test
    fun `directly visible fixed repository missing from installation list requests access`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(body = repositoryJson(description = "unrelated user repository")))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            val result = client(server).createOrResume(attempt()) { error("must not persist a creation request") }
            assertEquals(SyncSpaceCreation.Failed(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS), result)
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `selected empty installation without fixed repository visibility requests repository access`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            val result = client(server).discover()
            assertEquals(
                SyncSpaceDiscovery.NeedsRepositoryAccess(SyncGitHubAccount(42, "synthetic-user")),
                result,
            )
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `account change fails before reading repositories`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = ACCOUNT))
            assertEquals(
                SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.ACCOUNT_CHANGED),
                client(server).discover(999),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `missing installation returns typed installation guidance`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = ACCOUNT))
            server.enqueue(MockResponse(body = """{"installations":[]}"""))

            assertEquals(
                SyncSpaceDiscovery.NeedsInstallation(SyncGitHubAccount(42, "synthetic-user")),
                client(server).discover(),
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `missing Contents write permission returns typed permission guidance`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server, permissions = """{"metadata":"read"}""")

            assertEquals(
                SyncSpaceDiscovery.NeedsContentsPermission(SyncGitHubAccount(42, "synthetic-user")),
                client(server).discover(),
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `suspended installation returns typed suspension guidance`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server, suspendedAt = "\"2026-09-18T12:00:00Z\"")

            assertEquals(
                SyncSpaceDiscovery.InstallationSuspended(SyncGitHubAccount(42, "synthetic-user")),
                client(server).discover(),
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `missing suspended timestamp is malformed instead of presumed suspended`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server, includeSuspendedAt = false)

            assertEquals(
                SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED),
                client(server).discover(),
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `contents and metadata installation discovers an empty repository without administration`() = runTest {
        emptyRepositoryServer().use { server ->
            val result = client(server).discover()

            assertTrue(result is SyncSpaceDiscovery.EmptyRepository)
            val requests = List(server.requestCount) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" })
        }
    }

    @Test
    fun `repository discovery never creates repositories through the user repositories endpoint`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            server.enqueue(MockResponse(body = """{"repositories":[],"total_count":0}"""))
            server.enqueue(MockResponse(code = 404, body = "{}"))

            client(server).createOrResume(attempt()) { }

            val methods = List(server.requestCount) { server.takeRequest().method }
            assertFalse(methods.any { it == "POST" }, "Repository discovery must not create a GitHub repository")
        }
    }

    @Test
    fun `verified private empty repository is exposed as an explicit candidate`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/user" -> MockResponse(body = ACCOUNT)
                    "/user/installations" -> MockResponse(
                        body = """{"installations":[{"id":7,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},"suspended_at":null,"repository_selection":"selected","permissions":{"contents":"write","metadata":"read"}}]}""",
                    )
                    "/user/installations/7/repositories" -> MockResponse(
                        body = """{"repositories":[${repositoryJson(
                            description = "user text has no proof",
                        )}],"total_count":1}""",
                    )
                    "/repos/synthetic-user/mihon-sync" -> MockResponse(
                        body = repositoryJson(description = "user text has no proof"),
                    )
                    "/repos/synthetic-user/mihon-sync/git/ref/heads/mihon-sync-v1" -> MockResponse(
                        code = 404,
                        body = "{}",
                    )
                    "/repos/synthetic-user/mihon-sync/git/ref/heads/main" -> MockResponse(
                        code = 409,
                        body = """{"message":"Git Repository is empty."}""",
                    )
                    "/repos/synthetic-user/mihon-sync/git/matching-refs/" ->
                        MockResponse(code = 409, body = """{"message":"Git Repository is empty."}""")
                    "/repos/synthetic-user/mihon-sync/branches",
                    "/repos/synthetic-user/mihon-sync/tags",
                    -> MockResponse(body = "[]")
                    "/repos/synthetic-user/mihon-sync/contents/" -> MockResponse(
                        code = 404,
                        body = """{"message":"This repository is empty."}""",
                    )
                    else -> MockResponse(code = 500, body = "unexpected request: ${request.method} ${request.url}")
                }
            }

            val result = client(server).discover()

            assertEquals("EmptyRepository", result::class.simpleName)
            assertTrue(List(server.requestCount) { server.takeRequest().method }.all { it == "GET" })
        }
    }

    @Test
    fun `unknown matching refs conflict is retryable and cannot prove an empty repository`() = runTest {
        emptyRepositoryServer(
            matchingRefsBody = """{"message":"Repository is temporarily unavailable."}""",
        ).use { server ->
            val result = client(server).discover()

            assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.RETRYABLE), result)
        }
    }

    @Test
    fun `contents missing response without explicit empty repository body is not proof`() = runTest {
        emptyRepositoryServer(
            matchingRefsCode = 200,
            matchingRefsBody = "[]",
            defaultRefCode = 404,
            defaultRefBody = """{"message":"Not Found"}""",
            contentsBody = """{"message":"Not Found"}""",
        ).use { server ->
            val result = client(server).discover()

            assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.RETRYABLE), result)
        }
    }

    @Test
    fun `archived and disabled fixed repositories are not initialization candidates`() = runTest {
        for (repository in listOf(
            repositoryJson().replace("\"archived\":false", "\"archived\":true"),
            repositoryJson().replace("\"disabled\":false", "\"disabled\":true"),
        )) {
            emptyRepositoryServer(repositoryJson = repository).use { server ->
                val result = client(server).discover()

                assertFalse(result is SyncSpaceDiscovery.EmptyRepository)
            }
        }
    }

    @Test
    fun `archived unrelated repository does not block a separate valid v2 space`() = runTest {
        MockWebServer().use { server ->
            server.start()
            enqueueIdentity(server)
            val archived = repositoryJson()
                .replace("mihon-sync", "archived-history")
                .replace("\"archived\":false", "\"archived\":true")
            val valid = repositoryJson()
                .replace("mihon-sync", "sync-two")
                .replace("\"id\":99", "\"id\":100")
            server.enqueue(MockResponse(body = """{"repositories":[$archived,$valid],"total_count":2}"""))
            server.enqueue(MockResponse(code = 404, body = "{}"))
            enqueueSpace(server, SyncSpaceDescriptorCodec.encode(SyncSpaceCrypto.create("two", 1, "").descriptor))

            val result = client(server).discover()

            assertTrue(result is SyncSpaceDiscovery.Found)
            assertEquals("two", (result as SyncSpaceDiscovery.Found).space.descriptor.spaceId)
        }
    }

    @Test
    fun `missing archived or disabled metadata is malformed instead of presumed active`() = runTest {
        for (repository in listOf(
            repositoryJson().replace(",\"archived\":false", ""),
            repositoryJson().replace(",\"disabled\":false", ""),
        )) {
            emptyRepositoryServer(repositoryJson = repository).use { server ->
                assertEquals(
                    SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED),
                    client(server).discover(),
                )
            }
        }
    }

    @Test
    fun `empty or missing default branch is malformed instead of falling back to main`() = runTest {
        for (repository in listOf(
            repositoryJson().replace("\"default_branch\":\"main\"", "\"default_branch\":\"\""),
            repositoryJson().replace(",\"default_branch\":\"main\"", ""),
        )) {
            emptyRepositoryServer(repositoryJson = repository).use { server ->
                val result = client(server).discover()

                assertEquals(SyncSpaceDiscovery.Failed(SyncDiscoveryProblem.MALFORMED), result)
            }
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
        OkHttpClient(),
        { "synthetic-token" },
        server.url("/").toString(),
    )

    private fun enqueueIdentity(
        server: MockWebServer,
        permissions: String = """{"administration":"write","contents":"write","metadata":"read"}""",
        suspendedAt: String = "null",
        includeSuspendedAt: Boolean = true,
    ) {
        server.enqueue(MockResponse(body = ACCOUNT))
        server.enqueue(
            MockResponse(
                body = """{"installations":[{"id":7,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},${if (includeSuspendedAt) "\"suspended_at\":$suspendedAt," else ""}"repository_selection":"selected","permissions":$permissions}]}""",
            ),
        )
    }

    companion object {
        private const val ACCOUNT = """{"id":42,"login":"synthetic-user","type":"User"}"""
        private const val INSTALLATIONS = """{"installations":[{"id":7,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},"suspended_at":null,"repository_selection":"selected","permissions":{"administration":"write","contents":"write","metadata":"read"}}]}"""
    }

    private fun attempt() = SyncCreationAttempt(SyncGitHubAccount(42, "synthetic-user"), "attempt-fixture-0001")

    private fun enqueueSpace(server: MockWebServer, descriptor: ByteArray) {
        server.enqueue(MockResponse(body = """{"object":{"sha":"${"a".repeat(40)}"}}"""))
        server.enqueue(MockResponse(body = """{"tree":{"sha":"${"b".repeat(40)}"}}"""))
        server.enqueue(
            MockResponse(
                body = """{"truncated":false,"tree":[{"path":".mihon-sync/space.json","type":"blob","mode":"100644","sha":"${"c".repeat(
                    40,
                )}"}]}""",
            ),
        )
        server.enqueue(
            MockResponse(body = """{"encoding":"base64","content":"${descriptor.toByteString().base64()}"}"""),
        )
    }

    private fun emptyRepositoryServer(
        repositoryJson: String = repositoryJson(),
        matchingRefsCode: Int = 409,
        matchingRefsBody: String = """{"message":"Git Repository is empty."}""",
        allRefsCode: Int = matchingRefsCode,
        allRefsBody: String = matchingRefsBody,
        defaultRefCode: Int = 409,
        defaultRefBody: String = """{"message":"Git Repository is empty."}""",
        contentsCode: Int = 404,
        contentsBody: String = """{"message":"This repository is empty."}""",
    ): MockWebServer = MockWebServer().apply {
        start()
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/user" -> MockResponse(body = ACCOUNT)
                "/user/installations" -> MockResponse(
                    body = """{"installations":[{"id":7,"app_slug":"mihon-desktop","account":{"id":42,"type":"User"},"suspended_at":null,"repository_selection":"selected","permissions":{"contents":"write","metadata":"read"}}]}""",
                )
                "/user/installations/7/repositories" -> MockResponse(
                    body = """{"repositories":[$repositoryJson],"total_count":1}""",
                )
                "/repos/synthetic-user/mihon-sync" -> MockResponse(body = repositoryJson)
                "/repos/synthetic-user/mihon-sync/git/ref/heads/mihon-sync-v1" -> MockResponse(code = 404, body = "{}")
                "/repos/synthetic-user/mihon-sync/git/ref/heads/main" -> MockResponse(
                    code = defaultRefCode,
                    body = defaultRefBody,
                )
                "/repos/synthetic-user/mihon-sync/git/matching-refs/" -> MockResponse(
                    code = allRefsCode,
                    body = allRefsBody,
                )
                "/repos/synthetic-user/mihon-sync/git/matching-refs/heads/",
                "/repos/synthetic-user/mihon-sync/git/matching-refs/tags/",
                -> MockResponse(
                    code = matchingRefsCode,
                    body = matchingRefsBody,
                )
                "/repos/synthetic-user/mihon-sync/branches",
                "/repos/synthetic-user/mihon-sync/tags",
                -> MockResponse(body = "[]")
                "/repos/synthetic-user/mihon-sync/contents/" -> MockResponse(
                    code = contentsCode,
                    body = contentsBody,
                )
                else -> MockResponse(code = 500, body = "unexpected request: ${request.method} ${request.url}")
            }
        }
    }

    private fun repositoryJson(description: String = "Mihon sync setup:attempt-fixture-0001") =
        """{"id":99,"name":"mihon-sync","full_name":"synthetic-user/mihon-sync","owner":{"id":42,"login":"synthetic-user","type":"User"},"private":true,"permissions":{"push":true},"description":"$description","size":0,"default_branch":"main","archived":false,"disabled":false}"""
}
