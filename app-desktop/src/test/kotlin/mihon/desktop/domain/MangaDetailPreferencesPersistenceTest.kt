package mihon.desktop.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.MangaDownloadDeletion
import tachiyomi.domain.download.service.MangaDownloadDeletionSettings
import tachiyomi.domain.download.service.removeBookmarkedForReader
import tachiyomi.domain.manga.model.MangaDetailAction
import tachiyomi.domain.manga.model.MangaDetailLayout
import tachiyomi.domain.manga.service.MangaDetailPreferences

class MangaDetailPreferencesPersistenceTest {
    @Test
    fun `reader bookmark permission cannot mix settings from before and after a save`() {
        val backend = DetailActionTestPreferences()
        val prefs = DownloadPreferences(DesktopPreferenceStore(backend))
        prefs.removeBookmarkedChapters().set(false)
        prefs.mangaDeletionSettings(7).set(MangaDownloadDeletionSettings(MangaDownloadDeletion.ENABLED))
        val key = prefs.mangaDeletionSettings(7).key()
        backend.afterRead = { readKey ->
            if (readKey == key) {
                backend.afterRead = null
                backend.put(key, "INHERIT|0|true")
            }
        }
        assertEquals(false, prefs.removeBookmarkedForReader(7))
    }

    @Test
    fun `reader options persist atomically and legacy mode updates preserve both options`() {
        val backend = DetailActionTestPreferences()
        val store = DesktopPreferenceStore(backend)
        val prefs = DownloadPreferences(store)
        backend.put(prefs.mangaDeletion(7).key(), "ENABLED")
        assertEquals(MangaDownloadDeletionSettings(MangaDownloadDeletion.ENABLED), prefs.mangaDeletionSettings(7).get())
        val selected = MangaDownloadDeletionSettings(MangaDownloadDeletion.ENABLED, 3, true)
        prefs.mangaDeletionSettings(7).set(selected)
        val reopened = DownloadPreferences(DesktopPreferenceStore(backend))
        assertEquals(selected, reopened.mangaDeletionSettings(7).get())
        assertEquals(MangaDownloadDeletion.ENABLED, reopened.mangaDeletion(7).get())
        reopened.mangaDeletion(7).set(MangaDownloadDeletion.DISABLED)
        assertEquals(selected.copy(mode = MangaDownloadDeletion.DISABLED), prefs.mangaDeletionSettings(7).get())
        assertEquals(MangaDownloadDeletionSettings(), reopened.mangaDeletionSettings(8).get())
        assertTrue(Preference.isAppState(reopened.mangaDeletionSettings(7).key()))
    }

    @Test
    fun `membership and order persist together without moving collection to first`() {
        val backend = DetailActionTestPreferences()
        val preferences = MangaDetailPreferences(DesktopPreferenceStore(backend))
        val primary = listOf(MangaDetailAction.TRACKING, MangaDetailAction.LIBRARY)
        val order = listOf(MangaDetailAction.TRACKING, MangaDetailAction.WEB, MangaDetailAction.LIBRARY)
        preferences.layout().set(MangaDetailLayout(primary, order))
        val reopened = MangaDetailPreferences(DesktopPreferenceStore(backend))
        assertEquals(primary, reopened.layout().get().primary)
        assertEquals(primary, reopened.primaryActions().get())
        assertEquals(order, reopened.layout().get().order.take(3))
    }

    @Test
    fun `layout persists across store recreation and sanitizes unknown repeated and oversized values`() {
        val backend = DetailActionTestPreferences()
        val first = DesktopPreferenceStore(backend)
        MangaDetailPreferences(first).primaryActions().set(listOf(MangaDetailAction.LIBRARY))
        val reopened = DesktopPreferenceStore(backend)
        assertEquals(listOf(MangaDetailAction.LIBRARY), MangaDetailPreferences(reopened).primaryActions().get())
        first.getString("manga_detail_primary_actions").set("UNKNOWN,WEB,WEB,TRACKING,SHARE,COPY_LINK")
        assertEquals(
            listOf(
                MangaDetailAction.LIBRARY,
                MangaDetailAction.WEB,
                MangaDetailAction.TRACKING,
                MangaDetailAction.SHARE,
            ),
            MangaDetailPreferences(reopened).primaryActions().get(),
        )
    }

    @Test
    fun `work deletion is local state and persists without leaking to another work`() {
        val backend = DetailActionTestPreferences()
        val downloads = DownloadPreferences(DesktopPreferenceStore(backend))
        downloads.mangaDeletion(7).set(MangaDownloadDeletion.ENABLED)
        val reopened = DownloadPreferences(DesktopPreferenceStore(backend))
        assertEquals(MangaDownloadDeletion.ENABLED, reopened.mangaDeletion(7).get())
        assertEquals(MangaDownloadDeletion.INHERIT, reopened.mangaDeletion(8).get())
        assertTrue(Preference.isAppState(reopened.mangaDeletion(7).key()))
        backend.put(reopened.mangaDeletion(7).key(), "UNKNOWN_FUTURE_VALUE")
        assertEquals(MangaDownloadDeletion.INHERIT, reopened.mangaDeletion(7).get())
    }
}
