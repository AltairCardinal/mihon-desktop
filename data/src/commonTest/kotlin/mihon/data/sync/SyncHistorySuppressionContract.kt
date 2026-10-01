package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.inbox.SyncInboxStore
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.creator.CreatorArchiveLegacyBootstrap
import tachiyomi.data.creator.CreatorArchiveLegacyBridge
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.model.ReadingProgressEvent
import java.io.File
import java.util.Date

@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SyncHistorySuppressionContract {
    protected abstract fun open(path: String? = null, create: Boolean = true): Storage
    private val repository = SyncRepository("owner", "history-sync", "mihon-sync")
    private val mangaKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = "42", originalUrl = "/manga")
    private val chapterKey = SyncObjectKey(
        SyncObjectType.CHAPTER,
        sourceId = "42",
        originalUrl = "/chapter",
        parentUrl = "/manga",
    )

    @Test
    fun `chapter clear uses history identity and preserves other history and duration`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val other = s.localChapter("/other", "/other-chapter")
            s.receive(history(1))
            val chapter = s.chapter(chapterKey)
            s.handler.await {
                historyQueries.upsert(chapter.id, Date(1000), 17)
                historyQueries.upsert(other.id, Date(2000), 23)
            }
            // Deliberately make the history primary key differ from its chapter primary key.
            s.driver.execute(null, "UPDATE history SET _id = 800 WHERE chapter_id = ${chapter.id}", 0)
            s.history.resetHistory(800)
            s.rebuild()
            assertEquals(0L, s.readAt(chapter.id))
            assertEquals(2000L, s.readAt(other.id))
            assertEquals(40L, s.history.getTotalReadDuration())
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `manga clear includes received history that has not been projected`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            val existing = s.localChapter("/manga", "/existing")
            assertTrue(s.inbox.ingest(history(1).copy(objects = emptyList())).accepted)
            s.projectAll()
            assertEquals(
                null,
                s.handler.await {
                    sync_projectionQueries.getChapterByIdentity(
                        chapterUrl = "/chapter",
                        mangaUrl = "/manga",
                        sourceId = 42,
                    ).executeAsOneOrNull()
                },
            )
            s.history.resetHistoryByMangaId(existing.mangaId)
            s.handler.await {
                sync_inboxQueries.insertDescription(
                    "space",
                    1,
                    chapterKey.stableKey,
                    kotlinx.serialization.json.Json.encodeToString(SyncObjectDescriptor(chapterKey, "章节")),
                )
            }
            s.projector.retryUnavailable("space", 1)
            s.projectAll()
            assertEquals(
                0L,
                s.handler.await { historyQueries.getHistoryByMangaId(existing.mangaId).executeAsList().size.toLong() },
            )
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `all clear survives reopen duplicate receipt and projection cache rebuild`() = runBlocking {
        val file = File.createTempFile("sync-history-clear-", ".db")
        try {
            open(file.absolutePath).use { s ->
                s.connect(repository)
                s.receive(history(1))
                assertTrue(s.history.deleteAllHistory())
            }
            open(file.absolutePath, false).use { s ->
                s.connect(repository)
                assertTrue(s.inbox.ingest(history(1)).duplicate)
                s.rebuild()
                assertEquals(0L, s.readAt(s.chapter(chapterKey).id))
                assertEquals(0L, s.history.getTotalReadDuration())
            }
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun `baseline replay stays hidden while newer user reading and unknown actors can appear`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            s.receive(history(5))
            assertTrue(s.history.deleteAllHistory())
            s.receive(history(6, SyncOrigin.INITIAL_IMPORT))
            assertEquals(0L, s.readAt(s.chapter(chapterKey).id))
            s.receive(history(4))
            assertEquals(0L, s.readAt(s.chapter(chapterKey).id))
            s.receive(history(7))
            assertEquals(7000L, s.readAt(s.chapter(chapterKey).id))
            assertTrue(s.history.deleteAllHistory())
            // A never-observed actor cannot be classified as old from this device's local watermark.
            s.receive(history(1, actor = "unseen"))
            assertEquals(1000L, s.readAt(s.chapter(chapterKey).id))
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
        }
    }

    @Test
    fun `all clear hides received missing description history before any business row exists`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            assertTrue(s.inbox.ingest(history(1).copy(objects = emptyList())).accepted)
            s.projectAll()
            assertEquals(
                null,
                s.handler.await { mangasQueries.getMangaByUrlAndSource("/manga", 42).executeAsOneOrNull() },
            )
            assertTrue(s.history.deleteAllHistory())
            s.handler.await {
                listOf(mangaKey to "漫画", chapterKey to "章节").forEach { (key, title) ->
                    sync_inboxQueries.insertDescription(
                        "space",
                        1,
                        key.stableKey,
                        kotlinx.serialization.json.Json.encodeToString(SyncObjectDescriptor(key, title)),
                    )
                }
            }
            s.projector.retryUnavailable("space", 1)
            s.projectAll()
            assertEquals(null, s.history.getLastHistory())
            assertEquals(1L, s.handler.await { sync_historyQueries.countClears().executeAsOne() })
        }
    }

    @Test
    fun `failed business clear rolls back suppression and preserves later projection`() = runBlocking {
        for (mode in listOf("chapter", "manga", "all")) {
            open().use { s ->
                s.connect(repository)
                s.receive(history(1))
                val chapter = s.chapter(chapterKey)
                val before = s.handler.await { historyQueries.getHistoryByMangaId(chapter.mangaId).executeAsOne() }
                val operation = if (mode == "all") "DELETE" else "UPDATE"
                s.driver.execute(
                    null,
                    "CREATE TRIGGER fail_clear BEFORE $operation ON history " +
                        "BEGIN SELECT RAISE(ABORT, 'injected'); END",
                    0,
                )
                when (mode) {
                    "chapter" -> s.history.resetHistory(before._id)
                    "manga" -> s.history.resetHistoryByMangaId(chapter.mangaId)
                    else -> assertFalse(s.history.deleteAllHistory())
                }
                assertEquals(
                    before,
                    s.handler.await { historyQueries.getHistoryByMangaId(chapter.mangaId).executeAsOne() },
                )
                assertEquals(0L, s.handler.await { sync_historyQueries.countClears().executeAsOne() })
                s.driver.execute(null, "DROP TRIGGER fail_clear", 0)
                s.receive(history(2))
                assertEquals(2000L, s.readAt(chapter.id))
            }
        }
    }

    @Test
    fun `failed suppression write cannot clear business history`() = runBlocking {
        for (mode in listOf("chapter", "manga", "all")) {
            open().use { s ->
                s.connect(repository)
                s.receive(history(1))
                val chapter = s.chapter(chapterKey)
                val before = s.handler.await { historyQueries.getHistoryByMangaId(chapter.mangaId).executeAsOne() }
                s.driver.execute(
                    null,
                    "CREATE TRIGGER fail_mask BEFORE INSERT ON sync_history_clears " +
                        "BEGIN SELECT RAISE(ABORT, 'injected'); END",
                    0,
                )
                when (mode) {
                    "chapter" -> s.history.resetHistory(before._id)
                    "manga" -> s.history.resetHistoryByMangaId(chapter.mangaId)
                    else -> assertFalse(s.history.deleteAllHistory())
                }
                assertEquals(
                    before,
                    s.handler.await { historyQueries.getHistoryByMangaId(chapter.mangaId).executeAsOne() },
                )
                assertEquals(0L, s.handler.await { sync_historyQueries.countClears().executeAsOne() })
            }
        }
    }

    @Test
    fun `real local user reading remains visible after a history clear without a deletion outbox`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            s.receive(history(1))
            val chapter = s.chapter(chapterKey)
            assertTrue(s.history.deleteAllHistory())
            assertTrue(s.journal.pendingEvents("space", 1).isEmpty())
            s.handler.await {
                SqlDelightReadingProgressRepository(this).record(
                    ReadingProgressEvent(chapter.id, 3, 10, Date(3000), 11, syncContext = SyncMutationContext.User),
                )
            }
            s.rebuild()
            assertEquals(3000L, s.readAt(chapter.id))
            assertEquals(11L, s.history.getTotalReadDuration())
            assertEquals(SyncCategory.READING, s.journal.pendingEvents("space", 1).single().category)
        }
    }

    @Test
    fun `clear resets only matching shareable history without read page or duration changes`() = runBlocking {
        for (mode in listOf("chapter", "manga", "all")) {
            open().use { s ->
                val first = s.localChapter("/first", "/first-chapter")
                val other = s.localChapter("/other", "/other-chapter")
                s.handler.await {
                    historyQueries.upsert(first.id, Date(1000), 17)
                    historyQueries.upsert(other.id, Date(2000), 23)
                    sync_importQueries.markPrivateReading(first.id)
                    sync_importQueries.markPrivateReading(other.id)
                }
                val firstHistory = s.handler.await {
                    historyQueries.getHistoryByMangaId(first.mangaId).executeAsOne()
                }
                when (mode) {
                    "chapter" -> s.history.resetHistory(firstHistory._id)
                    "manga" -> s.history.resetHistoryByMangaId(first.mangaId)
                    else -> assertTrue(s.history.deleteAllHistory())
                }
                val shadows = s.handler.await {
                    sync_historyQueries.getPublicHistory(listOf(first.id, other.id)).executeAsList()
                }
                assertEquals(0L, shadows.single { it.chapter_id == first.id }.public_read_at)
                assertEquals(
                    if (mode == "all") 0L else 2000L,
                    shadows.single { it.chapter_id == other.id }.public_read_at,
                )
                assertTrue(shadows.all { it.public_read == 1L && it.public_page == 6L })
                assertEquals(if (mode == "all") 0L else 40L, s.history.getTotalReadDuration())
                assertEquals(first, s.chapters.getChapterById(first.id))
                assertEquals(other, s.chapters.getChapterById(other.id))
            }
        }
    }

    @Test
    fun `relinked current URL cannot bypass an accepted canonical chapter history clear`() = runBlocking {
        open().use { s ->
            s.connect(repository)
            s.receive(history(1))
            val chapter = s.chapter(chapterKey)
            s.chapters.update(tachiyomi.domain.chapter.model.ChapterUpdate(chapter.id, chapterNumber = 2.0))
            val recognized = requireNotNull(s.chapters.getChapterById(chapter.id))
            s.chapters.syncDirectory(
                tachiyomi.domain.chapter.service.ChapterDirectoryCommit(
                    chapter.mangaId,
                    listOf(
                        tachiyomi.domain.chapter.service.PreparedSourceChapter(
                            recognized.copy(url = "/new-chapter"),
                            0,
                        ),
                    ),
                    2000,
                ),
            )
            val historyId = s.handler.await { historyQueries.getHistoryByMangaId(chapter.mangaId).executeAsOne()._id }
            s.history.resetHistory(historyId)
            val currentKey = chapterKey.copy(originalUrl = "/new-chapter")
            val incoming = history(2, SyncOrigin.INITIAL_IMPORT).let { batch ->
                batch.copy(
                    events = batch.events.map { event ->
                        event.copy(
                            effects = event.effects.map { effect ->
                                effect.copy(
                                    payload = buildJsonObject {
                                        put("chapterKey", currentKey.stableKey)
                                        put("readAt", 2000)
                                    },
                                )
                            },
                        )
                    },
                    objects = batch.objects.map {
                        if (it.objectKey ==
                            chapterKey
                        ) {
                            it.copy(objectKey = currentKey)
                        } else {
                            it
                        }
                    },
                )
            }
            s.receive(incoming)
            assertEquals(0L, s.readAt(chapter.id), "A current URL alias must not resurrect cleared imported history")
            assertEquals(chapter.id, s.chapters.getChapterByUrlAndMangaId("/chapter", chapter.mangaId)?.id)
        }
    }

    private fun history(seq: Long, origin: SyncOrigin = SyncOrigin.USER, actor: String = "remote"): SyncBatch {
        val id = "$actor-$seq-${origin.name}"
        val event = SyncEventEnvelope(
            1, "space", 1, actor, 1, seq, SyncCategory.READING,
            listOf(
                SyncEffect(
                    "history",
                    mangaKey,
                    SyncField.READING_SUMMARY,
                    SyncEffectKind.READING_SUMMARY,
                    payload = buildJsonObject {
                        put("chapterKey", chapterKey.stableKey)
                        put("readAt", seq * 1000)
                    },
                ),
            ),
            origin, importId = if (origin == SyncOrigin.USER) null else "import", batchId = id,
        )
        return SyncBatch(
            1,
            "space",
            1,
            id,
            listOf(event),
            listOf(SyncObjectDescriptor(mangaKey, "漫画"), SyncObjectDescriptor(chapterKey, "章节")),
        )
    }

    protected fun database(driver: SqlDriver, create: Boolean): Database {
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val journal = SyncLocalJournal(handler)
        val inbox = SyncInboxStore(handler)
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val mangas = MangaRepositoryImpl(handler, creators)
        val chapters = ChapterRepositoryImpl(handler)
        val history = HistoryRepositoryImpl(handler)
        val sources = mutableSetOf(42L)
        val writer = SyncRemoteProjectionWriter(handler, creators, creators, bootstrap, sources::contains)
        val projector = SyncInboxProjector(handler, writer)
        suspend fun connect(repository: SyncRepository) = journal.connect("space", 1, repository, "local", 1)
        suspend fun localChapter(url: String, chapterUrl: String): Chapter {
            val manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = url, title = url)),
            ).single()
            return chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapterUrl,
                        name = chapterUrl,
                        read = true,
                        lastPageRead = 6,
                    ),
                ),
            ).single()
        }
        suspend fun receive(batch: SyncBatch) {
            assertTrue(inbox.ingest(batch).accepted)
            projectAll()
        }
        suspend fun projectAll() {
            repeat(30) { if (projector.project("space", 1) == 0) return }
            error("dirty fields")
        }
        suspend fun chapter(key: SyncObjectKey): Chapter = chapters.getChapterByMangaId(
            handler.await {
                mangasQueries.getMangaByUrlAndSource(requireNotNull(key.parentUrl), 42).executeAsOne()._id
            },
        ).single { it.url == key.originalUrl }
        suspend fun readAt(chapterId: Long): Long = handler.await {
            sync_projectionQueries.getChapterHistory(chapterId).executeAsOneOrNull()?.last_read?.time ?: 0
        }
        suspend fun rebuild() {
            driver.execute(null, "DELETE FROM sync_projected_history", 0)
            driver.execute(null, "UPDATE sync_field_state SET dirty = 1, revision = revision + 1", 0)
            projectAll()
        }
        override fun close() = driver.close()
    }
}
