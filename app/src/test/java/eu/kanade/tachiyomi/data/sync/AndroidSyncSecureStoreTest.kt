package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.data.sync.security.SyncRecordCipher
import mihon.domain.sync.security.SyncSecureStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidSyncSecureStoreTest {
    @Test
    fun `android records reopen outside ordinary backup preferences`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "sync-secrets")
        directory.deleteRecursively()
        try {
            val cipher = TestCipher()
            assertTrue(AndroidSyncSecureStore(context, cipher).compareAndSet("auth", null, "synthetic-secret"))
            assertEquals("synthetic-secret", AndroidSyncSecureStore(context, cipher).read("auth"))
            assertTrue(directory.listFiles()!!.any { it.extension == "record" })
            val preferences = PreferenceBackupCreator(mockk(), AndroidPreferenceStore(context)).createApp(true)
            assertFalse(preferences.toString().contains("synthetic-secret"))
            assertTrue(directory.listFiles()!!.none { it.readText().contains("synthetic-secret") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `android cipher failure remains failure and preserves existing record`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "sync-secrets")
        directory.deleteRecursively()
        try {
            val cipher = TestCipher()
            val store = AndroidSyncSecureStore(context, cipher)
            assertTrue(store.compareAndSet("auth", null, "secret"))
            val failed = object : SyncRecordCipher {
                override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = error("secret")
                override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray = error("secret")
            }
            val error = runCatching { AndroidSyncSecureStore(context, failed).read("auth") }.exceptionOrNull()
            assertTrue(error is SyncSecureStoreException)
            assertFalse(error.toString().contains("secret"))
            assertEquals("secret", store.read("auth"))
        } finally {
            directory.deleteRecursively()
        }
    }

    // Exercises the real file adapter only. AndroidKeyStore itself requires ART/device validation.
    private class TestCipher : SyncRecordCipher {
        private val key = SecretKeySpec(ByteArray(32) { 42 }, "AES")
        override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }
}
