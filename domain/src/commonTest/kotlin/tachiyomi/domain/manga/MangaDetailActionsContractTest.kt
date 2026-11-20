package tachiyomi.domain.manga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.download.service.MangaDownloadDeletion
import tachiyomi.domain.download.service.MangaDownloadDeletionSettings
import tachiyomi.domain.manga.model.MangaDetailAction

class MangaDetailActionsContractTest {
    @Test
    fun `reader override keeps selected retention and malformed permission cannot enable bookmark deletion`() {
        for (slots in 0..4) {
            val setting = MangaDownloadDeletionSettings.decode("ENABLED|$slots|true")
            assertEquals(slots, setting.mode.resolveSlots(-1, setting.slots))
            assertEquals(true, setting.removeBookmarked)
        }
        assertEquals(false, MangaDownloadDeletionSettings.decode("ENABLED|2|unknown").removeBookmarked)
        assertEquals(MangaDownloadDeletionSettings(), MangaDownloadDeletionSettings.decode("UNKNOWN|2|true"))
        assertEquals(-1, MangaDownloadDeletion.DISABLED.resolveSlots(3, 2))
        assertEquals(3, MangaDownloadDeletion.INHERIT.resolveSlots(3, 2))
    }

    @Test
    fun `selected collection retains its chosen position`() {
        val selected = listOf(MangaDetailAction.WEB, MangaDetailAction.LIBRARY, MangaDetailAction.TRACKING)
        assertEquals(selected, MangaDetailAction.normalize(selected))
    }

    @Test
    fun `empty selection keeps only mandatory library action`() {
        assertEquals(listOf(MangaDetailAction.LIBRARY), MangaDetailAction.normalize(emptyList()))
    }

    @Test
    fun `duplicates and oversized selections retain library within the four button limit`() {
        val selected = MangaDetailAction.normalize(MangaDetailAction.entries.reversed() + MangaDetailAction.WEB)
        assertEquals(MangaDetailAction.LIBRARY, selected.last())
        assertEquals(4, selected.size)
        assertEquals(selected.distinct(), selected)
    }

    @Test
    fun `work deletion overrides both global states and inherits exact retention count`() {
        for (global in listOf(-1, 0, 1, 5)) {
            assertEquals(global, MangaDownloadDeletion.INHERIT.resolveSlots(global))
            assertEquals(0, MangaDownloadDeletion.ENABLED.resolveSlots(global))
            assertEquals(-1, MangaDownloadDeletion.DISABLED.resolveSlots(global))
        }
    }
}
