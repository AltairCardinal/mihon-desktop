package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.MigrationCommit
import mihon.domain.migration.MigrationReceipt
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class DesktopMigrateMangaUseCaseChapterAdapterTest {
    private lateinit var storage: DirectorySqlFixture

    @org.junit.jupiter.api.BeforeEach fun openStorage() {
        storage = DirectorySqlFixture()
    }

    @org.junit.jupiter.api.AfterEach fun closeStorage() {
        storage.close()
    }

    @Test
    fun `Desktop production adapter delegates chapter state to the atomic migration repository`() = runTest {
        val mangas = spyk(storage.mangas)
        var directoryCommits = 0
        var standaloneWrites = 0
        val chapters = object : tachiyomi.domain.chapter.repository.ChapterRepository by storage.chapters {
            override suspend fun syncDirectory(
                request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit,
            ): tachiyomi.domain.chapter.service.ChapterDirectoryResult {
                directoryCommits++
                return storage.chapters.syncDirectory(request)
            }

            override suspend fun update(chapterUpdate: tachiyomi.domain.chapter.model.ChapterUpdate) {
                standaloneWrites++
                storage.chapters.update(chapterUpdate)
            }

            override suspend fun updateAll(chapterUpdates: List<tachiyomi.domain.chapter.model.ChapterUpdate>) {
                standaloneWrites++
                storage.chapters.updateAll(chapterUpdates)
            }
        }
        mangas.seed(Manga.create().copy(id = 10, source = 1, url = "/source", title = "Source", favorite = true))
        val source = mangas.getMangaById(10)
        var receipt: MigrationReceipt? = null
        var accepted: MigrationCommit? = null
        coEvery { mangas.migrationReceipt(source.id) } answers { receipt }
        coEvery { mangas.prepareMigration(any()) } answers {
            MigrationReceipt(firstArg(), filesReady = true).also { receipt = it }
        }
        coEvery { mangas.commitMigration(any()) } coAnswers {
            accepted = firstArg()
            receipt = requireNotNull(receipt).copy(committed = true)
            mangas.getMangaById(requireNotNull(accepted).targetMangaId)
        }
        coEvery { mangas.completeMigrationFiles(any()) } answers
            { receipt = requireNotNull(receipt).copy(filesComplete = true) }
        coEvery { mangas.acknowledgeMigration(any()) } answers { receipt = null }
        val useCase = DesktopMigrateMangaUseCase(
            SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters),
            mangas,
        )
        val target = useCase.await(
            source,
            SManga.create().apply {
                url = "/target"
                title = "Target"
            },
            2,
            listOf(
                SChapter.create().apply {
                    url = "/1"
                    name = "One"
                    chapter_number = 1f
                },
            ),
            MigrationOptions(copyCategories = false, copyNotes = false),
            replace = false,
        )
        coVerify(exactly = 1) { mangas.commitMigration(any()) }
        assertEquals(setOf(MigrationFlag.CHAPTER), requireNotNull(accepted).flags)
        assertEquals(source.id, accepted?.sourceMangaId)
        assertEquals(target.id, accepted?.targetMangaId)
        assertTrue(target.id != source.id)
        assertEquals(1, directoryCommits)
        assertEquals(0, standaloneWrites, "No retired standalone chapter writes can bypass the atomic repository")
        assertEquals(listOf("/1"), chapters.getChapterByMangaId(target.id).map { it.url })
        assertEquals(source, mangas.getMangaById(source.id))
    }
}
