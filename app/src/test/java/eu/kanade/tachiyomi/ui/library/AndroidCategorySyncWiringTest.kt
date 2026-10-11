package eu.kanade.tachiyomi.ui.library

import android.app.Application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.data.sync.journal.SyncLocalJournal
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidCategorySyncWiringTest {
    @Test
    fun `library category action journals only selected manga and explicit clearing retains favorites`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val host = ScreenModelTestHost()
        var modelJob: Job? = null
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val handler = AndroidDatabaseHandler(database, driver)
            val categories = CategoryRepositoryImpl(handler)
            val mangas = MangaRepositoryImpl(handler, mockk(relaxed = true))
            val context = RuntimeEnvironment.getApplication()
            val preferences = AndroidPreferenceStore(
                context,
                context.getSharedPreferences("category-sync-${java.util.UUID.randomUUID()}", 0),
            )
            try {
                categories.insert(Category(1, "Selected category", 0, 0))
                val selected = mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 7, url = "/selected", title = "Selected", favorite = true)),
                ).single()
                val untouched = mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 7, url = "/untouched", title = "Untouched", favorite = true)),
                ).single()
                val journal = SyncLocalJournal(handler)
                journal.connect("categories-space", 1, SyncRepository("owner", "sync", "sync"), "android", 1)
                val model = host.create {
                    LibraryScreenModel(
                        getLibraryManga = mockk { every { subscribe() } returns flowOf(emptyList()) },
                        getCategories = GetCategories(categories),
                        getTracksPerManga = mockk { every { subscribe() } returns flowOf(emptyMap()) },
                        getNextChapters = mockk(relaxed = true),
                        getChaptersByMangaId = mockk(relaxed = true),
                        getBookmarkedChaptersByMangaId = mockk(relaxed = true),
                        setReadStatus = mockk(relaxed = true),
                        updateManga = mockk(relaxed = true),
                        setMangaCategories = SetMangaCategories(mangas),
                        preferences = BasePreferences(RuntimeEnvironment.getApplication(), preferences),
                        libraryPreferences = LibraryPreferences(preferences),
                        coverCache = mockk(relaxed = true),
                        sourceManager = mockk(relaxed = true),
                        downloadManager = mockk(relaxed = true),
                        downloadCache = mockk { every { changes } returns MutableStateFlow(Unit) },
                        trackerManager = mockk { every { loggedInTrackersFlow() } returns flowOf(emptyList()) },
                        recordReadingProgress = mockk(relaxed = true),
                    )
                }
                modelJob = model.screenModelScope.coroutineContext[Job]
                val owner = requireNotNull(modelJob)
                suspend fun changeCategories(add: List<Long>, remove: List<Long>) {
                    val existingJobs = owner.children.toSet()
                    model.setMangaCategories(listOf(selected), add, remove)
                    withTimeout(5_000) {
                        owner.children.filterNot { it in existingJobs }.toList().joinAll()
                    }
                }
                changeCategories(listOf(1), emptyList())
                assertEquals(setOf(1L), categories.getCategoriesByMangaId(selected.id).map { it.id }.toSet())
                val saved = journal.pendingEvents("categories-space", 1).single()
                assertEquals(2, saved.protocolVersion)
                assertEquals(SyncOrigin.USER, saved.origin)
                assertEquals("MANGA_CATEGORIES", saved.effects.single().field.name)
                assertEquals("SET_CATEGORIES", saved.effects.single().kind.name)
                assertEquals("/selected", saved.effects.single().objectKey.originalUrl)
                assertEquals(
                    listOf("Selected category"),
                    mihon.domain.sync.SyncMetadataPolicy.categoryNames(saved.effects.single().payload),
                )
                assertTrue(categories.getCategoriesByMangaId(untouched.id).isEmpty())

                changeCategories(emptyList(), listOf(1))
                assertTrue(categories.getCategoriesByMangaId(selected.id).isEmpty())
                val cleared = journal.pendingEvents("categories-space", 1).last()
                assertEquals(2, journal.pendingEvents("categories-space", 1).size)
                assertEquals(listOf(saved.effects.single().ref(saved)), cleared.effects.single().parents)
                assertEquals(
                    emptyList<String>(),
                    mihon.domain.sync.SyncMetadataPolicy.categoryNames(cleared.effects.single().payload),
                )
                assertTrue(mangas.getMangaById(selected.id).favorite)
                assertTrue(mangas.getMangaById(untouched.id).favorite)
            } finally {
                host.close()
                modelJob?.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }
    }
}
