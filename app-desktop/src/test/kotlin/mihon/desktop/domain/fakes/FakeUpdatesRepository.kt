package mihon.desktop.domain.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository

class FakeUpdatesRepository : UpdatesRepository {

    private val items = mutableListOf<UpdatesWithRelations>()

    fun addUpdate(item: UpdatesWithRelations) {
        items += item
    }

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<UpdatesWithRelations> {
        return items
            .filter { it.read == read && it.dateFetch > after }
            .take(limit.toInt())
    }

    override fun subscribeAll(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
    ): Flow<List<UpdatesWithRelations>> {
        val filtered = items.filter { it.dateFetch > after }.let { list ->
            if (unread != null) {
                list.filter { !it.read == unread }
            } else {
                list
            }
        }.take(limit.toInt())
        return flowOf(filtered)
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): Flow<List<UpdatesWithRelations>> {
        val filtered = items
            .filter { it.read == read && it.dateFetch > after }
            .take(limit.toInt())
        return flowOf(filtered)
    }
}
