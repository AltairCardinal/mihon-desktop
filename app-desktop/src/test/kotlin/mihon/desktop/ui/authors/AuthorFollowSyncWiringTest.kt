package mihon.desktop.ui.authors

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.sync.SyncMutationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.repository.CreatorRepository

class AuthorFollowSyncWiringTest {
    @Test
    fun `desktop author toggle sends user follow and unfollow through the shared command`() = runBlocking {
        val contexts = mutableListOf<SyncMutationContext>()
        val followed = MutableStateFlow(emptyList<CreatorWatch>())
        val watch = CreatorWatch(7, true, emptyList(), emptyList(), null, null, null, 1)
        val repository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(emptyList())
            every { getFollowedCreatorsAsFlow() } returns followed
            coEvery { getCreator(7) } returns null
            coEvery { getDiscoveryCandidatesForCreator(7) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7) } returns emptyList()
            coEvery { getMangaTitlesForCreator(7) } returns emptyMap()
            coEvery { followCreator(7, null, null, any()) } answers {
                contexts.add(arg(3))
                followed.value = listOf(watch)
                watch
            }
            coEvery { unfollowCreator(7, any()) } answers {
                contexts.add(arg(1))
                followed.value = emptyList()
            }
        }
        val identity = mockk<AuthorIdentityActions> {
            every { manageCreatorIdentity } returns mockk {
                every { observe(7) } returns kotlinx.coroutines.flow.emptyFlow()
            }
            coEvery { getManualAliases(7) } returns emptyList()
        }
        val model = AuthorDetailScreenModel(
            creatorId = 7,
            collectOnOpen = false,
            getCreatorDetails = GetCreatorDetails(repository),
            getCreators = GetCreators(repository),
            setCreatorFollow = SetCreatorFollow(repository),
            discoveryScheduler = null,
            creatorArchive = null,
            identityActions = identity,
        )
        try {
            withTimeout(5_000) { model.state.filter { !it.loading }.first() }
            model.toggleFollow().join()
            withTimeout(5_000) { model.state.filter { it.followed }.first() }
            model.toggleFollow().join()
            withTimeout(5_000) { model.state.filter { !it.followed }.first() }
            assertEquals(listOf(SyncMutationContext.User, SyncMutationContext.User), contexts)
            assertNull(model.state.value.error)
        } finally {
            model.onDispose()
        }
    }
}
