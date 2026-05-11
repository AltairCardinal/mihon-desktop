package mihon.desktop.domain.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

class FakeMangaRepository : MangaRepository {

    private val store = mutableMapOf<Long, Manga>()
    private val mangaCategoryMap = mutableMapOf<Long, List<Long>>()
    private var nextId = 1L
    val updates = mutableListOf<MangaUpdate>()

    fun seed(manga: Manga) {
        store[manga.id] = manga
    }

    override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
        return manga.map { m ->
            val existing = store.values.find { it.url == m.url && it.source == m.source }
            if (existing != null) {
                existing
            } else {
                val withId = m.copy(id = nextId++)
                store[withId.id] = withId
                withId
            }
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        updates += update
        val existing = store[update.id] ?: return false
        store[update.id] = existing.copy(
            favorite = update.favorite ?: existing.favorite,
            dateAdded = update.dateAdded ?: existing.dateAdded,
            initialized = update.initialized ?: existing.initialized,
        )
        return true
    }

    fun get(id: Long): Manga? = store[id]

    // ── unused in Phase A ──────────────────────────────────────────────────

    override suspend fun getMangaById(id: Long): Manga = store.getValue(id)
    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> = flowOf(store.getValue(id))
    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? =
        store.values.find { it.url == url && it.source == sourceId }
    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> =
        flowOf(store.values.find { it.url == url && it.source == sourceId })
    override suspend fun getFavorites(): List<Manga> = store.values.filter { it.favorite }
    override suspend fun getReadMangaNotInLibrary(): List<Manga> = emptyList()
    override suspend fun getLibraryManga(): List<LibraryManga> = emptyList()
    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> = flowOf(emptyList())
    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> = flowOf(emptyList())
    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> = emptyList()
    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> = flowOf(emptyList())
    override suspend fun resetViewerFlags(): Boolean = true
    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        mangaCategoryMap[mangaId] = categoryIds
    }

    fun getMangaCategoryIds(mangaId: Long): List<Long> = mangaCategoryMap[mangaId] ?: emptyList()
    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        mangaUpdates.forEach { update(it) }
        return true
    }
}
