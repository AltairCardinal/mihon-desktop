package mihon.desktop.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExtensionRepoUseCaseTest {

    private lateinit var repo: FakeExtensionRepoRepository
    private lateinit var getExtensionRepo: GetExtensionRepo
    private lateinit var deleteExtensionRepo: DeleteExtensionRepo
    private lateinit var replaceExtensionRepo: ReplaceExtensionRepo

    @BeforeEach
    fun setUp() {
        repo = FakeExtensionRepoRepository()
        getExtensionRepo = GetExtensionRepo(repo)
        deleteExtensionRepo = DeleteExtensionRepo(repo)
        replaceExtensionRepo = ReplaceExtensionRepo(repo)
    }

    @Test
    fun `subscribeAll returns empty list when no repos`() = runBlocking {
        val result = getExtensionRepo.subscribeAll().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `subscribeAll reflects inserted repo`() = runBlocking {
        repo.insertRepo("https://example.com", "Example", null, "https://example.com", "fingerprint1")

        val result = getExtensionRepo.subscribeAll().first()
        assertEquals(1, result.size)
        assertEquals("https://example.com", result[0].baseUrl)
        assertEquals("Example", result[0].name)
    }

    @Test
    fun `getAll returns all repos`() = runBlocking {
        repo.insertRepo("https://a.com", "A", null, "https://a.com", "fp-a")
        repo.insertRepo("https://b.com", "B", null, "https://b.com", "fp-b")

        val result = getExtensionRepo.getAll()
        assertEquals(2, result.size)
    }

    @Test
    fun `deleteRepo removes repo from list`() = runBlocking {
        repo.insertRepo("https://example.com", "Example", null, "https://example.com", "fp1")
        deleteExtensionRepo.await("https://example.com")

        val result = getExtensionRepo.subscribeAll().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `replaceRepo replaces repo with same signing key fingerprint`() = runBlocking {
        repo.insertRepo("https://old.com", "Old", null, "https://old.com", "shared-fp")

        val newRepo = ExtensionRepo(
            baseUrl = "https://new.com",
            name = "New",
            shortName = null,
            website = "https://new.com",
            signingKeyFingerprint = "shared-fp",
        )
        replaceExtensionRepo.await(newRepo)

        val result = getExtensionRepo.getAll()
        assertEquals(1, result.size)
        assertEquals("https://new.com", result[0].baseUrl)
        assertNull(result[0].shortName)
    }

    @Test
    fun `insertRepo duplicate baseUrl throws SaveExtensionRepoException`() = runBlocking {
        repo.insertRepo("https://example.com", "Example", null, "https://example.com", "fp1")

        var threw = false
        try {
            repo.insertRepo("https://example.com", "Example2", null, "https://example.com", "fp2")
        } catch (e: mihon.domain.extensionrepo.exception.SaveExtensionRepoException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `subscribeAll emits updated list after delete`() = runBlocking {
        repo.insertRepo("https://a.com", "A", null, "https://a.com", "fp-a")
        repo.insertRepo("https://b.com", "B", null, "https://b.com", "fp-b")

        deleteExtensionRepo.await("https://a.com")

        val result = getExtensionRepo.subscribeAll().first()
        assertEquals(1, result.size)
        assertEquals("https://b.com", result[0].baseUrl)
    }
}
