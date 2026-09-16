package eu.kanade.tachiyomi.ui.browse.author

import cafe.adriel.voyager.core.model.screenModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.sync.SyncMutationContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.repository.CreatorRepository

class AndroidAuthorFollowSyncWiringTest {
    @Test
    fun `android author toggle sends user follow and unfollow through the shared command`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val contexts = mutableListOf<SyncMutationContext>()
        val followed = MutableStateFlow(emptyList<CreatorWatch>())
        val watch = CreatorWatch(7, true, emptyList(), emptyList(), null, null, null, 1)
        val repository = mockk<CreatorRepository> {
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
        val archive = mockk<CreatorArchive> {
            every { observe(7) } returns flowOf(CreatorWorkArchive(emptyList(), emptyList(), emptyList()))
        }
        val model = AndroidAuthorDetailScreenModel(
            creatorId = 7,
            details = GetCreatorDetails(repository),
            creators = GetCreators(repository),
            follow = SetCreatorFollow(repository),
            discovery = mockk(),
            archive = archive,
            sources = mockk(),
        )
        try {
            model.toggleFollow().join()
            assertTrue(model.state.value.followed)
            model.toggleFollow().join()
            assertFalse(model.state.value.followed)
            assertEquals(listOf(SyncMutationContext.User, SyncMutationContext.User), contexts)
            assertNull(model.state.value.error)
        } finally {
            model.screenModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
