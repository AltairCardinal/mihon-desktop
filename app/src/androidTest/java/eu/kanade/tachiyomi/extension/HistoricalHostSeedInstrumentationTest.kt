package eu.kanade.tachiyomi.extension

import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Properties

/** Runs against the fixed pre-migration host, not the current application. */
class HistoricalHostSeedInstrumentationTest {
    @Test
    fun seedOldHostUsingItsActualDatabaseAndDownloadNaming() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "app.mihon.aex05.dev")
        val receipt = context.getSharedPreferences("aex05-host-upgrade", 0)
        check(!receipt.contains("seeded")) { "Never overwrite an existing upgrade fixture" }
        val driver = Injekt.get<SqlDriver>()
        val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        }, 0).value
        assertEquals(18L, version)
        Injekt.get<DatabaseHandler>().await(inTransaction = true) {
            listOf(
                "INSERT INTO categories VALUES (90007, 'AEX05 category', 1, 0)",
                "INSERT INTO mangas (_id,source,url,title,status,favorite,initialized,viewer,chapter_flags," +
                    "cover_last_modified,date_added,notes) VALUES " +
                    "(90005,2499283573021220255,'/manga/aex05-owned','AEX05 custom title',1,1,1,3,4,5,6,'AEX05 notes')",
                "INSERT INTO chapters (_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number," +
                    "source_order,date_fetch,date_upload) VALUES " +
                    "(90006,90005,'/chapter/aex05-owned','AEX05 chapter',1,1,7,1,0,123,456)",
                "INSERT INTO mangas_categories VALUES (90008,90005,90007)",
                "INSERT INTO extension_repos VALUES ('http://127.0.0.1:18965','AEX05 repo',NULL," +
                    "'http://127.0.0.1:18965','9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2')",
            ).forEach { driver.execute(null, it, 0) }
        }
        val packageName = "eu.kanade.tachiyomi.extension.all.mangadex"
        val apk = File(context.filesDir, "exts/$packageName.ext")
        check(!apk.exists())
        check(apk.parentFile!!.mkdirs() || apk.parentFile!!.isDirectory)
        instrumentation.context.assets.open("keiyoushi-mangadex-1.4.211.apk").use { input ->
            apk.outputStream().use { input.copyTo(it) }
        }
        check(apk.setReadOnly())
        val trust = File(context.filesDir, "extension-install-metadata/private-$packageName.properties")
        check(trust.parentFile!!.mkdirs() || trust.parentFile!!.isDirectory)
        Properties().apply {
            setProperty("repository.baseUrl", "http://127.0.0.1:18965")
            setProperty("repository.name", "AEX05 repo")
            setProperty("repository.fingerprint", "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2")
            setProperty("artifact.sha256", "eff4ee157380f0cd4f19a2150f93220ca7a9bcd4e5d570736f639230ef338236")
        }.also { values -> trust.outputStream().use { values.store(it, null) } }
        val manager = Injekt.get<ExtensionManager>()
        ExtensionInstallReceiver.notifyReplaced(context, packageName)
        withTimeout(20_000) {
            manager.installedExtensionsFlow.first { list -> list.any { it.pkgName == packageName } }
        }
        val sources = Injekt.get<SourceManager>()
        val source = withTimeout(10_000) {
            while (sources.get(2499283573021220255) == null) delay(20)
            requireNotNull(sources.get(2499283573021220255))
        }
        context.getSharedPreferences("source_${source.id}", 0).edit().putString("aex05_quality", "original").commit()
        val root = File(context.filesDir, "aex05-storage")
        check(root.mkdirs() || root.isDirectory)
        Injekt.get<StoragePreferences>().baseStorageDirectory().set(Uri.fromFile(root).toString())
        withTimeout(10_000) {
            while (Injekt.get<StorageManager>().getDownloadsDirectory()?.uri?.path != File(root, "downloads").path) {
                delay(20)
            }
        }
        val provider = Injekt.get<DownloadProvider>()
        val sourceDir = File(File(root, "downloads"), provider.getSourceDirName(source))
        val mangaDir = File(sourceDir, provider.getMangaDirName("AEX05 custom title"))
        val chapterDir = File(mangaDir, provider.getChapterDirName("AEX05 chapter", null, "/chapter/aex05-owned"))
        check(chapterDir.mkdirs())
        File(chapterDir, "001.png").writeBytes(
            Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII=",
                0,
            ),
        )
        check(receipt.edit().putBoolean("seeded", true).putLong("schema", version!!).commit())
        println("AEX05_OLD_HOST_SEEDED schema=$version source=${source.id} download=${chapterDir.name}")
    }
}
