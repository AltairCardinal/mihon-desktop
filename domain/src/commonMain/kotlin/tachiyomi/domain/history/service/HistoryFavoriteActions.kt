package tachiyomi.domain.history.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.service.HistoryDialog.ChangeCategory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.LibraryMembershipResult
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga

/** Official history decisions, with platform tracker execution behind one narrow port. */
class HistoryFavoriteActions(
    private val getManga: GetManga,
    private val getCategories: GetCategories,
    private val getDuplicates: GetDuplicateLibraryManga,
    private val preferences: LibraryPreferences,
    private val updateMembership: UpdateLibraryMembership,
    private val bindEnhanced: suspend (Manga) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun categories() = getCategories.await().filterNot { it.isSystemCategory }

    suspend fun add(mangaId: Long, allowDuplicate: Boolean, onDialog: (HistoryDialog?) -> Unit) {
        val bindTarget = mutex.withLock {
            val manga = requireNotNull(getManga.await(mangaId))
            if (manga.favorite) return@withLock null
            if (!allowDuplicate) {
                val duplicates = getDuplicates(manga)
                if (duplicates.isNotEmpty()) {
                    onDialog(HistoryDialog.Duplicate(manga, duplicates))
                    return@withLock null
                }
            }
            val categories = getCategories.await().filterNot { it.isSystemCategory }
            val defaultId = preferences.defaultCategory().get().toLong()
            val defaultCategory = categories.firstOrNull { it.id == defaultId }
            when {
                defaultCategory != null -> {
                    commit(manga, listOf(defaultCategory.id))
                    onDialog(null)
                }
                defaultId == 0L || categories.isEmpty() -> {
                    commit(manga, emptyList())
                    onDialog(null)
                }
                else -> onDialog(ChangeCategory(manga, categories, getCategories.await(manga.id).map { it.id }))
            }
            manga
        }
        // The chooser is already visible. Slow tracker I/O cannot block category confirmation.
        if (bindTarget != null) bindEnhanced(bindTarget)
    }

    suspend fun confirm(manga: Manga, categoryIds: List<Long>) = mutex.withLock {
        val current = requireNotNull(getManga.await(manga.id))
        require(current.source == manga.source && current.url == manga.url) { "History manga identity changed" }
        val available = getCategories.await().filterNot { it.isSystemCategory }.map { it.id }
        require(categoryIds.all { it in available }) { "Category no longer exists" }
        if (current.favorite &&
            getCategories.await(current.id).map { it.id }.sorted() == categoryIds.distinct().sorted()
        ) {
            return@withLock
        }
        commit(current, categoryIds)
    }

    private suspend fun commit(manga: Manga, categories: List<Long>) {
        when (val result = updateMembership.await(manga, true, categories)) {
            is LibraryMembershipResult.Success -> Unit
            is LibraryMembershipResult.Failure -> error(result.message)
        }
    }
}
