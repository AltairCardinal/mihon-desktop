package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.domain.migration.MigrationCommit
import mihon.domain.migration.MigrationReceipt
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class DesktopMigrateMangaUseCaseChapterAdapterTest {
    @Test
    fun `Desktop production adapter delegates chapter state to the atomic migration repository`() = runTest {
        val mangas = spyk(FakeMangaRepository())
        val chapters = FakeChapterRepository()
        val source = Manga.create().copy(id = 10, source = 1, url = "/source", title = "Source", favorite = true)
        mangas.seed(source)
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
            SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters, directoryCommit = { _, _ ->
                tachiyomi.domain.chapter.service.ChapterDirectoryResult(
                    tachiyomi.domain.chapter.service.ChapterDirectoryPlan(
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptySet(),
                    ),
                    emptyList(),
                )
            }),
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
        assertTrue(chapters.updates.isEmpty(), "No retired standalone chapter writes can bypass the atomic repository")
        assertEquals(source, mangas.getMangaById(source.id))
    }
}
