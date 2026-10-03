package mihon.desktop.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.service.LibraryPreferences

/** A complete Desktop projection of the two shared category keys, with bounded interrupted-write recovery. */
class DesktopLibraryCategoryPolicy(
    store: PreferenceStore,
    private val preferences: LibraryPreferences,
    private val getCategories: GetCategories,
    private val deleteCategory: DeleteCategory,
    private val operations: Mutex,
    scope: CoroutineScope,
    private val migration: LibraryPreferenceMigration? = null,
) {
    data class Snapshot(val included: Set<Long>, val excluded: Set<Long>)

    sealed interface State {
        data class Ready(val snapshot: Snapshot) : State
        data class Unavailable(val recoveryRequired: Boolean) : State
    }

    private data class Previous(
        val included: Set<String>,
        val excluded: Set<String>,
        val includedSet: Boolean,
        val excludedSet: Boolean,
        val requiresChoice: Boolean,
    )

    private val journal = store.getStringSet(Preference.appStateKey("library_update_scope_recovery"), emptySet())
    private val requiresChoice = store.getBoolean(Preference.appStateKey("library_update_scope_requires_choice"), false)
    private val mutableState = MutableStateFlow<State>(State.Unavailable(true))
    val state = mutableState.asStateFlow()
    private var unacknowledgedRecovery: Previous? = null

    init {
        scope.launch { getCategories.subscribe().collect { recover() } }
    }

    suspend fun recover(): Boolean {
        if (deleteCategory.recoverPending() is DeleteCategory.Result.InternalError) {
            mutableState.value = State.Unavailable(true)
            return false
        }
        return operations.withLock {
            try {
                if (!recoverMigration()) return@withLock false
                val previous = unacknowledgedRecovery ?: journal.get().takeIf(Set<String>::isNotEmpty)?.let(::decode)
                if (previous != null) {
                    restore(previous)
                    unacknowledgedRecovery = null
                }
                publishCurrent()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = State.Unavailable(true)
                false
            }
        }
    }

    suspend fun snapshot(): Snapshot {
        check(recover()) { "Library update categories need recovery or a valid selection" }
        return (state.value as State.Ready).snapshot
    }

    suspend fun save(included: Set<Long>, excluded: Set<Long>): Boolean = withContext(NonCancellable) {
        if (deleteCategory.recoverPending() is DeleteCategory.Result.InternalError) {
            mutableState.value = State.Unavailable(true)
            return@withContext false
        }
        try {
            operations.withLock {
                if (!recoverMigration()) return@withLock false
                val validIds = getCategories.await().map { it.id }.toSet() + 0L
                if (!(included + excluded).all(validIds::contains)) return@withLock false
                if (journal.get().isNotEmpty() || unacknowledgedRecovery != null) return@withLock false
                val previous = Previous(
                    preferences.updateCategories().get(),
                    preferences.updateCategoriesExclude().get(),
                    preferences.updateCategories().isSet(),
                    preferences.updateCategoriesExclude().isSet(),
                    requiresChoice.get() ||
                        (preferences.updateCategories().get() + preferences.updateCategoriesExclude().get()).any {
                            it.toLongOrNull() !in validIds
                        },
                )
                // No downstream mutation is allowed until this recovery record is acknowledged.
                try {
                    journal.set(encode(previous))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value = State.Unavailable(true)
                    return@withLock false
                }
                mutableState.value = State.Unavailable(true)
                try {
                    preferences.updateCategories().set(included.map(Long::toString).toSet())
                    preferences.updateCategoriesExclude().set(excluded.map(Long::toString).toSet())
                    requiresChoice.set(false)
                    journal.delete()
                    mutableState.value = State.Ready(Snapshot(included, excluded))
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unacknowledgedRecovery = previous
                    try {
                        // Reinstall before compensating if an unacknowledged delete removed the record in memory.
                        journal.set(encode(previous))
                        restore(previous)
                        unacknowledgedRecovery = null
                        publishCurrent()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableState.value = State.Unavailable(true)
                    }
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = State.Unavailable(true)
            false
        }
    }

    private suspend fun recoverMigration(): Boolean {
        if (migration != null && !migration.isComplete()) {
            val validIds = getCategories.await().map { it.id }.toSet() + 0L
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { migration.migrate(validIds) }
            if (!migration.isComplete()) {
                mutableState.value = State.Unavailable(true)
                return false
            }
        }
        return true
    }

    private suspend fun restore(previous: Previous) {
        val validIds = getCategories.await().map { it.id.toString() }.toSet() + "0"
        val included = previous.included.intersect(validIds)
        val excluded = previous.excluded.intersect(validIds)
        val lostAllIncluded = previous.included.isNotEmpty() && included.isEmpty()
        // Persist the validation block before clearing the journal; another startup cannot widen to all.
        requiresChoice.set(previous.requiresChoice || lostAllIncluded)
        restorePreference(preferences.updateCategories(), included, previous.includedSet)
        restorePreference(preferences.updateCategoriesExclude(), excluded, previous.excludedSet)
        journal.delete()
    }

    private fun restorePreference(preference: Preference<Set<String>>, value: Set<String>, wasSet: Boolean) {
        if (wasSet) preference.set(value) else preference.delete()
    }

    private suspend fun publishCurrent(): Boolean {
        val validIds = getCategories.await().map { it.id }.toSet() + 0L
        fun parse(raw: Set<String>): Set<Long>? {
            val parsed = raw.map { it.toLongOrNull() ?: return null }.toSet()
            return parsed.takeIf { it.all(validIds::contains) }
        }
        val included = parse(preferences.updateCategories().get())
        val excluded = parse(preferences.updateCategoriesExclude().get())
        val ready = !requiresChoice.get() && included != null && excluded != null
        mutableState.value = if (ready) {
            State.Ready(Snapshot(requireNotNull(included), requireNotNull(excluded)))
        } else {
            State.Unavailable(false)
        }
        return ready
    }

    private fun encode(previous: Previous): Set<String> = buildSet {
        add("v1")
        if (previous.includedSet) add("included-set")
        if (previous.excludedSet) add("excluded-set")
        if (previous.requiresChoice) add("requires-choice")
        previous.included.forEach { add("included:$it") }
        previous.excluded.forEach { add("excluded:$it") }
    }

    private fun decode(raw: Set<String>): Previous {
        require(
            "v1" in raw && raw.all {
                it in setOf("v1", "included-set", "excluded-set", "requires-choice") ||
                    it.startsWith("included:") || it.startsWith("excluded:")
            },
        ) { "Invalid library update recovery record" }
        return Previous(
            raw.filter { it.startsWith("included:") }.map { it.removePrefix("included:") }.toSet(),
            raw.filter { it.startsWith("excluded:") }.map { it.removePrefix("excluded:") }.toSet(),
            "included-set" in raw,
            "excluded-set" in raw,
            "requires-choice" in raw,
        )
    }
}
