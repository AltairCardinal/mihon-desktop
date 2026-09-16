package eu.kanade.tachiyomi.extension

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.runBlocking
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.backup.BackupCodec
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** Fixed old serializer output, actual Android restore/create and default DI/database. */
class HistoricalBackupRestoreInstrumentationTest {
    @Test
    fun historicalBackupRestoresWithoutItsSourceAndExportsPreservedLibraryState() = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val categories = Injekt.get<CategoryRepository>()
        val repositories = Injekt.get<ExtensionRepoRepository>()
        val handler = Injekt.get<DatabaseHandler>()
        val preferences = Injekt.get<PreferenceStore>()
        val theme = preferences.getString("theme")
        val themeWasSet = theme.isSet()
        val previousTheme = theme.get()
        val categorized = preferences.getBoolean("categorized_display")
        val categorizedWasSet = categorized.isSet()
        val previousCategorized = categorized.get()
        val sourcePrefs = context.getSharedPreferences("101", Context.MODE_PRIVATE)
        check(!sourcePrefs.contains("quality"))
        check(mangas.getMangaByUrlAndSourceId("/manga", 101) == null)
        check(categories.getAll().none { it.name == "Category" })
        check(repositories.getRepo("https://repo") == null)
        val input = File.createTempFile("aex05-historical-", ".tachibk", context.cacheDir)
        val output = File.createTempFile("aex05-export-", ".tachibk", context.cacheDir)
        try {
            val bytes = instrumentation.context.assets.open("android-full.tachibk").use { it.readBytes() }
            assertEquals(
                "f8ddfe8bea24ff9d428ce06058beef8194144542c8774b6ab25493528acd89a8",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            )
            input.writeBytes(bytes)
            BackupRestorer(context, BackupNotifier(context), false).restore(Uri.fromFile(input), RestoreOptions())
            val manga = requireNotNull(mangas.getMangaByUrlAndSourceId("/manga", 101))
            assertEquals("Canonical manga", manga.title)
            assertEquals("Notes", manga.notes)
            assertTrue(manga.favorite)
            assertTrue(manga.memo.isEmpty())
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            assertTrue(chapter.read)
            assertTrue(chapter.bookmark)
            assertEquals(7L, chapter.lastPageRead)
            assertTrue(chapter.memo.isEmpty())
            assertEquals("Category", categories.getCategoriesByMangaId(manga.id).single().name)
            assertEquals("dark", theme.get())
            assertEquals(3, sourcePrefs.getInt("quality", -1))
            assertEquals("fingerprint", requireNotNull(repositories.getRepo("https://repo")).signingKeyFingerprint)

            BackupCreator(context, false).backup(
                Uri.fromFile(output),
                BackupOptions(appSettings = false, sourceSettings = false, readEntries = false),
            )
            val exported = BackupCodec.decode(Backup.serializer(), output.readBytes())
            val restored = exported.backupManga.single { it.source == 101L && it.url == "/manga" }
            assertEquals("Notes", restored.notes)
            assertEquals(7L, restored.chapters.single().lastPageRead)
            assertTrue(restored.chapters.single().read)
            assertTrue(restored.chapters.single().bookmark)
            assertTrue(exported.backupCategories.any { it.name == "Category" })
            assertTrue(exported.backupExtensionRepo.any { it.baseUrl == "https://repo" })
        } finally {
            // Only the fixture's preflight-absent records/keys are owned by this test.
            handler.await(inTransaction = true) {
                Injekt.get<SqlDriver>().execute(null, "DELETE FROM mangas WHERE source = 101 AND url = '/manga'", 0)
                categoriesQueries.getCategories().executeAsList().filter { it.name == "Category" }
                    .forEach { categoriesQueries.delete(it.id) }
            }
            repositories.deleteRepo("https://repo")
            sourcePrefs.edit().remove("quality").commit()
            if (themeWasSet) theme.set(previousTheme) else theme.delete()
            if (categorizedWasSet) categorized.set(previousCategorized) else categorized.delete()
            input.delete()
            output.delete()
        }
    }
}
