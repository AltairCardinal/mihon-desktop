package mihon.desktop.test.http

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.desktop.domain.CreatorDiscoveryScheduler
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.AddCreatorAliasesRequest
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.SetCreatorDisplayNameRequest
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorSettingsEditor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class AuthorArchiveTestSnapshot(
    val creators: List<Long>,
    val followedCreators: List<Long>,
    val discoveryIds: List<Long>,
    val discoveryTaskStatus: String,
    val identities: List<AuthorIdentityTestSnapshot> = emptyList(),
    val frequency: String = "daily",
    val resolvedCreatorId: Long? = null,
)

@Serializable
data class AuthorIdentityTestSnapshot(
    val id: Long,
    val revision: Long,
    val displayName: String,
    val names: List<String>,
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
    private val preferences: CreatorDiscoveryPreferences? = null,
    private val syncFixture: (suspend (String) -> Unit)? = null,
    private val mangaRepository: tachiyomi.domain.manga.repository.MangaRepository? = null,
) {
    private val closed = AtomicBoolean(false)
    private val cachedSnapshot = AtomicReference(emptySnapshot())
    private val setCreatorFollow = SetCreatorFollow(creatorRepository)
    private val identity = ManageCreatorIdentity(archiveRepository)

    suspend fun hydrate() = refresh()

    fun snapshot(): AuthorArchiveTestSnapshot = cachedSnapshot.get()

    suspend fun execute(action: String, params: Map<String, String>): AuthorArchiveTestActionResult {
        if (closed.get()) return failure(AuthorArchiveTestFailureCode.OWNER_CLOSED)
        var resolvedCreatorId: Long? = null
        val failure = runCatching {
            when (action) {
                "authors_state", "author_compare" -> null
                "author_resolve" -> {
                    val mangaId = params["mangaId"]?.toLongOrNull()
                        ?: return failure(AuthorArchiveTestFailureCode.MISSING_PARAMETER)
                    val name = params["name"]?.takeIf(String::isNotBlank)
                        ?: return failure(AuthorArchiveTestFailureCode.MISSING_PARAMETER)
                    val manga = requireNotNull(mangaRepository).getMangaById(mangaId)
                    val mention = tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga().await(manga)
                        .firstOrNull { it.displayName == name }
                        ?: return failure(AuthorArchiveTestFailureCode.ROW_NOT_FOUND)
                    resolvedCreatorId =
                        (identity.resolve(manga, mention) as tachiyomi.domain.creator.model.CreatorMentionResolution.Resolved).creatorId
                    null
                }
                "author_sync_fixture" -> {
                    val step = params["step"] ?: return failure(AuthorArchiveTestFailureCode.MISSING_PARAMETER)
                    requireNotNull(syncFixture) { "An isolated test profile is required" }.invoke(step)
                    null
                }
                "author_add_aliases" -> addAliases(params)
                "author_set_display_name" -> setDisplayName(params)
                "author_set_frequency" -> setFrequency(params)
                "author_follow" -> creatorAction(params) { setCreatorFollow.await(it, true) }
                "author_unfollow" -> creatorAction(params) { setCreatorFollow.await(it, false) }
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
        return AuthorArchiveTestActionResult(failure == null, snapshot().copy(resolvedCreatorId = resolvedCreatorId), failure)
    }

    fun close() {
        closed.set(true)
    }

    private suspend fun addAliases(params: Map<String, String>): AuthorArchiveTestFailureCode? {
        val creatorId = creatorId(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val revision = params["revision"]?.toLongOrNull() ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val key = params["idempotencyKey"]?.takeIf(String::isNotBlank)
            ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val selected = params["selectedRevisions"]?.let {
            runCatching { Json.decodeFromString<Map<Long, Long>>(it) }.getOrNull()
        } ?: return AuthorArchiveTestFailureCode.INVALID_PARAMETER
        identity.addAliases(AddCreatorAliasesRequest(creatorId, revision, selected, key))
        return null
    }

    private suspend fun setDisplayName(params: Map<String, String>): AuthorArchiveTestFailureCode? {
        val creatorId = creatorId(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val revision = params["revision"]?.toLongOrNull() ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val name = params["name"]?.takeIf(String::isNotBlank) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val key = params["idempotencyKey"]?.takeIf(String::isNotBlank)
            ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        identity.setDisplayName(SetCreatorDisplayNameRequest(creatorId, revision, name, key))
        return null
    }

    private suspend fun setFrequency(params: Map<String, String>): AuthorArchiveTestFailureCode? {
        val frequency = params["frequency"]?.let(CreatorCheckFrequency::parse)
            ?: return AuthorArchiveTestFailureCode.INVALID_PARAMETER
        coroutineScope {
            val editor = CreatorSettingsEditor(requireNotNull(preferences), this) {
                scheduler.runIfDue()
            }
            editor.open()
            editor.select(frequency)
            editor.save().join()
            check(editor.state.value.savedRevision == 1L) { "Author frequency save failed" }
        }
        return null
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
        val dimension =
            params["dimension"]?.let { runCatching { LanguageDimension.valueOf(it.uppercase()) }.getOrNull() }
                ?: return AuthorArchiveTestFailureCode.INVALID_PARAMETER
        val tag = params["tag"]?.takeIf(String::isNotBlank) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        archiveRepository.setManualLanguage(subject, dimension, tag, now())
        return null
    }

    private suspend fun languageUndo(params: Map<String, String>): AuthorArchiveTestFailureCode? {
        val subject = languageSubject(params) ?: return AuthorArchiveTestFailureCode.MISSING_PARAMETER
        val dimension =
            params["dimension"]?.let { runCatching { LanguageDimension.valueOf(it.uppercase()) }.getOrNull() }
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
        val creators = creatorRepository.getCreatorsAsFlow().first()
        val identities = creators.map { creator ->
            val snapshot = identity.snapshot(creator.id)
            AuthorIdentityTestSnapshot(snapshot.id, snapshot.revision, snapshot.displayName, snapshot.names)
        }
        cachedSnapshot.set(
            AuthorArchiveTestSnapshot(
                creators = creators.map { it.id },
                followedCreators = creatorRepository.getFollowedCreators().map { it.creatorId },
                discoveryIds = archiveRepository.getDiscoveries(200).map { it.id },
                discoveryTaskStatus = scheduler.state.value.status.name,
                identities = identities,
                frequency = preferences?.current()?.value ?: "daily",
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
