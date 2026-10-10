package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubSyncRepositoryManager
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncRepositoryCreationIntent
import mihon.data.sync.auth.SyncRepositoryRepairTarget
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncRepositoryManagementContractTest {
    @Test
    fun `browser verified record never gains native permission for automatic scope repair on resume`() = runTest {
        server(existing = repo()).use { server ->
            val original = server.dispatcher
            var invisible = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (invisible && request.url.encodedPath == "/repos/${repository.fullName}") {
                        return MockResponse(code = 404, body = "{}")
                    }
                    return original.dispatch(request)
                }
            }
            var saved = intent()
            assertEquals(
                SyncSpaceCreation.Ready(repository, 99, "main"),
                manager(server).verifyManualSelection(saved) { saved = it },
            )
            assertTrue(saved.submitted)
            assertTrue(saved.manuallyConfirmed)
            assertFalse(saved.creationAdminConfirmed)
            invisible = true
            assertEquals(
                SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED),
                manager(server).createOrResume(saved) { saved = it },
            )
            assertTrue(List(server.requestCount) { server.takeRequest().method }.all { it == "GET" })
        }
    }

    @Test
    fun `browser verification cannot repair access or create after the repository disappears`() = runTest {
        server(existing = repo()).use { server ->
            val original = server.dispatcher
            var namedReads = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath == "/repos/${repository.fullName}" && ++namedReads > 1) {
                        return MockResponse(code = 404, body = "{}")
                    }
                    return original.dispatch(request)
                }
            }
            var saved = intent().copy(submitted = true, repositoryId = 99, manuallyConfirmed = true)
            assertEquals(
                SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED),
                manager(server).verifyManualSelection(saved) { saved = it },
            )
            assertFalse(saved.creationAdminConfirmed)
            assertTrue(List(server.requestCount) { server.takeRequest().method }.all { it == "GET" })
        }
    }

    @Test
    fun `creation-only automatic repository access is read back without administration or scope PUT`() = runTest {
        server(installationPermissions = "\"contents\":\"write\",\"repository_creation\":\"write\"").use { server ->
            var recorded = intent()
            assertEquals(
                SyncSpaceCreation.Ready(repository, 99, "main"),
                manager(server).createOrResume(recorded) { recorded = it },
            )
            val requests = List(server.requestCount) { server.takeRequest() }
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals(0, requests.count { it.method == "PUT" })
            assertTrue(requests.any { it.url.encodedPath == "/user/installations/7/repositories" })
            assertTrue(requests.any { it.url.encodedPath == "/repositories/99" })
        }
    }

    @Test
    fun `confirmed creation id survives unknown permissions and waits for official scope selection`() = runTest {
        val withoutPermissions = repo().replace("\"permissions\":{\"push\":true,\"admin\":true},", "")
        server(postBody = withoutPermissions, visibleAfterPost = false).use { server ->
            var saved = intent()
            val failed = manager(server).createOrResume(saved) { saved = it } as SyncSpaceCreation.Failed
            assertEquals(99L, saved.repositoryId)
            assertEquals(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS, failed.problem)
            manager(server).createOrResume(saved) { saved = it }
            assertEquals(1, List(server.requestCount) { server.takeRequest().method }.count { it == "POST" })
        }
    }

    @Test
    fun `failed id checkpoint after accepted creation resumes submit marker without another post`() = runTest {
        server().use { server ->
            var saved = intent()
            val failed = manager(server).createOrResume(saved) {
                if (it.repositoryId != null) error("checkpoint unavailable")
                saved = it
            }
            assertEquals(SyncSpaceCreation.Failed(SyncDiscoveryProblem.STORAGE_ERROR), failed)
            assertTrue(saved.submitted)
            assertEquals(null, saved.repositoryId)
            assertEquals(
                SyncSpaceCreation.Ready(
                    repository,
                    99,
                    "main",
                ),
                manager(
                    server,
                ).createOrResume(
                    saved,
                )
                    {
                        saved = it
                    },
            )
            assertEquals(1, List(server.requestCount) { server.takeRequest().method }.count { it == "POST" })
        }
    }

    @Test
    fun `fixed repository forbidden requests scope repair without another user login`() = runTest {
        server(existing = repo(), metadataCode = 403).use { server ->
            assertEquals(
                SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                manager(
                    server,
                ).repairProperties(
                    target,
                    true,
                    true,
                ),
            )
            assertEquals(0, List(server.requestCount) { server.takeRequest().method }.count { it != "GET" })
        }
    }

    @Test
    fun `install scope addition is confirmed by listing and fixed id rather than trusting put`() = runTest {
        MockWebServer().use { server ->
            server.start()
            var authorized = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/user" ->
                        MockResponse(
                            body = """{"id":42,"login":"synthetic-user","type":"User"}""",
                        )
                    request.url.encodedPath == "/user/installations" ->
                        MockResponse(
                            body =
                            """{"installations":[{"id":7,"app_slug":"mihon-desktop",""" +
                                """"account":{"id":42,"type":"User"},"suspended_at":null,""" +
                                """"permissions":{"contents":"write"}}]}""",
                        )
                    request.url.encodedPath == "/repositories/99" -> MockResponse(body = repo())
                    request.method == "PUT" -> {
                        authorized = true
                        MockResponse(code = 204)
                    }
                    request.url.encodedPath == "/user/installations/7/repositories" ->
                        MockResponse(
                            body = """{"repositories":[${if (authorized) repo() else ""}]}""",
                        )
                    else -> MockResponse(code = 500)
                }
            }
            assertEquals(null, manager(server).authorizeRepository(target))
            val requests = List(server.requestCount) { server.takeRequest() }
            assertEquals(1, requests.count { it.method == "PUT" })
            assertEquals(2, requests.count { it.url.encodedPath == "/user/installations/7/repositories" })
            assertEquals(
                "/user/installations/7/repositories/99",
                requests.single {
                    it.method == "PUT"
                }.url.encodedPath,
            )
        }
    }

    @Test
    fun `install scope rejection reports exact permission without treating forbidden as rate limit`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath == "/user" ->
                        MockResponse(
                            body = """{"id":42,"login":"synthetic-user","type":"User"}""",
                        )
                    request.url.encodedPath == "/user/installations" ->
                        MockResponse(
                            body =
                            """{"installations":[{"id":7,"app_slug":"mihon-desktop",""" +
                                """"account":{"id":42,"type":"User"},"suspended_at":null,""" +
                                """"permissions":{"contents":"write"}}]}""",
                        )
                    request.url.encodedPath == "/repositories/99" -> MockResponse(body = repo())
                    request.method == "PUT" -> MockResponse(code = 403, body = "{}")
                    request.url.encodedPath == "/user/installations/7/repositories" ->
                        MockResponse(
                            body = """{"repositories":[]}""",
                        )
                    else -> MockResponse(code = 500)
                }
            }
            assertEquals("NEEDS_INSTALLATION_ACCESS_PERMISSION", manager(server).authorizeRepository(target)?.name)
        }
    }

    @Test
    fun `new selected-scope repository grants access before metadata readback`() =
        runTest {
            for (denyFirstGrant in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.start()
                    var created = false
                    var authorized = false
                    var posts = 0
                    var grantDenied = denyFirstGrant
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse = when {
                            request.url.encodedPath == "/user" ->
                                MockResponse(
                                    body = """{"id":42,"login":"synthetic-user","type":"User"}""",
                                )
                            request.url.encodedPath == "/user/installations" ->
                                MockResponse(
                                    body =
                                    """{"installations":[{"id":7,"app_slug":"mihon-desktop",""" +
                                        """"account":{"id":42,"type":"User"},"suspended_at":null,""" +
                                        """"permissions":{"contents":"write"}}]}""",
                                )
                            request.url.encodedPath == "/user/repos" &&
                                request.method == "POST" -> {
                                posts++
                                created = true
                                MockResponse(
                                    code = 201,
                                    body = repo(),
                                )
                            }
                            request.method == "PUT" -> {
                                assertTrue(
                                    created,
                                )
                                if (
                                    grantDenied
                                ) {
                                    MockResponse(
                                        code = 403,
                                        body = "{}",
                                    )
                                } else {
                                    authorized = true
                                    MockResponse(
                                        code = 204,
                                    )
                                }
                            }
                            request.url.encodedPath == "/user/installations/7/repositories" ->
                                MockResponse(
                                    body = """{"repositories":[${if (authorized) repo() else ""}]}""",
                                )
                            request.url.encodedPath.endsWith("/git/matching-refs/") && authorized -> {
                                MockResponse(body = "[]")
                            }
                            request.url.encodedPath.endsWith("/git/ref/heads/main") && authorized -> {
                                MockResponse(code = 404)
                            }
                            request.url.encodedPath.endsWith(
                                "/contents/",
                            ) &&
                                authorized ->
                                MockResponse(
                                    code = 404,
                                    body = """{"message":"This repository is empty."}""",
                                )
                            authorized -> MockResponse(body = repo())
                            else -> MockResponse(code = 404)
                        }
                    }
                    var saved = intent()
                    if (denyFirstGrant) {
                        val result = manager(server).createOrResume(saved) { saved = it }
                        val failed = result as SyncSpaceCreation.Failed
                        assertEquals("NEEDS_INSTALLATION_ACCESS_PERMISSION", failed.problem.name)
                        assertEquals(99L, saved.repositoryId)
                        assertTrue(saved.submitted)
                        grantDenied = false
                    }
                    assertEquals(
                        SyncSpaceCreation.Ready(
                            repository,
                            99,
                            "main",
                        ),
                        manager(
                            server,
                        ).createOrResume(
                            saved,
                        )
                            {
                                saved = it
                            },
                    )
                    assertEquals(1, posts)
                    assertEquals(99L, saved.repositoryId)
                    assertTrue(authorized)
                }
            }
        }

    @Test
    fun `property mutation permission rate and server failures keep their distinct continuation`() = runTest {
        for ((code, body, expected) in listOf(
            Triple(403, "{}", "NEEDS_ADMINISTRATION_PERMISSION"),
            Triple(403, """{"message":"secondary rate limit"}""", "RATE_LIMITED"),
            Triple(429, "{}", "RATE_LIMITED"),
            Triple(500, "{}", "RETRYABLE"),
        )) {
            server(
                existing = repo().replace("\"private\":true", "\"private\":false"),
                patchCode = code,
                patchBody = body,
            ).use { server ->
                assertEquals(expected, manager(server).repairProperties(target, true, false)?.name)
            }
        }
    }

    @Test
    fun `accepted property patch does not pass when fixed id readback still shows public repository`() = runTest {
        server(
            existing = repo().replace("\"private\":true", "\"private\":false"),
            applyPatch = false,
        ).use { server ->
            assertEquals(
                SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE,
                manager(
                    server,
                ).repairProperties(
                    target,
                    true,
                    false,
                ),
            )
        }
    }

    @Test
    fun `failed durable submit marker prevents creation`() = runTest {
        server().use { server ->
            val result = manager(server).createOrResume(intent()) { error("store unavailable") }
            assertTrue(result is SyncSpaceCreation.Failed)
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `malformed success or changed id is not creation confirmation`() = runTest {
        for (body in listOf("not-json", "{}", repo().replace("\"id\":99", "\"id\":100"))) {
            server(postBody = body).use { server ->
                val result = manager(server).createOrResume(intent()) {}
                assertTrue(result is SyncSpaceCreation.Failed)
            }
        }
    }

    @Test
    fun `confirmed explicit name is durably submitted before private empty creation and fixed id readback`() = runTest {
        server().use { server ->
            var saved = intent()
            val manager = manager(server)
            val result = manager.createOrResume(saved) { saved = it }
            assertEquals(SyncSpaceCreation.Ready(repository, 99, "main"), result)
            assertTrue(saved.submitted)
            assertEquals(99L, saved.repositoryId)
            val requests = List(server.requestCount) { server.takeRequest() }
            val post = requests.single { it.method == "POST" }
            assertEquals("/user/repos", post.url.encodedPath)
            val body = post.body!!.utf8()
            assertTrue(body.contains("\"private\":true"))
            assertTrue(body.contains("\"auto_init\":false"))
            assertTrue(body.contains("mihon-sync-recovered"))
            assertTrue(body.contains("attempt-fixture-0001"))
            assertTrue(requests.any { it.method == "GET" && it.url.encodedPath == "/repositories/99" })
        }
    }

    @Test
    fun `lost create result is read back by recorded marker and never posted twice`() = runTest {
        server(postCode = 500).use { server ->
            var saved = intent()
            assertEquals(
                SyncSpaceCreation.Ready(
                    repository,
                    99,
                    "main",
                ),
                manager(
                    server,
                ).createOrResume(
                    saved,
                )
                    {
                        saved = it
                    },
            )
            assertEquals(
                SyncSpaceCreation.Ready(
                    repository,
                    99,
                    "main",
                ),
                manager(
                    server,
                ).createOrResume(
                    saved,
                )
                    {
                        saved = it
                    },
            )
            val methods = List(server.requestCount) { server.takeRequest().method }
            assertEquals(1, methods.count { it == "POST" })
        }
    }

    @Test
    fun `unrelated or nonempty occupied names are never adopted or overwritten`() = runTest {
        for (body in listOf(repo().replace(MARKER, "someone else"), repo().replace("\"size\":0", "\"size\":1"))) {
            server(existing = body).use { server ->
                val result = manager(server).createOrResume(intent()) {}
                assertTrue(result is SyncSpaceCreation.Failed)
                repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
            }
        }
    }

    @Test
    fun `creation permissions and real rate limits remain different and allow explicit permission retry`() = runTest {
        for ((code, body, expected) in listOf(
            Triple(403, "{}", "NEEDS_CREATION_PERMISSION"),
            Triple(403, """{"message":"secondary rate limit"}""", "RATE_LIMITED"),
            Triple(429, "{}", "RATE_LIMITED"),
            Triple(401, "{}", "AUTHORIZATION_REQUIRED"),
        )) {
            server(postCode = code, postBody = body, visibleAfterPost = false).use { server ->
                var saved = intent()
                val result = manager(server).createOrResume(saved) { saved = it } as SyncSpaceCreation.Failed
                assertEquals(expected, result.problem.name)
                if (expected == "NEEDS_CREATION_PERMISSION") assertFalse(saved.submitted)
            }
        }
    }

    @Test
    fun `submitted unknown creation with absent repository cannot issue another post`() = runTest {
        server(visibleAfterPost = false).use { server ->
            val result = manager(server).createOrResume(intent().copy(submitted = true)) {}
            assertEquals(SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED), result)
            repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
        }
    }

    @Test
    fun `property repair checks account and fixed repository before patch and verifies readback`() = runTest {
        server(
            existing = repo().replace(
                "\"private\":true",
                "\"private\":false",
            ).replace(
                "\"archived\":false",
                "\"archived\":true",
            ),
        ).use { server ->
            assertEquals(null, manager(server).repairProperties(target, makePrivate = true, unarchive = true))
            assertEquals(1, List(server.requestCount) { server.takeRequest().method }.count { it == "PATCH" })
        }
    }

    @Test
    fun `wrong account or changed repository id blocks every mutation`() = runTest {
        for (body in listOf(repo().replace("\"id\":99", "\"id\":100"), repo().replace("\"id\":42", "\"id\":43"))) {
            server(existing = body).use { server ->
                assertEquals(SyncDiscoveryProblem.ACCOUNT_CHANGED, manager(server).repairProperties(target, true, true))
                repeat(server.requestCount) { assertEquals("GET", server.takeRequest().method) }
            }
        }
    }

    private fun manager(
        server: MockWebServer,
    ) = GitHubSyncRepositoryManager(
        OkHttpClient(),
        {
            "fixture-token"
        },
        server.url(
            "/",
        ).toString(),
    )
    private fun intent() = SyncRepositoryCreationIntent(account, repository.name, "attempt-fixture-0001")
    private fun server(
        existing: String? = null,
        postCode: Int = 201,
        postBody: String = repo(),
        visibleAfterPost: Boolean = true,
        patchCode: Int = 200,
        patchBody: String = repo(),
        applyPatch: Boolean = true,
        metadataCode: Int = 200,
        installationPermissions: String = "\"contents\":\"write\"",
    ) = MockWebServer().apply {
        start()
        var posted = false
        var patched = false
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/user" ->
                    MockResponse(
                        body = """{"id":42,"login":"synthetic-user","type":"User"}""",
                    )
                request.url.encodedPath == "/repositories/99" &&
                    metadataCode != 200 ->
                    MockResponse(
                        code = metadataCode,
                        body = "{}",
                    )
                request.method == "POST" -> {
                    posted = true
                    MockResponse(code = postCode, body = postBody)
                }
                request.method == "PATCH" -> {
                    patched = applyPatch
                    MockResponse(code = patchCode, body = patchBody)
                }
                request.url.encodedPath == "/user/installations" ->
                    MockResponse(
                        body =
                        """{"installations":[{"id":7,"app_slug":"mihon-desktop",""" +
                            """"account":{"id":42,"type":"User"},"suspended_at":null,""" +
                            """"permissions":{$installationPermissions}}]}""",
                    )
                request.url.encodedPath == "/user/installations/7/repositories" ->
                    MockResponse(
                        body = """{"repositories":[${repo()}]}""",
                    )
                request.url.encodedPath.endsWith("/git/matching-refs/") -> MockResponse(body = "[]")
                request.url.encodedPath.endsWith("/git/ref/heads/main") -> MockResponse(code = 404, body = "{}")
                request.url.encodedPath.endsWith(
                    "/contents/",
                )
                ->
                    MockResponse(
                        code = 404,
                        body = """{"message":"This repository is empty."}""",
                    )
                patched -> MockResponse(body = repo())
                existing != null -> MockResponse(body = existing)
                posted && visibleAfterPost -> MockResponse(body = repo())
                else -> MockResponse(code = 404, body = "{}")
            }
        }
    }

    companion object {
        private val account = SyncGitHubAccount(42, "synthetic-user")
        private val repository = SyncRepository(account.login, "mihon-sync-recovered", "mihon-sync-v1")
        private val target = SyncRepositoryRepairTarget(account, repository, 99)
        private const val MARKER = "Mihon sync setup:attempt-fixture-0001"
        private fun repo() =
            """{"id":99,"name":"mihon-sync-recovered",""" +
                """"full_name":"synthetic-user/mihon-sync-recovered",""" +
                """"owner":{"id":42,"login":"synthetic-user","type":"User"},""" +
                """"private":true,"permissions":{"push":true,"admin":true},""" +
                """"description":"$MARKER","size":0,"default_branch":"main",""" +
                """"archived":false,"disabled":false}"""
    }
}
