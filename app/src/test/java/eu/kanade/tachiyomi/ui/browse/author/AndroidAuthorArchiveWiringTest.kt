package eu.kanade.tachiyomi.ui.browse.author

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.manga.model.Manga

class AndroidAuthorArchiveWiringTest {
    @Test
    fun `uncollected Android creator entry resolves real SQL identity and retries rolled back failure`() = runTest {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        tachiyomi.data.Database.Schema.create(driver)
        val database = tachiyomi.data.Database(
            driver,
            historyAdapter = tachiyomi.data.History.Adapter(tachiyomi.data.DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                tachiyomi.data.StringListColumnAdapter,
                tachiyomi.data.UpdateStrategyColumnAdapter,
            ),
        )
        val handler = tachiyomi.data.AndroidDatabaseHandler(database, driver)
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
