package mihon.domain.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class ExtensionCatalogServiceTest {

    @Test
    fun `independent repositories are fetched concurrently`() = runBlocking {
        val repositories = listOf(repository("one"), repository("two"))
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()

        val refresh = async {
            ExtensionCatalogService().refresh(repositories) { repo ->
                entered.send(Unit)
                release.await()
                RepositoryFetchResult.Success(repo.toIdentity(), emptyList())
            }
        }

        withTimeout(1_000) {
            repeat(repositories.size) { entered.receive() }
        }
        release.complete(Unit)

        assertTrue(refresh.await().isCompleteEmpty)
    }

    @Test
    fun `all successful empty repositories produce a true empty catalog`() = runBlocking {
        val repositories = listOf(repository("one"), repository("two"))

        val result = ExtensionCatalogService().refresh(repositories) { repo ->
            RepositoryFetchResult.Success(repo.toIdentity(), emptyList())
        }

        assertTrue(result.entries.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertEquals(repositories.map { it.toIdentity() }, result.repositories)
        assertTrue(result.isCompleteEmpty)
    }

    @Test
    fun `one failed repository preserves successful entries and reports the failed repository`() = runBlocking {
        val successful = repository("success")
        val failed = repository("failed")
        val expected =
            ExtensionCatalogEntry(artifact(successful.toIdentity()), artifact(successful.toIdentity()).compatibility())

        val result = ExtensionCatalogService().refresh(listOf(successful, failed)) { repo ->
            if (repo == successful) {
                RepositoryFetchResult.Success(repo.toIdentity(), listOf(expected))
            } else {
                RepositoryFetchResult.Failure(repo.toIdentity(), AppError.Server(500))
            }
        }

        assertEquals(listOf(expected), result.entries)
        assertEquals(listOf(failed.toIdentity()), result.failures.map { it.repository })
        assertEquals(500, (result.failures.single().error as AppError.Server).statusCode)
        assertFalse(result.isCompleteEmpty)
    }

    @Test
    fun `all repository failures are not disguised as an empty catalog`() = runBlocking {
        val repositories = listOf(repository("one"), repository("two"))

        val result = ExtensionCatalogService().refresh(repositories) { repo ->
            RepositoryFetchResult.Failure(repo.toIdentity(), AppError.Network())
        }

        assertTrue(result.entries.isEmpty())
        assertEquals(2, result.failures.size)
        assertFalse(result.isCompleteEmpty)
    }

    @Test
    fun `unexpected per repository exception is isolated without cancelling successful repositories`() = runBlocking {
        val successful = repository("success")
        val failed = repository("failed")
        val entry =
            ExtensionCatalogEntry(artifact(successful.toIdentity()), artifact(successful.toIdentity()).compatibility())

        val result = ExtensionCatalogService().refresh(listOf(successful, failed)) { repo ->
            if (repo == failed) error("broken repository")
            RepositoryFetchResult.Success(repo.toIdentity(), listOf(entry))
        }

        assertEquals(listOf(entry), result.entries)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().error is AppError.Unknown)
    }

    private fun artifact(repository: mihon.domain.extension.model.RepositoryIdentity) = ExtensionArtifact(
        name = "Example",
        packageName = "extension.example",
        versionName = "1.4.1",
        versionCode = 1,
        language = "en",
        isNsfw = false,
        sources = emptyList(),
        repository = repository,
        downloadUrl = "${repository.baseUrl}/apk/example.apk",
        iconUrl = "${repository.baseUrl}/icon/example.png",
        declaredSha256 = null,
    )

    private fun repository(name: String) = ExtensionRepo(
        baseUrl = "https://$name.example",
        name = name,
        shortName = name,
        website = "https://$name.example",
        signingKeyFingerprint = "$name-fingerprint",
    )
}
