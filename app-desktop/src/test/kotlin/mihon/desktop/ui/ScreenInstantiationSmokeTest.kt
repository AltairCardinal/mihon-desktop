package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.authors.AuthorDetailScreen
import mihon.desktop.ui.authors.AuthorsRootScreen
import mihon.desktop.ui.authors.AuthorsTab
import mihon.desktop.ui.authors.WorkCompareScreen
import mihon.desktop.ui.browse.LocalChapterScreen
import mihon.desktop.ui.browse.LocalMangaBrowseScreen
import mihon.desktop.ui.browse.LocalSourceSettingsScreen
import mihon.desktop.ui.browse.SourceBrowseScreen
import mihon.desktop.ui.download.DownloadQueueScreen
import mihon.desktop.ui.extension.ExtensionListScreen
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import mihon.desktop.ui.extension.SourcePreferencesScreen
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.home.HomeScreen
import mihon.desktop.ui.library.LibraryRootScreen
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.migration.MigrationMangaScreen
import mihon.desktop.ui.migration.MigrationSearchScreen
import mihon.desktop.ui.migration.MigrationSourceScreen
import mihon.desktop.ui.migration.MigrationBatchQueueScreen
import mihon.desktop.ui.tracking.TrackingSettingsScreen
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.more.StatsScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import mihon.desktop.ui.settings.AboutScreen
import mihon.desktop.ui.settings.AdvancedSettingsScreen
import mihon.desktop.ui.settings.AppearanceSettingsScreen
import mihon.desktop.ui.settings.BackupSettingsScreen
import mihon.desktop.ui.settings.DownloadSettingsScreen
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.settings.LibrarySettingsScreen
import mihon.desktop.ui.settings.MoreRootScreen
import mihon.desktop.ui.settings.ReaderSettingsScreen
import mihon.desktop.ui.updates.UpcomingScreen
import mihon.desktop.ui.updates.UpdatesTab
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Stage 24.1 — Screen instantiation smoke tests.
 *
 * Every Voyager Screen/Tab must be constructable on the JVM without throwing.
 * Also asserts correct interface (Screen vs Tab) to catch ClassCastException bugs
 * that would only surface at runtime navigation.
 */
class ScreenInstantiationSmokeTest {

    // ── Authors ─────────────────────────────────────────────────────────────

    @Test fun `AuthorsTab is Tab`() {
        assert(AuthorsTab is Tab)
    }

    @Test fun `AuthorsRootScreen is Screen not Tab`() {
        val s = AuthorsRootScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `AuthorDetailScreen is Screen not Tab`() {
        val s = AuthorDetailScreen(creatorId = 1L)
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `WorkCompareScreen is Screen not Tab`() {
        val s = WorkCompareScreen(workId = 1L)
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Browse ──────────────────────────────────────────────────────────────

    @Test fun `SourceBrowseScreen is Screen`() {
        val s = SourceBrowseScreen(sourceId = 1L)
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `GlobalSearchScreen is Screen`() {
        val s = GlobalSearchScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `GlobalSearchScreen accepts initial query`() {
        val s = GlobalSearchScreen(initialQuery = "naruto")
        assertNotNull(s)
    }

    @Test fun `LocalMangaBrowseScreen is Screen`() {
        val s = LocalMangaBrowseScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `LocalChapterScreen is Screen`() {
        val s = LocalChapterScreen(mangaDirPath = "/tmp/manga", mangaName = "My Manga")
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `LocalSourceSettingsScreen is Screen`() {
        val s = LocalSourceSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Download ────────────────────────────────────────────────────────────

    @Test fun `DownloadQueueScreen is Screen`() {
        val s = DownloadQueueScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Extensions ──────────────────────────────────────────────────────────

    @Test fun `ExtensionListScreen is Screen`() {
        val s = ExtensionListScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `ExtensionDetailsScreen is Screen`() {
        val s = ExtensionDetailsScreen(jarPath = "C:/Mihon/extensions/example.jar")
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `SourcePreferencesScreen is Screen`() {
        val s = SourcePreferencesScreen(sourceId = 99L, sourceName = "Fake Source")
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Home ────────────────────────────────────────────────────────────────

    @Test fun `HomeScreen is Screen`() {
        val s = HomeScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Library ─────────────────────────────────────────────────────────────

    @Test fun `LibraryTab is Tab`() {
        assert(LibraryTab is Tab)
    }

    @Test fun `LibraryRootScreen is Screen not Tab`() {
        val s = LibraryRootScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `MangaDetailScreen is Screen not Tab`() {
        val s = MangaDetailScreen(mangaId = 42L)
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Migration ───────────────────────────────────────────────────────────

    @Test fun `MigrationSourceScreen is Screen`() {
        val s = MigrationSourceScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `MigrationMangaScreen is Screen`() {
        val s = MigrationMangaScreen(sourceId = 1L, sourceName = "Old Source")
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `MigrationSearchScreen is Screen`() {
        val s = MigrationSearchScreen(sourceMangaId = 1L, sourceMangaTitle = "Test", batchQueueId = "batch-1")
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `TrackingSettingsScreen is Screen not Tab`() {
        val settings = TrackingSettingsScreen()
        val manga = TrackingSettingsScreen(mangaId = 42L, mangaTitle = "Manga", totalChapters = 12)
        assert(settings is Screen)
        assert(settings !is Tab)
        assert(manga is Screen)
    }

    @Test fun `MigrationBatchQueueScreen is Screen`() {
        val screen = MigrationBatchQueueScreen("batch-1")
        assert(screen is Screen)
        assert(screen !is Tab)
    }

    // ── More ────────────────────────────────────────────────────────────────

    @Test fun `MoreTab is Tab`() {
        assert(MoreTab is Tab)
    }

    @Test fun `StatsScreen is Screen`() {
        val s = StatsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Reader ──────────────────────────────────────────────────────────────

    @Test fun `DesktopReaderScreen is Screen with minimal params`() {
        val s = DesktopReaderScreen(chapterTitle = "Chapter 1")
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `DesktopReaderScreen is Screen with full params`() {
        val chapters = listOf(
            ReaderChapterRef(id = 1L, url = "/ch/1", name = "Chapter 1"),
            ReaderChapterRef(id = 2L, url = "/ch/2", name = "Chapter 2"),
        )
        val s = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            mangaTitle = "Test Manga",
            pageUrls = listOf("https://example.com/p1.jpg", "https://example.com/p2.jpg"),
            isWebtoon = false,
            sourceId = 1L,
            chapterUrl = "/ch/1",
            chapterId = 1L,
            chapters = chapters,
            currentChapterIndex = 0,
            initialPage = 0,
            mangaViewerFlags = 0L,
            isRtl = false,
            isDualPage = false,
            progressTracker = null,
        )
        assert(s is Screen)
    }

    // ── Settings ────────────────────────────────────────────────────────────

    @Test fun `AboutScreen is Screen`() {
        val s = AboutScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `AppearanceSettingsScreen is Screen`() {
        val s = AppearanceSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `AdvancedSettingsScreen is Screen`() {
        val s = AdvancedSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `BackupSettingsScreen is Screen`() {
        val s = BackupSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `DownloadSettingsScreen is Screen`() {
        val s = DownloadSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `ExtensionRepoScreen is Screen`() {
        val s = ExtensionRepoScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `GeneralSettingsScreen is Screen`() {
        val s = GeneralSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `LibrarySettingsScreen is Screen`() {
        val s = LibrarySettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `MoreRootScreen is Screen not Tab`() {
        val s = MoreRootScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    @Test fun `ReaderSettingsScreen is Screen`() {
        val s = ReaderSettingsScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── Updates ─────────────────────────────────────────────────────────────

    @Test fun `UpdatesTab is Tab`() {
        assert(UpdatesTab is Tab)
    }

    @Test fun `UpcomingScreen is Screen`() {
        val s = UpcomingScreen()
        assert(s is Screen)
        assert(s !is Tab)
    }

    // ── History ─────────────────────────────────────────────────────────────

    @Test fun `HistoryTab is Tab`() {
        assert(HistoryTab is Tab)
    }
}
