package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.paging.testing.asSnapshot
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService

class BrowseSourceScreenModelBehaviorTest {

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `late old Pager generation cannot replace or pollute the current listing Pager`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = HangingBrowseSource()
        val model = screenModel(source)
        backgroundScope.launch(dispatcher) { model.mangaPagerFlowFlow.collect() }
        runCurrent()

        val oldPager = model.mangaPagerFlowFlow.value
        val oldSnapshot = backgroundScope.async(dispatcher) { oldPager.asSnapshot() }
        runCurrent()
        source.oldStarted.await()

        model.setListing(BrowseSourceScreenModel.Listing.Search("new", FilterList()))
        runCurrent()
        val newPager = model.mangaPagerFlowFlow.value
        assertNotSame(oldPager, newPager)
        val newSnapshot = backgroundScope.async(dispatcher) { newPager.asSnapshot() }
        runCurrent()
        source.newStarted.await()

        source.oldResult.complete(MangasPage(listOf(manga("/old", "Old")), false))
        runCurrent()

        assertEquals(listOf("/old"), oldSnapshot.await().map { it.value.url })
        assertSame(newPager, model.mangaPagerFlowFlow.value)
        assertFalse(newSnapshot.isCompleted)

        source.newResult.complete(MangasPage(listOf(manga("/new", "New")), false))
        runCurrent()

        assertEquals(listOf("/new"), newSnapshot.await().map { it.value.url })
        assertSame(newPager, model.mangaPagerFlowFlow.value)
    }

    private fun kotlinx.coroutines.test.TestScope.screenModel(source: CatalogueSource): BrowseSourceScreenModel {
        val preferenceStore = InMemoryPreferenceStore()
        val repository = mockk<MangaRepository>()
        coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
        val getManga = mockk<GetManga> {
            every { subscribe(any(), any()) } returns flowOf(null)
        }
        val sourceManager = mockk<SourceManager> {
            every { getOrStub(source.id) } returns source
        }
        val getIncognitoState = mockk<GetIncognitoState> {
            every { await(source.id) } returns false
        }

        return BrowseSourceScreenModel(
            sourceId = source.id,
            listingQuery = BrowseSourceScreenModel.Listing.Popular.query,
            sourceManager = sourceManager,
            sourcePreferences = SourcePreferences(preferenceStore),
            libraryPreferences = LibraryPreferences(preferenceStore),
            coverCache = mockk<CoverCache>(),
            sourceMangaSearchService = SourceMangaSearchService(),
            networkToLocalManga = NetworkToLocalManga(repository),
            getDuplicateLibraryManga = mockk<GetDuplicateLibraryManga>(),
            getCategories = mockk<GetCategories>(),
            setMangaCategories = mockk<SetMangaCategories>(),
            setMangaDefaultChapterFlags = mockk<SetMangaDefaultChapterFlags>(),
            getManga = getManga,
            updateManga = mockk<UpdateManga>(),
            addTracks = mockk<AddTracks>(),
            getIncognitoState = getIncognitoState,
            pagerCoroutineScope = backgroundScope,
        )
    }

    private class HangingBrowseSource : CatalogueSource {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<MangasPage>()
        val newResult = CompletableDeferred<MangasPage>()

        override val id = 13L
        override val name = "Hanging browse source"
        override val lang = "en"
        override val supportsLatest = false

        override suspend fun getPopularManga(page: Int): MangasPage {
            oldStarted.complete(Unit)
            return withContext(NonCancellable) { oldResult.await() }
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            newStarted.complete(Unit)
            return withContext(NonCancellable) { newResult.await() }
        }

        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private companion object {
        fun manga(url: String, title: String) = SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
