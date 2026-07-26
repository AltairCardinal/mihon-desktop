package mihon.desktop.domain.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

class FakeChapterRepository : ChapterRepository {

    private val store = mutableMapOf<Long, Chapter>()
    private var nextId = 1L
    val addedChapters = mutableListOf<Chapter>()
    val updates = mutableListOf<ChapterUpdate>()

    fun seed(chapter: Chapter) {
        store[chapter.id] = chapter
        nextId = maxOf(nextId, chapter.id + 1)
    }

    override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
        val assigned = chapters.map { ch ->
            val withId = if (ch.id == -1L) ch.copy(id = nextId++) else ch
            store[withId.id] = withId
            withId
        }
        addedChapters += assigned
        return assigned
    }

    override suspend fun update(chapterUpdate: ChapterUpdate) {
        updates += chapterUpdate
        val existing = store[chapterUpdate.id] ?: return
        store[chapterUpdate.id] = existing.copy(
            read = chapterUpdate.read ?: existing.read,
            lastPageRead = chapterUpdate.lastPageRead ?: existing.lastPageRead,
            bookmark = chapterUpdate.bookmark ?: existing.bookmark,
            chapterNumber = chapterUpdate.chapterNumber ?: existing.chapterNumber,
            name = chapterUpdate.name ?: existing.name,
            scanlator = chapterUpdate.scanlator ?: existing.scanlator,
            dateFetch = chapterUpdate.dateFetch ?: existing.dateFetch,
            dateUpload = chapterUpdate.dateUpload ?: existing.dateUpload,
            sourceOrder = chapterUpdate.sourceOrder ?: existing.sourceOrder,
            version = chapterUpdate.version ?: existing.version,
        )
    }

    override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
        chapterUpdates.forEach { update(it) }
    }

    override suspend fun getChapterByMangaId(
        mangaId: Long,
        applyScanlatorFilter: Boolean,
    ): List<Chapter> = store.values.filter { it.mangaId == mangaId }

    override suspend fun getChapterById(id: Long): Chapter? = store[id]

    override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
        store.values.find { it.url == url && it.mangaId == mangaId }

    // ── unused in Phase A ──────────────────────────────────────────────────

    override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
        chapterIds.forEach { store.remove(it) }
    }

    override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
    override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> =
        store.values.filter { it.mangaId == mangaId && it.bookmark }
    override suspend fun getChapterByMangaIdAsFlow(
        mangaId: Long,
        applyScanlatorFilter: Boolean,
    ): Flow<List<Chapter>> = flowOf(store.values.filter { it.mangaId == mangaId })
}
