package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageContentFact
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderPairingPersistenceRequest
import mihon.domain.reader.ReaderPairingPersistenceStatus
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageId
import java.util.IdentityHashMap

/** Retained by ReaderViewModel; all access belongs to the reader Main owner. */
class DualPagePairingStore(val requiresRestoration: Boolean = false) {
    internal data class RestoreRequest(
        val chapter: ReaderChapter,
        val pages: List<ReaderPage>,
        val owner: SharedDualPageSession,
        val request: ReaderPairingPersistenceRequest.Load,
    )
    internal data class ChapterPairing(
        val pages: List<ReaderPage>,
        val session: SharedDualPageSession,
        val factRetrySequences: MutableMap<ReaderPage, Long> = mutableMapOf(),
        val cachedFacts: MutableMap<ReaderPage, ReaderPageContentFact> = mutableMapOf(),
    )
    internal val chapters = IdentityHashMap<ReaderChapter, ChapterPairing>()
    private var pageListRevision = 0L

    internal fun ensure(chapter: ReaderChapter, pages: List<ReaderPage>): ChapterPairing {
        val sampled = pages.map { page -> page to page.captureContentFact() }
        val previous = chapters[chapter]
        if (previous?.pages === pages) {
            sampled.forEach { (page, captured) ->
                if (captured.second.retrySequence >
                    (previous.factRetrySequences[page] ?: captured.second.retrySequence)
                ) {
                    previous.session.reloadContent(previous.session.identity.pageIds[page.index])
                }
            }
        }
        previous?.takeIf {
            it.pages === pages && it.session.identity.content.map { content -> content.contentVersion } ==
                sampled.map { it.second.first }
        }?.let { entry ->
            sampled.forEach { (page, captured) ->
                if (entry.cachedFacts[page] != captured.second) {
                    page.consumeContentFact(captured) {
                        val original = entry.session.requestFact(entry.session.identity.pageIds[page.index])
                        if (entry.session.acceptOrientedFact(original, captured.second.fact) !=
                            null
                        ) {
                            entry.cachedFacts[page] = captured.second
                        }
                    }
                }
            }
            return entry
        }
        val target = previous?.session?.location?.pageId?.sourcePageIndex ?: chapter.requestedPage
        val chapterState = chapter.sharedSessionStateFlow.value
        val revision = ++pageListRevision
        val identity = ReaderDualPageIdentity(
            generation = chapterState.generation,
            pageListRevision = revision,
            content = sampled.map { (page, captured) ->
                ReaderDualPageContentIdentity(
                    ReaderPageId(chapterState.activeChapter.id, page.index),
                    captured.first,
                    sourceOrientation = captured.second.fact.sourceOrientation,
                )
            },
        )
        val samePages = previous?.session?.identity?.let {
            it.pageIds == identity.pageIds && it.generation == identity.generation
        } == true
        if (samePages && !requireNotNull(previous).session.canRebindContent(identity)) {
            // A normal loader snapshot may arrive while its old decoded image is still exposed.
            // Keep its owner and facts together until the native viewport releases that lease.
            return previous
        }
        val session = if (samePages) {
            requireNotNull(previous).session.rebindContent(identity)
        } else {
            previous?.session?.close()
            SharedDualPageSession(
                identity,
                ReaderDirection.RTL,
                identity.pageIds.getOrNull(target) ?: identity.pageIds.firstOrNull(),
                mangaId = chapter.chapter.manga_id.takeIf { requiresRestoration },
            )
        }
        sampled.forEach { (page, captured) ->
            page.consumeContentFact(captured) {
                val ticket = session.requestFact(identity.pageIds[page.index])
                session.acceptFact(ticket, captured.second.fact)
            }
        }
        return ChapterPairing(
            pages,
            session,
            factRetrySequences = sampled.associateTo(mutableMapOf()) { (page, captured) ->
                page to (previous?.factRetrySequences?.get(page) ?: captured.second.retrySequence)
            },
            cachedFacts = sampled.associateTo(mutableMapOf()) { (page, captured) -> page to captured.second },
        ).also { chapters[chapter] = it }
    }

    fun install(
        chapter: ReaderChapter,
        pages: List<ReaderPage>,
        snapshot: ChapterPairingSnapshot,
        canSave: Boolean = true,
    ) {
        val entry = ensure(chapter, pages)
        entry.session.beginPairingRestore()?.let { request ->
            if (canSave) {
                entry.session.acceptPairingRestore(request, snapshot)
            } else {
                entry.session.failPairingRestore(request)
                entry.session.useDefaultPairingForSession()
            }
        }
    }

    internal fun beginRestore(chapter: ReaderChapter, pages: List<ReaderPage>): RestoreRequest? {
        val owner = ensure(chapter, pages).session
        return owner.beginPairingRestore()?.let { RestoreRequest(chapter, pages, owner, it) }
    }

    internal fun acceptRestore(operation: RestoreRequest, snapshot: ChapterPairingSnapshot): Boolean {
        val entry = chapters[operation.chapter]?.takeIf {
            it.pages === operation.pages && it.session === operation.owner &&
                operation.chapter.pages === operation.pages
        } ?: return false
        if (!entry.session.acceptPairingRestore(operation.request, snapshot)) return false
        return true
    }

    internal fun failRestore(operation: RestoreRequest): Boolean =
        chapters[operation.chapter]?.takeIf {
            it.pages === operation.pages && it.session === operation.owner &&
                operation.chapter.pages === operation.pages
        }?.session?.failPairingRestore(operation.request) == true

    internal fun useDefault(chapter: ReaderChapter, pages: List<ReaderPage>): Boolean {
        val entry = ensure(chapter, pages)
        if (!entry.session.useDefaultPairingForSession()) return false
        return true
    }

    fun snapshot(chapter: ReaderChapter, pages: List<ReaderPage>): ChapterPairingSnapshot? =
        chapters[chapter]?.takeIf { it.pages === pages }?.session?.pairingState?.let { state ->
            val revision = state.revision ?: return null
            ChapterPairingSnapshot(
                state.committedForcedSinglePages.takeIf { it.isNotEmpty() }?.let {
                    mihon.domain.reader.ChapterPairingRecord(1, pages.size, it)
                },
                revision,
            )
        }

    fun isRestored(chapter: ReaderChapter, pages: List<ReaderPage>): Boolean =
        chapters[chapter]?.takeIf { it.pages === pages }?.session?.pairingState?.status in setOf(
            ReaderPairingPersistenceStatus.READY,
            ReaderPairingPersistenceStatus.SAVING,
            ReaderPairingPersistenceStatus.SAVE_FAILED,
            ReaderPairingPersistenceStatus.CONFLICT,
            ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED,
            ReaderPairingPersistenceStatus.TEMPORARY,
        )

    internal fun acceptSave(operation: PairingAdjustmentRequest, snapshot: ChapterPairingSnapshot): Boolean =
        chapters[operation.chapter]?.takeIf {
            it.pages === operation.pages && it.session === operation.owner &&
                operation.chapter.pages === operation.pages
        }?.session?.acceptPairingSave(operation.save, snapshot) == true

    internal fun failSave(operation: PairingAdjustmentRequest, conflict: Boolean): Boolean =
        chapters[operation.chapter]?.takeIf {
            it.pages === operation.pages && it.session === operation.owner &&
                operation.chapter.pages === operation.pages
        }?.session?.failPairingSave(operation.save, conflict) == true
}
