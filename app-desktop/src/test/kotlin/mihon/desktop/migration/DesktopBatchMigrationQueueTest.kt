package mihon.desktop.migration

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DesktopBatchMigrationQueueTest {
    @Test
    fun `batch queue screen delegates state to persistent controller`() {
        val source = Files.readString(Path.of("src/main/kotlin/mihon/desktop/ui/migration/MigrationBatchQueueScreen.kt"))

        assertFalse(source.contains("MigrationBatchQueueState"))
        assertTrue(source.contains("BatchMigrationItemStatus.WAITING_FOR_USER"))
        assertTrue(source.contains("MigrationSearchScreen(item.mangaId, item.title, queueId)"))
        assertTrue(source.contains("batchMigrationController"))
    }
}
