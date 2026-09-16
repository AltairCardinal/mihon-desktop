package eu.kanade.tachiyomi.extension

import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.data.backup.BackupCodec
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Explicit phases run before/after replacing ONLY the isolated application's signing identity. */
class CrossSignatureBackupInstrumentationTest {
    @Test
    fun transferProductionBackupWithoutCopyingPrivateInstallTrust() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "app.mihon.aex05.dev")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val directory = requireNotNull(context.getExternalFilesDir("aex05-migration"))
        val backupFile = File(directory, "library.tachibk")
        val archive = File(directory, "downloads.zip")
        val downloads = File(context.filesDir, "aex05-storage/downloads")
        val phase = InstrumentationRegistry.getArguments().getString("aex05MigrationPhase")
        check(phase in setOf("export", "prepare", "restore", "verify"))
        if (phase == "prepare") {
            check(!backupFile.exists())
            check(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(URL, SOURCE) == null)
            println("AEX05_CROSS_SIGNATURE_PREPARED")
            return@runBlocking
        }
        if (phase == "export") {
            check(context.getSharedPreferences("aex05-host-upgrade", 0).getBoolean("seeded", false))
            withTimeout(15_000) {
                while (Injekt.get<SourceManager>().get(SOURCE) == null) delay(20)
            }
            verifyLibrary()
            check(backupFile.createNewFile() || backupFile.isFile)
            BackupCreator(context, false).backup(
                Uri.fromFile(backupFile),
                BackupOptions(appSettings = false, readEntries = false),
            )
            val decoded = BackupCodec.decode(Backup.serializer(), backupFile.readBytes())
            assertTrue(
                decoded.backupSourcePreferences.any { entry ->
                    entry.prefs.any { it.key == "aex05_quality" }
                },
            )
            val pages = downloads.walkTopDown().filter { it.isFile && it.extension == "png" }.toList()
            check(pages.size == 1)
            ZipOutputStream(archive.outputStream()).use { zip ->
                pages.forEach { page ->
                    zip.putNextEntry(ZipEntry(page.relativeTo(downloads).invariantSeparatorsPath))
                    page.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            File(directory, "page.sha256").writeText(digest(pages.single()), Charsets.UTF_8)
            println("AEX05_CROSS_SIGNATURE_EXPORTED backup=${digest(backupFile)} downloads=${digest(archive)}")
            return@runBlocking
        }
        check(!context.getSharedPreferences("aex05-host-upgrade", 0).contains("seeded"))
        check(!File(context.filesDir, "extension-install-metadata").exists())
        assertTrue(Injekt.get<ExtensionManager>().installedExtensionsFlow.value.isEmpty())
        if (phase == "restore") {
            check(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(URL, SOURCE) == null)
            BackupRestorer(context, BackupNotifier(context), false).restore(
                Uri.fromFile(backupFile),
                RestoreOptions(appSettings = false),
            )
            check(!downloads.exists())
            check(downloads.mkdirs())
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(downloads, entry.name).canonicalFile
                    check(target.toPath().startsWith(downloads.canonicalFile.toPath()))
                    check(!entry.isDirectory && !target.exists())
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    target.outputStream().use { zip.copyTo(it) }
                    zip.closeEntry()
                }
            }
        }
        verifyLibrary()
        val pages = downloads.walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, pages.size)
        assertEquals(File(directory, "page.sha256").readText(Charsets.UTF_8), digest(pages.single()))
        assertEquals(null, Injekt.get<SourceManager>().get(SOURCE))
        println("AEX05_CROSS_SIGNATURE_OK phase=$phase sourceMissing=true downloadBytesPreserved=true")
    }

    private suspend fun verifyLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manga = requireNotNull(Injekt.get<MangaRepository>().getMangaByUrlAndSourceId(URL, SOURCE))
        assertEquals("AEX05 custom title", manga.title)
        assertEquals("AEX05 notes", manga.notes)
        assertTrue(manga.favorite)
        val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()
        assertEquals("/chapter/aex05-owned", chapter.url)
        assertEquals(7L, chapter.lastPageRead)
        assertTrue(chapter.read && chapter.bookmark)
        assertEquals(
            "AEX05 category",
            Injekt.get<CategoryRepository>().getCategoriesByMangaId(manga.id).single().name,
        )
        assertEquals("original", context.getSharedPreferences("source_$SOURCE", 0).getString("aex05_quality", null))
        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            requireNotNull(Injekt.get<ExtensionRepoRepository>().getRepo("http://127.0.0.1:18965"))
                .signingKeyFingerprint,
        )
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SOURCE = 2499283573021220255L
        const val URL = "/manga/aex05-owned"
    }
}
