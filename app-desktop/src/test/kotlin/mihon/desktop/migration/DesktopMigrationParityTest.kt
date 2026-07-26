package mihon.desktop.migration

import mihon.domain.migration.MigrationChapter
import mihon.domain.migration.MigrationOrchestrator
import mihon.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `desktop migration adapter contains no duplicate chapter business rules`() {
        val source = Files.readString(Path.of("src/main/kotlin/mihon/desktop/domain/DesktopMigrateMangaUseCase.kt"))
        assertTrue(source.contains("MigrationOrchestrator"))
        assertFalse(source.contains("fun buildReadChapterNumbers"))
        assertFalse(source.contains("fun shouldMarkRead"))
    }

    @Test
    fun `desktop product work comparison remains protected`() {
        val authors = Files.readString(Path.of("src/main/kotlin/mihon/desktop/ui/authors/AuthorsTab.kt"))
        assertTrue(authors.contains("data class WorkCompareScreen"))
    }
}
