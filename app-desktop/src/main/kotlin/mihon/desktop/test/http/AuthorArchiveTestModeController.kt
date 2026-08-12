package mihon.desktop.test.http

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import mihon.desktop.domain.CreatorDiscoveryScheduler
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository

@Serializable
data class AuthorArchiveTestSnapshot(
    val creators: List<Long>,
    val followedCreators: List<Long>,
    val discoveryIds: List<Long>,
    val discoveryTaskStatus: String,
)

@Serializable
enum class AuthorArchiveTestFailureCode {
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    ROW_NOT_FOUND,
    OPERATION_REJECTED,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class AuthorArchiveTestActionResult(
    val success: Boolean,
    val snapshot: AuthorArchiveTestSnapshot,
    val failureCode: AuthorArchiveTestFailureCode? = null,
)

class AuthorArchiveTestModeController(
    private val creatorRepository: CreatorRepository,
    private val archiveRepository: CreatorArchiveRepository,
    private val scheduler: CreatorDiscoveryScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val closed = AtomicBoolean(false)
    private val cachedSnapshot = AtomicReference(emptySnapshot())

    suspend fun hydrate() = refresh()

    fun snapshot(): AuthorArchiveTestSnapshot = cachedSnapshot.get()

    suspend fun execute(action: String, params: Map<String, String>): AuthorArchiveTestActionResult {
        if (closed.get()) return failure(AuthorArchiveTestFailureCode.OWNER_CLOSED)
        val failure = runCatching {
            when (action) {
                "authors_state", "author_compare" -> null
                "author_follow" -> creatorAction(params, creatorRepository::followCreator)
                "author_unfollow" -> creatorAction(params, creatorRepository::unfollowCreator)
                "author_manual_scan" -> creatorAction(params) { scheduler.runForCreator(it).join() }
                "author_cancel_scan" -> null.also { scheduler.cancel() }
                "author_feed_seen" -> discoveryAction(params) { archiveRepository.markDiscoverySeen(it, now()) }
                "author_feed_ignore" -> discoveryAction(params) {
                    archiveRepository.setDiscoveryReview(
                        it,
                        tachiyomi.domain.creator.model.ReviewDisposition.IGNORED,
                        now(),
                    )
                }
                "author_feed_undo" -> discoveryAction(params) {
                    archiveRepository.setDiscoveryReview(
                        it,
                        tachiyomi.domain.creator.model.ReviewDisposition.PENDING,
                        now(),
                    )
                }
                "author_confirm", "author_reject", "author_decision_undo" -> workDecision(action, params)
                "author_language_override" -> languageOverride(params)
                "author_language_undo" -> languageUndo(params)
                else -> AuthorArchiveTestFailureCode.UNSUPPORTED_ACTION
            }
        }.getOrElse { AuthorArchiveTestFailureCode.OPERATION_REJECTED }
        refresh()
        return AuthorArchiveTestActionResult(failure == null, snapshot(), failure)
    }

    fun close() {
        closed.set(true)
    }

    private suspend fun workDecision(
        action: String,
        params: Map<String, String>,
    ): AuthorArchiveTestFailureCode? {
        val creatorId = creatorId(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val sourceWorkId = params["sourceWorkId"]?.toLongOrNull()
            ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val archive = archiveRepository.getCreatorWorkArchive(creatorId)
        val version = (archive.works.flatMap { it.versions } + archive.pending + archive.rejected)
            .firstOrNull { it.sourceWorkId == sourceWorkId }
            ?: return AuthorArchiveTestFailureCode.ROW_NOT_FOUND
        val current = version.decision
        val workId = current?.workId ?: params["workId"]?.toLongOrNull() ?: creatorRepository.createCanonicalWork(
            primaryTitle = version.title,
            primaryCreatorId = creatorId,
            originalLanguage = version.originalLanguage.tag.takeUnless { it == "und" },
        ).id
        val decidedAt = now()
        archiveRepository.appendUserWorkDecisionIfCurrent(
            sourceWork = version.naturalKey,
            workId = workId,
            state = when (action) {
                "author_confirm" -> WorkDecisionState.CONFIRMED
                "author_reject" -> WorkDecisionState.REJECTED
                else -> WorkDecisionState.SUGGESTED
            },
            expectedDecidedAt = current?.decidedAt,
            score = 1.0,
            evidence = "desktop-test-mode",
            decidedAt = decidedAt,
            idempotencyKey = "desktop-test-mode:$sourceWorkId:$workId:$decidedAt",
        )
        return null
    }

    private suspend fun languageOverride(params: Map<String, String>): AuthorArchiveTestFailureCode? {
        val subject = languageSubject(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val dimension = params["dimension"]?.let { runCatching { LanguageDimension.valueOf(it.uppercase()) }.getOrNull() }
            ?: return AuthorArchiveTestFailureCode.INVALID_PARAMETER
        val tag = params["tag"]?.takeIf(String::isNotBlank) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        archiveRepository.setManualLanguage(subject, dimension, tag, now())
        return null
    }

    private suspend fun languageUndo(params: Map<String, String>): AuthorArchiveTestFailureCode? {
        val subject = languageSubject(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val dimension = params["dimension"]?.let { runCatching { LanguageDimension.valueOf(it.uppercase()) }.getOrNull() }
            ?: return AuthorArchiveTestFailureCode.INVALID_PARAMETER
        archiveRepository.withdrawManualLanguage(subject, dimension, now())
        return null
    }

    private fun languageSubject(params: Map<String, String>): ArchiveLanguageSubject.SourceWork? {
        val sourceId = params["sourceId"]?.toLongOrNull() ?: return null
        val url = params["url"]?.takeIf(String::isNotBlank) ?: return null
        return ArchiveLanguageSubject.SourceWork(tachiyomi.domain.creator.model.SourceWorkNaturalKey(sourceId, url))
    }

    private suspend fun refresh() {
        if (closed.get()) return
        cachedSnapshot.set(
            AuthorArchiveTestSnapshot(
                creators = creatorRepository.getCreatorsAsFlow().first().map { it.id },
                followedCreators = creatorRepository.getFollowedCreators().map { it.creatorId },
                discoveryIds = archiveRepository.getDiscoveries(200).map { it.id },
                discoveryTaskStatus = scheduler.state.value.status.name,
            ),
        )
    }

    private fun creatorId(params: Map<String, String>) = params["creatorId"]?.toLongOrNull()
    private fun discoveryId(params: Map<String, String>) = params["discoveryId"]?.toLongOrNull()

    private suspend fun creatorAction(
        params: Map<String, String>,
        action: suspend (Long) -> Unit,
    ): AuthorArchiveTestFailureCode? {
        val id = creatorId(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        action(id)
        return null
    }

    private suspend fun discoveryAction(
        params: Map<String, String>,
        action: suspend (Long) -> Unit,
    ): AuthorArchiveTestFailureCode? {
        val id = discoveryId(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        action(id)
        return null
    }

    private fun failure(code: AuthorArchiveTestFailureCode) =
        AuthorArchiveTestActionResult(false, snapshot(), code)

    private companion object {
        fun emptySnapshot() = AuthorArchiveTestSnapshot(emptyList(), emptyList(), emptyList(), "COMPLETED")
    }
}

object AuthorArchiveTestModeBridge {
    private val value = AtomicReference<AuthorArchiveTestModeController?>()
    val controller: AuthorArchiveTestModeController? get() = value.get()
    fun install(controller: AuthorArchiveTestModeController) = value.set(controller)
    fun clear(expected: AuthorArchiveTestModeController) = value.compareAndSet(expected, null)
}
