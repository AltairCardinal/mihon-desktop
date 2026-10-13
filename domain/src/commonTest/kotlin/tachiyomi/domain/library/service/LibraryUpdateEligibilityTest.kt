package tachiyomi.domain.library.service

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryUpdateEligibilityTest {
    @Test
    fun `manual override filters only selected known rules while automatic and source limits stay intact`() {
        val key = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD
        val restrictions = setOf(key, LibraryPreferences.MANGA_HAS_UNREAD, "source_protection")
        assertEquals(
            restrictions - key,
            effectiveLibraryUpdateRestrictions(restrictions, setOf(key, "source_protection"), true),
        )
        assertEquals(restrictions, effectiveLibraryUpdateRestrictions(restrictions, setOf(key), false))
    }

    @Test
    fun `smart draft write failures restore both values and original unset state`() {
        for (failure in 1..2) {
            for (initiallySet in listOf(false, true)) {
                val sets = mutableMapOf<String, Preference<Set<String>>>()
                val actual = object : PreferenceStore by InMemoryPreferenceStore() {
                    override fun getStringSet(key: String, defaultValue: Set<String>) = sets.getOrPut(key) {
                        InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
                    }
                }
                var writes = 0
                val store = object : PreferenceStore by actual {
                    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                        val preference = actual.getStringSet(key, defaultValue)
                        return object : Preference<Set<String>> by preference {
                            override fun set(value: Set<String>) {
                                preference.set(value)
                                writes++
                                if (writes == failure) error("write failed after storing")
                            }
                        }
                    }
                }
                val original = LibraryPreferences(actual)
                if (initiallySet) {
                    original.autoUpdateMangaRestrictions().set(setOf(LibraryPreferences.MANGA_HAS_UNREAD))
                    original.manualUpdateMangaRestrictionOverrides().set(emptySet())
                }
                val before = original.autoUpdateMangaRestrictions().get()
                assertEquals(
                    false,
                    LibraryPreferences(
                        store,
                    ).saveSmartUpdateSettings(emptySet(), setOf(LibraryPreferences.MANGA_HAS_UNREAD)),
                )
                assertEquals(before, original.autoUpdateMangaRestrictions().get())
                assertEquals(emptySet<String>(), original.manualUpdateMangaRestrictionOverrides().get())
                assertEquals(initiallySet, original.autoUpdateMangaRestrictions().isSet())
                assertEquals(initiallySet, original.manualUpdateMangaRestrictionOverrides().isSet())
            }
        }
    }

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
