package mihon.data.sync

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncProjectionUnavailable
import mihon.data.sync.projection.SyncProjectionUnavailableReason
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.transport.SyncRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.Date

/** Real business repositories and SQL nested under each platform's production transaction handler. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
abstract class SyncRemoteProjectionContract {
    protected abstract fun open(): Storage

    @Test
    fun `remote favorite reconstructs exact source identity and existing creator library index`() = runBlocking {
        open().use { s ->
            s.prepare()
            val descriptions = mapOf(mangaKey to SyncObjectDescriptor(mangaKey, "远端漫画", author = "作者甲"))
            assertNull(s.writer.localMembership(mangaKey))
            s.writer.applyMembership(mangaKey, true, descriptions::get)
            val manga = requireNotNull(s.manga.getMangaByUrlAndSourceId("/manga", SOURCE))
            assertEquals("远端漫画", manga.title)
            assertEquals(SOURCE, manga.source)
            assertTrue(manga.favorite)
            assertEquals(true, s.writer.localMembership(mangaKey))
            val creator = s.creators.getCreatorsAsFlow().first().single { it.displayName == "作者甲" }
            assertEquals(listOf(manga.id), s.creators.getMangaCreatorsForCreator(creator.id).map { it.mangaId })
            s.writer.applyMembership(mangaKey, false, unavailableDescription)
            assertFalse(s.manga.getMangaById(manga.id).favorite)
            assertTrue(s.creators.getMangaCreatorsForCreator(creator.id).isEmpty())
            s.assertNoOutbox()
        }
    }

    @Test
    fun `existing manga preserves preferences and categories without source or descriptors`() = runBlocking {
        open().use { s ->
            s.prepare()
            val manga = s.seedManga()
            s.manga.update(MangaUpdate(manga.id, viewerFlags = 7, chapterFlags = 319, notes = "本机备注"))
            s.manga.setMangaCategories(manga.id, listOf(0))
            val before = s.manga.getMangaById(manga.id)
            s.sources.clear()
            s.writer.applyMembership(mangaKey, true, unavailableDescription)
            val after = s.manga.getMangaById(manga.id)
            assertTrue(after.favorite)
            assertEquals(before.viewerFlags, after.viewerFlags)
            assertEquals(before.chapterFlags, after.chapterFlags)
            assertEquals(before.notes, after.notes)
            assertEquals(before.version, after.version)
            assertEquals(before.title, after.title)
            assertEquals(
                listOf(0L),
                s.handler.await {
                    categoriesQueries.getCategoriesByMangaId(manga.id).executeAsList().map { it.id }
                },
            )
            s.writer.applyMembership(mangaKey, false, unavailableDescription)
            assertEquals(false, s.writer.localMembership(mangaKey))
            assertEquals(before.version, s.manga.getMangaById(manga.id).version)
            s.assertNoOutbox()
        }
    }

    @Test
    fun `missing source and descriptions defer reconstruction and source arrival permits retry`() = runBlocking {
        open().use { s ->
            s.prepare()
            s.sources.clear()
            val describe = { key: SyncObjectKey -> SyncObjectDescriptor(key, "远端") }
            s.assertUnavailable(SyncProjectionUnavailableReason.SOURCE) {
                s.writer.applyMembership(mangaKey, true, describe)
            }
            assertNull(s.manga.getMangaByUrlAndSourceId("/manga", SOURCE))
            s.sources += SOURCE
            s.assertUnavailable(SyncProjectionUnavailableReason.DESCRIPTION) {
                s.writer.applyMembership(mangaKey, true) { null }
            }
            val wrong = mangaKey.copy(sourceId = "42")
            s.assertUnavailable(SyncProjectionUnavailableReason.IDENTITY) {
                s.writer.applyMembership(mangaKey, true) { SyncObjectDescriptor(wrong, "同名") }
            }
            assertNull(s.manga.getMangaByUrlAndSourceId("/manga", SOURCE))
            s.writer.applyMembership(mangaKey, true, describe)
            assertEquals(true, s.writer.localMembership(mangaKey))
            assertNull(s.writer.localMembership(wrong))
            s.assertNoOutbox()
        }
    }

    @Test
    fun `same named authors retain separate portable keys and local watch policy`() = runBlocking {
        open().use { s ->
            s.prepare()
            val a = authorKey("remote-a")
            val b = authorKey("remote-b")
            val describe = { key: SyncObjectKey -> SyncObjectDescriptor(key, "同名作者") }
            s.writer.applyMembership(a, true, describe)
            s.writer.applyMembership(b, true, describe)
            val aId = s.authorId("remote-a")
            val bId = s.authorId("remote-b")
            assertTrue(aId != bId)
            assertEquals(setOf(aId, bId), s.creators.getFollowedCreators().map { it.creatorId }.toSet())
            val policy = ArchiveWatchPolicy(aId, true, 123456, setOf(42), setOf("ja"), true, true, true, false)
            s.creators.upsertWatchPolicy(policy, 50)
            s.writer.applyMembership(a, false, unavailableDescription)
            assertEquals(policy.copy(enabled = false), s.creators.getWatchPolicy(aId))
            s.writer.applyMembership(a, true, unavailableDescription)
            assertEquals(policy, s.creators.getWatchPolicy(aId))
            assertEquals(true, s.writer.localMembership(b))
            s.assertNoOutbox()
        }
    }

    @Test
    fun `author redirects follow identity and deleted or cyclic identities remain unavailable`() = runBlocking {
        open().use { s ->
            s.prepare()
            val target = s.insertAuthor("target")
            val alias = s.insertAuthor("alias")
            s.merge(alias, target)
            s.writer.applyMembership(authorKey("alias"), true, unavailableDescription)
            assertEquals(listOf(target), s.creators.getFollowedCreators().map { it.creatorId })
            assertEquals(true, s.writer.localMembership(authorKey("alias")))
            val deleted = s.insertAuthor("deleted")
            s.driver.execute(null, "UPDATE author_archive_creators SET status='DELETED' WHERE _id=$deleted", 0)
            s.assertUnavailable(SyncProjectionUnavailableReason.IDENTITY) {
                s.writer.applyMembership(authorKey("deleted"), true) { SyncObjectDescriptor(it, "复活") }
            }
            val first = s.insertAuthor("cycle-a")
            val second = s.insertAuthor("cycle-b")
            s.merge(first, second)
            s.merge(second, first)
            s.assertUnavailable(SyncProjectionUnavailableReason.IDENTITY) {
                s.writer.applyMembership(authorKey("cycle-a"), true, unavailableDescription)
            }
            assertEquals(listOf(target), s.creators.getFollowedCreators().map { it.creatorId })
            s.assertNoOutbox()
        }
    }

    @Test
    fun `read resume and history preserve local settings and never replay reading duration`() = runBlocking {
        open().use { s ->
            s.prepare()
            val manga = s.seedManga()
            val chapter = s.seedChapter(manga.id)
            s.chapters.update(ChapterUpdate(chapter.id, bookmark = true, lastPageRead = 8))
            s.handler.await { historyQueries.upsert(chapter.id, Date(500), 77) }
            val beforeManga = s.manga.getMangaById(manga.id)
            val beforeChapter = requireNotNull(s.chapters.getChapterById(chapter.id))
            s.sources.clear()
            s.writer.applyReadStatus(chapterKey, true, unavailableDescription)
            assertTrue(requireNotNull(s.chapters.getChapterById(chapter.id)).read)
            assertEquals(8L, requireNotNull(s.chapters.getChapterById(chapter.id)).lastPageRead)
            s.writer.applyResume(mangaKey, chapterKey, 3, unavailableDescription)
            s.writer.applyHistory(mangaKey, chapterKey, 1000, unavailableDescription)
            s.writer.applyHistory(mangaKey, chapterKey, 1000, unavailableDescription)
            s.writer.applyHistory(mangaKey, chapterKey, 200, unavailableDescription)
            val history = s.handler.await { historyQueries.getHistoryByMangaId(manga.id).executeAsOne() }
            assertEquals(Date(1000), history.last_read)
            assertEquals(77L, history.time_read)
            assertEquals(3L, requireNotNull(s.chapters.getChapterById(chapter.id)).lastPageRead)
            s.writer.applyReadStatus(chapterKey, false, unavailableDescription)
            val after = requireNotNull(s.chapters.getChapterById(chapter.id))
            assertFalse(after.read)
            assertEquals(0L, after.lastPageRead)
            assertTrue(after.bookmark)
            assertEquals(beforeChapter.version, after.version)
            assertEquals(beforeManga.version, s.manga.getMangaById(manga.id).version)
            assertEquals(0L, s.handler.await { chaptersQueries.getChapterById(chapter.id).executeAsOne().is_syncing })
            s.assertNoOutbox()
        }
    }

    @Test
    fun `chapter reconstruction binds identity and missing metadata rolls back parent creation`() = runBlocking {
        open().use { s ->
            s.prepare()
            val descriptors = mapOf(
                mangaKey to SyncObjectDescriptor(mangaKey, "父漫画"),
                chapterKey to SyncObjectDescriptor(chapterKey, "第二话", chapterNumber = 2.5, sourceOrder = 9),
            )
            s.assertUnavailable(SyncProjectionUnavailableReason.DESCRIPTION) {
                s.writer.applyResume(mangaKey, chapterKey, 4) { descriptors[it].takeIf { it?.objectKey == mangaKey } }
            }
            assertNull(s.manga.getMangaByUrlAndSourceId("/manga", SOURCE))
            s.assertUnavailable(SyncProjectionUnavailableReason.IDENTITY) {
                s.writer.applyResume(mangaKey.copy(originalUrl = "/other"), chapterKey, 4, descriptors::get)
            }
            s.writer.applyResume(mangaKey, chapterKey, 4, descriptors::get)
            val manga = requireNotNull(s.manga.getMangaByUrlAndSourceId("/manga", SOURCE))
            val chapter = requireNotNull(s.chapters.getChapterByUrlAndMangaId("/chapter", manga.id))
            assertEquals("第二话", chapter.name)
            assertEquals(2.5, chapter.chapterNumber)
            assertEquals(9L, chapter.sourceOrder)
            assertEquals(4L, chapter.lastPageRead)
            assertFalse(chapter.read)
            s.writer.applyHistory(mangaKey, chapterKey, 1000, unavailableDescription)
            assertEquals(0L, s.handler.await { historyQueries.getReadDuration().executeAsOne() })
            assertFalse(s.manga.getMangaById(manga.id).favorite)
            s.assertNoOutbox()
        }
    }

    @Test
    fun `outer receive transaction rolls back business and author index after later failure`() = runBlocking {
        open().use { s ->
            s.prepare()
            var sawFavorite = false
            var sawFollow = false
            val failure = runCatching {
                s.handler.await(inTransaction = true) {
                    s.writer.applyMembership(mangaKey, true) { SyncObjectDescriptor(it, "待回滚", author = "索引作者") }
                    s.writer.applyMembership(authorKey("rollback"), true) { SyncObjectDescriptor(it, "关注作者") }
                    sawFavorite = s.writer.localMembership(mangaKey) == true
                    sawFollow = s.writer.localMembership(authorKey("rollback")) == true
                    error("synthetic later inbox failure")
                }
            }.exceptionOrNull()
            assertEquals("synthetic later inbox failure", failure?.message)
            assertTrue(sawFavorite)
            assertTrue(sawFollow)
            assertNull(s.manga.getMangaByUrlAndSourceId("/manga", SOURCE))
            assertTrue(s.creators.getCreatorsAsFlow().first().isEmpty())
            assertTrue(s.creators.getFollowedCreators().isEmpty())
            s.assertNoOutbox()
        }
    }

    protected fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    protected class Storage(val driver: SqlDriver, val handler: DatabaseHandler) : AutoCloseable {
        val bootstrap = CreatorArchiveLegacyBootstrap(CreatorArchiveLegacyBridge(handler))
        val creators = CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val manga = MangaRepositoryImpl(handler, creators)
        val chapters = ChapterRepositoryImpl(handler)
        val sources = mutableSetOf(SOURCE)
        val writer = SyncRemoteProjectionWriter(handler, creators, creators, bootstrap, sources::contains) { 1000 }

        suspend fun prepare() {
            writer.prepare()
            SyncLocalJournal(handler).connect("space", 1, SyncRepository("owner", "repo", "sync"), "device-a", 1)
        }
        suspend fun seedManga() = manga.insertNetworkManga(
            listOf(
                Manga.create().copy(source = SOURCE, url = "/manga", title = "本机漫画"),
            ),
        ).single()
        suspend fun seedChapter(mangaId: Long) = chapters.addAll(
            listOf(
                Chapter.create().copy(mangaId = mangaId, url = "/chapter", name = "本机章节"),
            ),
        ).single()
        suspend fun authorId(key: String) = handler.await {
            author_archiveQueries.getArchiveCreatorIdByPortableKey(key).executeAsOne()
        }
        suspend fun insertAuthor(key: String) = handler.await {
            author_archiveQueries.insertArchiveCreator(key, key, key, key, 1, 1)
            author_archiveQueries.getArchiveCreatorIdByPortableKey(key).executeAsOne()
        }
        fun merge(sourceId: Long, targetId: Long) {
            driver.execute(
                null,
                "UPDATE author_archive_creators SET status='MERGED', " +
                    "merged_into_creator_id=$targetId WHERE _id=$sourceId",
                0,
            )
        }
        suspend fun assertUnavailable(reason: SyncProjectionUnavailableReason, action: suspend () -> Unit) {
            val error = runCatching { action() }.exceptionOrNull()
            assertTrue(error is SyncProjectionUnavailable, "Expected $reason, got $error")
            assertEquals(reason, (error as SyncProjectionUnavailable).reason)
        }
        suspend fun assertNoOutbox() {
            assertTrue(SyncLocalJournal(handler).pendingEvents("space", 1).isEmpty())
            assertEquals(0L, handler.await { sync_journalQueries.countEvents().executeAsOne() })
        }
        override fun close() = driver.close()
    }

    private companion object {
        const val SOURCE = Long.MAX_VALUE - 1
        val mangaKey = SyncObjectKey(SyncObjectType.MANGA, sourceId = SOURCE.toString(), originalUrl = "/manga")
        val chapterKey = SyncObjectKey(
            SyncObjectType.CHAPTER,
            sourceId = SOURCE.toString(),
            originalUrl = "/chapter",
            parentUrl = "/manga",
        )
        fun authorKey(key: String) = SyncObjectKey(SyncObjectType.AUTHOR, portableKey = key)
        val unavailableDescription: (SyncObjectKey) -> SyncObjectDescriptor? = {
            error("Existing object needs no descriptor")
        }
    }
}
