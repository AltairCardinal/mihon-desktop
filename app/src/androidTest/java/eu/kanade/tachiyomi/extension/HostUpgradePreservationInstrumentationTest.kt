package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.net.ServerSocket

/** Must follow installation over the seeded fixed historical host, without clearing app data. */
class HostUpgradePreservationInstrumentationTest {
    @Test
    fun currentHostMigratesOldDatabaseAndReadsTheExistingDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "app.mihon.aex05.dev")
        val receipt = context.getSharedPreferences("aex05-host-upgrade", 0)
        check(receipt.getBoolean("seeded", false))
        assertEquals(18L, receipt.getLong("schema", -1))
        val driver = Injekt.get<SqlDriver>()
        val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        }, 0).value
        assertEquals(20L, version)
        val manga = Injekt.get<MangaRepository>().getMangaById(90005)
        val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()
        assertEquals("AEX05 custom title", manga.title)
        assertEquals("AEX05 notes", manga.notes)
        assertEquals(2499283573021220255L, manga.source)
        assertTrue(manga.favorite)
        assertTrue(manga.memo.isEmpty())
        assertEquals(90006L, chapter.id)
        assertEquals(7L, chapter.lastPageRead)
        assertTrue(chapter.read)
        assertTrue(chapter.bookmark)
        assertTrue(chapter.memo.isEmpty())
        val category = Injekt.get<CategoryRepository>().getCategoriesByMangaId(manga.id).single()
        assertEquals(90007L, category.id)
        assertEquals("AEX05 category", category.name)
        assertEquals(
            "original",
            context.getSharedPreferences("source_${manga.source}", 0).getString("aex05_quality", null),
        )
        val repository = requireNotNull(Injekt.get<ExtensionRepoRepository>().getRepo("http://127.0.0.1:18965"))
        assertEquals("AEX05 repo", repository.name)
        assertEquals(null, repository.indexUrl)
        if (InstrumentationRegistry.getArguments().getString("aex05CatalogUpgrade") == "true") {
            upgradeFromMigratedRepository(context)
        }
        val sources = Injekt.get<SourceManager>()
        val source = withTimeout(20_000) {
            while (sources.get(manga.source) == null) delay(20)
            requireNotNull(sources.get(manga.source))
        }
        val downloads = Injekt.get<DownloadManager>()
        assertTrue(
            downloads.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.title,
                source.id,
                skipCache = true,
            ),
        )
        val reader = ReaderChapter(chapter)
        try {
            ChapterLoader(context, downloads, Injekt.get<DownloadProvider>(), manga, source).loadChapter(reader)
            assertTrue(requireNotNull(reader.pageLoader).isLocal)
            val page = requireNotNull(reader.pages).single()
            assertTrue(requireNotNull(page.stream).invoke().use { it.readBytes().isNotEmpty() })
        } finally {
            reader.pageLoader?.recycle()
        }
        println("AEX05_HOST_UPGRADE_OK schema=$version manga=${manga.id} chapter=${chapter.id} localDownload=true")
    }

    private suspend fun upgradeFromMigratedRepository(context: Context) {
        val manager = Injekt.get<ExtensionManager>()
        val packageName = "eu.kanade.tachiyomi.extension.all.mangadex"
        val old = withTimeout(15_000) {
            manager.installedExtensionsFlow.first { list -> list.any { it.pkgName == packageName } }
                .single { it.pkgName == packageName }
        }
        assertEquals(211L, old.versionCode)
        val apk = InstrumentationRegistry.getInstrumentation().context.assets
            .open("keiyoushi-mangadex-1.6.0.apk").use { it.readBytes() }
        ServerSocket(18965, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = Thread {
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val path = input.readLine().split(' ')[1]
                            while (!input.readLine().isNullOrEmpty()) Unit
                            val body = when (path) {
                                "/repo.json" -> """
                                    {"name":"AEX05 repo","badgeLabel":"AEX05",
                                    "signingKey":"9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                                    "contact":{"website":"http://127.0.0.1:18965"},
                                    "extensionList":{"extensions":[{"name":"MangaDex","packageName":"$packageName",
                                    "resources":{"apkUrl":"http://127.0.0.1:18965/fixture.apk","iconUrl":""},
                                    "extensionLib":"1.6","versionCode":106000,"versionName":"1.6.0",
                                    "contentWarning":"CONTENT_WARNING_SAFE","sources":[]}]}}
                                """.trimIndent().toByteArray()
                                "/fixture.apk" -> apk
                                else -> error("Unexpected catalog request $path")
                            }
                            socket.getOutputStream().use { output ->
                                output.write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        .toByteArray(),
                                )
                                output.write(body)
                            }
                        }
                    } catch (error: Exception) {
                        if (!server.isClosed) throw error
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            try {
                withTimeout(60_000) {
                    manager.findAvailableExtensions()
                    val candidate = manager.availableExtensionsFlow.value.single { it.pkgName == packageName }
                    assertEquals(1.6, candidate.libVersion, 0.0)
                    assertTrue(!manager.installedExtensionsFlow.value.single { it.pkgName == packageName }.isObsolete)
                    Injekt.get<BasePreferences>().extensionInstaller().set(BasePreferences.ExtensionInstaller.PRIVATE)
                    assertEquals(
                        InstallStep.Installed,
                        manager.installExtension(candidate).first(InstallStep::isCompleted),
                    )
                    val upgraded = manager.installedExtensionsFlow.first { list ->
                        list.any { it.pkgName == packageName && it.versionCode == 106000L }
                    }.single { it.pkgName == packageName }
                    assertEquals(old.sources.map { it.id }.toSet(), upgraded.sources.map { it.id }.toSet())
                    val sources = Injekt.get<SourceManager>()
                    sources.querySources.first { list -> upgraded.sources.all { source -> list.any { it === source } } }
                }
                println("AEX05_CATALOG_UPGRADE_OK legacyRepository=true protocol=1.6 sourceIdsPreserved=true")
            } finally {
                server.close()
                worker.join(5_000)
            }
        }
    }
}
