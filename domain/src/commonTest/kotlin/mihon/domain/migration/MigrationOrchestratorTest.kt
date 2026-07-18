package mihon.domain.migration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationOrchestratorTest {
    @Test
    fun `current shared migration flag bits round trip`() {
        val flags = MigrationFlag.entries.toSet()

        assertEquals(flags, MigrationFlag.fromBit(MigrationFlag.toBit(flags)))
        assertEquals(0, MigrationFlag.toBit(emptySet()))
    }

    @Test
    fun `chapter plan copies exact metadata and marks every recognized chapter through max read`() {
        val source = listOf(
            MigrationChapter(1, 1.0, read = true, bookmark = true, dateFetch = 10),
            MigrationChapter(2, 2.0, read = false, bookmark = false, dateFetch = 20),
            MigrationChapter(3, -1.0, read = true, bookmark = true, dateFetch = 30),
        )
        val target = listOf(
            MigrationChapter(11, 1.0),
            MigrationChapter(12, 1.5),
            MigrationChapter(13, 2.0),
            MigrationChapter(14, -1.0),
        )

        val result = MigrationOrchestrator().chapterUpdates(source, target)

        assertEquals(MigrationChapterUpdate(11, read = true, bookmark = true, dateFetch = 10), result[0])
        assertEquals(MigrationChapterUpdate(12), result[1])
        assertEquals(MigrationChapterUpdate(13, bookmark = false, dateFetch = 20), result[2])
        assertEquals(null, result[3].read)
        assertEquals(null, result[3].dateFetch)
    }

    @Test
    fun `chapter plan never clears target read state beyond source progress or for unknown numbers`() {
        val source = listOf(MigrationChapter(1, 2.0, read = true))
        val target = listOf(
            MigrationChapter(11, 3.0, read = true),
            MigrationChapter(12, Double.NaN, read = true),
            MigrationChapter(13, 1.0, read = false),
        )

        val result = MigrationOrchestrator().chapterUpdates(source, target)

        assertEquals(null, result[0].read)
        assertEquals(null, result[1].read)
        assertEquals(true, result[2].read)
    }

    @Test
    fun `source read NaN preserves every target read state like fixed original Mihon`() {
        val source = listOf(
            MigrationChapter(1, 2.0, read = true),
            MigrationChapter(2, Double.NaN, read = true),
        )
        val target = listOf(
            MigrationChapter(11, 1.0, read = false),
            MigrationChapter(12, 2.0, read = false),
            MigrationChapter(13, 3.0, read = true),
            MigrationChapter(14, Double.NaN, read = true),
        )

        val result = MigrationOrchestrator().chapterUpdates(source, target)

        assertEquals(listOf(null, null, null, null), result.map { it.read })
    }

    @Test
    fun `duplicate chapter numbers copy metadata from the first fixed original Mihon match`() {
        val source = listOf(
            MigrationChapter(1, 1.0, bookmark = true, dateFetch = 10),
            MigrationChapter(2, 1.0, bookmark = false, dateFetch = 20),
        )
        val target = listOf(MigrationChapter(11, 1.0))

        val update = MigrationOrchestrator().chapterUpdates(source, target).single()

        assertEquals(true, update.bookmark)
        assertEquals(10, update.dateFetch)
    }

    @Test
    fun `library plan copies categories notes reading flags and keeps source for copy`() {
        val plan = MigrationOrchestrator().libraryPlan(
            current = MigrationMangaMetadata(
                mangaId = 1,
                categoryIds = listOf(4, 4, 5),
                chapterFlags = 7,
                viewerFlags = 8,
                dateAdded = 9,
                notes = "note",
            ),
            targetMangaId = 2,
            flags = setOf(MigrationFlag.CATEGORY, MigrationFlag.NOTES),
            replace = false,
            now = 100,
        )

        assertEquals(listOf(4L, 5L), plan.targetCategoryIds)
        assertEquals("note", plan.targetNotes)
        assertEquals(7, plan.targetChapterFlags)
        assertEquals(8, plan.targetViewerFlags)
        assertEquals(100, plan.targetDateAdded)
        assertFalse(plan.removeCurrentFromLibrary)
    }

    @Test
    fun `batch continues after item failure and exposes resume checkpoint`() = runTest {
        val events = mutableListOf<BatchMigrationEvent<Int>>()

        BatchMigrationOrchestrator<Int>().run(listOf(1, 2, 3), startIndex = 0) { item ->
            if (item == 2) error("unreachable")
            item * 10
        }.collect(events::add)

        assertEquals(
            listOf(
                BatchMigrationEvent.Succeeded(0, 1, 10),
                BatchMigrationEvent.Failed(1, 2, "unreachable"),
                BatchMigrationEvent.Succeeded(2, 3, 30),
                BatchMigrationEvent.Completed(nextIndex = 3),
            ),
            events,
        )
    }

    @Test
    fun `batch resumes at checkpoint without repeating completed items`() = runTest {
        val processed = mutableListOf<Int>()

        BatchMigrationOrchestrator<Int>().run(listOf(1, 2, 3), startIndex = 2) { item ->
            processed += item
            item
        }.collect {}

        assertEquals(listOf(3), processed)
    }

    @Test
    fun `batch cancellation propagates and does not become item failure`() {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                BatchMigrationOrchestrator<Int>().run(listOf(1)) {
                    throw CancellationException("cancel")
                }.collect {}
            }
        }
    }

    @Test
    fun `batch waits for user without advancing durable checkpoint`() = runTest {
        val events = mutableListOf<BatchMigrationEvent<Int>>()

        BatchMigrationOrchestrator<Int>().run(listOf(10, 20, 30)) { item ->
            if (item == 20) throw BatchMigrationWaitingForUserException()
            item
        }.collect(events::add)

        assertEquals(
            listOf(
                BatchMigrationEvent.Succeeded(0, 10, 10),
                BatchMigrationEvent.WaitingForUser(1, 20),
                BatchMigrationEvent.Completed(1),
            ),
            events,
        )
    }
}
