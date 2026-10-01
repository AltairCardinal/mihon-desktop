package mihon.desktop.ui.authors

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.CopyOnWriteArrayList

class AuthorsScreenModelsTest {
    @Test
    fun `root screen identity preserves one activation and separates reentry`() {
        val first: Screen = AuthorsRootScreen("first-entry")
        val sameEntry: Screen = AuthorsRootScreen("first-entry")
        val nextEntry: Screen = AuthorsRootScreen("next-entry")
        assertEquals(first.key, sameEntry.key, "Returning to the same root must retain its navigation identity")
        assertNotEquals(first.key, nextEntry.key, "A new tab activation must use a fresh screen model and saveable identity")
    }

    @Test
    fun `scope switch publishes cards and loading for the same scope atomically`() = runBlocking {
        val handler = createDatabaseHandler()
        val repository = CreatorRepositoryImpl(handler)
        val followed = repository.upsertCreator("Followed author")
        repository.followCreator(followed.id)
        repository.upsertCreator("Unfollowed author")
        val indexer = CreatorLibraryIndexer(
            object : CreatorLibraryMangaSource {
                override suspend fun countLibraryMangaForCreatorIndex(): Long = 0
                override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long) = emptyList<Manga>()
            },
            NoopCreatorLibraryIndexWriter,
            ExtractCreatorsFromManga(),
        )
        val model = AuthorsRootScreenModel(GetCreators(repository), CreatorArchive(repository, repository), indexer)
        val emissions = CopyOnWriteArrayList<AuthorsRootState>()
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            model.state.collect { emissions += it }
        }
        try {
            withTimeout(5_000) { model.state.first { !it.loading && it.cards.size == 1 } }
            emissions.clear()
            model.showAllAuthors()
            withTimeout(5_000) { model.state.first { !it.loading && !it.followedOnly && it.cards.size == 2 } }
            assertTrue(emissions.none { !it.followedOnly && it.cards.size == 1 }, "All must never expose the preceding followed cards: $emissions")
            emissions.clear()
            model.showFollowing()
            withTimeout(5_000) { model.state.first { !it.loading && it.followedOnly && it.cards.size == 1 } }
            assertTrue(emissions.none { it.followedOnly && it.cards.size == 2 }, "Following must never expose the preceding All cards: $emissions")
        } finally {
            observer.cancel()
            observer.join()
            model.onDispose()
            indexer.stop()
            handler.close()
        }
    }

    @Test
    fun `unknown chapter count is omitted while a complete zero count is retained`() {
        val unknown = version(ChapterCatalogCompleteness.UNKNOWN)
        val complete = version(ChapterCatalogCompleteness.COMPLETE)

        assertEquals(null, chapterCountForWorkMatching(unknown))
        assertEquals(0, chapterCountForWorkMatching(complete))
    }

    private fun createDatabaseHandler(): JvmDatabaseHandler {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        return JvmDatabaseHandler(
            Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
    }

    private fun version(completeness: ChapterCatalogCompleteness) = SourceWorkArchiveVersion(
        sourceWorkId = 1L,
        naturalKey = SourceWorkNaturalKey(sourceId = 1L, stableSourceUrl = "/work"),
        mangaId = null,
        title = "Work",
        readingLanguage = LanguageProjectionContract(
            dimension = LanguageDimension.READING,
            tag = "und",
            certainty = LanguageCertainty.UNKNOWN,
            evidenceKind = LanguageEvidenceKind.UNKNOWN,
        ),
        chapterCount = 0L,
        inLibrary = false,
        detailsFetchedAt = null,
        lastSeenAt = 1L,
        decision = null,
        chapterCompleteness = completeness,
    )
}
