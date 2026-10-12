package tachiyomi.domain.creator.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.manga.model.Manga

enum class CreatorTitleAttributionFeedback { ENABLED, DISABLED, REINDEXED, CANCELLED, FAILED }

data class CreatorTitleAttributionState(
    val open: Boolean = false,
    val manga: Manga? = null,
    val enabled: Boolean = false,
    val draftEnabled: Boolean = false,
    val preview: List<CreatorMention> = emptyList(),
    val busy: Boolean = false,
    val reindexing: Boolean = false,
    val progress: Int = 0,
    val feedback: CreatorTitleAttributionFeedback? = null,
    val error: String? = null,
    val focusRevision: Long = 0,
)

/** One native dialog session; the shared editor owns persistence and indexing. */
class CreatorTitleAttributionSession(
    private val editor: CreatorTitleAttributionEditor,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CreatorTitleAttributionState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun open(manga: Manga) {
        if (state.value.busy) return
        val enabled = editor.policy.isEnabled(manga.source)
        mutableState.value = state.value.copy(
            open = true,
            manga = manga,
            enabled = enabled,
            draftEnabled = enabled,
            preview = editor.preview(manga),
            progress = 0,
            feedback = null,
            error = null,
        )
    }

    fun refresh(manga: Manga) {
        val current = state.value
        if (current.open && !current.busy && current.manga?.id == manga.id && current.manga.source == manga.source) {
            mutableState.value = current.copy(manga = manga, preview = editor.preview(manga))
        }
    }

    fun select(enabled: Boolean) {
        if (state.value.open && !state.value.busy) {
            mutableState.value = state.value.copy(draftEnabled = enabled, error = null)
        }
    }

    fun dismiss() {
        val current = state.value
        if (current.busy && !current.reindexing) return
        if (current.busy) operation?.cancel()
        mutableState.value = current.copy(
            open = false,
            error = null,
            feedback = if (current.busy) CreatorTitleAttributionFeedback.CANCELLED else current.feedback,
            focusRevision = current.focusRevision + if (current.busy) 0 else 1,
        )
    }

    fun save(): Job {
        val snapshot = state.value
        val manga = snapshot.manga ?: return completedJob()
        if (!snapshot.open || snapshot.busy) return completedJob()
        mutableState.value = snapshot.copy(busy = true, reindexing = false, error = null, feedback = null)
        return scope.launch {
            try {
                val result = editor.setEnabled(manga.id, manga.source, snapshot.draftEnabled)
                mutableState.value = state.value.copy(
                    open = false,
                    enabled = result.enabled,
                    draftEnabled = result.enabled,
                    feedback = if (result.enabled) {
                        CreatorTitleAttributionFeedback.ENABLED
                    } else {
                        CreatorTitleAttributionFeedback.DISABLED
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = state.value.copy(error = failure.message ?: "署名设置保存失败，请重试")
            } finally {
                mutableState.value = state.value.copy(
                    busy = false,
                    focusRevision = state.value.focusRevision + if (state.value.open) 0 else 1,
                )
            }
        }.also { operation = it }
    }

    fun reindex(): Job {
        val snapshot = state.value
        val manga = snapshot.manga ?: return completedJob()
        if (!snapshot.open || snapshot.busy || snapshot.draftEnabled != snapshot.enabled) return completedJob()
        mutableState.value = snapshot.copy(busy = true, reindexing = true, progress = 0, error = null, feedback = null)
        return scope.launch {
            try {
                val count = editor.reindexLibrary(manga.source) {
                    mutableState.value = state.value.copy(progress = it)
                }
                mutableState.value =
                    state.value.copy(progress = count, feedback = CreatorTitleAttributionFeedback.REINDEXED)
            } catch (cancelled: CancellationException) {
                mutableState.value = state.value.copy(feedback = CreatorTitleAttributionFeedback.CANCELLED)
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = state.value.copy(
                    error = failure.message ?: "书架署名整理失败，请重试",
                    feedback = CreatorTitleAttributionFeedback.FAILED,
                )
            } finally {
                mutableState.value = state.value.copy(
                    busy = false,
                    reindexing = false,
                    focusRevision = state.value.focusRevision + if (state.value.open) 0 else 1,
                )
            }
        }.also { operation = it }
    }

    fun dispose() {
        operation?.cancel()
    }

    private fun completedJob(): Job = Job().apply { complete() }
}
