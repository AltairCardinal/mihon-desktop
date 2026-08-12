package mihon.desktop.test.http

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.CreatorDiscoveryTaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository

class AuthorArchiveTestModeControllerTest {
    @Test
    fun `follow action mutates production repository and refreshes observable state`() = runBlocking {
        val creator = Creator(7, "Author", "author", null, emptyList(), 1, 1)
        val watch = CreatorWatch(7, true, emptyList(), emptyList(), null, null, null, 2)
        val repository = mockk<CreatorRepository>()
        every { repository.getCreatorsAsFlow() } returns flowOf(listOf(creator))
        coEvery { repository.getFollowedCreators() } returnsMany listOf(emptyList(), listOf(watch))
        coEvery { repository.followCreator(7) } returns watch
        val archive = mockk<CreatorArchiveRepository>()
        coEvery { archive.getDiscoveries(200) } returns emptyList()
        val scheduler = mockk<CreatorDiscoveryScheduler>()
        every { scheduler.state } returns MutableStateFlow(CreatorDiscoveryTaskState())
        val controller = AuthorArchiveTestModeController(repository, archive, scheduler)

        controller.hydrate()
        val result = controller.execute("author_follow", mapOf("creatorId" to "7"))

        assertTrue(result.success)
        assertEquals(listOf(7L), result.snapshot.creators)
        assertEquals(listOf(7L), result.snapshot.followedCreators)
        coVerify(exactly = 1) { repository.followCreator(7) }
    }

    @Test
    fun `missing creator id returns typed failure without mutation`() = runBlocking {
        val repository = mockk<CreatorRepository>()
        every { repository.getCreatorsAsFlow() } returns flowOf(emptyList())
        coEvery { repository.getFollowedCreators() } returns emptyList()
        val archive = mockk<CreatorArchiveRepository>()
        coEvery { archive.getDiscoveries(200) } returns emptyList()
        val scheduler = mockk<CreatorDiscoveryScheduler>()
        every { scheduler.state } returns MutableStateFlow(CreatorDiscoveryTaskState())
        val controller = AuthorArchiveTestModeController(repository, archive, scheduler)
        controller.hydrate()

        val result = controller.execute("author_follow", emptyMap())

        assertFalse(result.success)
        assertEquals(AuthorArchiveTestFailureCode.MISSING_PARAMETER, result.failureCode)
        coVerify(exactly = 0) { repository.followCreator(any()) }
    }
}
