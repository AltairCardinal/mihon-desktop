package eu.kanade.tachiyomi.ui.browse.author

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.data.Database
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorIdentitySnapshot
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-mdpi")
class AndroidCreatorCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `actual author scan reports compatibility without showing source internals`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                tachiyomi.data.StringListColumnAdapter,
                tachiyomi.data.UpdateStrategyColumnAdapter,
            ),
        )
        val idReads = AtomicInteger()
        val calls = AtomicInteger()
        val source = object : CatalogueSource {
            override val id: Long get() {
                idReads.incrementAndGet()
                throw NoSuchMethodError("never probe registered ID")
            }
            override val name = "Creator ABI fixture"
            override val lang = "en"
            override val supportsLatest = false
            override fun getFilterList() = FilterList()
            override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
                calls.incrementAndGet()
                throw NoSuchMethodError("private creator ABI details")
            }
            override suspend fun getMangaDetails(manga: SManga) = manga
            override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
            override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
            override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
        }
        val creator = Creator(1L, "ONE", "one", null, emptyList(), 0L, 0L)
        val repository = mockk<CreatorRepository>(relaxed = true) {
            coEvery { getCreator(1L) } returns creator
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        }
        val details = GetCreatorDetails(repository)
        val archive = mockk<CreatorArchive>(relaxed = true) {
            every { observe(1L) } returns flowOf(CreatorWorkArchive(emptyList(), emptyList(), emptyList()))
            coEvery { getPresentationExclusions(any()) } returns emptySet()
        }
        val preferences = AndroidPreferenceStore(activity.get())
        val identities = mockk<CreatorArchiveRepository>(relaxed = true) {
            every { observeIdentitySnapshot(1L) } returns flowOf(
                CreatorIdentitySnapshot(1L, 1L, "ONE", listOf("ONE"), null, false),
            )
        }
        try {
            Injekt.importModule(AppModule(activity.get().application))
            // AppModule posts startup warmup; keep unrelated IO outside this screen fixture.
            Injekt.addSingleton(mockk<NetworkHelper>(relaxed = true))
            Injekt.addSingleton(mockk<Database>(relaxed = true))
            Injekt.addSingleton(mockk<DownloadManager>(relaxed = true))
            Injekt.addSingleton(BasePreferences(activity.get(), preferences))
            Injekt.addSingleton(LibraryPreferences(preferences))
            Injekt.addSingleton(details)
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(SetCreatorFollow(repository))
            Injekt.importModule(eu.kanade.tachiyomi.di.PreferenceModule(activity.get().application))
            Injekt.importModule(eu.kanade.domain.DomainModule())
            Injekt.addSingleton<tachiyomi.data.DatabaseHandler>(tachiyomi.data.AndroidDatabaseHandler(database, driver))
            val actualRepository = Injekt.get<CreatorRepository>()
            kotlinx.coroutines.runBlocking { actualRepository.upsertCreator("ONE") }
            Injekt.addSingleton<eu.kanade.tachiyomi.extension.ExtensionManager>(
                mockk(relaxed = true) {
                    every { installedExtensionsFlow } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
                },
            )
            Injekt.addSingleton(details)
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(SetCreatorFollow(repository))
            Injekt.addSingleton(archive)
            Injekt.addSingleton(ManageCreatorIdentity(identities))
            Injekt.addSingleton(mockk<NetworkToLocalManga>(relaxed = true))
            Injekt.addSingleton<SourceManager>(object : SourceManager {
                override val isInitialized = kotlinx.coroutines.flow.MutableStateFlow(true)
                override val catalogueSources = flowOf(listOf(source))
                override fun getRegisteredSourceId(candidate: eu.kanade.tachiyomi.source.Source) =
                    if (candidate === source) 10L else null
                override fun get(sourceKey: Long) = source.takeIf { sourceKey == 10L }
                override fun getOrStub(sourceKey: Long) = source
                override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
                override fun getCatalogueSources() = listOf(source)
                override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
            })
            val actualService = Injekt.get<CreatorDiscoveryService>()
            Injekt.addSingleton(DiscoverCreatorWorks(actualService, details))
            activity.get().setContent { MaterialTheme { Navigator(AndroidAuthorDetailScreen(1L)) { CurrentScreen() } } }
            compose.waitForIdle()
            compose.onNodeWithText(activity.get().stringResource(MR.strings.desktop_ui_check_new_works)).performClick()
            val compatibilityMessage = activity.get().stringResource(MR.strings.source_error_extension_incompatible)
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(compatibilityMessage).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                println("CREATOR_UI calls=${calls.get()} idReads=${idReads.get()}")
                println(compose.onRoot(useUnmergedTree = true).printToString())
                throw error
            }
            compose.onNodeWithText(
                activity.get().stringResource(MR.strings.source_error_extension_incompatible),
            ).assertExists()
            compose.onNodeWithText("private creator ABI details", substring = true).assertDoesNotExist()
            compose.waitForIdle()
            assertEquals(1, calls.get())
            assertEquals(0, idReads.get())
        } finally {
            activity.get().setContent {}
            activity.pause().stop().destroy()
            Injekt = previous
            driver.close()
        }
    }
}
