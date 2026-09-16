package eu.kanade.tachiyomi.extension

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceMangaSearchRequest
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Explicit, bounded live-site acceptance; unlike the hermetic reader test, no HTTP is rewritten. */
class LiveMangaDexAcceptanceInstrumentationTest {
    @Test
    fun installedSignedExtensionQueriesUpdatesAndMaterializesALivePage() {
        ExtensionV16LifecycleInstrumentationTest().runLifecycle(
            BasePreferences.ExtensionInstaller.PRIVATE,
            fixture = LifecycleApkFixture(
                "keiyoushi-mangadex-1.6.0.apk",
                "35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35",
                "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                "eu.kanade.tachiyomi.extension.all.mangadex",
                "MangaDex",
                "1.6.0",
                106000,
                1.6,
            ),
        ) { installed, _ ->
            val source = installed.sources.single { it.lang == "en" }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val mangas = Injekt.get<MangaRepository>()
            val chapters = Injekt.get<ChapterRepository>()
            var ownedId: Long? = null
            var reader: ReaderChapter? = null
            var phase = "query"
            try {
                withTimeout(120_000) {
                    val result = Injekt.get<SourceMangaSearchService>().loadPage(
                        source,
                        1,
                        SourceMangaSearchRequest.Latest,
                    )
                    val entry = result.mangas.first()
                    check(mangas.getMangaByUrlAndSourceId(entry.url, source.id) == null) {
                        "Refusing to modify existing library data"
                    }
                    val manga = mangas.insertNetworkManga(
                        listOf(Manga.create().copy(source = source.id, url = entry.url, title = entry.title)),
                    ).single().also { ownedId = it.id }
                    phase = "update"
                    Injekt.get<UpdateManga>().awaitFromRemote(manga, source, fetchDetails = true, fetchChapters = true)
                    val chapter = chapters.getChapterByMangaId(manga.id).first()
                    phase = "page-list"
                    val loaded = ReaderChapter(chapter).also { reader = it }
                    ChapterLoader(
                        context,
                        Injekt.get<DownloadManager>(),
                        Injekt.get<DownloadProvider>(),
                        mangas.getMangaById(manga.id),
                        source,
                    ).loadChapter(loaded)
                    assertTrue(loaded.state is ReaderChapter.State.Loaded)
                    val page = requireNotNull(loaded.pages).first()
                    phase = "image"
                    requireNotNull(loaded.pageLoader).onPageSelected(page)
                    while (page.status != Page.State.Ready) {
                        check(page.status !is Page.State.Error) { "Production reader could not materialize image" }
                        delay(50)
                    }
                    val size = requireNotNull(page.stream).invoke().use { it.readBytes().size }
                    assertTrue(size > 0)
                    println(
                        "AEX05_LIVE_OK source=${source.id} chapters=${chapters.getChapterByMangaId(
                            manga.id,
                        ).size} imageBytes=$size",
                    )
                }
            } catch (error: Throwable) {
                throw AssertionError("Live MangaDex acceptance failed at $phase", error)
            } finally {
                reader?.pageLoader?.recycle()
                ownedId?.let { id ->
                    Injekt.get<DatabaseHandler>().await(inTransaction = true) {
                        Injekt.get<SqlDriver>().execute(null, "DELETE FROM mangas WHERE _id = ?", 1) { bindLong(0, id) }
                    }
                }
            }
        }
    }
}
