package mihon.desktop.domain

import tachiyomi.data.DatabaseHandler

class SetExcludedScanlators(
    private val handler: DatabaseHandler,
) {

    suspend fun await(mangaId: Long, excludedScanlators: Set<String>) {
        handler.await(inTransaction = true) {
            val currentExcluded = excluded_scanlatorsQueries
                .getExcludedScanlatorsByMangaId(mangaId)
                .executeAsList()
                .toSet()
            val toAdd = excludedScanlators.minus(currentExcluded)
            for (scanlator in toAdd) {
                excluded_scanlatorsQueries.insert(mangaId, scanlator)
            }
            val toRemove = currentExcluded.minus(excludedScanlators)
            excluded_scanlatorsQueries.remove(mangaId, toRemove)
        }
    }
}
