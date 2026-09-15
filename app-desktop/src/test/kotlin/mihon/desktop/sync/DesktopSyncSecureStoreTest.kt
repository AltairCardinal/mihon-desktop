package mihon.desktop.sync

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mihon.desktop.backup.DesktopBackupCreator
import mihon.desktop.platform.CredentialBackend
import mihon.desktop.platform.CredentialNamespace
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.platform.PlatformCredentialBackend
import mihon.domain.sync.security.SyncSecureStoreException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences

@Timeout(30)
class DesktopSyncSecureStoreTest {
    @Test
    fun `ordinary desktop backup includes normal preferences but excludes secure records`() = runBlocking {
        val directory = Files.createTempDirectory("desktop-backup-secrets").toFile()
        val node = Preferences.userRoot().node("/mihon-test/backup/${UUID.randomUUID()}")
        try {
            assertTrue(DesktopSyncSecureStore(directory, TestBackend()).compareAndSet("auth", null, "hidden-secret"))
            val preferences = DesktopPreferenceStore(node)
            preferences.getString("theme").set("visible-theme")
            val backup = DesktopBackupCreator.createFromDatabase(
                mangaRepository = mockk(relaxed = true),
                chapterRepository = mockk(relaxed = true),
                categoryRepository = mockk(relaxed = true),
                historyRepository = mockk(relaxed = true),
                trackRepository = mockk(relaxed = true),
                preferenceStore = preferences,
                sourcePreferenceStore = { preferences },
                extensionRepoRepository = mockk(relaxed = true),
            )
            val restored = DesktopBackupCreator.decodeFromBytes(DesktopBackupCreator.encodeToBytes(backup))
            assertTrue(restored.backupPreferences.toString().contains("visible-theme"))
            assertFalse(restored.toString().contains("hidden-secret"))
        } finally {
            node.removeNode()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `desktop encrypts large records and serializes independent adapters`() = runBlocking {
        val directory = Files.createTempDirectory("desktop-sync-secrets").toFile()
        val backend = TestBackend()
        try {
            val value = "synthetic-token".repeat(4000)
            assertTrue(DesktopSyncSecureStore(directory, backend).compareAndSet("auth", null, value))
            assertEquals(value, DesktopSyncSecureStore(directory, backend).read("auth"))
            assertTrue(backend.values.values.all { it.length < 100 })
            assertTrue(directory.listFiles()!!.none { it.readText().contains("synthetic-token") })
            val results = (1..8).map { index ->
                async(Dispatchers.IO) {
                    DesktopSyncSecureStore(directory, backend).compareAndSet("auth", value, "replacement-$index")
                }
            }.awaitAll()
            assertEquals(1, results.count { it })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `lost desktop wrapping key never regenerates or overwrites ciphertext`() = runBlocking {
        val directory = Files.createTempDirectory("desktop-sync-secrets").toFile()
        val backend = TestBackend()
        try {
            val store = DesktopSyncSecureStore(directory, backend)
            assertTrue(store.compareAndSet("auth", null, "secret"))
            backend.values.clear()
            assertTrue(runCatching { store.read("auth") }.exceptionOrNull() is SyncSecureStoreException)
            assertTrue(runCatching { store.compareAndSet("auth", null, "replacement") }.isFailure)
            assertTrue(backend.values.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `desktop adapter roundtrips with the current operating system backend`() = runBlocking {
        val platform = OperatingSystem.detect()
        if (platform != OperatingSystem.WINDOWS && platform != OperatingSystem.MACOS) return@runBlocking
        val directory = Files.createTempDirectory("desktop-sync-native").toFile()
        val delegate = PlatformCredentialBackend(platform, namespace = CredentialNamespace.SYNC_V1)
        val accounts = mutableSetOf<String>()
        val backend = object : CredentialBackend {
            override fun save(account: String, secret: CharArray) {
                accounts += account
                delegate.save(account, secret)
            }
            override fun load(account: String) = delegate.load(account)
            override fun delete(account: String) = delegate.delete(account)
        }
        try {
            assertTrue(DesktopSyncSecureStore(directory, backend).compareAndSet("auth", null, "native-secret"))
            assertEquals("native-secret", DesktopSyncSecureStore(directory, backend).read("auth"))
            assertFalse(accounts.isEmpty())
        } finally {
            accounts.forEach(delegate::delete)
            directory.deleteRecursively()
        }
    }

    private class TestBackend : CredentialBackend {
        val values = mutableMapOf<String, String>()
        override fun save(account: String, secret: CharArray) {
            values[account] = secret.concatToString()
        }
        override fun load(account: String) = values[account]?.toCharArray()
        override fun delete(account: String) {
            values.remove(account)
        }
    }
}
