package mihon.desktop.download

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import javax.imageio.ImageIO

@Isolated
class ReadDownloadDeletionIntegrationTest {
    @Test
    fun `manual read commands serialize and protect new downloads`(@TempDir root: File) = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var writes = 0
        val node = Preferences.userRoot().node("/mihon-test/read-delete/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            root,
            DesktopPreferenceStore(node),
            startDownloadWorker = false,
            chapterRepositoryOverride = { actual ->
                object : ChapterRepository by actual {
                    override suspend fun updateAll(chapterUpdates: List<tachiyomi.domain.chapter.model.ChapterUpdate>) {
                        writes++
                        if (writes == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        actual.updateAll(chapterUpdates)
                    }
                }
            },
        )
        try {
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(Manga.create().copy(source = 7, url = "/race", title = "Race")),
            ).single()
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/race/chapter", name = "Race chapter")),
            ).single()
            val provider = Injekt.get<DesktopDownloadProvider>()
            val file = provider.chapterDownloadDir(manga.source, manga.title, chapter.name)
                .apply { mkdirs() }.resolve("page.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -39)) }
            Injekt.get<DownloadPreferences>().removeAfterMarkedAsRead().set(true)
            val setter = Injekt.get<SetChapterReadStatus>()
            val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                setter.awaitOrThrow(chapter, true)
            }
            entered.await()
            val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                setter.awaitOrThrow(chapter, true)
            }
            assertEquals(1, writes)
            file.parentFile.deleteRecursively()
            assertTrue(
                Injekt.get<DesktopDownloadManager>().enqueue(
                    DownloadItem(manga.source, manga.title, chapter.name, chapter.id, manga.id, chapter.url),
                ),
            )
            file.parentFile.mkdirs()
            file.writeBytes(byteArrayOf(-1, -40, -1, -39))
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(2, writes)
            assertTrue(file.exists())
        } finally {
            release.complete(Unit)
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `detail and updates share cleanup and failed writes preserve files`(@TempDir root: File) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-test/read-delete/${UUID.randomUUID()}")
        var failWrite = false
        val context = initDesktopDIForTest(
            root,
            DesktopPreferenceStore(node),
            startDownloadWorker = false,
            chapterRepositoryOverride = { actual ->
                object : ChapterRepository by actual {
                    override suspend fun updateAll(chapterUpdates: List<tachiyomi.domain.chapter.model.ChapterUpdate>) {
                        check(!failWrite) { "read write failed" }
                        actual.updateAll(chapterUpdates)
                    }
                }
            },
        )
        try {
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(Manga.create().copy(source = 7, url = "/entries", title = "Entries")),
            ).single()
            val chapters = Injekt.get<ChapterRepository>().addAll(
                (0..1).map { index ->
                    Chapter.create().copy(mangaId = manga.id, url = "/entry-$index", name = "Entry $index")
                },
            )
            val provider = Injekt.get<DesktopDownloadProvider>()
            val files = chapters.map { chapter ->
                provider.chapterDownloadDir(manga.source, manga.title, chapter.name)
                    .apply { mkdirs() }.resolve("page.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -39)) }
            }
            Injekt.get<DownloadPreferences>().removeAfterMarkedAsRead().set(true)
            val detail = mihon.desktop.library.MangaDetailScreenModelFactory.create(manga.id)
            failWrite = true
            assertTrue(runCatching { detail.toggleChapterRead(chapters[0]) }.isFailure)
            assertTrue(files[0].exists())
            assertFalse(Injekt.get<ChapterRepository>().getChapterById(chapters[0].id)!!.read)
            failWrite = false
            detail.toggleChapterRead(chapters[0])
            assertFalse(files[0].exists())
            val updates = mihon.desktop.updates.UpdatesScreenModelFactory.create()
            updates.markRead(
                tachiyomi.domain.updates.model.UpdatesWithRelations(
                    manga.id, manga.title, chapters[1].id, chapters[1].name, null, chapters[1].url, false,
                    false, 0, manga.source, 0, manga.asMangaCover(),
                ),
            )
            assertFalse(files[1].exists())
            detail.onDispose()
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `real reader close honors slots zero through four and protected candidates`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-test/read-delete/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        try {
            val repository = Injekt.get<ChapterRepository>()
            val provider = Injekt.get<DesktopDownloadProvider>()
            val factory = Injekt.get<mihon.desktop.reader.DesktopReaderRuntimeFactory>()
            val prefs = Injekt.get<DownloadPreferences>()
            val cases = (-1..4).map { it to "ordinary" } + listOf(
                2 to "unread",
                0 to "bookmark",
                0 to "excluded",
                0 to "late-bookmark",
                0 to "replacement",
                0 to "occupied",
                0 to "occupied-cbz",
            )
            for ((slots, scenario) in cases) {
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 7,
                            url = "/reader-$slots-$scenario",
                            title = "Reader $slots $scenario",
                        ),
                    ),
                ).single()
                val chapters = repository.addAll(
                    (0..5).map { index ->
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/$slots/$index",
                            name = "Chapter $index",
                            chapterNumber = index.toDouble(),
                            sourceOrder = (5 - index).toLong(),
                            read = index < 5 && !(scenario == "unread" && index == 3),
                            bookmark = scenario == "bookmark" && index == 5,
                        )
                    },
                ).sortedBy { it.chapterNumber }
                val files = chapters.associate { chapter ->
                    chapter.id to
                        provider.chapterDownloadDir(manga.source, manga.title, chapter.name).apply { mkdirs() }
                            .resolve("page.png").apply {
                                ImageIO.write(
                                    BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB),
                                    "png",
                                    this,
                                )
                            }
                }.toMutableMap()
                if (scenario == "occupied-cbz") {
                    files.toMap().forEach { (id, page) ->
                        val archive = File(page.parentFile.path + ".cbz")
                        java.util.zip.ZipOutputStream(archive.outputStream()).use { output ->
                            output.putNextEntry(java.util.zip.ZipEntry("page.png"))
                            output.write(page.readBytes())
                            output.closeEntry()
                        }
                        page.parentFile.deleteRecursively()
                        files[id] = archive
                    }
                }
                prefs.removeAfterReadSlots().set(slots)
                prefs.removeAfterMarkedAsRead().set(scenario.startsWith("occupied"))
                prefs.removeExcludeCategories().set(if (scenario == "excluded") setOf("0") else emptySet())
                val current = chapters.last()
                val model = factory.createScreenModel(
                    initialContext = mihon.desktop.reader.DesktopReaderChapterContext(
                        current.id, manga.source,
                        current.url, manga.title, current.name, current.chapterNumber, 0, 0, false, mangaId = manga.id,
                    ),
                    isWebtoon = false,
                    mangaViewerFlags = 0,
                    dualPageOverride = null,
                    chapters = chapters.asReversed().map {
                        mihon.desktop.reader.ReaderChapterRef(it.id, it.url, it.name, it.read, it.chapterNumber)
                    },
                )
                val runtime = requireNotNull(model.runtime)
                var blocker: mihon.desktop.reader.DesktopReaderRuntime? = null
                try {
                    val page = withTimeout(5_000) {
                        while (runtime.session.state.value.snapshot.activeChapter.pages.isEmpty()) delay(10)
                        runtime.session.state.value.snapshot.activeChapter.pages.single().id
                    }
                    if (scenario.startsWith("occupied")) {
                        blocker = factory.createRuntime(runtime.session.state.value.context, this)
                        withTimeout(5_000) {
                            while (blocker.session.state.value.snapshot.activeChapter.pages.isEmpty()) delay(10)
                        }
                        runCatching { Injekt.get<SetChapterReadStatus>().awaitOrThrow(current, true) }
                        assertTrue(files.getValue(current.id).exists(), "manual read protects the other open runtime")
                    }
                    runtime.session.settleViewport(setOf(page), page)
                    withTimeout(5_000) { while (repository.getChapterById(current.id)?.read != true) delay(10) }
                    assertTrue(files.values.all { it.exists() })
                    // Wait for this accepted completion's capture before changing its finite target.
                    runtime.session.awaitAcceptedProgress()
                    if (scenario == "late-bookmark") {
                        Injekt.get<tachiyomi.domain.chapter.interactor.UpdateChapter>().awaitOrThrow(
                            tachiyomi.domain.chapter.model.ChapterUpdate(current.id, bookmark = true),
                        )
                    }
                    if (scenario == "replacement") {
                        files.getValue(current.id).parentFile.deleteRecursively()
                        assertTrue(
                            Injekt.get<DesktopDownloadManager>().enqueue(
                                DownloadItem(
                                    manga.source,
                                    manga.title,
                                    current.name,
                                    current.id,
                                    manga.id,
                                    current.url,
                                ),
                            ),
                        )
                        files.getValue(current.id).parentFile.mkdirs()
                        ImageIO.write(
                            BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB),
                            "png",
                            files.getValue(current.id),
                        )
                    }
                } finally {
                    runtime.close()
                    runtime.session.awaitProgressDrained()
                    model.onDispose()
                }
                val expected = if (slots < 0 || scenario != "ordinary") null else chapters[5 - slots].id
                files.forEach { (id, file) -> assertEquals(id != expected, file.exists(), "slots=$slots chapter=$id") }
                blocker?.close()
                blocker?.session?.awaitProgressDrained()
            }
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `legacy preference migrates once and shared explicit value survives restart`() {
        listOf<Boolean?>(true, false, null).forEach { legacy ->
            val node = Preferences.userRoot().node("/mihon-test/read-delete/${UUID.randomUUID()}")
            try {
                var store = DesktopPreferenceStore(node)
                legacy?.let { store.getBoolean("delete_after_read", false).set(it) }
                DesktopDownloadPreferences(store)
                assertEquals(if (legacy == true) 0 else -1, DownloadPreferences(store).removeAfterReadSlots().get())
                DownloadPreferences(store).removeAfterReadSlots().set(3)
                node.flush()
                store = DesktopPreferenceStore(node)
                DesktopDownloadPreferences(store)
                assertEquals(3, DownloadPreferences(store).removeAfterReadSlots().get())
                assertTrue(DownloadPreferences(store).removeAfterReadSlots().isSet())
                DownloadPreferences(store).removeAfterReadSlots().set(-1)
                DesktopDownloadPreferences(store)
                assertEquals(-1, DownloadPreferences(store).removeAfterReadSlots().get())
            } finally {
                node.removeNode()
            }
        }
    }

    @Test
    fun `manual cleanup protects bookmarks categories and repeated read commands`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("/mihon-test/read-delete/${UUID.randomUUID()}")
        val context = initDesktopDIForTest(root, DesktopPreferenceStore(node), startDownloadWorker = false)
        try {
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(Manga.create().copy(source = 7, url = "/manual", title = "Manual")),
            ).single()
            val repository = Injekt.get<ChapterRepository>()
            val chapters = repository.addAll(
                listOf(
                    Chapter.create().copy(mangaId = manga.id, url = "/normal", name = "Normal"),
                    Chapter.create().copy(mangaId = manga.id, url = "/bookmark", name = "Bookmark", bookmark = true),
                    Chapter.create().copy(mangaId = manga.id, url = "/read", name = "Read", read = true),
                ),
            )
            val provider = Injekt.get<DesktopDownloadProvider>()
            fun file(chapter: Chapter) = provider.chapterDownloadDir(manga.source, manga.title, chapter.name)
                .apply { mkdirs() }.resolve("page.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -39)) }
            val files = chapters.associate { it.id to file(it) }
            val prefs = Injekt.get<DownloadPreferences>()
            prefs.removeAfterMarkedAsRead().set(true)
            Injekt.get<SetChapterReadStatus>().awaitOrThrow(chapters, true)
            assertFalse(files.getValue(chapters[0].id).exists())
            assertTrue(files.getValue(chapters[1].id).exists())
            assertTrue(files.getValue(chapters[2].id).exists())
            file(chapters[0])
            Injekt.get<SetChapterReadStatus>().awaitOrThrow(repository.getChapterByMangaId(manga.id), true)
            assertTrue(files.getValue(chapters[0].id).exists())
            prefs.removeExcludeCategories().set(emptySet())
            prefs.removeBookmarkedChapters().set(true)
            Injekt.get<SetChapterReadStatus>().awaitOrThrow(chapters[1], false)
            Injekt.get<SetChapterReadStatus>().awaitOrThrow(repository.getChapterById(chapters[1].id)!!, true)
            assertFalse(files.getValue(chapters[1].id).exists())
            Injekt.get<SetChapterReadStatus>().awaitOrThrow(chapters[0], false)
            prefs.removeExcludeCategories().set(setOf("0"))
            Injekt.get<SetChapterReadStatus>().awaitOrThrow(repository.getChapterById(chapters[0].id)!!, true)
            assertTrue(files.getValue(chapters[0].id).exists())
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }
}
