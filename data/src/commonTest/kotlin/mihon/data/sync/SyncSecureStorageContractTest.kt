package mihon.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mihon.data.sync.auth.PersistentGitHubCredentialStore
import mihon.data.sync.security.EncryptedFileSyncSecureStore
import mihon.data.sync.security.SyncRecordCipher
import mihon.data.sync.security.SyncSecureFileCommit
import mihon.domain.sync.auth.GitHubAccessToken
import mihon.domain.sync.security.SyncSecureStoreException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Timeout(30)
class SyncSecureStorageContractTest {
    @Test
    fun `file records reopen with exact limits and no plaintext`() = runBlocking {
        withStore { directory, cipher ->
            val first = EncryptedFileSyncSecureStore(directory, cipher)
            val value = "密".repeat(21845) + "x"
            assertEquals(65536, value.toByteArray().size)
            assertTrue(first.compareAndSet("space-key", null, value))
            assertEquals(value, EncryptedFileSyncSecureStore(directory, cipher).read("space-key"))
            assertTrue(directory.listFiles()!!.any { it.length() > 65536 })
            assertTrue(directory.listFiles()!!.none { it.readBytes().toString(Charsets.UTF_8).contains("密密密") })
            val oversized = runCatching { first.compareAndSet("space-key", value, value + "x") }
            assertTrue(oversized.exceptionOrNull() is SyncSecureStoreException)
            assertEquals(value, first.read("space-key"))
            assertFalse(first.compareAndSet("space-key", "stale", null))
            assertTrue(first.compareAndSet("space-key", value, null))
            assertNull(first.read("space-key"))
        }
    }

    @Test
    fun `independent instances serialize a concurrent compare and set`() = runBlocking {
        withStore { directory, cipher ->
            val results = (1..16).map { index ->
                async(Dispatchers.IO) {
                    EncryptedFileSyncSecureStore(directory, cipher).compareAndSet("auth", null, "winner-$index")
                }
            }.awaitAll()
            assertEquals(1, results.count { it })
            assertTrue(EncryptedFileSyncSecureStore(directory, cipher).read("auth")!!.startsWith("winner-"))
        }
    }

    @Test
    fun `failed atomic commit retains prior record and exposes no payload`() = runBlocking {
        withStore { directory, cipher ->
            val normal = EncryptedFileSyncSecureStore(directory, cipher)
            assertTrue(normal.compareAndSet("auth", null, "old-secret"))
            val broken = EncryptedFileSyncSecureStore(
                directory,
                cipher,
                SyncSecureFileCommit { _, _ -> error("new-secret storage failure") },
            )
            val result = runCatching { broken.compareAndSet("auth", "old-secret", "new-secret") }
            assertTrue(result.exceptionOrNull() is SyncSecureStoreException)
            assertFalse(result.exceptionOrNull().toString().contains("new-secret"))
            assertNull(result.exceptionOrNull()?.cause)
            assertEquals("old-secret", normal.read("auth"))
        }
    }

    @Test
    fun `damaged or moved records fail closed and unsafe purpose keys are rejected`() = runBlocking {
        withStore { directory, cipher ->
            val store = EncryptedFileSyncSecureStore(directory, cipher)
            assertTrue(store.compareAndSet("auth", null, "secret"))
            val auth = directory.listFiles()!!.single { it.extension == "record" }
            val original = auth.readBytes()
            assertTrue(store.compareAndSet("space", null, "other"))
            val space = directory.listFiles()!!.single { it.extension == "record" && it != auth }
            space.writeBytes(original)
            assertTrue(runCatching { store.read("space") }.exceptionOrNull() is SyncSecureStoreException)
            auth.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertTrue(runCatching { store.read("auth") }.exceptionOrNull() is SyncSecureStoreException)
            assertTrue(runCatching { store.compareAndSet("auth", null, "overwrite") }.isFailure)
            assertTrue(runCatching { store.read("../escape") }.exceptionOrNull() is SyncSecureStoreException)
        }
    }

    @Test
    fun `persistent credentials keep full token revision and reject stale refresh after clearing`() = runBlocking {
        withStore { directory, cipher ->
            fun credentials() = PersistentGitHubCredentialStore(EncryptedFileSyncSecureStore(directory, cipher))
            val token = token("first-secret")
            val first = credentials().replace(null, token)
            assertEquals(1L, first.revision)
            assertEquals(first, credentials().read())
            assertTrue(runCatching { credentials().replace(null, token("wrong")) }.isFailure)
            val second = credentials().replace(first.revision, token("second-secret"))
            assertEquals(2L, second.revision)
            assertTrue(runCatching { credentials().replace(first.revision, token) }.isFailure)
            credentials().clear()
            assertNull(credentials().read())
            val next = credentials().replace(null, token("next-account"))
            assertTrue(next.revision > second.revision)
            assertTrue(runCatching { credentials().replace(second.revision, token) }.isFailure)
            assertEquals(next, credentials().read())
            assertFalse(credentials().toString().contains("next-account"))
        }
    }

    @Test
    fun `credential decoding never converts corruption into missing authorization`() = runBlocking {
        withStore { directory, cipher ->
            val store = EncryptedFileSyncSecureStore(directory, cipher)
            val credentials = PersistentGitHubCredentialStore(store)
            assertTrue(store.compareAndSet("github-auth", null, "malformed-secret"))
            val error = runCatching { credentials.read() }.exceptionOrNull()
            assertTrue(error is SyncSecureStoreException)
            assertFalse(error.toString().contains("malformed-secret"))
            assertNull(error?.cause)
            assertTrue(runCatching { credentials.replace(null, token("replacement")) }.isFailure)
        }
    }

    private fun token(access: String) = GitHubAccessToken(
        access,
        "refresh-secret",
        "bearer",
        setOf("repo", "read:user"),
        100000,
        200000,
    )

    private suspend fun withStore(block: suspend (java.io.File, SyncRecordCipher) -> Unit) {
        val directory = Files.createTempDirectory("sync-secure-contract").toFile()
        try {
            block(directory, TestRecordCipher())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Real AES/GCM for file protocol tests; does not represent Android or desktop OS key protection. */
    private class TestRecordCipher : SyncRecordCipher {
        private val secret = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }
}
