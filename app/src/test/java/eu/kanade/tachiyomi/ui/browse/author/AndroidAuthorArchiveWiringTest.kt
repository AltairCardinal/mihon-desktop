package eu.kanade.tachiyomi.ui.browse.author

import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class AndroidAuthorArchiveWiringTest {
    @Test
    fun `Android archive state applies shared work search and source contract`() {
        tachiyomi.data.creator.verifyCreatorWorkFilterProjection { archive, filter ->
            AuthorState(archive = archive, workFilter = filter).visibleArchive
        }
    }

    @Test
    fun `actual Android detail editor obeys shared identity contract`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                UpdateStrategyColumnAdapter,
            ),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        var model: AndroidAuthorDetailScreenModel? = null
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(
            handler,
            tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
        )
        val networkToLocal = NetworkToLocalManga(mangaRepository)
        try {
            tachiyomi.data.creator.verifyCreatorIdentityEditor(handler) { repository, manager, id ->
                AndroidAuthorDetailScreenModel(
                    id, GetCreatorDetails(repository), GetCreators(repository),
                    SetCreatorFollow(repository), mockk(), CreatorArchive(repository, repository), mockk(), manager,
                    networkToLocal,
                ).also { model = it }.identityEditor
            }
            val detail = requireNotNull(model)
            val version = SourceWorkArchiveVersion(
                1, SourceWorkNaturalKey(42, "/specific-version"), 321, "Specific",
                LanguageProjectionContract(
                    LanguageDimension.READING,
                    "und",
                    LanguageCertainty.UNKNOWN,
                    LanguageEvidenceKind.UNKNOWN,
                ),
                0, false, null, 0, null,
            )
            val existing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { detail.openManga.first() }
            detail.openVersion(version).join()
            assertEquals(321L, existing.await())
            val listed = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { detail.openManga.first() }
            detail.openVersion(version.copy(mangaId = null)).join()
            val stored = mangaRepository.getMangaById(listed.await())
            assertEquals(42L, stored.source)
            assertEquals("/specific-version", stored.url)
            assertEquals(false, stored.favorite)
        } finally {
            model?.screenModelScope?.cancel()
            driver.close()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `uncollected Android creator entry resolves real SQL identity and retries rolled back failure`() = runTest {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                UpdateStrategyColumnAdapter,
            ),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        try {
            tachiyomi.data.creator.verifyExactCreatorEntry(handler) { manager, manga, mention ->
                val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = manager)
                var opened: Long? = null
                AndroidCreatorOpenCoordinator(
                    resolve = navigator::resolve,
                    onResolved = { opened = it },
                    onAmbiguous = { error("Exact identity cannot require a choice") },
                    onFailure = { throw it },
                ).open(manga, mention)
                CreatorMentionResolution.Resolved(requireNotNull(opened))
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `author detail is a Voyager screen`() {
        assertTrue(AndroidAuthorDetailScreen(7) is Screen)
    }

    @Test
    fun `Android author surface consumes the shared commands`() {
        assertNotNull(GetCreators::class.java)
        assertNotNull(GetCreatorDetails::class.java)
        assertNotNull(SetCreatorFollow::class.java)
        assertNotNull(DiscoverCreatorWorks::class.java)
        assertNotNull(CreatorArchive::class.java)
        assertNotNull(ManageCreatorIdentity::class.java)
        assertNotNull(AndroidMangaCreatorNavigator::class.java)
    }

    @Test
    fun `discovery owns a distinct Android worker`() {
        assertNotNull(CreatorDiscoveryJob::class.java)
    }

    @Test
    fun `manga creator navigation splits author and artist fields into people`() {
        val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = mockk())
        val manga = Manga.create().copy(author = "ONE / Murata", artist = "Murata, Boichi")

        assertEquals(listOf("ONE", "Murata", "Boichi"), navigator.mentions(manga).map { it.displayName })
    }

    @Test
    fun `Android manga creator navigation executes shared resolution and retries preparation failure`() = runTest {
        val manga = Manga.create().copy(author = "ONE")
        val manager = mockk<ManageCreatorIdentity>()
        val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = manager)
        val mention = navigator.mentions(manga).single()
        coEvery { manager.resolve(manga, mention) } throws IllegalStateException("preparing") andThen
            CreatorMentionResolution.Resolved(41L)
        val opened = mutableListOf<Long>()
        var failures = 0
        val coordinator = AndroidCreatorOpenCoordinator(
            resolve = navigator::resolve,
            onResolved = opened::add,
            onAmbiguous = {},
            onFailure = {
                failures += 1
                true
            },
        )

        coordinator.open(manga, mention)

        assertEquals(1, failures)
        assertEquals(listOf(41L), opened)
    }
}
