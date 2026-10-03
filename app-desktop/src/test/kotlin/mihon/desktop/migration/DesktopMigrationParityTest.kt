package mihon.desktop.migration

import mihon.domain.migration.MigrationChapter
import mihon.domain.migration.MigrationOrchestrator
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.nio.file.Path

class DesktopMigrationParityTest {
    @Test
    fun `desktop uses shared Android migration options and chapter semantics`() {
        assertEquals(
            MigrationFlag.entries.toSet(),
            MigrationFlag.fromBit(MigrationFlag.toBit(MigrationFlag.entries.toSet())),
        )
        val updates = MigrationOrchestrator().chapterUpdates(
            listOf(MigrationChapter(1, 3.0, read = true, bookmark = true, dateFetch = 9)),
            listOf(MigrationChapter(2, 2.0), MigrationChapter(3, 3.0)),
        )
        assertEquals(true, updates[0].read)
        assertEquals(true, updates[1].read)
        assertEquals(true, updates[1].bookmark)
        assertEquals(9, updates[1].dateFetch)
    }

    @Test
    fun `desktop migration adapter contains no duplicate chapter business rules`(
        @org.junit.jupiter.api.io.TempDir root: java.io.File,
    ) = kotlinx.coroutines.runBlocking<Unit> {
        val context = mihon.desktop.di.initDesktopDIForTest(
            root,
            mihon.desktop.di.isolatedDesktopPreferenceStore(),
            startDownloadWorker = false,
        )
        try {
            val mangas = uy.kohesive.injekt.Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
            val chapters = uy.kohesive.injekt.Injekt.get<tachiyomi.domain.chapter.repository.ChapterRepository>()
            val source = mangas.insertNetworkManga(
                listOf(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = 1,
                        url = "/original",
                        title = "Original",
                        favorite = true,
                    ),
                ),
            ).single()
            chapters.addAll(
                listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        mangaId = source.id,
                        url = "/old/3",
                        name = "Chapter 3",
                        chapterNumber = 3.0,
                        read = true,
                        bookmark = true,
                        dateFetch = 9,
                    ),
                ),
            )
            val target = uy.kohesive.injekt.Injekt.get<mihon.desktop.domain.DesktopMigrateMangaUseCase>().await(
                source,
                eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    url = "/independent"
                    title = "Target"
                },
                2,
                listOf(2f, 3f).map { number ->
                    eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                        url = "/new/$number"
                        name = "Chapter $number"
                        chapter_number = number
                    }
                },
                mihon.desktop.domain.MigrationOptions(copyCategories = false, copyNotes = false),
                replace = false,
            )
            val migrated = chapters.getChapterByMangaId(target.id, false).sortedBy { it.chapterNumber }
            assertTrue(target.id != source.id)
            assertEquals(listOf(2.0, 3.0), migrated.map { it.chapterNumber })
            assertTrue(migrated.all { it.read })
            assertTrue(migrated.last().bookmark)
            assertEquals(9L, migrated.last().dateFetch)
            assertTrue(mangas.getMangaById(source.id).favorite)
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `desktop product work comparison remains protected`() {
        val authors = Files.readString(Path.of("src/main/kotlin/mihon/desktop/ui/authors/AuthorsTab.kt"))
        assertTrue(authors.contains("data class WorkCompareScreen"))
    }
}
