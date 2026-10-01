package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeCatalogueSource
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class SaveSourceMangaForDetailsTest {
    @Test
    fun `detail preparation cancellation propagates without publishing a failure`() = runBlocking<Unit> {
        val mangas = FakeMangaRepository()
        val manga = Manga.create().copy(id = 71, source = 42, url = "/cancel", title = "Cancel", initialized = true)
        mangas.seed(manga)
        val cancellation = kotlinx.coroutines.CancellationException("Cancelled catalogue check")
        val chapters = object : tachiyomi.domain.chapter.repository.ChapterRepository by FakeChapterRepository() {
            override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> = throw cancellation
        }
        val owner = SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters)
        val thrown = org.junit.jupiter.api.Assertions.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { owner.prepareForDetails(manga) }
        }
        org.junit.jupiter.api.Assertions.assertSame(cancellation, thrown)
        val legacy = org.junit.jupiter.api.Assertions.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                owner.awaitListedForDetails(
                    SManga.create().apply {
                        url = manga.url
                        title = manga.title
                    },
                    manga.source,
                )
            }
        }
        org.junit.jupiter.api.Assertions.assertSame(cancellation, legacy)
        assertEquals(emptyMap<SourceMangaRefreshKey, SourceMangaRefreshState>(), owner.refreshStates.value)
    }

    @Test
    fun `another work prepares independently while the first source response waits`() = runBlocking<Unit> {
        val mangas = FakeMangaRepository()
        val chapters = FakeChapterRepository()
        val owner = SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters)
        val first = owner.awaitListed(
            SManga.create().apply {
                url = "/first"
                title = "First"
            },
            42,
        )
        val second = owner.awaitListed(
            SManga.create().apply {
                url = "/second"
                title = "Second"
            },
            42,
        )
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 42L
            override val name = "Two works"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                if (manga.url == first.url) {
                    reached.complete(Unit)
                    release.await()
                }
                return eu.kanade.tachiyomi.source.model.SMangaUpdate(
                    manga,
                    listOf(
                        SChapter.create().apply {
                            url = manga.url + "/1"
                            name = manga.title + " 1"
                        },
                    ),
                )
            }
        }
        kotlinx.coroutines.coroutineScope {
            val pending = async { owner.awaitPrepared(source, first) }
            reached.await()
            val independent = owner.awaitPrepared(source, second)
            assertEquals(second.id, (independent as mihon.desktop.extension.SourceCallResult.Success).value.manga.id)
            assertEquals(listOf("/second/1"), chapters.getChapterByMangaId(second.id).map { it.url })
            release.complete(Unit)
            assertEquals(first.id, (pending.await() as mihon.desktop.extension.SourceCallResult.Success).value.manga.id)
            assertEquals(listOf("/first/1"), chapters.getChapterByMangaId(first.id).map { it.url })
        }
    }

    @Test
    fun `detail refresh shares source request and cancellation of a waiter preserves another`() = runBlocking<Unit> {
        val mangas = FakeMangaRepository()
        val chapters = FakeChapterRepository()
        val owner = SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 42L
            override val name = "Shared"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                return eu.kanade.tachiyomi.source.model.SMangaUpdate(
                    manga,
                    listOf(
                        SChapter.create().apply {
                            url = "/1"
                            name = "Chapter 1"
                        },
                    ),
                )
            }
        }
        val listed = SManga.create().apply {
            url = "/shared"
            title = "Shared"
        }
        val first = owner.refreshFromSource(source, listed)
        entered.await()
        val second = owner.refreshFromSource(source, listed)
        first.cancel()
        release.complete(Unit)
        second.join()
        assertEquals(1, calls.get())
        assertEquals(1, chapters.addedChapters.size)
        assertEquals(null, owner.refreshStates.value[SourceMangaRefreshKey(42, "/shared")])
    }

    @Test
    fun `detail refresh calls combined update exactly once with both flags`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val listed = SManga.create().apply {
            url = "/combined"
            title = "Combined"
        }
        var calls = 0
        val source = object : eu.kanade.tachiyomi.source.Source {
            override val id = 42L
            override val name = "Combined update source"
            override suspend fun getMangaUpdate(
                manga: SManga,
                chapters: List<SChapter>,
                fetchDetails: Boolean,
                fetchChapters: Boolean,
            ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                calls++
                assertEquals(true, fetchDetails)
                assertEquals(true, fetchChapters)
                return eu.kanade.tachiyomi.source.model.SMangaUpdate(
                    manga,
                    listOf(
                        SChapter.create().apply {
                            url = "/chapter"
                            name = "Chapter 1"
                        },
                    ),
                )
            }
        }

        val saved = useCase.awaitFromSource(source, listed)

        assertEquals(1, calls)
        assertEquals(listOf("/chapter"), chapterRepo.getChapterByMangaId(saved.id).map { it.url })
    }

    @Test
    fun `background detail refresh publishes loading then keeps the structured source failure`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val listed = SManga.create().apply {
            url = "/comic/invalid-details"
            title = "Invalid details"
        }
        val source = object : eu.kanade.tachiyomi.source.CatalogueSource {
            override val id = 42L
            override val name = "Broken source"
            override val lang = "en"
            override val supportsLatest = false
            override suspend fun getMangaDetails(manga: SManga): SManga =
                throw IllegalStateException("results was null")
            override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
            override suspend fun getPopularManga(page: Int) = eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int) = eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
            override suspend fun getSearchManga(
                page: Int,
                query: String,
                filters: eu.kanade.tachiyomi.source.model.FilterList,
            ) = eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
            override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()
        }

        val refresh = useCase.refreshFromSource(source, listed)
        assertInstanceOf(
            SourceMangaRefreshState.Loading::class.java,
            useCase.refreshStates.value[SourceMangaRefreshKey(source.id, listed.url)],
        )
        refresh.join()

        val failure = assertInstanceOf(
            SourceMangaRefreshState.Failure::class.java,
            useCase.refreshStates.value[SourceMangaRefreshKey(source.id, listed.url)],
        )
        assertInstanceOf(AppError.MalformedData::class.java, failure.error)
        assertEquals(0, chapterRepo.addedChapters.size)
    }

    @Test
    fun `search results use canonical mapping deduplicate per source and preserve existing state`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, FakeChapterRepository())
        mangaRepo.seed(
            Manga.create().copy(
                id = 7,
                source = 42,
                url = "/same",
                title = "Existing",
                favorite = true,
                initialized = true,
            ),
        )
        val listed = SManga.create().apply {
            url = "/same"
            title = "Listed"
            artist = "Artist"
            author = "Author"
            description = "Description"
            genre = "Drama, Action"
            status = SManga.COMPLETED
            thumbnail_url = "https://example.invalid/cover.jpg"
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = false
        }

        val sameSource = useCase.awaitSearchResults(listOf(listed, listed), 42)
        val otherSource = useCase.awaitSearchResults(listOf(listed), 43).single()

        assertEquals(listOf(7L), sameSource.map(Manga::id))
        assertEquals(true, sameSource.single().favorite)
        assertEquals(true, sameSource.single().initialized)
        assertEquals(43L, otherSource.source)
        assertEquals("Artist", otherSource.artist)
        assertEquals("Author", otherSource.author)
        assertEquals("Description", otherSource.description)
        assertEquals(listOf("Drama", "Action"), otherSource.genre)
        assertEquals(SManga.COMPLETED.toLong(), otherSource.status)
        assertEquals("https://example.invalid/cover.jpg", otherSource.thumbnailUrl)
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, otherSource.updateStrategy)
    }

    @Test
    fun `saves source manga as non favorite with chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        val result = useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/chainsaw-man"
                title = "Chainsaw Man"
                author = "Tatsuki Fujimoto"
            },
            sourceId = 42L,
            sChapters = listOf(
                SChapter.create().apply {
                    url = "/chapter/1"
                    name = "Chapter 1"
                },
                SChapter.create().apply {
                    url = "/chapter/2"
                    name = "Chapter 2"
                },
            ),
        )

        assertFalse(result.favorite)
        assertEquals("Chainsaw Man", result.title)
        assertEquals(42L, result.source)
        assertEquals(2, chapterRepo.addedChapters.size)
        assertEquals(result.id, chapterRepo.addedChapters.first().mangaId)
    }

    @Test
    fun `saving source manga records the complete catalogue through the archive repository`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val archive = mockk<CreatorArchiveRepository>(relaxed = true)
        val useCase = SaveSourceMangaForDetails(
            NetworkToLocalManga(mangaRepo),
            mangaRepo,
            chapterRepo,
            archive,
            sourceDateExtensionIdentityProvider = { SourceDateExtensionIdentity("example.extension", "1.0+1") },
        )
        val chapters = listOf(
            SChapter.create().apply {
                url = "/chapter/1"
                name = "Chapter 1"
                date_upload = 1_790_000_000_000L
            },
        )

        val saved = useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/archive"
                title = "Archive Work"
            },
            sourceId = 42L,
            sChapters = chapters,
        )

        coVerify {
            archive.recordSourceDateQualityObservations(
                match { observations ->
                    observations.single().identity.field == SourceDateField.CHAPTER_UPDATED &&
                        observations.single().identity.extensionPackage == "example.extension" &&
                        observations.single().identity.extensionVersion == "1.0+1" &&
                        observations.single().workNaturalKey == saved.url &&
                        observations.single().chapterNaturalKey == chapters.single().url &&
                        observations.single().valueAt == chapters.single().date_upload &&
                        observations.single().precision == SourceDatePrecision.DAY
                },
                now = any(),
            )
        }
        coVerify {
            archive.updateSourceWorkCatalog(
                sourceWork = SourceWorkNaturalKey(42L, "/manga/archive"),
                chapterCount = 1L,
                completeness = ChapterCatalogCompleteness.COMPLETE,
                latestChapterAt = 1_790_000_000_000L,
                observedAt = any(),
                mangaId = saved.id,
            )
        }
    }

    @Test
    fun `recognizes chapter numbers from source chapter names when saving details`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)

        useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/soul-eater"
                title = "SOUL EATER噬魂者"
            },
            sourceId = 42L,
            sChapters = listOf(
                SChapter.create().apply {
                    url = "/chapter/16"
                    name = "第16卷"
                },
                SChapter.create().apply {
                    url = "/chapter/22"
                    name = "第22卷"
                },
            ),
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.addedChapters.map { it.chapterNumber })
    }

    @Test
    fun `updates existing unrecognized chapter numbers when saving details again`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/soul-eater",
            title = "SOUL EATER噬魂者",
            initialized = true,
        )
        mangaRepo.seed(existing)
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(id = 100L, mangaId = 7L, url = "/chapter/16", name = "第16卷", chapterNumber = -1.0),
                Chapter.create().copy(id = 101L, mangaId = 7L, url = "/chapter/22", name = "第22卷", chapterNumber = -1.0),
            ),
        )

        useCase.await(
            sManga = SManga.create().apply {
                url = "/manga/soul-eater"
                title = "SOUL EATER噬魂者"
            },
            sourceId = 42L,
            sChapters = listOf(
                SChapter.create().apply {
                    url = "/chapter/16"
                    name = "第16卷"
                },
                SChapter.create().apply {
                    url = "/chapter/22"
                    name = "第22卷"
                },
            ),
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.getChapterByMangaId(7L).map { it.chapterNumber })
    }

    @Test
    fun `does not duplicate chapters on repeated source detail open`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val sManga = SManga.create().apply {
            url = "/manga/chainsaw-man"
            title = "Chainsaw Man"
        }
        val chapters = listOf(
            SChapter.create().apply {
                url = "/chapter/1"
                name = "Chapter 1"
            },
        )

        val first = useCase.await(sManga, sourceId = 42L, sChapters = chapters)
        val second = useCase.await(sManga, sourceId = 42L, sChapters = chapters)

        assertEquals(first.id, second.id)
        assertEquals(1, chapterRepo.addedChapters.size)
    }

    @Test
    fun `fetches source details and chapters before opening unified manga detail`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val source = FakeCatalogueSource(
            details = SManga.create().apply {
                // url intentionally omitted by the source detail response
                title = "Detailed Title"
                author = "Author A"
                thumbnail_url = "https://example.invalid/detail-cover.jpg"
            },
            chapters = listOf(
                SChapter.create().apply {
                    url = "/chapter/1"
                    name = "Chapter 1"
                },
            ),
        )

        val result = useCase.awaitFromSource(
            source = source,
            listedManga = SManga.create().apply {
                url = "/manga/source-result"
                title = "Listing Title"
                thumbnail_url = "https://example.invalid/list-cover.jpg"
            },
        )

        assertEquals("/manga/source-result", result.url)
        assertEquals("Detailed Title", result.title)
        assertEquals("Author A", result.author)
        assertEquals("https://example.invalid/detail-cover.jpg", result.thumbnailUrl)
        assertEquals(1, chapterRepo.addedChapters.size)
        assertEquals(result.id, chapterRepo.addedChapters.single().mangaId)
    }

    @Test
    fun `saves listed manga without fetching source before navigation`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val listed = SManga.create().apply {
            url = "/manga/fast-open"
            title = "Fast Open"
            thumbnail_url = "https://example.invalid/list-cover.jpg"
        }

        val result = useCase.awaitListed(listed, sourceId = 42L)

        assertEquals("/manga/fast-open", result.url)
        assertEquals("Fast Open", result.title)
        assertEquals("https://example.invalid/list-cover.jpg", result.thumbnailUrl)
        assertFalse(result.initialized)
        assertEquals(0, chapterRepo.addedChapters.size)
    }

    @Test
    fun `listed save returns existing initialized manga without downgrading it`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/already-opened",
            title = "Already Opened",
            initialized = true,
        )
        mangaRepo.seed(existing)

        val result = useCase.awaitListed(
            sManga = SManga.create().apply {
                url = "/manga/already-opened"
                title = "Already Opened"
            },
            sourceId = 42L,
        )

        assertEquals(7L, result.id)
        assertEquals(true, result.initialized)
        assertEquals(true, mangaRepo.get(7L)?.initialized)
    }

    @Test
    fun `listed detail open requests refresh for existing initialized manga with no chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/empty-old-record",
            title = "Empty Old Record",
            initialized = true,
        )
        mangaRepo.seed(existing)

        val result = useCase.awaitListedForDetails(
            sManga = SManga.create().apply {
                url = "/manga/empty-old-record"
                title = "Empty Old Record"
            },
            sourceId = 42L,
        )

        assertEquals(7L, result.manga.id)
        assertEquals(true, result.manga.initialized)
        assertEquals(true, result.needsRefresh)
        assertEquals(true, mangaRepo.get(7L)?.initialized)
    }

    @Test
    fun `listed detail open requests refresh for unobserved initialized manga with chapters`() = runBlocking<Unit> {
        val mangaRepo = FakeMangaRepository()
        val chapterRepo = FakeChapterRepository()
        val useCase = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepo), mangaRepo, chapterRepo)
        val existing = Manga.create().copy(
            id = 7L,
            source = 42L,
            url = "/manga/already-has-chapters",
            title = "Already Has Chapters",
            initialized = true,
        )
        mangaRepo.seed(existing)
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 7L, url = "/chapter/1", name = "Chapter 1"),
            ),
        )

        val result = useCase.awaitListedForDetails(
            sManga = SManga.create().apply {
                url = "/manga/already-has-chapters"
                title = "Already Has Chapters"
            },
            sourceId = 42L,
        )

        assertEquals(true, result.needsRefresh)
    }

    @Test
    fun `remote duplicate stable URLs keep first occurrence and consecutive order`() = runBlocking<Unit> {
        val mangas = FakeMangaRepository()
        val chapters = FakeChapterRepository()
        val owner = SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters)
        owner.await(
            SManga.create().apply {
                url = "/duplicates"
                title = "Duplicates"
            },
            42,
            listOf("/3", "/3", "/2", "/1").map { url ->
                SChapter.create().apply {
                    this.url = url
                    name = url
                }
            },
        )
        assertEquals(listOf("/3", "/2", "/1"), chapters.addedChapters.map { it.url })
        assertEquals(listOf(0L, 1L, 2L), chapters.addedChapters.map { it.sourceOrder })
    }
}
