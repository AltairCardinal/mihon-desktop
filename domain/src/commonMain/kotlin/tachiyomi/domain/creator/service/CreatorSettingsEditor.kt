package tachiyomi.domain.creator.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreatorSettingsState(
    val open: Boolean = false,
    val draft: CreatorCheckFrequency = CreatorCheckFrequency.DAILY,
    val saving: Boolean = false,
    val error: String? = null,
    val focusRevision: Long = 0,
    val savedRevision: Long = 0,
)

/** One ephemeral draft per author-list window; preference storage is the only authority. */
class CreatorSettingsEditor(
    private val preferences: CreatorDiscoveryPreferences,
    private val scope: CoroutineScope,
    private val onSaved: suspend () -> Unit,
) {
    private val mutableState = MutableStateFlow(CreatorSettingsState())
    val state = mutableState.asStateFlow()

    fun open() {
        if (state.value.saving) return
        mutableState.value = state.value.copy(open = true, draft = preferences.current(), error = null)
    }

    fun select(value: CreatorCheckFrequency) {
        if (state.value.open && !state.value.saving) mutableState.value = state.value.copy(draft = value, error = null)
    }

    fun cancel() {
        if (!state.value.saving) {
            mutableState.value =
                state.value.copy(open = false, error = null, focusRevision = state.value.focusRevision + 1)
        }
    }

    fun save(): Job {
        val snapshot = state.value
        if (!snapshot.open || snapshot.saving || !mutableState.compareAndSet(snapshot, snapshot.copy(saving = true))) {
            return Job().apply { complete() }
        }
        return scope.launch {
            var previous: String? = null
            var persisted = false
            try {
                previous = preferences.frequency().get()
                preferences.frequency().set(snapshot.draft.value)
                persisted = true
                onSaved()
                mutableState.value =
                    snapshot.copy(
                        open = false,
                        focusRevision = snapshot.focusRevision + 1,
                        savedRevision =
                        snapshot.savedRevision + 1,
                    )
            } catch (error: Throwable) {
                if (persisted) {
                    try {
                        preferences.frequency().set(requireNotNull(previous))
                    } catch (rollback: Throwable) {
                        error.addSuppressed(rollback)
                    }
                }
                mutableState.value = snapshot.copy(error = error.message ?: "Unable to save author settings")
                if (error is CancellationException) throw error
            }
        }
    }
}
