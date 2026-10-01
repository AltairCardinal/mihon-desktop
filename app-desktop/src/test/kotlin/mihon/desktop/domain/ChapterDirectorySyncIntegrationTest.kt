package mihon.desktop.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceMangaUpdateService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class ChapterDirectorySyncIntegrationTest {
    @TempDir lateinit var directory: File

    private fun remote(url: String, name: String, number: Double) = SChapter.create().apply {
        this.url = url
        this.name = name
        chapter_number = number.toFloat()
        scanlator = "Group"
    }

    private fun source(rows: List<SChapter>) = object : Source {
        override val id = 42L
        override val name = "Directory"
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) =
            SMangaUpdate(manga, rows)
    }

    @Test
    fun `actual Desktop SQL directory refresh renames reorders removes and preserves same URL user state`() =
        runBlocking<Unit> {
            Storage(directory.resolve("metadata.db")).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                val original = storage.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/2",
                            name = "Old name",
                            chapterNumber = 2.0,
                            scanlator = "Group",
                            sourceOrder = 0,
                            read = true,
                            bookmark = true,
                            lastPageRead = 7,
                            dateFetch = 123,
                        ),
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/obsolete",
                            name = "Obsolete",
                            chapterNumber = 9.0,
                            sourceOrder = 1,
                        ),
                    ),
                ).first()
                LibraryUpdateChecker(
                    storage.chapters,
                    storage.mangas,
                ).checkForUpdates(
                    manga,
                    source(listOf(remote("/3", "Chapter 3", 3.0), remote("/2", "Renamed chapter 2", 2.0))),
                )
                val rows = storage.chapters.getChapterByMangaId(manga.id).sortedBy { it.sourceOrder }
                assertEquals(
                    listOf("/3", "/2"),
                    rows.map {
                        it.url
                    },
                    "A complete response must replace directory membership and order",
                )
                val kept = rows.single { it.url == "/2" }
                assertEquals(original.id, kept.id)
                assertEquals("Renamed chapter 2", kept.name)
                assertEquals(true, kept.read)
                assertEquals(true, kept.bookmark)
                assertEquals(7L, kept.lastPageRead)
                assertEquals(123L, kept.dateFetch)
            }
        }

    @Test
    fun `actual Desktop unique relink preserves chapter ID history and progress`() = runBlocking<Unit> {
        val path = directory.resolve("relink.db")
        var chapterId = 0L
        var mangaId = 0L
        Storage(path).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
            ).single()
            mangaId = manga.id
            val original = storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/old",
                        name = "Chapter 2",
                        chapterNumber = 2.0,
                        scanlator = "Group",
                        read = true,
                        bookmark = true,
                        lastPageRead = 9,
                        dateFetch = 321,
                    ),
                ),
            ).single()
            chapterId = original.id
            storage.handler.await {
                historyQueries.upsert(original.id, java.util.Date(1000), 20)
                chapter_pairingsQueries.insertPairing(original.id, 1, 12, 3)
                chapter_pairingsQueries.insertBoundary(original.id, 4)
                chapter_pairingsQueries.insertRevision(original.id, 3)
            }
            val result = LibraryUpdateChecker(
                storage.chapters,
                storage.mangas,
            ).checkForUpdates(manga, source(listOf(remote("/new", "Chapter 2 corrected", 2.0))))
            val rows = storage.chapters.getChapterByMangaId(manga.id)
            assertEquals(1, rows.size, "Unique chapter replacement must not leave both old and new URLs")
            val kept = rows.single()
            assertEquals(original.id, kept.id, "A uniquely identified relink must keep the original SQL identity")
            assertEquals("/new", kept.url)
            assertEquals(9L, kept.lastPageRead)
            assertEquals(true, kept.read)
            assertEquals(true, kept.bookmark)
            assertEquals(321L, kept.dateFetch)
            assertNotNull(storage.handler.await { historyQueries.getHistoryByChapterUrl("/new").executeAsOneOrNull() })
            assertEquals(0, result.newChapterCount)
        }
        Storage(path, create = false).use { storage ->
            storage.handler.await {
                assertEquals(12L, chapter_pairingsQueries.selectPairing(chapterId).executeAsOne().page_count)
                assertEquals(listOf(4L), chapter_pairingsQueries.selectBoundaries(chapterId).executeAsList())
                assertEquals(3L, chapter_pairingsQueries.selectRevision(chapterId).executeAsOne())
            }
            val manga = storage.mangas.getMangaById(mangaId)
            LibraryUpdateChecker(
                storage.chapters,
                storage.mangas,
            ).checkForUpdates(manga, source(listOf(remote("/unrelated", "Chapter 7", 7.0))))
            storage.handler.await {
                assertEquals(null, chapter_pairingsQueries.selectPairing(chapterId).executeAsOneOrNull())
                assertEquals(emptyList<Long>(), chapter_pairingsQueries.selectBoundaries(chapterId).executeAsList())
                assertEquals(null, chapter_pairingsQueries.selectRevision(chapterId).executeAsOneOrNull())
            }
        }
    }

    @Test
    fun `retained duplicate identity prevents a missing incoming pair from being rebound`() = runBlocking<Unit> {
        Storage(directory.resolve("ambiguous.db")).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
            ).single()
            val old = storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/retained",
                        name = "Chapter 2",
                        chapterNumber = 2.0,
                        scanlator = "Group",
                    ),
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/removed",
                        name = "Chapter 2",
                        chapterNumber = 2.0,
                        scanlator = "Group",
                        read = true,
                        bookmark = true,
                        dateFetch = 123,
                    ),
                ),
            ).last()
            LibraryUpdateChecker(
                storage.chapters,
                storage.mangas,
            ).checkForUpdates(
                manga,
                source(listOf(remote("/retained", "Chapter 2", 2.0), remote("/incoming", "Chapter 2 revised", 2.0))),
            )
            val incoming = storage.chapters.getChapterByMangaId(manga.id).single { it.url == "/incoming" }
            assertNotEquals(old.id, incoming.id, "An identity also used by a retained row cannot prove a unique relink")
            assertEquals(true, incoming.read, "Upstream number-based reading inheritance remains available")
            assertEquals(true, incoming.bookmark)
            assertEquals(123L, incoming.dateFetch)
        }
    }

    @Test
    fun `old source URL still resolves to the unique relink after database reopen`() = runBlocking<Unit> {
        val path = directory.resolve("old-url.db")
        var mangaId = 0L
        var chapterId = 0L
        Storage(path).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
            ).single()
            mangaId = manga.id
            chapterId =
                storage.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                            scanlator = "Group",
                            lastPageRead = 9,
                        ),
                    ),
                ).single().id
            LibraryUpdateChecker(
                storage.chapters,
                storage.mangas,
            ).checkForUpdates(manga, source(listOf(remote("/new", "Chapter 2", 2.0))))
            assertEquals(
                chapterId,
                storage.chapters.getChapterByUrlAndMangaId("/old", mangaId)?.id,
                "Previously accepted reading references must still resolve",
            )
        }
        Storage(path, create = false).use { storage ->
            assertEquals(chapterId, storage.chapters.getChapterByUrlAndMangaId("/old", mangaId)?.id)
            assertEquals(9L, storage.chapters.getChapterByUrlAndMangaId("/new", mangaId)?.lastPageRead)
        }
    }

    @Test
    fun `shared source service rejects explicit incomplete directory before returning a usable update`() =
        runBlocking<Unit> {
            val manga = Manga.create().copy(id = 1, source = 42, url = "/work", title = "Work")
            val incomplete = object : Source {
                override val id = 42L
                override val name = "Incomplete"
                override suspend fun getMangaUpdate(
                    manga: SManga,
                    chapters: List<SChapter>,
                    fetchDetails: Boolean,
                    fetchChapters: Boolean,
                ) =
                    SMangaUpdate(manga, listOf(remote("/1", "Chapter 1", 1.0)), chapterListComplete = false)
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { SourceMangaUpdateService().await(incomplete, manga, emptyList(), false, true) }
            }
        }

    @Test
    fun `Browse source save rolls back manga metadata together with a refused directory insert`() = runBlocking<Unit> {
        Storage(directory.resolve("browse-atomic.db")).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/work",
                        title = "Work",
                        favorite = true,
                        initialized = true,
                    ),
                ),
            ).single()
            val old = storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/1",
                        name = "Chapter 1",
                        chapterNumber = 1.0,
                        read = true,
                    ),
                ),
            ).single()
            storage.driver.execute(
                null,
                "CREATE TRIGGER refuse_directory BEFORE INSERT ON chapters WHEN NEW.url='/2' BEGIN SELECT " +
                    "RAISE(ABORT, 'directory refused'); END",
                0,
            )
            val details = SManga.create().apply {
                url = manga.url
                title = manga.title
                memo = Json.parseToJsonElement("""{"notCommitted":true}""").jsonObject
            }
            assertThrows(Exception::class.java) {
                runBlocking {
                    SaveSourceMangaForDetails(NetworkToLocalManga(storage.mangas), storage.mangas, storage.chapters)
                        .await(
                            details,
                            manga.source,
                            listOf(remote("/1", "Renamed chapter 1", 1.0), remote("/2", "Chapter 2", 2.0)),
                        )
                }
            }
            assertEquals(manga, storage.mangas.getMangaById(manga.id))
            assertEquals(listOf(old), storage.chapters.getChapterByMangaId(manga.id))
        }
    }

    @Test
    fun `production Desktop backup creator codec and restorer preserve relink aliases after restart`() =
        runBlocking<Unit> {
            val backup = Storage(directory.resolve("backup-source.db")).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                s.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                            scanlator = "Group",
                            read = true,
                            lastPageRead = 6,
                        ),
                    ),
                )
                LibraryUpdateChecker(
                    s.chapters,
                    s.mangas,
                ).checkForUpdates(manga, source(listOf(remote("/new", "Chapter 2", 2.0))))
                val tracks = io.mockk.mockk<tachiyomi.domain.track.repository.TrackRepository>()
                io.mockk.coEvery { tracks.getTracksByMangaId(any()) } returns emptyList()
                val preferences = io.mockk.mockk<tachiyomi.core.common.preference.PreferenceStore>()
                io.mockk.every { preferences.getAll() } returns emptyMap<String, Any>()
                val extensions = io.mockk.mockk<mihon.domain.extensionrepo.repository.ExtensionRepoRepository>()
                io.mockk.coEvery { extensions.getAll() } returns emptyList()
                mihon.desktop.backup.DesktopBackupCreator.createFromDatabase(
                    s.mangas,
                    s.chapters,
                    tachiyomi.data.category.CategoryRepositoryImpl(s.handler),
                    tachiyomi.data.history.HistoryRepositoryImpl(s.handler),
                    trackRepository = tracks,
                    preferenceStore = preferences,
                    sourcePreferenceStore = {
                        preferences
                    },
                    extensionRepoRepository = extensions,
                )
            }
            val decoded = mihon.desktop.backup.DesktopBackupCreator.decodeFromBytes(
                mihon.desktop.backup.DesktopBackupCreator.encodeToBytes(backup),
            )
            val path = directory.resolve("backup-target.db")
            Storage(path).use { s ->
                val restorer = mihon.desktop.backup.DesktopBackupRestorer(
                    s.mangas,
                    s.chapters,
                    tachiyomi.data.category.CategoryRepositoryImpl(s.handler),
                    tachiyomi.data.history.HistoryRepositoryImpl(s.handler),
                    backupRestoreSync = mihon.data.sync.journal.SyncBackupRestorer(
                        s.handler,
                        tachiyomi.domain.creator.repository.ReadyCreatorArchiveBootstrap,
                    ),
                )
                assertEquals(false, restorer.restore(decoded).hasErrors)
            }
            Storage(path, false).use { s ->
                val manga = s.mangas.getFavorites().single()
                val restored = s.chapters.getChapterByUrlAndMangaId("/old", manga.id)
                assertNotNull(restored, "Backup restore must rebuild accepted old chapter references")
                assertEquals("/new", restored!!.url)
                assertEquals(6L, restored.lastPageRead)
                assertEquals(true, restored.read)
            }
        }

    @Test
    fun `chapter identity floor survives empty directory reopen and refuses missing or overflow state`() =
        runBlocking<Unit> {
            val path = directory.resolve("floor.db")
            var mangaId = 0L
            Storage(path).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work")),
                ).single()
                mangaId = manga.id
                s.driver.execute(
                    null,
                    "INSERT INTO " +
                        "chapters(_id,manga_id,url,name,read,bookmark,last_page_read,chapter_number," +
                        "source_order,date_fetch,date_upload) VALUES (100,$mangaId,'/explicit'," +
                        "'Explicit',0,0,0,1,0,0,0)",
                    0,
                )
                val next = s.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = mangaId, url = "/next", name = "Next")),
                ).single()
                assertEquals(101L, next.id)
                s.handler.await(inTransaction = true) {
                    runCatching {
                        transaction {
                            chaptersQueries.insert(
                                mangaId,
                                "/rolled",
                                "Rolled",
                                null,
                                false,
                                false,
                                0,
                                2.0,
                                0,
                                0,
                                0,
                                0,
                                tachiyomi.data.JsonObjectEmptyBytes,
                            )
                            error("rollback")
                        }
                    }
                }
                val afterRollback = s.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = mangaId, url = "/after-rollback", name = "After")),
                ).single()
                assertEquals(102L, afterRollback.id, "Rolled back inserts do not advance the persisted identity floor")
                s.chapters.removeChaptersWithIds(s.chapters.getChapterByMangaId(mangaId).map { it.id })
            }
            Storage(path, false).use { s ->
                val afterReopen = s.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = mangaId, url = "/reopened", name = "Reopened")),
                ).single()
                assertEquals(103L, afterReopen.id, "Deleting every chapter must not reset its allocation floor")
                s.driver.execute(null, "DELETE FROM chapter_id_floor", 0)
                assertThrows(Exception::class.java) {
                    runBlocking {
                        s.chapters.syncDirectory(
                            tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                                mangaId,
                                listOf(
                                    tachiyomi.domain.chapter.service.PreparedSourceChapter(
                                        afterReopen.copy(id = -1, url = "/missing-floor"),
                                        0,
                                    ),
                                ),
                                2000,
                            ),
                        )
                    }
                }
                assertEquals(listOf(afterReopen), s.chapters.getChapterByMangaId(mangaId))
                s.driver.execute(null, "INSERT INTO chapter_id_floor VALUES (1,9223372036854775807)", 0)
                assertThrows(Exception::class.java) {
                    runBlocking {
                        s.chapters.syncDirectory(
                            tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                                mangaId,
                                listOf(
                                    tachiyomi.domain.chapter.service.PreparedSourceChapter(
                                        afterReopen.copy(id = -1, url = "/overflow"),
                                        0,
                                    ),
                                ),
                                3000,
                            ),
                        )
                    }
                }
                assertEquals(listOf(afterReopen), s.chapters.getChapterByMangaId(mangaId))
            }
        }

    @Test
    fun `directory rename keeps actual downloaded image readable through the new chapter identity`() =
        runBlocking<Unit> {
            Storage(directory.resolve("rename-file.db")).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                val old = s.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                            scanlator = "Group",
                        ),
                    ),
                ).single()
                val source = source(listOf(remote("/new", "Chapter 2 revised", 2.0)))
                val provider = mihon.desktop.download.DesktopDownloadProvider(directory.resolve("downloads"))
                fun identity(chapter: Chapter) = mihon.domain.reader.content.DownloadChapterIdentity(
                    sourceDisplayName = source.toString(),
                    mangaTitle = manga.title,
                    chapterName = chapter.name,
                    scanlator = chapter.scanlator,
                    chapterUrl = chapter.url,
                    disallowNonAsciiFilenames = false,
                )
                val oldDirectory = provider.canonicalChapterDownloadDir(identity(old)).apply { mkdirs() }
                val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                    setRGB(0, 0, 0xFFFF0000.toInt())
                }
                javax.imageio.ImageIO.write(image, "png", oldDirectory.resolve("001.png"))
                LibraryUpdateChecker(
                    s.chapters,
                    s.mangas,
                    renameDirectoryChapter = { phase, change -> provider.renameDirectoryChapter(phase, change) },
                ).checkForUpdates(manga, source)
                val current = s.chapters.getChapterByMangaId(manga.id).single()
                val artifact = provider.downloadArtifactLookup(manga.source).locate(identity(current))
                assertNotNull(artifact, "A committed rename must preserve the real downloaded reader artifact")
                val pages = provider.getDownloadedPages(File(artifact!!.opaqueLocation))
                assertEquals(1, pages.size)
                assertEquals(0xFFFF0000.toInt(), javax.imageio.ImageIO.read(pages.single()).getRGB(0, 0))
            }
        }

    @Test
    fun `Desktop alias restore conflict rejects the manga unit and preserves the existing directory`() =
        runBlocking<Unit> {
            Storage(directory.resolve("backup-conflict.db")).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 42,
                            url = "/work",
                            title = "Work",
                            favorite = true,
                            initialized = true,
                        ),
                    ),
                ).single()
                val original = s.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/current",
                            name = "Current",
                            lastPageRead = 8,
                            read = true,
                        ),
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/occupied",
                            name = "Occupied",
                            bookmark = true,
                        ),
                    ),
                )
                val backup = mihon.desktop.backup.models.Backup(
                    backupManga = listOf(
                        mihon.desktop.backup.models.BackupManga(
                            source = 42,
                            url = manga.url,
                            title = manga.title,
                            favorite = true,
                            chapters = listOf(
                                mihon.desktop.backup.models.BackupChapter(
                                    url = "/current",
                                    name = "Changed",
                                    urlAliases = listOf("/old", "/occupied"),
                                    canonicalUrl = "/old",
                                ),
                            ),
                        ),
                    ),
                )
                val restorer = mihon.desktop.backup.DesktopBackupRestorer(
                    s.mangas,
                    s.chapters,
                    tachiyomi.data.category.CategoryRepositoryImpl(s.handler),
                    tachiyomi.data.history.HistoryRepositoryImpl(s.handler),
                    backupRestoreSync = mihon.data.sync.journal.SyncBackupRestorer(
                        s.handler,
                        tachiyomi.domain.creator.repository.ReadyCreatorArchiveBootstrap,
                    ),
                )
                assertEquals(true, restorer.restore(backup).hasErrors)
                assertEquals(original, s.chapters.getChapterByMangaId(manga.id))
                assertEquals(null, s.chapters.getChapterByUrlAndMangaId("/old", manga.id))
            }
        }

    @Test
    fun `original source date observations are idempotent when phase acknowledgement fails and restarts`() =
        runBlocking<Unit> {
            val path = directory.resolve("observation-phase.db")
            var phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase? = null
            var first: tachiyomi.domain.creator.model.SourceDateQualitySnapshot? = null
            val identity = tachiyomi.domain.creator.model.SourceDateQualityIdentity(
                "test.extension",
                "1",
                42,
                tachiyomi.domain.creator.model.SourceDateField.CHAPTER_UPDATED,
            )
            Storage(path).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work")),
                ).single()
                val archive = tachiyomi.data.creator.CreatorRepositoryImpl(s.handler)
                val rows =
                    listOf(
                        remote("/missing", "Chapter 1", 1.0),
                        remote("/dated", "Chapter 2", 2.0).apply {
                            date_upload =
                                1000
                        },
                    )
                s.driver.execute(
                    null,
                    "CREATE TRIGGER reject_phase_ack BEFORE DELETE ON chapter_directory_phases BEGIN SELECT " +
                        "RAISE(ABORT,'ack refused'); END",
                    0,
                )
                assertThrows(Exception::class.java) {
                    runBlocking {
                        LibraryUpdateChecker(
                            s.chapters,
                            s.mangas,
                            archive,
                            sourceDateExtensionIdentityProvider = {
                                tachiyomi.domain.creator.model.SourceDateExtensionIdentity("test.extension", "1")
                            },
                        ).checkForUpdates(manga, source(rows))
                    }
                }
                phase = s.chapters.pendingDirectoryPhase(manga.id)
                assertNotNull(phase)
                first = archive.getSourceDateQualitySnapshot(identity)
                assertEquals(2, first!!.sampleCount)
                s.driver.execute(null, "DROP TRIGGER reject_phase_ack", 0)
            }
            Storage(path, false).use { s ->
                val archive = tachiyomi.data.creator.CreatorRepositoryImpl(s.handler)
                val manga = s.mangas.getMangaById(phase!!.mangaId)
                val unavailable = object : Source {
                    override val id = 42L
                    override val name = "Unavailable after restart"
                    override suspend fun getMangaUpdate(
                        manga: SManga,
                        chapters: List<SChapter>,
                        fetchDetails: Boolean,
                        fetchChapters: Boolean,
                    ): SMangaUpdate =
                        error("Recovery must not fetch a replacement response")
                }
                LibraryUpdateChecker(
                    s.chapters,
                    s.mangas,
                    archive,
                    sourceDateExtensionIdentityProvider = {
                        tachiyomi.domain.creator.model.SourceDateExtensionIdentity("test.extension", "2")
                    },
                ).checkForUpdates(manga, unavailable)
                assertEquals(
                    first,
                    archive.getSourceDateQualitySnapshot(identity),
                    "Replaying the same phase must not manufacture extra samples",
                )
                assertEquals(null, s.chapters.pendingDirectoryPhase(manga.id))
            }
        }

    @Test
    fun `directory collision keeps artifacts and ACK retry restores the moved file after restart`() =
        runBlocking<Unit> {
            val path = directory.resolve("file-phase.db")
            val provider = mihon.desktop.download.DesktopDownloadProvider(directory.resolve("phase-downloads"))
            val originalSource = source(listOf(remote("/new", "Revised chapter 2", 2.0)))
            var mangaId = 0L
            var oldPath: File? = null
            var targetPath: File? = null
            Storage(path).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work")),
                ).single()
                mangaId = manga.id
                val chapter = s.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                            scanlator = "Group",
                        ),
                    ),
                ).single()
                fun identity(
                    url: String,
                    name: String,
                ) = mihon.domain.reader.content.DownloadChapterIdentity(
                    originalSource.toString(),
                    manga.title,
                    name,
                    "Group",
                    url,
                    false,
                )
                oldPath = provider.canonicalChapterDownloadDir(identity(chapter.url, chapter.name)).apply { mkdirs() }
                oldPath!!.resolve("001.png").writeBytes(byteArrayOf(1, 2, 3))
                targetPath =
                    provider.canonicalChapterDownloadDir(identity("/new", "Revised chapter 2")).apply { mkdirs() }
                targetPath!!.resolve("unrelated.png").writeBytes(byteArrayOf(9))
                val checker = LibraryUpdateChecker(
                    s.chapters,
                    s.mangas,
                    renameDirectoryChapter = { p, change -> provider.renameDirectoryChapter(p, change) },
                )
                assertThrows(Exception::class.java) { runBlocking { checker.checkForUpdates(manga, originalSource) } }
                assertEquals(listOf<Byte>(1, 2, 3), oldPath!!.resolve("001.png").readBytes().toList())
                assertEquals(listOf<Byte>(9), targetPath!!.resolve("unrelated.png").readBytes().toList())
                assertNotNull(s.chapters.pendingDirectoryPhase(manga.id))
                targetPath!!.resolve("unrelated.png").delete()
                targetPath!!.delete()
                s.driver.execute(
                    null,
                    "CREATE TRIGGER reject_phase_ack BEFORE DELETE ON chapter_directory_phases BEGIN SELECT " +
                        "RAISE(ABORT,'ack refused'); END",
                    0,
                )
                assertThrows(Exception::class.java) { runBlocking { checker.checkForUpdates(manga, originalSource) } }
                assertEquals(false, oldPath!!.exists())
                assertEquals(listOf<Byte>(1, 2, 3), targetPath!!.resolve("001.png").readBytes().toList())
                s.driver.execute(null, "DROP TRIGGER reject_phase_ack", 0)
            }
            Storage(path, false).use { s ->
                LibraryUpdateChecker(
                    s.chapters,
                    s.mangas,
                    renameDirectoryChapter = { p, change -> provider.renameDirectoryChapter(p, change) },
                ).checkForUpdates(s.mangas.getMangaById(mangaId), originalSource)
                assertEquals(null, s.chapters.pendingDirectoryPhase(mangaId))
                assertEquals(listOf<Byte>(1, 2, 3), targetPath!!.resolve("001.png").readBytes().toList())
            }
        }

    @Test
    fun `legacy chapter protobuf has empty alias fields and retains source memo`() {
        val bytes = byteArrayOf(0x0a, 4, 47, 111, 108, 100, 0x12, 4, 78, 97, 109, 101)
        val decoded = kotlinx.serialization.protobuf.ProtoBuf.decodeFromByteArray(
            eu.kanade.tachiyomi.data.backup.models.BackupChapter.serializer(),
            bytes,
        )
        assertEquals("/old", decoded.url)
        assertEquals("Name", decoded.name)
        assertEquals(emptyList<String>(), decoded.urlAliases)
        assertEquals(null, decoded.canonicalUrl)
        assertEquals(tachiyomi.data.JsonObjectEmptyBytes.toList(), decoded.memo.toList())
    }

    @Test
    fun `actual DI queued directory mutation is refused before SQL and keeps accepted download identity`() =
        runBlocking<Unit> {
            val previous = Injekt
            Injekt =
                uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
            var context: mihon.desktop.di.DesktopTestDIContext? = null
            try {
                context = mihon.desktop.di.initDesktopDIForTest(
                    directory.resolve("queue-di"),
                    mihon.desktop.di.isolatedDesktopPreferenceStore(),
                    startDownloadWorker = false,
                )
                val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                val chapters = Injekt.get<ChapterRepository>()
                val manga = mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                val old = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                            scanlator = "Group",
                        ),
                    ),
                ).single()
                val manager = Injekt.get<mihon.desktop.download.DesktopDownloadManager>()
                manager.pauseAll()
                org.junit.jupiter.api.Assertions.assertTrue(
                    manager.enqueue(
                        mihon.desktop.download.DownloadItem(42, manga.title, old.name, old.id, manga.id, old.url),
                    ),
                )
                val accepted = manager.queue.value
                val checker = Injekt.get<LibraryUpdateChecker>()
                runCatching {
                    checker.checkForUpdates(manga, source(listOf(remote("/new", "Chapter 2 corrected", 2.0))))
                }
                assertEquals(
                    listOf(old),
                    chapters.getChapterByMangaId(manga.id),
                    "Queued chapter identity must stay unchanged until the user completes or " +
                        "cancels its accepted download",
                )
                assertEquals(accepted, manager.queue.value)
                assertEquals(true, manager.isPaused.value)
                assertEquals(null, chapters.pendingDirectoryPhase(manga.id))
                // Pure source order changes do not invalidate the accepted download URL.
                checker.checkForUpdates(
                    manga,
                    source(listOf(remote("/3", "Chapter 3", 3.0), remote(old.url, old.name, 2.0))),
                )
                assertEquals(old.url, chapters.getChapterById(old.id)!!.url)
                assertEquals(1L, chapters.getChapterById(old.id)!!.sourceOrder)
                assertEquals(accepted, manager.queue.value)
                assertEquals(true, manager.cancelAndAwaitRetirements(listOf(old.id)))
                assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                    runBlocking {
                        manager.withDirectoryChanges(setOf(old.id)) {
                            assertEquals(false, manager.enqueue(accepted.single()))
                            throw kotlinx.coroutines.CancellationException("Cancelled directory owner")
                        }
                    }
                }
                assertEquals(
                    true,
                    manager.enqueue(accepted.single()),
                    "Cancelled directory work must release its original enqueue reservation",
                )
            } finally {
                try {
                    context?.closeAndJoin()
                } finally {
                    Injekt = previous
                }
            }
        }

    @Test
    fun `older observation phase replay keeps newer current version and same bucket evidence`() = runBlocking<Unit> {
        Storage(directory.resolve("observation-order.db")).use { s ->
            val archive = tachiyomi.data.creator.CreatorRepositoryImpl(s.handler)
            val field = tachiyomi.domain.creator.model.SourceDateField.CHAPTER_UPDATED
            val old = tachiyomi.domain.creator.model.SourceDateQualityIdentity("test.extension", "1", 42, field)
            val newer = old.copy(extensionVersion = "2")
            fun observation(identity: tachiyomi.domain.creator.model.SourceDateQualityIdentity, at: Long) =
                tachiyomi.domain.creator.model.SourceDateObservation(
                    identity,
                    "/work",
                    "/chapter",
                    valueAt = 1000,
                    precision = tachiyomi.domain.creator.model.SourceDatePrecision.DAY,
                    semanticConfirmed = true,
                    observedAt = at,
                )
            archive.recordSourceDateQualityObservations(listOf(observation(old, 2000)), 2000)
            val latestBucket = archive.recordSourceDateQualityObservations(listOf(observation(old, 90000000)), 90000000)
            archive.recordSourceDateQualityObservations(listOf(observation(newer, 90000001)), 90000001)
            archive.recordSourceDateQualityObservations(listOf(observation(old, 2000)), 2000)
            val current = s.driver.executeQuery(
                null,
                "SELECT extension_version, updated_at FROM author_archive_source_date_quality_current " +
                    "WHERE source_id=42",
                { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0) to cursor.getLong(1))
                },
                0,
            ).value
            assertEquals(
                "2" to 90000001L,
                current,
                "A pending old phase cannot move the active extension clock backwards",
            )
            assertEquals(
                latestBucket,
                archive.getSourceDateQualitySnapshot(old),
                "Old replay cannot discard newer same-version observations",
            )
        }
    }

    @Test
    fun `real MangaDex HTTP failures and incomplete pagination never publish a destructive directory`() =
        runBlocking<Unit> {
            mockwebserver3.MockWebServer().use { server ->
                server.start()
                Storage(directory.resolve("http-directory.db")).use { s ->
                    val source = mihon.desktop.source.MangaDexSource(
                        okhttp3.OkHttpClient(),
                        Json,
                        server.url("/").toString().trimEnd('/'),
                        browserJsonFetcher = null,
                    )
                    val manga = s.mangas.insertNetworkManga(
                        listOf(Manga.create().copy(source = source.id, url = "/manga/work", title = "Work")),
                    ).single()
                    val old = s.chapters.addAll(
                        listOf(
                            Chapter.create().copy(
                                mangaId = manga.id,
                                url = "/chapter/old",
                                name = "Old",
                                read = true,
                                bookmark = true,
                                lastPageRead = 9,
                            ),
                        ),
                    ).single()
                    val checker = LibraryUpdateChecker(s.chapters, s.mangas)
                    for ((code, body) in listOf(
                        200 to """{"data":[],"total":0}""",
                        200 to "malformed",
                        403 to "Forbidden",
                        429 to "Rate limited",
                        500 to "Failure",
                    )) {
                        server.enqueue(mockwebserver3.MockResponse.Builder().code(code).body(body).build())
                        val result = runCatching { checker.checkForUpdates(manga, source) }
                        org.junit.jupiter.api.Assertions.assertTrue(
                            result.isFailure || result.getOrNull()?.sourceError != null,
                        )
                        assertEquals(listOf(old), s.chapters.getChapterByMangaId(manga.id))
                    }
                    val item = """{"id":"new",
                "attributes":{"chapter":"2","title":"New","volume":null},
                "relationships":[]}"""
                    val firstPage = (1..500).joinToString(",") { n ->
                        """{"id":"c$n",
                    "attributes":{"chapter":"$n","title":"Chapter $n","volume":null},
                    "relationships":[]}"""
                    }
                    server.enqueue(
                        mockwebserver3.MockResponse.Builder().body("""{"data":[$firstPage],"total":501}""").build(),
                    )
                    server.enqueue(
                        mockwebserver3.MockResponse.Builder().body("""{"result":"ok","total":501}""").build(),
                    )
                    val partial = runCatching { checker.checkForUpdates(manga, source) }
                    assertEquals(
                        listOf(old),
                        s.chapters.getChapterByMangaId(manga.id),
                        "A missing pagination body is an incomplete response, not a replacement directory",
                    )
                    org.junit.jupiter.api.Assertions.assertTrue(
                        partial.isFailure || partial.getOrNull()?.sourceError != null,
                    )
                    server.enqueue(mockwebserver3.MockResponse.Builder().body("""{"data":[$item],"total":1}""").build())
                    assertEquals(1, checker.checkForUpdates(manga, source).newChapterCount)
                    assertEquals(listOf("/chapter/new"), s.chapters.getChapterByMangaId(manga.id).map { it.url })
                }
            }
        }

    @Test
    fun `persistent queue acceptance resumes linked Browse phase without redelivery or network`() =
        runBlocking<Unit> {
            val previous = Injekt
            Injekt =
                uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
            val root = directory.resolve("queue-ack")
            val store = mihon.desktop.di.isolatedDesktopPreferenceStore()
            var context: mihon.desktop.di.DesktopTestDIContext? = null
            var reject = true
            var mangaId = 0L
            var accepted: mihon.desktop.download.DownloadItem? = null
            try {
                context = mihon.desktop.di.initDesktopDIForTest(
                    root,
                    store,
                    startDownloadWorker = false,
                    chapterRepositoryOverride = { actual ->
                        object : ChapterRepository by actual {
                            override suspend fun acknowledgeDirectoryPhase(
                                phase: tachiyomi.domain.chapter.service.ChapterDirectoryPhase,
                            ) {
                                if (reject && phase.complete) throw java.io.IOException("Acknowledgement refused")
                                actual.acknowledgeDirectoryPhase(phase)
                            }
                        }
                    },
                )
                val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                val chapters = Injekt.get<ChapterRepository>()
                val manga = mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                mangaId = manga.id
                Injekt.get<DownloadPreferences>().downloadNewChapters().set(
                    true,
                )
                val checker = Injekt.get<LibraryUpdateChecker>()
                assertThrows(java.io.IOException::class.java) {
                    runBlocking { checker.checkForUpdates(manga, source(listOf(remote("/new", "Chapter 1", 1.0)))) }
                }
                val pending = requireNotNull(chapters.pendingDirectoryPhase(manga.id))
                assertEquals(true, pending.downloadsPending)
                accepted =
                    Injekt.get<mihon.desktop.download.DesktopDownloadManager>().queue.value.single()
                assertEquals(pending.downloadIds.single(), accepted!!.chapterId)
                context.closeAndJoin()
                context = null
                reject = false
                // A different current preference and Browse origin cannot replace the accepted original policy.
                Injekt.get<DownloadPreferences>().downloadNewChapters().set(
                    false,
                )
                context = mihon.desktop.di.initDesktopDIForTest(root, store, startDownloadWorker = false)
                val recoverySource = object : Source {
                    override val id = 42L
                    override val name = "Unavailable"
                    override suspend fun getMangaUpdate(
                        manga: SManga,
                        chapters: List<SChapter>,
                        fetchDetails: Boolean,
                        fetchChapters: Boolean,
                    ): SMangaUpdate =
                        error("Pending Browse recovery must precede replacement network")
                }
                val linked = Injekt.get<SaveSourceMangaForDetails>().awaitLinkedChapter(
                    recoverySource,
                    SManga.create().apply {
                        url = "/work"
                        title = "Do not overwrite"
                    },
                    remote("/new", "Chapter 1", 1.0),
                )
                assertEquals(mangaId, linked.manga.id)
                assertEquals("Work", linked.manga.title)
                assertEquals(accepted!!.chapterId, linked.chapter!!.id)
                assertEquals(
                    null,
                    Injekt.get<ChapterRepository>().pendingDirectoryPhase(
                        mangaId,
                    ),
                )
                assertEquals(
                    listOf(accepted),
                    Injekt.get<mihon.desktop.download.DesktopDownloadManager>().queue.value,
                )
            } finally {
                try {
                    context?.closeAndJoin()
                } finally {
                    Injekt = previous
                }
            }
        }

    @Test
    fun `actual creator index write refusal rolls back the whole metadata and directory transaction`() =
        runBlocking<Unit> {
            val previous = Injekt
            Injekt =
                uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
            var context: mihon.desktop.di.DesktopTestDIContext? = null
            try {
                val root = directory.resolve("creator-rollback")
                context =
                    mihon.desktop.di.initDesktopDIForTest(
                        root,
                        mihon.desktop.di.isolatedDesktopPreferenceStore(),
                        startDownloadWorker = false,
                    )
                val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                val chapters = Injekt.get<ChapterRepository>()
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 42,
                            url = "/work",
                            title = "Work",
                            author = "Original",
                            favorite = true,
                        ),
                    ),
                ).single()
                val old = chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "/old", name = "Old", read = true)),
                ).single()
                JdbcSqliteDriver("jdbc:sqlite:" + root.resolve("mihon.db").absolutePath).use { driver ->
                    driver.execute(
                        null,
                        "CREATE TRIGGER refuse_creator_link BEFORE INSERT ON author_archive_manga_links BEGIN " +
                            "SELECT RAISE(ABORT,'index refused'); END",
                        0,
                    )
                    assertThrows(Exception::class.java) {
                        runBlocking {
                            chapters.syncDirectory(
                                tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                                    manga.id,
                                    tachiyomi.domain.chapter.service.ChapterDirectoryPlan.prepare(
                                        manga,
                                        listOf(remote("/new", "Chapter 2", 2.0)),
                                    ),
                                    2000,
                                    mangaMetadata = tachiyomi.domain.manga.model.MangaUpdate(
                                        manga.id,
                                        author = "New author",
                                        updateAuthor = true,
                                    ),
                                ),
                            )
                        }
                    }
                    assertEquals(manga, mangas.getMangaById(manga.id))
                    assertEquals(listOf(old), chapters.getChapterByMangaId(manga.id))
                    driver.execute(null, "DROP TRIGGER refuse_creator_link", 0)
                }
            } finally {
                try {
                    context?.closeAndJoin()
                } finally {
                    Injekt = previous
                }
            }
        }

    @Test
    fun `real complete first page followed by empty or regressed total cannot publish partial chapters`() =
        runBlocking<Unit> {
            val failures = mutableListOf<Throwable>()
            for ((index, next) in listOf(
                """{"data":[],"total":501}""",
                """{"data":[{"id":"last","attributes":{"chapter":"501","title":"Last"},"relationships":[]}]}""",
                """{"data":[{"id":"last",
                "attributes":{"chapter":"501","title":"Last"},
                "relationships":[]}],"total":500}""",
            ).withIndex()) {
                mockwebserver3.MockWebServer().use { server ->
                    server.start()
                    Storage(directory.resolve("partial-$index.db")).use { s ->
                        val source = mihon.desktop.source.MangaDexSource(
                            okhttp3.OkHttpClient(),
                            Json,
                            server.url("/").toString().trimEnd('/'),
                            browserJsonFetcher = null,
                        )
                        val manga = s.mangas.insertNetworkManga(
                            listOf(Manga.create().copy(source = source.id, url = "/manga/work", title = "Work")),
                        ).single()
                        val old = s.chapters.addAll(
                            listOf(
                                Chapter.create().copy(
                                    mangaId = manga.id,
                                    url = "/chapter/old",
                                    name = "Old",
                                    read = true,
                                    lastPageRead = 9,
                                ),
                            ),
                        ).single()
                        val first = (1..500).joinToString(",") { n ->
                            """{"id":"c$n",
                        "attributes":{"chapter":"$n","title":"Chapter $n","volume":null},
                        "relationships":[]}"""
                        }
                        server.enqueue(
                            mockwebserver3.MockResponse.Builder().body("""{"data":[$first],"total":501}""").build(),
                        )
                        server.enqueue(mockwebserver3.MockResponse.Builder().body(next).build())
                        val result =
                            runCatching { LibraryUpdateChecker(s.chapters, s.mangas).checkForUpdates(manga, source) }
                        runCatching {
                            assertEquals(
                                listOf(old),
                                s.chapters.getChapterByMangaId(manga.id),
                                "Incomplete page variant $index must retain the old directory",
                            )
                            org.junit.jupiter.api.Assertions.assertTrue(
                                result.isFailure || result.getOrNull()?.sourceError != null,
                            )
                        }.exceptionOrNull()?.let(failures::add)
                    }
                }
            }
            org.junit.jupiter.api.Assertions.assertAll(
                failures.map { error ->
                    org.junit.jupiter.api.function.Executable { throw error }
                },
            )
        }

    @Test
    fun `real complete 500 plus 1 pagination commits every parsed chapter`() = runBlocking<Unit> {
        mockwebserver3.MockWebServer().use { server ->
            server.start()
            Storage(directory.resolve("complete-pagination.db")).use { storage ->
                val source = mihon.desktop.source.MangaDexSource(
                    okhttp3.OkHttpClient(),
                    Json,
                    server.url("/").toString().trimEnd('/'),
                    browserJsonFetcher = null,
                )
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = source.id, url = "/manga/work", title = "Work")),
                ).single()
                fun page(numbers: IntRange) = numbers.joinToString(",") { n ->
                    """{"id":"c$n","attributes":{"chapter":"$n","title":"Chapter $n"},"relationships":[]}"""
                }
                server.enqueue(
                    mockwebserver3.MockResponse.Builder().body("""{"data":[${page(1..500)}],"total":501}""").build(),
                )
                server.enqueue(
                    mockwebserver3.MockResponse.Builder().body("""{"data":[${page(501..501)}],"total":501}""").build(),
                )
                val result = LibraryUpdateChecker(storage.chapters, storage.mangas).checkForUpdates(manga, source)
                assertEquals(null, result.sourceError)
                assertEquals(501, storage.chapters.getChapterByMangaId(manga.id).size)
                assertEquals(
                    (1..501).map {
                        "/chapter/c$it"
                    }.toSet(),
                    storage.chapters.getChapterByMangaId(manga.id).map { it.url }.toSet(),
                )
                assertEquals(501, result.newChapters.size)
            }
        }
    }

    @Test
    fun `actual bound library scheduler reports HTTP refusal and retries through the same committed directory core`() =
        runBlocking<Unit> {
            val previous = Injekt
            Injekt =
                uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
            var context: mihon.desktop.di.DesktopTestDIContext? = null
            try {
                mockwebserver3.MockWebServer().use { server ->
                    server.start()
                    val source = mihon.desktop.source.MangaDexSource(
                        okhttp3.OkHttpClient(),
                        Json,
                        server.url("/").toString().trimEnd('/'),
                        browserJsonFetcher = null,
                    )
                    context = mihon.desktop.di.initDesktopDIForTest(
                        directory.resolve("library-http"),
                        mihon.desktop.di.isolatedDesktopPreferenceStore(),
                        startDownloadWorker = false,
                        builtInSources = listOf(source),
                    )
                    val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
                    val chapters = Injekt.get<ChapterRepository>()
                    val manga = mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = source.id,
                                url = "/manga/work",
                                title = "Work",
                                favorite = true,
                            ),
                        ),
                    ).single()
                    val original = chapters.addAll(
                        listOf(
                            Chapter.create().copy(
                                mangaId = manga.id,
                                url = "/chapter/old",
                                name = "Ch.2",
                                chapterNumber = 2.0,
                                read = true,
                                bookmark = true,
                                lastPageRead = 8,
                            ),
                        ),
                    ).single()
                    val scheduler = Injekt.get<LibraryUpdateScheduler>()
                    assertEquals(
                        source,
                        Injekt.get<tachiyomi.domain.source.service.SourceManager>().get(source.id),
                    )
                    server.enqueue(mockwebserver3.MockResponse.Builder().code(500).body("Unavailable").build())
                    scheduler.runNow().join()
                    assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()!!.status)
                    assertEquals(listOf(original), chapters.getChapterByMangaId(manga.id))
                    server.enqueue(
                        mockwebserver3.MockResponse.Builder().body(
                            """{"data":[{"id":"new",
                        "attributes":{"chapter":"2","title":"Corrected"},
                        "relationships":[]},
                        {"id":"three",
                        "attributes":{"chapter":"3"},
                        "relationships":[]}],"total":2}""",
                        ).build(),
                    )
                    scheduler.runNow().join()
                    assertEquals(mihon.domain.task.TaskStatus.Completed, scheduler.taskSnapshot()!!.status)
                    assertEquals(
                        setOf("/chapter/new", "/chapter/three"),
                        chapters.getChapterByMangaId(manga.id).map {
                            it.url
                        }.toSet(),
                    )
                    val kept = chapters.getChapterById(original.id)!!
                    assertEquals("/chapter/new", kept.url)
                    assertEquals(8L, kept.lastPageRead)
                    assertEquals(true, kept.bookmark)
                    assertEquals(null, chapters.pendingDirectoryPhase(manga.id))
                }
            } finally {
                try {
                    context?.closeAndJoin()
                } finally {
                    Injekt = previous
                }
            }
        }

    private class Storage(path: File, create: Boolean = true) : AutoCloseable {
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${path.absolutePath}",
            java.util.Properties().apply { setProperty("foreign_keys", "true") },
        ).also {
            it.execute(null, "PRAGMA foreign_keys=ON", 0)
        }
        val database = run {
            if (create) Database.Schema.create(driver)
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val handler = JvmDatabaseHandler(database, driver)
        val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val chapters = ChapterRepositoryImpl(handler)
        override fun close() = driver.close()
    }
}
