package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.sourceFailureIdentity
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class LibraryUpdateCheckerTest {
    private lateinit var storage: DirectorySqlFixture

    @Test
    fun `checker freezes owner before API and never reassigns late returned metadata failure`() = runBlocking {
        var owner = eu.kanade.tachiyomi.network.SourceFailureIdentity(42L, "trusted.old", "Old", "1.2.3", 123L)
        val base = SManga.create().apply { title = "Remote" }
        val remote = object : SManga by base {
            override fun getGenres(): List<String>? = throw NoSuchMethodError("late genres")
        }
        val source = mockk<eu.kanade.tachiyomi.source.Source>()
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
            owner = owner.copy(extensionPackage = "unrelated.new", versionName = "9.9.9")
            eu.kanade.tachiyomi.source.model.SMangaUpdate(remote, listOf(sChapter("/new")))
        }
        var captures = 0
        val checker = LibraryUpdateChecker(
            storage.chapters,
            storage.mangas.apply { seed(manga()) },
            failureIdentityProvider = { actual, id ->
                org.junit.jupiter.api.Assertions.assertSame(source, actual)
                assertEquals(42L, id)
                captures++
                owner
            },
        )
        val failure = checker.checkForUpdates(manga(), source, origin = "DETAIL_REFRESH").sourceError
        assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure)
        assertEquals("trusted.old", failure?.sourceFailureIdentity()?.extensionPackage)
        assertEquals("1.2.3", failure?.sourceFailureIdentity()?.versionName)
        assertEquals(1, captures)
    }

    @Test
    fun `checker storage failures remain host controls and ordinary IO instead of source errors`() = runBlocking {
        val fatal = object : VirtualMachineError("controlled") {}
        val host = AssertionError("host")
        val cancel = kotlinx.coroutines.CancellationException("controlled")
        val ordinary = IOException("ordinary storage")
        for ((thrown, expected) in listOf(
            IOException("wrapper", fatal) to fatal,
            IOException("wrapper", host) to host,
            IOException("wrapper", cancel) to cancel,
            ordinary to ordinary,
        )) {
            val checker = LibraryUpdateChecker(
                storage.chapters,
                storage.mangas.apply { seed(manga()) },
                directoryCommit = { _, _ -> throw thrown },
            )
            val actual = runCatching {
                checker.checkForUpdates(manga(), StubSource(listOf(sChapter("/new"))))
            }.exceptionOrNull()
            if (expected === cancel) {
                assertInstanceOf(kotlinx.coroutines.CancellationException::class.java, actual)
                assertEquals(cancel.message, actual?.message)
            } else {
                org.junit.jupiter.api.Assertions.assertSame(expected, actual)
            }
        }
    }

    @Test
    fun `actual desktop DI library checker uses registered ID and keeps unknown extension ownership`(
        @org.junit.jupiter.api.io.TempDir root: java.io.File,
    ) = runBlocking {
        var apiCalls = 0
        val source = object : CatalogueSource {
            override val id = 42L
            override val name = "Builtin fixture"
            override val lang = "en"
            override val supportsLatest = false
            override fun getFilterList() = FilterList()
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                apiCalls++
                val base = SManga.create().apply { title = "Bad detail" }
                val remote = object : SManga by base {
                    override fun getGenres(): List<String>? = throw NoSuchMethodError("private detail")
                }
                return eu.kanade.tachiyomi.source.model.SMangaUpdate(remote, listOf(sChapter("/new")))
            }
        }
        val context = mihon.desktop.di.initDesktopDIForTest(
            root,
            mihon.desktop.di.inMemoryDesktopPreferenceStore(),
            updateManga = { LibraryUpdateChecker.UpdateResult(0) },
            startDownloadWorker = false,
            builtInSources = listOf(source),
        )
        try {
            org.junit.jupiter.api.Assertions.assertEquals(
                42L,
                Injekt.get<tachiyomi.domain.source.service.SourceManager>()
                    .getRegisteredSourceId(source),
            )
            val repository = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
            val current = repository.insertNetworkManga(listOf(manga().copy(id = -1L))).single()
            val result = Injekt.get<LibraryUpdateChecker>()
                .checkForUpdates(current, source, origin = "DETAIL_REFRESH")
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, result.sourceError)
            val identity = result.sourceError?.sourceFailureIdentity()
            assertEquals(1, apiCalls)
            assertEquals(42L, identity?.sourceId)
            assertEquals(null, identity?.extensionPackage)
            assertEquals("Test", repository.getMangaById(current.id).title)
            val manager = Injekt.get<mihon.desktop.extension.DesktopExtensionManager>()
            val field = mihon.desktop.extension.DesktopExtensionManager::class.java
                .getDeclaredField("mutableRuntimeSnapshot").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val published = field.get(manager) as kotlinx.coroutines.flow.MutableStateFlow<
                mihon.desktop.extension.DesktopExtensionRuntimeSnapshot,
                >
            val original = published.value
            try {
                val installed = mihon.desktop.extension.InstalledExtension(
                    java.io.File(root, "not-an-extension-identity.jar"),
                    listOf(source),
                    metadataSnapshot = mihon.desktop.extension.ExtensionMeta(
                        "trusted.installed",
                        123L,
                        "1.2.3",
                        name = "Installed owner",
                    ),
                    registeredSources = listOf(tachiyomi.domain.source.service.SourceRegistration(source, 42L)),
                )
                published.value = original.copy(installed = listOf(installed))
                assertEquals(1, manager.installedExtensions.value.size)
                val owned = Injekt.get<LibraryUpdateChecker>().checkForUpdates(
                    current,
                    source,
                    origin = "DETAIL_REFRESH",
                )
                assertEquals(2, apiCalls)
                assertEquals("trusted.installed", owned.sourceError?.sourceFailureIdentity()?.extensionPackage)
                assertEquals("1.2.3", owned.sourceError?.sourceFailureIdentity()?.versionName)
            } finally {
                published.value = original
            }
        } finally {
            context.closeAndJoin()
        }
    }

    @Test
    fun `actual library checker isolates returned metadata label and prepare failures before commit`() = runBlocking {
        val observations = mutableListOf<Triple<String, LibraryUpdateChecker.UpdateResult?, Throwable?>>()
        for (site in listOf("genres", "memo", "label", "prepare")) {
            val base = SManga.create().apply {
                title = "Future metadata"
                thumbnail_url = "https://new.invalid/cover"
            }
            val remote = object : SManga by base {
                override fun getGenres(): List<String>? {
                    if (site == "genres") throw NoSuchMethodError("private genres")
                    return base.getGenres()
                }
                override var memo: kotlinx.serialization.json.JsonObject
                    get() {
                        if (site == "memo") throw NoSuchMethodError("private memo")
                        return base.memo
                    }
                    set(value) {
                        base.memo = value
                    }
            }
            val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>()
            every { source.id } throws AssertionError("host ID required")
            every { source.toString() } answers
                { if (site == "label") throw NoSuchMethodError("private label") else "Fixture" }
            every { source.prepareNewChapter(any(), any()) } answers {
                if (site == "prepare") throw NoSuchMethodError("private prepare")
                Unit
            }
            coEvery { source.getMangaUpdate(any(), any(), true, true) } returns
                eu.kanade.tachiyomi.source.model.SMangaUpdate(remote, listOf(sChapter("/future")))
            var commits = 0
            val checker = LibraryUpdateChecker(
                storage.chapters,
                storage.mangas.apply { seed(manga()) },
                directoryCommit = { _, _ ->
                    commits++
                    error("bad source must not commit")
                },
            )
            val attempt = runCatching { checker.checkForUpdates(manga(), source, origin = "DETAIL_REFRESH") }
            observations += Triple(site, attempt.getOrNull(), attempt.exceptionOrNull())
            assertEquals(0, commits)
        }
        observations.forEach { (site, result, thrown) ->
            assertEquals(null, thrown, site)
            assertInstanceOf(AppError.ExtensionIncompatible::class.java, result?.sourceError, site)
        }
    }

    @org.junit.jupiter.api.BeforeEach fun openStorage() {
        storage = DirectorySqlFixture(manga())
    }

    @org.junit.jupiter.api.AfterEach fun closeStorage() {
        storage.close()
    }

    private fun checker(chapterRepository: DirectorySqlFixture.ChapterFixture) = LibraryUpdateChecker(
        chapterRepository,
        storage.mangas.apply { seed(manga()) },
    )

    private val sourceId = 42L

    /** Stub source that returns a fixed chapter list for any manga. */
    private class StubSource(
        private val chapters: List<SChapter>,
        override val id: Long = 42L,
        private val failure: Throwable? = null,
    ) : CatalogueSource {
        override val name = "Stub"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = SManga.create()
        override suspend fun getChapterList(manga: SManga) = failure?.let { throw it } ?: chapters
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private fun manga(id: Long = 1L, url: String = "/manga/test") =
        Manga.create().copy(id = id, url = url, source = sourceId, title = "Test", favorite = true)

    private fun sChapter(url: String, name: String = "Ch") =
        SChapter.create().apply {
            this.url = url
            this.name = name
        }

    @Test
    fun `adds new chapters not already in DB`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        // DB already has ch-1
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
            ),
        )

        val source = StubSource(
            listOf(
                sChapter("/ch/1", "Ch 1"),
                sChapter("/ch/2", "Ch 2"),
                sChapter("/ch/3", "Ch 3"),
            ),
        )

        val checker = checker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(2, result.newChapterCount)
        // DB should now have 3 chapters
        assertEquals(3, chapterRepo.getChapterByMangaId(1L).size)
    }

    @Test
    fun `returns zero when no new chapters`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
            ),
        )

        val source = StubSource(listOf(sChapter("/ch/1", "Ch 1")))

        val checker = checker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(0, result.newChapterCount)
    }

    @Test
    fun `successful update records complete catalogue facts`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        val mangaRepo = storage.mangas.apply { seed(manga()) }
        val archive = mockk<CreatorArchiveRepository>(relaxed = true)
        val latest = sChapter("/ch/latest").apply { date_upload = 1_790_000_000_000L }
        val checker = LibraryUpdateChecker(
            chapterRepo,
            mangaRepo,
            archive,
            sourceDateExtensionIdentityProvider = { SourceDateExtensionIdentity("example.extension", "1.0+1") },
        )

        checker.checkForUpdates(manga(), StubSource(listOf(latest)))

        coVerify {
            archive.recordSourceDateQualityObservations(
                match { observations ->
                    observations.single().identity.field == SourceDateField.CHAPTER_UPDATED &&
                        observations.single().identity.extensionPackage == "example.extension" &&
                        observations.single().identity.extensionVersion == "1.0+1" &&
                        observations.single().workNaturalKey == manga().url &&
                        observations.single().chapterNaturalKey == latest.url &&
                        observations.single().valueAt == latest.date_upload &&
                        observations.single().precision == SourceDatePrecision.DAY
                },
                now = any(),
            )
        }
        coVerify {
            archive.updateSourceWorkCatalog(
                SourceWorkNaturalKey(sourceId, manga().url),
                1L,
                ChapterCatalogCompleteness.COMPLETE,
                latest.date_upload,
                any(),
                manga().id,
            )
        }
    }

    @Test
    fun `handles empty source chapter list`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
            ),
        )

        val source = StubSource(emptyList())
        val checker = checker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(0, result.newChapterCount)
    }

    @Test
    fun `assigns correct sourceOrder to new chapters`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        val source = StubSource(
            listOf(
                sChapter("/ch/1", "Ch 1"),
                sChapter("/ch/2", "Ch 2"),
            ),
        )

        val checker = checker(chapterRepo)
        checker.checkForUpdates(manga(), source)

        val chapters = chapterRepo.getChapterByMangaId(1L)
        assertEquals(2, chapters.size)
        val byUrl = chapters.associateBy { it.url }
        assertEquals(0L, byUrl["/ch/1"]?.sourceOrder)
        assertEquals(1L, byUrl["/ch/2"]?.sourceOrder)
    }

    @Test
    fun `recognizes chapter numbers from source chapter names during library update`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        val source = StubSource(
            listOf(
                sChapter("/ch/16", "第16卷"),
                sChapter("/ch/22", "第22卷"),
            ),
        )

        val checker = checker(chapterRepo)
        checker.checkForUpdates(
            manga = manga().copy(title = "SOUL EATER噬魂者"),
            source = source,
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.getChapterByMangaId(1L).map { it.chapterNumber })
    }

    @Test
    fun `updates existing unrecognized chapter numbers during library update`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 1L, url = "/ch/16", name = "第16卷", chapterNumber = -1.0),
                Chapter.create().copy(mangaId = 1L, url = "/ch/22", name = "第22卷", chapterNumber = -1.0),
            ),
        )
        val source = StubSource(
            listOf(
                sChapter("/ch/16", "第16卷"),
                sChapter("/ch/22", "第22卷"),
            ),
        )

        val checker = checker(chapterRepo)
        checker.checkForUpdates(
            manga = manga().copy(title = "SOUL EATER噬魂者"),
            source = source,
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.getChapterByMangaId(1L).map { it.chapterNumber })
    }

    @Test
    fun `result includes inserted chapter models with correct URLs`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        val source = StubSource(
            listOf(
                sChapter("/ch/1", "Ch 1"),
                sChapter("/ch/2", "Ch 2"),
            ),
        )

        val checker = checker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(2, result.newChapters.size)
        val urls = result.newChapters.map { it.url }.toSet()
        assert("/ch/1" in urls) { "Expected /ch/1 in newChapters" }
        assert("/ch/2" in urls) { "Expected /ch/2 in newChapters" }
    }

    @Test
    fun `result newChapters is empty when no new chapters`() = runBlocking<Unit> {
        val chapterRepo = storage.chapters
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
            ),
        )
        val source = StubSource(listOf(sChapter("/ch/1")))

        val checker = checker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(0, result.newChapters.size)
    }

    @Test
    fun `source network failure remains structured for scheduler and notification presentation`() = runBlocking<Unit> {
        val result = checker(storage.chapters).checkForUpdates(
            manga(),
            StubSource(emptyList(), failure = IOException("connection reset")),
        )

        assertInstanceOf(AppError.Network::class.java, result.sourceError)
    }
}
