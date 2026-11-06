package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.Application
import androidx.paging.testing.asSnapshot
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class AndroidBrowseDefaultRegistryWiringTest {
    @Test
    fun `production AppModule supplies the default browse extension manager without probing source ID`(): Unit =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val previous = Injekt
            Injekt = InjektScope(DefaultRegistrar())
            val host = ScreenModelTestHost()
            var manager: ExtensionManager? = null
            var idReads = 0
            try {
                val app = RuntimeEnvironment.getApplication()
                Injekt.importModule(AppModule(app))
                Injekt.importModule(PreferenceModule(app))
                Injekt.addSingleton(mockk<eu.kanade.tachiyomi.network.NetworkHelper>(relaxed = true))
                Injekt.addSingleton(mockk<tachiyomi.data.Database>(relaxed = true))
                Injekt.addSingleton(mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true))
                Injekt.addSingleton(mockk<eu.kanade.domain.extension.interactor.TrustExtension>(relaxed = true))
                Injekt.addSingleton<mihon.domain.extensionrepo.interactor.GetExtensionRepo>(
                    mockk {
                        every { subscribeAll() } returns flowOf(emptyList())
                        coEvery { getAll() } returns emptyList()
                    },
                )
                val source = object : Source {
                    override val id: Long get() {
                        idReads++
                        throw NoSuchMethodError("registered browse ID")
                    }
                    override val name = "Registered default browse"
                    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
                }
                val sources = mockk<SourceManager>(relaxed = true) { every { getOrStub(9L) } returns source }
                Injekt.addSingleton<SourceManager>(sources)
                manager = Injekt.get<ExtensionManager>()
                assertSame(manager, Injekt.get<ExtensionManager>())
                val repository = mockk<MangaRepository>()
                coEvery { repository.insertNetworkManga(any()) } answers { firstArg() }
                val preferences = Injekt.get<SourcePreferences>()
                val model = host.create {
                    BrowseSourceScreenModel(
                        9L,
                        BrowseSourceScreenModel.Listing.Popular.query,
                        sourceManager = sources,
                        sourcePreferences = preferences,
                        libraryPreferences = Injekt.get(), coverCache = mockk(relaxed = true),
                        sourceMangaSearchService = SourceMangaSearchService(),
                        networkToLocalManga = NetworkToLocalManga(repository),
                        getDuplicateLibraryManga = mockk(), getCategories = mockk(), setMangaCategories = mockk(),
                        setMangaDefaultChapterFlags = mockk(relaxed = true),
                        getManga = mockk<GetManga> { every { subscribe(any(), any()) } returns flowOf(null) },
                        updateManga = mockk(), addTracks = mockk(relaxed = true),
                        getIncognitoState = mockk { every { await(9L) } returns true },
                        pagerCoroutineScope = backgroundScope,
                    )
                }
                backgroundScope.launch { model.mangaPagerFlowFlow.collect() }
                runCurrent()
                val result = backgroundScope.async { model.mangaPagerFlowFlow.value.asSnapshot() }
                runCurrent()
                assertEquals(emptyList<Any>(), result.await())
                assertEquals(0, idReads)
            } finally {
                host.close()
                manager?.scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
                Injekt = previous
                Dispatchers.resetMain()
            }
        }
}
