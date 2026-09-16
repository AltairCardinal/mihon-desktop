package mihon.data.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mihon.data.sync.auth.GitHubAuthClient
import mihon.data.sync.auth.GitHubPrivateRepositorySelector
import mihon.data.sync.auth.GitHubTokenRefresher
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.crypto.SyncRecoveryFactory
import mihon.data.sync.http.SyncHttpClient
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.SyncBatchSyncService
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthPort
import mihon.domain.sync.auth.GitHubCredentialStore
import mihon.domain.sync.auth.GitHubDeviceAuthResult
import mihon.domain.sync.auth.GitHubStoredCredential
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncCryptoBinding
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.transport.SyncBatchIndexEntry
import mihon.domain.sync.transport.SyncGitTree
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncPublishResult
import mihon.domain.sync.transport.SyncPublishStatus
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncSnapshot
import mihon.domain.sync.transport.SyncTransportPort
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncS2ContractTest {
    @Test
    fun `recovery material has a stable vector and remains bound to its space`() {
        val bundle = SyncRecoveryCodec.generate(
            spaceId = "space",
            generation = 7,
            keyId = "key-id-123456789",
            randomBytes = { ByteArray(32) { it.toByte() } },
            createdAtMillis = 123,
        )
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", bundle.data.rawKeyset)
        val encoded = SyncRecoveryCodec.encode(bundle.data)
        val decoded = SyncRecoveryCodec.decode(encoded).getOrThrow()
        assertEquals(bundle.data, decoded)
        assertEquals(bundle.secret, SyncRecoveryCodec.importSecret(decoded, "space", 7).getOrThrow())
        assertTrue(SyncRecoveryCodec.importSecret(decoded, "other-space", 7).isFailure)
        assertTrue(SyncRecoveryCodec.importSecret(decoded, "space", 8).isFailure)
        val generated = SyncRecoveryFactory.generate("space", 7, "key-id-123456789", 123)
        assertEquals(32, generated.secret.bytes.size)
    }

    @Test
    fun `device flow displays code then honors pending and slow down without secret`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse(
                    body = """
                {"device_code":"device-secret","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","expires_in":600,"interval":5}
                    """.trimIndent(),
                ),
            )
            server.enqueue(MockResponse(body = """{"error":"authorization_pending"}"""))
            server.enqueue(MockResponse(body = """{"error":"slow_down"}"""))
            val tokenResponse = """
                {"access_token":"access-secret","refresh_token":"refresh-secret","token_type":"bearer","expires_in":3600,"refresh_token_expires_in":86400}
            """.trimIndent()
            server.enqueue(
                MockResponse(
                    body = tokenResponse,
                ),
            )
            server.start()
            val waits = mutableListOf<Long>()
            val auth = GitHubAuthClient(
                OkHttpClient(),
                GitHubAuthEndpoints(
                    server.url("/login/device/code").toString(),
                    server.url("/login/oauth/access_token").toString(),
                    server.url("/").toString(),
                ),
                { millis -> waits += millis },
            )
            var shown = false
            val result = auth.authorize("public-client") { code ->
                shown = true
                assertEquals("ABCD-EFGH", code.userCode)
                assertTrue(code.toString().contains("redacted"))
                assertFalse(code.toString().contains("device-secret"))
            }
            assertTrue(shown)
            assertTrue(result is mihon.domain.sync.auth.GitHubDeviceAuthResult.Authorized)
            val token = (result as mihon.domain.sync.auth.GitHubDeviceAuthResult.Authorized).token
            assertFalse(token.toString().contains("access-secret"))
            assertFalse(token.toString().contains("refresh-secret"))
            assertEquals(listOf(5_000L, 5_000L, 10_000L), waits)
            val deviceRequest = server.takeRequest()
            val deviceBody = deviceRequest.body!!.utf8()
            assertTrue(deviceBody.contains("client_id=public-client"))
            assertFalse(deviceBody.contains("scope="))
            val tokenRequest = server.takeRequest()
            val tokenBody = tokenRequest.body!!.utf8()
            assertTrue(tokenBody.contains("grant_type"))
            assertFalse(tokenBody.contains("client_secret"))
        }
    }

    @Test
    fun `AEAD binds batch context and retains one frozen ciphertext for retry`() {
        val engine = SyncAeadEngineFactory.create()
        val secret = SyncSecret.fromBytes(ByteArray(32) { it.toByte() })
        val batch = validBatch()
        val path = ".mihon-sync/batches/actor/1/batch.json"
        val encrypted = SyncBatchEncryption.encrypt(engine, secret, batch, path)
        assertEquals(batch, SyncBatchEncryption.decrypt(engine, secret, encrypted))
        assertEquals(encrypted, encrypted.copy(ciphertext = SyncAeadCiphertext(encrypted.ciphertext.bytes.copyOf())))
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.decrypt(engine, SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() }), encrypted)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.decrypt(engine, secret, encrypted.copy(path = ".mihon-sync/batches/actor/1/other.json"))
        }
        val tampered = encrypted.ciphertext.bytes.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.decrypt(engine, secret, encrypted.copy(ciphertext = SyncAeadCiphertext(tampered)))
        }
    }

    @Test
    fun `sync http wrapper uses production client while isolating cookies and redirects`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse(code = 302, body = "redirect", headers = headersOf("Location", "https://evil.example/")),
            )
            server.start()
            val wrapper = SyncHttpClient(OkHttpClient(), setOf("localhost"))
            val request = wrapper.request(server.url("/sync").toString(), "GET", mapOf("Cookie" to "source-secret"))
            val response = wrapper.execute(request)
            assertEquals(302, response.code)
            assertNotNull(server.takeRequest())
            assertFalse(response.body.decodeToString().contains("source-secret"))
        }
    }

    @Test
    fun `concurrent refresh is single flight and storage failure keeps old credential`() = runTest {
        var calls = 0
        val old = GitHubStoredCredential(
            GitHubAccessToken("old", "refresh", "bearer", emptySet(), 0, 100_000),
            1,
        )
        val store = mihon.data.sync.auth.InMemoryGitHubCredentialStore(old)
        val auth = object : GitHubAuthPort {
            override suspend fun authorize(
                clientId: String,
                onDeviceCode: suspend (mihon.domain.sync.auth.GitHubDeviceCode) -> Unit,
            ): GitHubDeviceAuthResult = error("unused")

            override suspend fun refresh(
                clientId: String,
                current: GitHubStoredCredential,
            ): Result<GitHubStoredCredential> {
                calls++
                return Result.success(
                    GitHubStoredCredential(
                        current.credential.copy(accessToken = "new", accessTokenExpiresAtMillis = 200_000),
                        current.revision + 1,
                    ),
                )
            }
        }
        val refresher = GitHubTokenRefresher(auth, store) { 1_000 }
        (1..8).map { async { refresher.refreshIfNeeded("client") } }.awaitAll()
        assertEquals(1, calls)
        assertEquals("new", requireNotNull(store.read()).credential.accessToken)

        val failingStore = object : GitHubCredentialStore {
            private var current: GitHubStoredCredential? = old
            override suspend fun read(): GitHubStoredCredential? = current
            override suspend fun replace(expectedRevision: Long?, value: GitHubAccessToken): GitHubStoredCredential {
                error("secure storage unavailable")
            }
        }
        val failure = GitHubTokenRefresher(auth, failingStore) { 1_000 }.refreshIfNeeded("client")
        assertTrue(failure.isFailure)
        assertEquals("old", requireNotNull(failingStore.read()).credential.accessToken)
    }

    @Test
    fun `private repository selection follows bounded pages and deduplicates installations`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    headers = headersOf(
                        "Link",
                        "<${server.url("/user/installations?per_page=100&page=2")}>; rel=\"next\"",
                    ),
                    body = """{"installations":[{"id":1}]}""",
                ),
            )
            server.enqueue(MockResponse(body = """{"installations":[{"id":1},{"id":2}]}"""))
            server.enqueue(
                MockResponse(
                    body = """{"repositories":[
                    {"full_name":"owner/private-a","private":true,"permissions":{"push":true}},
                    {"full_name":"owner/public","private":false,"permissions":{"push":true}}
                ]}""",
                ),
            )
            server.enqueue(
                MockResponse(
                    body = """{"repositories":[
                    {"full_name":"owner/private-a","private":true,"permissions":{"push":true}},
                    {"full_name":"owner/private-c","private":true,"permissions":{"admin":true}}
                ]}""",
                ),
            )
            val selector = GitHubPrivateRepositorySelector(
                OkHttpClient(),
                { "access-secret" },
                server.url("/").toString().trimEnd('/'),
            )
            val selected = selector.select("main")
            assertEquals(listOf("owner/private-a", "owner/private-c"), selected.map { it.repository.fullName })
            assertEquals(1L, selected.first().installationId)
            assertFalse(selected.any { it.repository.fullName.contains("public") })
        }
    }

    private fun validBatch(): SyncBatch {
        val event = SyncEventEnvelope(
            protocolVersion = 1,
            spaceId = "space",
            generation = 1,
            actorId = "actor",
            epoch = 1,
            seq = 1,
            category = SyncCategory.FAVORITE,
            effects = listOf(
                SyncEffect(
                    effectId = "favorite",
                    objectKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = "1", originalUrl = "/manga"),
                    field = SyncField.FAVORITE,
                    kind = SyncEffectKind.ADD,
                ),
            ),
            origin = SyncOrigin.USER,
            batchId = "batch",
        )
        return SyncBatch(1, "space", 1, "batch", listOf(event))
    }
}
