package mihon.domain.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.service.ExtensionCatalogService
import mihon.domain.extension.service.RepositoryFetchResult
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionCatalogSnapshotContractTest {
    private val repository = ExtensionRepo("https://repo.example", "Store", null, "https://repo.example", "key")
    private val artifact = ExtensionArtifact(
        "Example", "example.extension", "1.4.1", 1, "en", false, emptyList(),
        repository.toIdentity(), "https://repo.example/extension.apk", "https://repo.example/icon.png", null,
    )
    private val entry = ExtensionCatalogEntry(artifact, artifact.compatibility())

    @Test
    fun `display name changes do not discard a trusted successful snapshot`() = runBlocking {
        val service = ExtensionCatalogService()
        service.refresh(listOf(repository)) { RepositoryFetchResult.Success(it.toIdentity(), listOf(entry)) }
        val renamed = repository.copy(name = "New display name")
        val failed = service.refresh(listOf(renamed)) {
            RepositoryFetchResult.Failure(it.toIdentity(), AppError.Network())
        }
        assertEquals(listOf(entry), failed.entries)
        assertEquals(renamed.toIdentity(), failed.failures.single().repository)
    }

    @Test
    fun `failed refresh retains last successful catalog but successful empty replaces it`() = runBlocking {
        val service = ExtensionCatalogService()
        service.refresh(listOf(repository)) { RepositoryFetchResult.Success(it.toIdentity(), listOf(entry)) }

        val failed = service.refresh(listOf(repository)) {
            RepositoryFetchResult.Failure(it.toIdentity(), AppError.Network())
        }
        assertEquals(listOf(entry), failed.entries)
        assertEquals(1, failed.failures.size)
        assertFalse(failed.isCompleteEmpty)

        val empty = service.refresh(listOf(repository)) { RepositoryFetchResult.Success(it.toIdentity(), emptyList()) }
        assertTrue(empty.isCompleteEmpty)
        val failedAgain = service.refresh(listOf(repository)) {
            RepositoryFetchResult.Failure(it.toIdentity(), AppError.Network())
        }
        assertTrue(failedAgain.entries.isEmpty())
    }

    @Test
    fun `removed repositories and replaced signing identities cannot reuse old snapshots`() = runBlocking {
        val service = ExtensionCatalogService()
        service.refresh(listOf(repository)) { RepositoryFetchResult.Success(it.toIdentity(), listOf(entry)) }
        val changed = repository.copy(signingKeyFingerprint = "other-key")
        assertTrue(
            service.refresh(listOf(changed)) {
                RepositoryFetchResult.Failure(it.toIdentity(), AppError.Network())
            }.entries.isEmpty(),
        )
        service.refresh(emptyList()) { error("No request after removal") }
        assertTrue(
            service.refresh(listOf(repository)) {
                RepositoryFetchResult.Failure(it.toIdentity(), AppError.Network())
            }.entries.isEmpty(),
        )
    }

    @Test
    fun `cancelled refresh propagates and does not publish partial snapshots`() = runBlocking {
        val service = ExtensionCatalogService()
        service.refresh(listOf(repository)) { RepositoryFetchResult.Success(it.toIdentity(), listOf(entry)) }
        val failure = runCatching {
            service.refresh(listOf(repository)) { throw CancellationException("cancel refresh") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(
            listOf(entry),
            service.refresh(listOf(repository)) {
                RepositoryFetchResult.Failure(it.toIdentity(), AppError.Network())
            }.entries,
        )
    }
}
