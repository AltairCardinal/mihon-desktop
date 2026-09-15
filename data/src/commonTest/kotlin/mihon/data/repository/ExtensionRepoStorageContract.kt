package mihon.data.repository

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

/** Both platform handlers execute these contracts against generated SQL and a real SQLite driver. */
abstract class ExtensionRepoStorageContract {
    protected abstract fun open(): Storage

    @Test
    fun `signing key lookup compares fingerprint bytes rather than display spelling`() = runBlocking {
        open().use { storage ->
            val expected = expectedRepo().copy(signingKeyFingerprint = "AA:BB")
            storage.repository.insertRepo(expected)
            assertEquals(expected, storage.repository.getRepoBySigningKeyFingerprint("aabb"))
        }
    }

    @Test
    fun `CRUD preserves identity metadata and observable repository state`() = runBlocking {
        open().use { storage ->
            val repository = storage.repository
            val original = expectedRepo()
            repository.insertRepo(original)
            assertEquals(original, repository.getRepoBySigningKeyFingerprint(original.signingKeyFingerprint))
            assertEquals(listOf(original), repository.subscribeAll().first())
            assertEquals(1, repository.getCount().first())

            val updated = original.copy(
                name = "Updated",
                indexUrl = "https://repo.example/next/index.pb",
                extensionListUrl = null,
                contactDiscord = "discord:updated",
            )
            repository.upsertRepo(updated)
            assertEquals(updated, repository.getRepo(original.baseUrl))
            val relocated = updated.copy(baseUrl = "https://relocated.example")
            repository.replaceRepo(relocated)
            assertNull(repository.getRepo(original.baseUrl))
            assertEquals(listOf(relocated), repository.getAll())
            assertEquals(relocated, repository.getRepoBySigningKeyFingerprint(original.signingKeyFingerprint))

            repository.deleteRepo(relocated.baseUrl)
            assertEquals(emptyList<ExtensionRepo>(), repository.subscribeAll().first())
            assertEquals(0, repository.getCount().first())
        }
    }

    @Test
    fun `duplicate fingerprint and conflicting replacement preserve existing repositories`() = runBlocking {
        open().use { storage ->
            val repository = storage.repository
            val original = expectedRepo()
            val other = original.copy(baseUrl = "https://other.example", signingKeyFingerprint = "other-key")
            repository.insertRepo(original)
            repository.insertRepo(other)
            assertThrows(SaveExtensionRepoException::class.java) {
                runBlocking { repository.insertRepo(original.copy(baseUrl = "https://duplicate.example")) }
            }
            assertThrows(Exception::class.java) {
                runBlocking { repository.replaceRepo(original.copy(baseUrl = other.baseUrl)) }
            }
            assertEquals(setOf(original, other), repository.getAll().toSet())
        }
    }

    @Test
    fun `cancelled writes propagate cancellation without modifying storage`() = runBlocking {
        val writes: List<suspend (ExtensionRepoRepositoryImpl, ExtensionRepo) -> Unit> = listOf(
            { repository, repo -> repository.insertRepo(repo) },
            { repository, repo -> repository.upsertRepo(repo) },
            { repository, repo ->
                repository.insertRepo(repo.baseUrl, repo.name, repo.shortName, repo.website, repo.signingKeyFingerprint)
            },
            { repository, repo ->
                repository.upsertRepo(repo.baseUrl, repo.name, repo.shortName, repo.website, repo.signingKeyFingerprint)
            },
        )
        for (write in writes) {
            open().use { storage ->
                val cancellation = CancellationException("cancel before database dispatch")
                var failure: Throwable? = null
                launch(start = CoroutineStart.UNDISPATCHED) {
                    currentCoroutineContext().cancel(cancellation)
                    try {
                        write(storage.repository, expectedRepo())
                    } catch (error: Throwable) {
                        failure = error
                    }
                }.join()
                assertSame(cancellation, failure)
                assertEquals(emptyList<ExtensionRepo>(), storage.repository.getAll())
            }
        }
    }

    protected class Storage(val driver: SqlDriver, handler: DatabaseHandler) : AutoCloseable {
        val repository = ExtensionRepoRepositoryImpl(handler)
        override fun close() = driver.close()
    }

    protected fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        return Database(
            driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
    }

    private fun expectedRepo() = ExtensionRepo(
        baseUrl = "https://repo.example",
        name = "Example",
        shortName = "EX",
        website = "https://repo.example/about",
        signingKeyFingerprint = "trusted-fingerprint",
        indexUrl = "https://repo.example/index.pb",
        extensionListUrl = "https://repo.example/extensions.pb",
        contactDiscord = "discord:example",
    )
}
