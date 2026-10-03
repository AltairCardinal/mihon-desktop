package tachiyomi.domain.library.service

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryUpdateEligibilityTest {
    @Test
    fun `each smart restriction and zero chapter exception retain source priority`() {
        val base = LibraryManga(Manga.create().copy(source = 42, status = 1), emptyList(), 2, 1, 0, 0, 0, 0)
        assertEquals(
            LibraryUpdateSkipReason.COMPLETED,
            libraryUpdateSkipReason(
                base.copy(manga = base.manga.copy(status = 2)),
                setOf(LibraryPreferences.MANGA_NON_COMPLETED),
                100,
            ),
        )
        assertEquals(
            LibraryUpdateSkipReason.HAS_UNREAD,
            libraryUpdateSkipReason(base, setOf(LibraryPreferences.MANGA_HAS_UNREAD), 100),
        )
        assertEquals(
            LibraryUpdateSkipReason.NOT_STARTED,
            libraryUpdateSkipReason(base.copy(readCount = 0), setOf(LibraryPreferences.MANGA_NON_READ), 100),
        )
        assertEquals(
            LibraryUpdateSkipReason.OUTSIDE_RELEASE_PERIOD,
            libraryUpdateSkipReason(
                base.copy(manga = base.manga.copy(nextUpdate = 101)),
                setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD),
                100,
            ),
        )
        for (rule in listOf(
            LibraryPreferences.MANGA_NON_COMPLETED,
            LibraryPreferences.MANGA_HAS_UNREAD,
            LibraryPreferences.MANGA_NON_READ,
            LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
        )) {
            assertEquals(null, libraryUpdateSkipReason(base.copy(totalChapters = 0, readCount = 0), setOf(rule), 100))
        }
        assertEquals(
            null,
            libraryUpdateSkipReason(
                base.copy(manga = base.manga.copy(nextUpdate = 100)),
                setOf(LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD),
                100,
            ),
        )
        val all =
            setOf(
                LibraryPreferences.MANGA_NON_COMPLETED,
                LibraryPreferences.MANGA_HAS_UNREAD,
                LibraryPreferences.MANGA_NON_READ,
                LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
            )
        val simultaneous = base.copy(manga = base.manga.copy(status = 2, nextUpdate = 101), readCount = 0)
        assertEquals(LibraryUpdateSkipReason.COMPLETED, libraryUpdateSkipReason(simultaneous, all, 100))
        assertEquals(
            LibraryUpdateSkipReason.HAS_UNREAD,
            libraryUpdateSkipReason(
                simultaneous,
                all - LibraryPreferences.MANGA_NON_COMPLETED,
                100,
            ),
        )
        assertEquals(
            LibraryUpdateSkipReason.NOT_STARTED,
            libraryUpdateSkipReason(
                simultaneous,
                all - LibraryPreferences.MANGA_NON_COMPLETED - LibraryPreferences.MANGA_HAS_UNREAD,
                100,
            ),
        )
        assertEquals(null, libraryUpdateSkipReason(simultaneous, emptySet(), 100))
    }

    @Test
    fun `fetch once is unconditional only after at least one stored chapter`() {
        val manga = Manga.create().copy(source = 42, updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE)
        val entry = LibraryManga(manga, emptyList(), 1, 1, 0, 0, 0, 0)
        assertEquals(LibraryUpdateSkipReason.ONLY_FETCH_ONCE, libraryUpdateSkipReason(entry, emptySet(), 100))
        assertEquals(null, libraryUpdateSkipReason(entry.copy(totalChapters = 0, readCount = 0), emptySet(), 100))
    }
}
