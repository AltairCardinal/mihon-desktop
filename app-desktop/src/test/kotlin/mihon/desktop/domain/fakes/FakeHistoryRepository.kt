package mihon.desktop.domain.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class FakeHistoryRepository : HistoryRepository {

    val upserted = mutableListOf<HistoryUpdate>()
    private val historyItems = mutableListOf<HistoryWithRelations>()

    fun addHistory(item: HistoryWithRelations) {
        historyItems += item
    }

    override suspend fun upsertHistory(historyUpdate: HistoryUpdate) {
        upserted += historyUpdate
    }

    override fun getHistory(query: String): Flow<List<HistoryWithRelations>> {
        val filtered = if (query.isBlank()) {
            historyItems.toList()
        } else {
            historyItems.filter { it.title.contains(query, ignoreCase = true) }
        }
        return flowOf(filtered)
    }

    override suspend fun getLastHistory(): HistoryWithRelations? = historyItems.lastOrNull()
    override suspend fun getTotalReadDuration(): Long = historyItems.sumOf { it.readDuration }
    override suspend fun getHistoryByMangaId(mangaId: Long): List<History> = emptyList()

    override suspend fun resetHistory(historyId: Long) {
        historyItems.removeAll { it.id == historyId }
    }

    override suspend fun resetHistoryByMangaId(mangaId: Long) {
        historyItems.removeAll { it.mangaId == mangaId }
    }

    override suspend fun deleteAllHistory(): Boolean {
        historyItems.clear()
        return true
    }
}
