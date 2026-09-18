package tachiyomi.domain.creator.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.AddCreatorAliasesRequest
import tachiyomi.domain.creator.model.CreatorAliasCandidates
import tachiyomi.domain.creator.model.CreatorIdentitySnapshot
import tachiyomi.domain.creator.model.SetCreatorDisplayNameRequest
import tachiyomi.domain.creator.model.StaleCreatorIdentityException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CreatorIdentityEditorState(
    val identity: CreatorIdentitySnapshot? = null,
    val loading: Boolean = true,
    val open: Boolean = false,
    val candidates: CreatorAliasCandidates? = null,
    val query: String = "",
    val selected: Set<Long> = emptySet(),
    val submitting: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
    val feedback: String? = null,
    val pendingName: String? = null,
    val focusTarget: String = "",
    val focusSequence: Int = 0,
    val loadGeneration: Int = 0,
    val nameGeneration: Int = 0,
    val pendingSnapshot: CreatorIdentitySnapshot? = null,
    val nameCommandKey: String = "",
) {
    val visibleCandidates: List<CreatorIdentitySnapshot> get() = candidates?.candidates.orEmpty().filter {
        it.names.any { name -> name.contains(query, ignoreCase = true) }
    }
    val canSubmit: Boolean get() = open && !loading && !submitting && !stale && selected.isNotEmpty()
}

@OptIn(ExperimentalUuidApi::class)
class CreatorIdentityEditor(
    private val creatorId: Long,
    private val manager: ManageCreatorIdentity,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CreatorIdentityEditorState())
    val state = mutableState.asStateFlow()
    private var commandKey = Uuid.random().toString()
    private fun observe() = scope.launch {
        try {
            manager.observe(creatorId).collect { identity ->
                mutableState.update {
                    it.copy(
                        identity = identity,
                        loading = if (it.open) it.loading else false,
                        error = if (!it.open && it.pendingName == null) null else it.error,
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(loading = false, error = error.message) }
        }
    }

    private var observer = observe()
    fun retryIdentity() {
        observer.cancel()
        observer = observe()
    }

    fun openAliases() = scope.launch {
        if (state.value.submitting) return@launch
        commandKey = Uuid.random().toString()
        mutableState.update {
            it.copy(
                open = true,
                query = "",
                selected = emptySet(),
                candidates = null,
                error = null,
                feedback = null,
                pendingName = null,
                pendingSnapshot = null,
                nameGeneration = it.nameGeneration + 1,
            )
        }
        loadCandidates()
    }

    fun refreshCandidates() = scope.launch { if (!state.value.submitting) loadCandidates() }

    private suspend fun loadCandidates() {
        val generation = mutableState.updateAndGet {
            it.copy(loading = true, error = null, loadGeneration = it.loadGeneration + 1)
        }.loadGeneration
        try {
            val candidates = manager.candidates(creatorId)
            if (generation != state.value.loadGeneration || !state.value.open) return
            commandKey = Uuid.random().toString()
            mutableState.update {
                if (it.loadGeneration != generation || !it.open) return@update it
                it.copy(
                    identity = candidates.target,
                    candidates = candidates,
                    loading = false,
                    stale = false,
                    selected = it.selected.intersect(candidates.candidates.map { row -> row.id }.toSet()),
                )
            }
        } catch (error: Exception) {
            if (generation == state.value.loadGeneration) fail(error)
        }
    }

    fun select(id: Long) {
        if (state.value.submitting || state.value.candidates?.candidates?.none { it.id == id } != false) return
        commandKey = Uuid.random().toString()
        mutableState.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    fun search(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun submit() = scope.launch {
        val draft = claimSubmission { it.canSubmit } ?: return@launch
        val candidates = requireNotNull(draft.candidates)
        try {
            val result = manager.addAliases(
                AddCreatorAliasesRequest(
                    candidates.target.id,
                    candidates.target.revision,
                    candidates.candidates.filter {
                        it.id in draft.selected
                    }.associate { it.id to it.revision },
                    commandKey,
                ),
            )
            mutableState.update {
                it.copy(
                    identity = result,
                    open = false,
                    submitting = false,
                    feedback = "已添加别名",
                    selected = emptySet(),
                    focusTarget = "add",
                    focusSequence = it.focusSequence + 1,
                )
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    fun dismiss() {
        if (state.value.submitting) return
        mutableState.update {
            it.copy(
                open = false,
                error = null,
                selected = emptySet(),
                loadGeneration =
                it.loadGeneration + 1,
                focusTarget = "add",
                focusSequence = it.focusSequence + 1,
            )
        }
    }

    fun chooseDisplayName(name: String) {
        val key = Uuid.random().toString()
        mutableState.update {
            val identity = it.identity ?: return@update it
            if (it.submitting || name !in identity.aliases) return@update it
            it.copy(
                pendingName = name,
                pendingSnapshot = identity,
                nameCommandKey = key,
                nameGeneration = it.nameGeneration + 1,
                error = null,
                stale = false,
                feedback = null,
            )
        }
    }

    fun cancelDisplayName() {
        mutableState.update {
            if (it.submitting) return@update it
            it.copy(
                pendingName = null,
                pendingSnapshot = null,
                nameGeneration = it.nameGeneration + 1,
                error = null,
                stale = false,
                focusTarget = "alias:${it.pendingName}",
                focusSequence = it.focusSequence + 1,
            )
        }
    }

    fun saveDisplayName() = scope.launch {
        val draft = claimSubmission {
            !it.submitting && !it.stale && it.pendingName != null && it.pendingSnapshot != null
        } ?: return@launch
        val before = draft.pendingSnapshot ?: return@launch
        val name = draft.pendingName ?: return@launch
        try {
            val result = manager.setDisplayName(
                SetCreatorDisplayNameRequest(before.id, before.revision, name, draft.nameCommandKey),
            )
            mutableState.update {
                it.copy(
                    identity = result,
                    pendingName = null,
                    pendingSnapshot = null,
                    nameGeneration = it.nameGeneration + 1,
                    submitting = false,
                    feedback = "显示名称已更新",
                    focusTarget = "title",
                    focusSequence = it.focusSequence + 1,
                )
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun claimSubmission(allowed: (CreatorIdentityEditorState) -> Boolean): CreatorIdentityEditorState? {
        while (true) {
            val draft = state.value
            if (!allowed(draft)) return null
            if (mutableState.compareAndSet(draft, draft.copy(submitting = true, error = null))) return draft
        }
    }

    private fun fail(error: Exception) {
        if (error is CancellationException) throw error
        mutableState.update {
            it.copy(
                loading = false,
                submitting = false,
                stale = error is StaleCreatorIdentityException,
                error = error.message ?: "操作失败，请重试",
            )
        }
    }

    fun refreshDisplayName() = scope.launch {
        val draft = mutableState.updateAndGet {
            if (it.submitting || it.pendingName == null) return@updateAndGet it
            it.copy(nameGeneration = it.nameGeneration + 1)
        }
        val name = draft.pendingName ?: return@launch
        if (draft.submitting) return@launch
        try {
            val identity = manager.snapshot(creatorId)
            val key = Uuid.random().toString()
            mutableState.update {
                if (it.nameGeneration != draft.nameGeneration || it.pendingName != name || it.submitting) {
                    return@update it
                }
                if (name !in identity.aliases) {
                    it.copy(
                        identity = identity,
                        pendingName = null,
                        pendingSnapshot = null,
                        nameGeneration = it.nameGeneration + 1,
                        error = null,
                        stale = false,
                        focusTarget = "alias:$name",
                        focusSequence = it.focusSequence + 1,
                    )
                } else {
                    it.copy(
                        identity = identity,
                        pendingSnapshot = identity,
                        nameCommandKey = key,
                        stale = false,
                        error = null,
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update {
                if (it.nameGeneration != draft.nameGeneration || it.pendingName != name || it.submitting) {
                    return@update it
                }
                it.copy(stale = error is StaleCreatorIdentityException, error = error.message ?: "操作失败，请重试")
            }
        }
    }

    fun close() {
        mutableState.update {
            it.copy(loadGeneration = it.loadGeneration + 1, nameGeneration = it.nameGeneration + 1)
        }
        observer.cancel()
    }
}
