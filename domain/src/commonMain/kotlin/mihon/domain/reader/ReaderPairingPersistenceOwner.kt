package mihon.domain.reader

enum class ReaderPairingPersistenceStatus {
    WAITING_FOR_RESTORE,
    RESTORING,
    READY,
    SAVING,
    RESTORE_FAILED,
    SAVE_FAILED,
    CONFLICT,
    DEFAULT_UNVERIFIED,
    TEMPORARY,
}

enum class ReaderPairingPersistenceNotice { NONE, INVALID_RECORD }

data class ReaderPairingPersistenceState(
    val status: ReaderPairingPersistenceStatus,
    val committedForcedSinglePages: Set<Int> = emptySet(),
    val revision: Long? = null,
    val notice: ReaderPairingPersistenceNotice = ReaderPairingPersistenceNotice.NONE,
)

sealed interface ReaderPairingPersistenceRequest {
    val identity: ReaderDualPageIdentity
    val mangaId: Long
    val operationId: Long

    data class Load(
        override val identity: ReaderDualPageIdentity,
        override val mangaId: Long,
        override val operationId: Long,
    ) : ReaderPairingPersistenceRequest

    data class Save(
        override val identity: ReaderDualPageIdentity,
        override val mangaId: Long,
        override val operationId: Long,
        val expectedRevision: Long,
        val adjustment: ReaderPairingAdjustment,
        val locationSequence: Long,
    ) : ReaderPairingPersistenceRequest
}

/**
 * Shared CAS/feedback state, consumed serially by the presentation owner.
 * Platforms execute the original ticket on the existing application-level coordinator. An accepted
 * write can finish after its view closes, but its callback cannot mutate a closed or restored owner.
 */
class ReaderPairingPersistenceOwner(val identity: ReaderDualPageIdentity, mangaId: Long?) {
    private val mangaId = mangaId?.takeIf { id ->
        id > 0 && identity.content.firstOrNull()?.pageId?.chapterId?.value?.let { it > 0 } == true
    }
    private var pending: ReaderPairingPersistenceRequest? = null
    private var nextOperationId = 0L
    private var closed = false
    var state = ReaderPairingPersistenceState(
        if (this.mangaId == null) {
            ReaderPairingPersistenceStatus.TEMPORARY
        } else {
            ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE
        },
    )
        private set

    /** Only the current shared owner can carry verified state to a replacement of the same ordered pages. */
    internal fun rebindContent(nextIdentity: ReaderDualPageIdentity): ReaderPairingPersistenceOwner {
        check(!closed)
        require(nextIdentity.pageIds == identity.pageIds)
        val next = ReaderPairingPersistenceOwner(nextIdentity, mangaId)
        next.state = state.copy(
            status = if (pending != null) ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE else state.status,
        )
        close()
        return next
    }

    fun beginRestore(): ReaderPairingPersistenceRequest.Load? {
        val mangaId = mangaId ?: return null
        if (closed ||
            pending is ReaderPairingPersistenceRequest.Load ||
            state.status == ReaderPairingPersistenceStatus.TEMPORARY
        ) {
            return null
        }
        // A reload supersedes delivery of an older save, not the accepted repository write.
        // The platform coordinator completes that write before this new read executes.
        return ReaderPairingPersistenceRequest.Load(identity, mangaId, nextOperationId++).also {
            pending = it
            state = state.copy(status = ReaderPairingPersistenceStatus.RESTORING)
        }
    }

    fun acceptRestore(request: ReaderPairingPersistenceRequest.Load, snapshot: ChapterPairingSnapshot): Boolean {
        if (!accepts(request)) return false
        if (snapshot.revision < 0) {
            failRestore(request)
            return false
        }
        val record = snapshot.record?.takeIf { it.isValidFor(identity.content.size) }
        pending = null
        state = ReaderPairingPersistenceState(
            ReaderPairingPersistenceStatus.READY,
            record?.forcedSinglePages?.toSet() ?: emptySet(),
            snapshot.revision,
            if (snapshot.record != null && record == null) {
                ReaderPairingPersistenceNotice.INVALID_RECORD
            } else {
                ReaderPairingPersistenceNotice.NONE
            },
        )
        return true
    }

    fun failRestore(request: ReaderPairingPersistenceRequest.Load): Boolean {
        if (!accepts(request)) return false
        pending = null
        state = state.copy(status = ReaderPairingPersistenceStatus.RESTORE_FAILED)
        return true
    }

    /** A failed initial read never authorizes a write. Default mode only changes this session. */
    fun useDefaultForSession(): Boolean {
        if (closed || state.status != ReaderPairingPersistenceStatus.RESTORE_FAILED) return false
        state = ReaderPairingPersistenceState(ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED)
        return true
    }

    fun beginSave(adjustment: ReaderPairingAdjustment, locationSequence: Long): ReaderPairingPersistenceRequest.Save? {
        if (closed || pending != null || adjustment.forcedSinglePages == state.committedForcedSinglePages) return null
        require(adjustment.currentPage in identity.content.indices)
        require(adjustment.forcedSinglePages.all { it in 1 until identity.content.size })
        if (state.status == ReaderPairingPersistenceStatus.TEMPORARY ||
            state.status == ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED
        ) {
            state = state.copy(committedForcedSinglePages = adjustment.forcedSinglePages.toSet())
            return null
        }
        if (state.status != ReaderPairingPersistenceStatus.READY &&
            state.status != ReaderPairingPersistenceStatus.SAVE_FAILED
        ) {
            return null
        }
        val revision = state.revision ?: return null
        val mangaId = mangaId ?: return null
        return ReaderPairingPersistenceRequest.Save(
            identity,
            mangaId,
            nextOperationId++,
            revision,
            adjustment.copy(forcedSinglePages = adjustment.forcedSinglePages.toSet()),
            locationSequence,
        ).also {
            pending = it
            state = state.copy(status = ReaderPairingPersistenceStatus.SAVING)
        }
    }

    fun acceptSave(request: ReaderPairingPersistenceRequest.Save, snapshot: ChapterPairingSnapshot): Boolean {
        if (!accepts(request)) return false
        val proposed = request.adjustment.forcedSinglePages
        val record = snapshot.record
        val matches = if (proposed.isEmpty()) {
            record == null
        } else {
            record?.isValidFor(identity.content.size) == true && record.forcedSinglePages == proposed
        }
        if (!matches || snapshot.revision <= request.expectedRevision) {
            failSave(request, conflict = false)
            return false
        }
        pending = null
        state = ReaderPairingPersistenceState(ReaderPairingPersistenceStatus.READY, proposed, snapshot.revision)
        return true
    }

    fun failSave(request: ReaderPairingPersistenceRequest.Save, conflict: Boolean): Boolean {
        if (!accepts(request)) return false
        pending = null
        state = state.copy(
            status = if (conflict) {
                ReaderPairingPersistenceStatus.CONFLICT
            } else {
                ReaderPairingPersistenceStatus.SAVE_FAILED
            },
        )
        return true
    }

    fun close() {
        closed = true
        pending = null
    }

    private fun accepts(request: ReaderPairingPersistenceRequest): Boolean =
        !closed && pending === request && request.identity == identity && request.mangaId == mangaId
}
