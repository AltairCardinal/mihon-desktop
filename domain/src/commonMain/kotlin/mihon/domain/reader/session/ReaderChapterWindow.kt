package mihon.domain.reader.session

import mihon.domain.reader.ReaderTransitionDirection

data class ReaderChapterWindowSnapshot(
    val currentChapterId: ReaderChapterId,
    val previousChapterId: ReaderChapterId?,
    val nextChapterId: ReaderChapterId?,
    val activationSequence: Long = 0,
    val lastActivation: ReaderChapterActivation? = null,
) {
    init {
        require(activationSequence >= 0) { "activationSequence must be non-negative" }
        require(
            listOfNotNull(currentChapterId, previousChapterId, nextChapterId)
                .let { chapterIds -> chapterIds.size == chapterIds.distinct().size },
        ) {
            "Current, previous, and next chapter identities must be distinct"
        }
        require(lastActivation == null || lastActivation.sequence <= activationSequence) {
            "Last activation cannot be newer than the window"
        }
        require(lastActivation == null || lastActivation.toChapterId == currentChapterId) {
            "Last activation target must be the current chapter"
        }
    }

    val retainedChapterIds: Set<ReaderChapterId>
        get() = linkedSetOfNotNull(currentChapterId, previousChapterId, nextChapterId)

    fun adjacentChapterId(direction: ReaderTransitionDirection): ReaderChapterId? = when (direction) {
        ReaderTransitionDirection.PREVIOUS -> previousChapterId
        ReaderTransitionDirection.NEXT -> nextChapterId
    }
}

data class ReaderChapterActivation(
    val sequence: Long,
    val direction: ReaderTransitionDirection,
    val fromChapterId: ReaderChapterId,
    val toChapterId: ReaderChapterId,
) {
    init {
        require(sequence > 0) { "Activation sequence must be positive" }
        require(fromChapterId != toChapterId) { "Activation must change chapters" }
    }
}

enum class ReaderChapterLoadPurpose {
    PREFETCH,
    ACTIVATE,
    RETRY,
}

sealed interface ReaderChapterWindowIntent {
    data class Replace(
        val currentChapterId: ReaderChapterId,
        val previousChapterId: ReaderChapterId?,
        val nextChapterId: ReaderChapterId?,
    ) : ReaderChapterWindowIntent

    data class PrefetchAdjacent(val direction: ReaderTransitionDirection) : ReaderChapterWindowIntent

    /**
     * Opens one exact adjacent target. The expected identities make replaying the same intent a
     * no-op after the first successful activation.
     */
    data class OpenAdjacent(
        val direction: ReaderTransitionDirection,
        val expectedCurrentChapterId: ReaderChapterId,
        val expectedTargetChapterId: ReaderChapterId?,
        val replacementChapterId: ReaderChapterId?,
    ) : ReaderChapterWindowIntent

    data class RetryChapter(val chapterId: ReaderChapterId) : ReaderChapterWindowIntent

    data object Close : ReaderChapterWindowIntent
}

sealed interface ReaderChapterWindowEffect {
    data class RetainChapter(val chapterId: ReaderChapterId) : ReaderChapterWindowEffect

    data class ReleaseChapter(val chapterId: ReaderChapterId) : ReaderChapterWindowEffect

    data class BeginPageListLoad(
        val chapterId: ReaderChapterId,
        val purpose: ReaderChapterLoadPurpose,
    ) : ReaderChapterWindowEffect {
        /** Starts an unloaded/error session while preserving an in-flight or loaded session. */
        fun reduceSession(snapshot: ReaderSessionSnapshot): ReaderSessionReduction {
            require(snapshot.activeChapter.id == chapterId) { "Page-list effect must target its own chapter session" }
            return when (snapshot.activeChapter.loadState) {
                ReaderChapterLoadState.Wait,
                is ReaderChapterLoadState.Error,
                -> ReaderSessionReducer.reduce(snapshot, ReaderSessionIntent.OpenChapter(chapterId))
                ReaderChapterLoadState.LoadingPageList,
                ReaderChapterLoadState.Loaded,
                -> ReaderSessionReduction(snapshot)
            }
        }
    }

    data class ActivateChapter(val activation: ReaderChapterActivation) : ReaderChapterWindowEffect

    data class Boundary(val direction: ReaderTransitionDirection) : ReaderChapterWindowEffect
}

data class ReaderChapterWindowReduction(
    val snapshot: ReaderChapterWindowSnapshot?,
    val effects: List<ReaderChapterWindowEffect> = emptyList(),
)

object ReaderChapterWindowReducer {

    fun reduce(
        snapshot: ReaderChapterWindowSnapshot?,
        intent: ReaderChapterWindowIntent,
    ): ReaderChapterWindowReduction = when (intent) {
        is ReaderChapterWindowIntent.Replace -> replace(snapshot, intent)
        is ReaderChapterWindowIntent.PrefetchAdjacent -> prefetch(snapshot, intent.direction)
        is ReaderChapterWindowIntent.OpenAdjacent -> openAdjacent(snapshot, intent)
        is ReaderChapterWindowIntent.RetryChapter -> retry(snapshot, intent.chapterId)
        ReaderChapterWindowIntent.Close -> close(snapshot)
    }

    private fun replace(
        snapshot: ReaderChapterWindowSnapshot?,
        intent: ReaderChapterWindowIntent.Replace,
    ): ReaderChapterWindowReduction {
        val replacement = ReaderChapterWindowSnapshot(
            currentChapterId = intent.currentChapterId,
            previousChapterId = intent.previousChapterId,
            nextChapterId = intent.nextChapterId,
            activationSequence = snapshot?.activationSequence ?: 0,
            lastActivation = snapshot?.lastActivation?.takeIf { it.toChapterId == intent.currentChapterId },
        )
        if (replacement == snapshot) return ReaderChapterWindowReduction(snapshot)
        return ReaderChapterWindowReduction(
            snapshot = replacement,
            effects = lifecycleEffects(snapshot, replacement),
        )
    }

    private fun prefetch(
        snapshot: ReaderChapterWindowSnapshot?,
        direction: ReaderTransitionDirection,
    ): ReaderChapterWindowReduction {
        snapshot ?: return ReaderChapterWindowReduction(null)
        val target = snapshot.adjacentChapterId(direction)
            ?: return ReaderChapterWindowReduction(snapshot, listOf(ReaderChapterWindowEffect.Boundary(direction)))
        return ReaderChapterWindowReduction(
            snapshot,
            listOf(ReaderChapterWindowEffect.BeginPageListLoad(target, ReaderChapterLoadPurpose.PREFETCH)),
        )
    }

    private fun openAdjacent(
        snapshot: ReaderChapterWindowSnapshot?,
        intent: ReaderChapterWindowIntent.OpenAdjacent,
    ): ReaderChapterWindowReduction {
        snapshot ?: return ReaderChapterWindowReduction(null)
        if (snapshot.currentChapterId != intent.expectedCurrentChapterId) {
            return ReaderChapterWindowReduction(snapshot)
        }
        val actualTarget = snapshot.adjacentChapterId(intent.direction)
        if (actualTarget != intent.expectedTargetChapterId) {
            return ReaderChapterWindowReduction(snapshot)
        }
        if (actualTarget == null) {
            return ReaderChapterWindowReduction(
                snapshot,
                listOf(ReaderChapterWindowEffect.Boundary(intent.direction)),
            )
        }

        val activation = ReaderChapterActivation(
            sequence = snapshot.activationSequence + 1,
            direction = intent.direction,
            fromChapterId = snapshot.currentChapterId,
            toChapterId = actualTarget,
        )
        val replacement = when (intent.direction) {
            ReaderTransitionDirection.PREVIOUS -> ReaderChapterWindowSnapshot(
                currentChapterId = actualTarget,
                previousChapterId = intent.replacementChapterId,
                nextChapterId = snapshot.currentChapterId,
                activationSequence = activation.sequence,
                lastActivation = activation,
            )
            ReaderTransitionDirection.NEXT -> ReaderChapterWindowSnapshot(
                currentChapterId = actualTarget,
                previousChapterId = snapshot.currentChapterId,
                nextChapterId = intent.replacementChapterId,
                activationSequence = activation.sequence,
                lastActivation = activation,
            )
        }
        val lifecycle = lifecycleEffects(snapshot, replacement)
        val retainCount = lifecycle.indexOfFirst { it is ReaderChapterWindowEffect.ReleaseChapter }
            .let { if (it < 0) lifecycle.size else it }
        val effects = buildList {
            addAll(lifecycle.take(retainCount))
            add(ReaderChapterWindowEffect.BeginPageListLoad(actualTarget, ReaderChapterLoadPurpose.ACTIVATE))
            add(ReaderChapterWindowEffect.ActivateChapter(activation))
            addAll(lifecycle.drop(retainCount))
        }
        return ReaderChapterWindowReduction(replacement, effects)
    }

    private fun retry(
        snapshot: ReaderChapterWindowSnapshot?,
        chapterId: ReaderChapterId,
    ): ReaderChapterWindowReduction {
        snapshot ?: return ReaderChapterWindowReduction(null)
        if (chapterId !in snapshot.retainedChapterIds) return ReaderChapterWindowReduction(snapshot)
        return ReaderChapterWindowReduction(
            snapshot,
            listOf(ReaderChapterWindowEffect.BeginPageListLoad(chapterId, ReaderChapterLoadPurpose.RETRY)),
        )
    }

    private fun close(
        snapshot: ReaderChapterWindowSnapshot?,
    ): ReaderChapterWindowReduction = ReaderChapterWindowReduction(
        snapshot = null,
        effects = snapshot?.retainedChapterIds.orEmpty().map(ReaderChapterWindowEffect::ReleaseChapter),
    )

    private fun lifecycleEffects(
        previous: ReaderChapterWindowSnapshot?,
        replacement: ReaderChapterWindowSnapshot,
    ): List<ReaderChapterWindowEffect> {
        val previousIds = previous?.retainedChapterIds.orEmpty()
        val replacementIds = replacement.retainedChapterIds
        return buildList {
            replacementIds.filterNot(previousIds::contains).forEach {
                add(ReaderChapterWindowEffect.RetainChapter(it))
            }
            previousIds.filterNot(replacementIds::contains).forEach {
                add(ReaderChapterWindowEffect.ReleaseChapter(it))
            }
        }
    }
}

private fun linkedSetOfNotNull(vararg values: ReaderChapterId?): Set<ReaderChapterId> = buildSet {
    values.forEach { value -> value?.let(::add) }
}
