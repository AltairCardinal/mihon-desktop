@file:Suppress("ktlint:standard:max-line-length")

package mihon.data.sync

import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncRepositoryScope
import mihon.data.sync.auth.SyncSpaceDiscovery
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncAcceptanceRepositoryContractTest {
    private val name = "mihon-sync-acceptance-20260930-none-fixture"

    @Test
    fun `acceptance rejects arbitrary or ordinary repository names`() {
        for (invalid in listOf(
            "mihon-sync",
            "other",
            "mihon-sync-acceptance-",
            "mihon-sync-acceptance-a/b",
            "MIHON-sync-acceptance-test",
        )) {
            assertThrows(IllegalArgumentException::class.java) { SyncRepositoryScope.acceptance(invalid) }
        }
        val scope = SyncRepositoryScope.acceptance(name)
        assertTrue(scope.accepts(name))
        assertFalse(scope.accepts("mihon-sync"))
        assertFalse(scope.accepts("$name-other"))
        assertEquals("mihon-sync", SyncRepositoryScope.Default.repositoryName)
        assertTrue(SyncRepositoryScope.Default.accepts("renamed-space"))
    }

    @Test
    fun `isolated discovery reads only exact target despite visible ordinary and malformed unrelated repositories`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val paths = mutableListOf<String>()
            val target = repository(name)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    paths += path
                    return when {
                        path == "/user" -> MockResponse(body = """{"id":1,"login":"fixture","type":"User"}""")
                        path == "/user/installations" -> MockResponse(
                            body = """{"installations":[{"id":2,"app_slug":"mihon-desktop","account":{"id":1,"type":"User"},"repository_selection":"all","suspended_at":null,"permissions":{"contents":"write"}}],"total_count":1}""",
                        )
                        path.endsWith(
                            "/repositories",
                        ) -> MockResponse(
                            body = """{"repositories":[$target,${repository(
                                "mihon-sync",
                            )},{"name":"unrelated","owner":{"id":1,"type":"User"}}],"total_count":3}""",
                        )
                        path == "/repos/fixture/$name" -> MockResponse(body = target)
                        path == "/repos/fixture/$name/git/matching-refs/" -> MockResponse(body = "[]")
                        path == "/repos/fixture/$name/contents/" -> MockResponse(
                            code = 404,
                            body = """{"message":"This repository is empty."}""",
                        )
                        path.startsWith("/repos/fixture/$name/git/ref/") -> MockResponse(code = 404, body = "{}")
                        else -> MockResponse(code = 500)
                    }
                }
            }
            val discovery = GitHubSyncSpaceClient(
                OkHttpClient(),
                { "synthetic" },
                server.url("/").toString().trimEnd('/'),
                repositoryScope = SyncRepositoryScope.acceptance(name),
            ).discover()
            assertTrue(discovery is SyncSpaceDiscovery.EmptyRepository, discovery.toString())
            assertEquals(name, (discovery as SyncSpaceDiscovery.EmptyRepository).candidate.repository.name)
            assertTrue(
                paths.filter { it.startsWith("/repos/") }.all {
                    it.startsWith("/repos/fixture/$name/") ||
                        it == "/repos/fixture/$name"
                },
            )
            assertTrue(paths.any { it == "/repos/fixture/$name" })
        }
    }

    private fun repository(repo: String) = """{"id":99,"name":"$repo","full_name":"fixture/$repo","owner":{"id":1,"login":"fixture","type":"User"},"private":true,"archived":false,"disabled":false,"size":0,"default_branch":"main","permissions":{"push":true}}"""
}
