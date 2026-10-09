package mihon.desktop.history

import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.journal.SyncLocalJournal
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.history.service.HistoryEvent
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@Isolated
class HistoryFavoritePersistenceIntegrationTest {
    @Test
    fun `SQL category failure rolls back favorite membership and user events`(@TempDir directory: File) = scenario(directory, true)

    @Test
    fun `repeated category confirm emits only one favorite operation`(@TempDir directory: File) = scenario(directory, false)

    private fun scenario(directory: File, fail: Boolean) = runBlocking {
        val context = initDesktopDIForTest(directory, inMemoryDesktopPreferenceStore())
        val model = HistoryScreenModelFactory.create()
        try {
            val manga = Injekt.get<SaveSourceMangaForDetails>().await(
                SManga.create().apply {
                    url = "/atomic-favorite"
                    title = "Atomic favorite"
                },
                42,
                listOf(
                    SChapter.create().apply {
                        url = "/1"
                        name = "Chapter 1"
                    },
                ),
            )
            Injekt.get<CategoryRepository>().insert(Category(0, "Chosen", 0, 0))
            val category = Injekt.get<GetCategories>().await().single { !it.isSystemCategory }
            val handler = Injekt.get<DatabaseHandler>()
            val journal = SyncLocalJournal(handler)
            journal.connect("history-favorite", 1, SyncRepository("fixture", "offline", "sync"), "device", 1)
            suspend fun eventCount() = handler.await { sync_journalQueries.countEvents().executeAsOne() }
            val before = eventCount()
            val favoriteBefore = journal.pendingEvents("history-favorite", 1).count { it.category == SyncCategory.FAVORITE }
            model.controller.addFavorite(manga.id)
            if (fail) {
                Injekt.get<SqlDriver>().execute(
                    null,
                    "CREATE TRIGGER reject_history_category BEFORE INSERT ON mangas_categories BEGIN SELECT RAISE(ABORT, 'category failure'); END",
                    0,
                )
                model.controller.confirmCategory(manga, listOf(category.id))
                assertEquals(HistoryEvent.InternalError, withTimeout(5_000) { model.controller.events.first() })
                val current = requireNotNull(Injekt.get<GetManga>().await(manga.id))
                assertFalse(current.favorite)
                assertEquals(0L, current.dateAdded)
                assertEquals(emptyList<Long>(), Injekt.get<GetCategories>().await(manga.id).filterNot { it.isSystemCategory }.map { it.id })
                assertEquals(before, eventCount())
            } else {
                val first = async { model.controller.confirmCategory(manga, listOf(category.id)) }
                val repeated = async { model.controller.confirmCategory(manga, listOf(category.id)) }
                first.await()
                repeated.await()
                val after = eventCount()
                assertEquals(before + 1, after)
                assertEquals(favoriteBefore + 1, journal.pendingEvents("history-favorite", 1).count { it.category == SyncCategory.FAVORITE })
                model.controller.confirmCategory(manga, listOf(category.id))
                assertEquals(after, eventCount())
                assertTrue(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                assertEquals(listOf(category.id), Injekt.get<GetCategories>().await(manga.id).map { it.id })
            }
        } finally {
            model.onDispose()
            context.closeAndJoin()
        }
    }
}
