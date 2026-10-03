package mihon.desktop.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.service.LibraryPreferences

/** Coordinates this one category-sort reset with its preference; it is not a database/store transaction. */
class DesktopCategorySortSettings(
    store: PreferenceStore,
    private val preferences: LibraryPreferences,
    private val repository: CategoryRepository,
    private val resetFlags: ResetCategoryFlags,
    private val operations: Mutex,
) {
    private val journal = store.getStringSet(Preference.appStateKey("category_sort_reset_pending"), emptySet())
    private val failedState = MutableStateFlow(false)
    val failed = failedState.asStateFlow()

    /** Shared sort command with the existing finite preference/SQL compensation boundary. */
    suspend fun setSort(categoryId: Long?, sharedSort: tachiyomi.domain.library.model.LibrarySort): Boolean {
        val oldSort = preferences.sortingMode().get() to preferences.sortingMode().isSet()
        val oldSeed = preferences.randomSortSeed().get() to preferences.randomSortSeed().isSet()
        val previousFlags = try {
            repository.getAll().associate { it.id to it.flags }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val local = categoryId?.takeIf { preferences.categorizedDisplaySettings().get() && it in previousFlags }
        val affected = if (local != null) previousFlags.filterKeys { it == local } else previousFlags
        return try {
            tachiyomi.domain.category.interactor.SetSortModeForCategory(preferences, repository)
                .await(categoryId, sharedSort.type, sharedSort.direction)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching {
                val current = repository.getAll().associateBy { it.id }
                val mask = sharedSort.mask
                val updates = affected.mapNotNull { (id, previous) ->
                    val actual = current[id] ?: return@mapNotNull null
                    if (actual.flags and mask != sharedSort.flag) return@mapNotNull null
                    CategoryUpdate(id = id, flags = (actual.flags and mask.inv()) or (previous and mask))
                }
                repository.updatePartial(updates)
            }
            runCatching {
                if (oldSort.second) preferences.sortingMode().set(oldSort.first) else preferences.sortingMode().delete()
            }
            runCatching {
                if (oldSeed.second) {
                    preferences.randomSortSeed().set(
                        oldSeed.first,
                    )
                } else {
                    preferences.randomSortSeed().delete()
                }
            }
            false
        }
    }

    suspend fun recover(): Boolean = operations.withLock {
        try {
            val pending = journal.get()
            if (pending.isNotEmpty()) finishRecovery(pending)
            failedState.value = false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failedState.value = true
            false
        }
    }

    suspend fun set(enabled: Boolean): Boolean = withContext(NonCancellable) {
        if (!recover()) return@withContext false
        operations.withLock {
            val preference = preferences.categorizedDisplaySettings()
            val old = preference.get()
            if (enabled == old) return@withLock true
            if (enabled) {
                val saved = saveDesktopPreference(preference, true)
                failedState.value = !saved
                return@withLock saved
            }
            var acknowledged = false
            var captured = emptySet<String>()
            try {
                val target = preferences.sortingMode().get()
                val pending = repository.getAll().map { "${it.id}:${it.flags and target.mask}" }.toSet() +
                    "target:${target.flag}:${target.mask}"
                captured = pending
                journal.set(pending)
                acknowledged = true
                resetFlags.await()
                preference.set(false)
                journal.delete()
                failedState.value = false
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                try {
                    if (acknowledged) finishRecovery(captured)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The acknowledged record remains for this operation's recovery and retry.
                }
                failedState.value = true
                false
            }
        }
    }

    private suspend fun finishRecovery(pending: Set<String>) {
        // Re-acknowledge a record that may exist only in RAM after a failed flush.
        journal.set(pending)
        val target = pending.single { it.startsWith("target:") }.split(':')
        require(target.size == 3)
        val flag = target[1].toLong()
        val mask = target[2].toLong()
        preferences.categorizedDisplaySettings().set(true)
        run {
            val originals = pending.filterNot { it.startsWith("target:") }.associate {
                val parts = it.split(':')
                require(parts.size == 2)
                parts[0].toLong() to parts[1].toLong()
            }
            repository.getAll().forEach { current ->
                val original = originals[current.id] ?: return@forEach
                // Preserve a later sort change and every flag outside this operation's sort mask.
                if ((current.flags and mask) == (flag and mask)) {
                    repository.updatePartial(
                        CategoryUpdate(current.id, flags = current.flags and mask.inv() or original),
                    )
                }
            }
        }
        journal.delete()
    }
}
