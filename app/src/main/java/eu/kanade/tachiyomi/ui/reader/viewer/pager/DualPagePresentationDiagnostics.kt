package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import logcat.LogPriority
import mihon.domain.reader.ReaderDualPageFrame
import mihon.domain.reader.ReaderDualPageUnit
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageId
import tachiyomi.core.common.util.system.logcat

/** Observes existing native facts and locks; never samples pixels or asks the owner for a new decision. */
internal class DualPagePresentationDiagnostics(private val session: String, private val holder: Long) {
    private data class State(
        val context: Long,
        val serial: Long,
        val sequence: Long,
        val ownerSequence: Long?,
        val revision: Long,
        val observedHint: ReaderPageSideHint,
        val submittedHint: ReaderPageSideHint?,
        val storedHint: ReaderPageSideHint,
        val factState: ReaderPageFactState,
        val sampled: Boolean,
        val matching: Boolean,
        val sideEnabled: Boolean,
        val crop: Boolean,
        val bounded: Boolean,
        val animated: Boolean,
        val actual: ReaderPortraitSingleSlot,
        val unit: ReaderDualPageUnit?,
        val beforeUnit: ReaderDualPageUnit?,
        val locked: Set<ReaderPageId>?,
        val lockedBefore: Boolean,
        val accepted: Boolean?,
        val prepared: Boolean?,
        val outcome: String,
    )

    // At most two source pages and three event kinds per holder. A new ticket replaces its old entry.
    private val observations = mutableMapOf<Pair<Int, String>, State>()

    fun retainSources(sources: Collection<Int>) {
        observations.keys.removeAll { it.first !in sources }
    }

    fun record(
        event: String,
        owner: SharedDualPageSession,
        ticket: ReaderDualPageViewportTicket,
        source: Int,
        image: ReaderPageImageView,
        config: ReaderPageImageView.Config,
        matching: Boolean,
        animated: Boolean,
        actual: ReaderPortraitSingleSlot,
        frame: ReaderDualPageFrame?,
        lockedBefore: Boolean,
        beforeFrame: ReaderDualPageFrame? = null,
        submittedHint: ReaderPageSideHint? = null,
        accepted: Boolean? = null,
        prepared: Boolean? = null,
        outcome: String,
    ) {
        val pageId = owner.identity.pageIds.getOrNull(source) ?: return
        val fact = owner.pageFact(pageId)
        val actualConfig = image.decodedSideConfig ?: config
        val state = State(
            ticket.frameId.context,
            ticket.frameId.serial,
            ticket.locationSequence,
            owner.location?.sequence,
            owner.layoutRevision,
            image.decodedPageSideHint,
            submittedHint,
            fact?.sideHint ?: ReaderPageSideHint.UNKNOWN,
            fact?.state ?: ReaderPageFactState.UNKNOWN,
            image.hasDecodedSideSample,
            matching,
            actualConfig.pageSideHintEnabled,
            actualConfig.cropBorders,
            actualConfig.sourceBounds != null,
            animated,
            actual,
            frame?.unit,
            beforeFrame?.unit,
            frame?.lockedPageIds,
            lockedBefore,
            accepted,
            prepared,
            outcome,
        )
        val key = source to event
        if (observations[key] == state) return
        observations[key] = state
        // Strings and neighbouring metadata are produced only for a new event/state, never per frame.
        val proposed = frame?.unit?.slots?.joinToString(",") {
            "${it.pageId.sourcePageIndex}:${it.position}"
        } ?: "none"
        val proposedBefore = beforeFrame?.unit?.slots?.joinToString(",") {
            "${it.pageId.sourcePageIndex}:${it.position}"
        } ?: "none"
        val locked = frame?.unit?.slots?.filter { it.pageId in frame.lockedPageIds }?.joinToString(",") {
            "${it.pageId.sourcePageIndex}:${it.position}"
        }?.ifEmpty { "none" } ?: "none"
        val neighbors = (source - 1..source + 1).mapNotNull { index ->
            owner.identity.pageIds.getOrNull(index)?.let { id ->
                val adjacent = owner.pageFact(id)
                "$index:${adjacent?.state ?: ReaderPageFactState.UNKNOWN}:" +
                    "${adjacent?.sideHint ?: ReaderPageSideHint.UNKNOWN}"
            }
        }.joinToString(",")
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.record(
            event,
            pageId.chapterId.value,
            source,
            mapOf(
                "holder" to holder,
                "sequence" to ticket.locationSequence, "ownerSequence" to state.ownerSequence,
                "context" to state.context, "serial" to state.serial,
                "observedHint" to state.observedHint, "submittedHint" to state.submittedHint,
                "storedHint" to state.storedHint, "sample" to state.sampled,
                "matching" to matching, "sideEnabled" to state.sideEnabled, "crop" to state.crop,
                "bounded" to state.bounded, "animated" to animated, "actual" to actual,
                "proposed" to proposed, "proposedBefore" to proposedBefore,
                "reasonBefore" to beforeFrame?.unit?.singleReason,
                "layoutRevision" to state.revision, "factState" to state.factState,
                "reason" to frame?.unit?.singleReason, "lockedBefore" to lockedBefore,
                "locked" to locked, "accepted" to accepted, "prepared" to prepared,
                "outcome" to outcome, "neighbors" to neighbors,
            ),
            session,
        )
        logcat(LogPriority.INFO) {
            "DualPagePresentation event=$event source=$source sequence=${ticket.locationSequence} " +
                "ownerSequence=${state.ownerSequence} context=${state.context} serial=${state.serial} " +
                "observedHint=${state.observedHint} submittedHint=${state.submittedHint ?: "NOT_SUBMITTED"} " +
                "storedHint=${state.storedHint} sample=${if (state.sampled) "ORIGINAL_DECODED" else "NOT_OBSERVED"} " +
                "matching=$matching sideEnabled=${state.sideEnabled} crop=${state.crop} " +
                "bounded=${state.bounded} animated=$animated actual=$actual proposed=$proposed " +
                "proposedBefore=$proposedBefore reasonBefore=${beforeFrame?.unit?.singleReason ?: "PAIR_OR_ABSENT"} " +
                "reason=${frame?.unit?.singleReason ?: if (frame == null) "UNAVAILABLE" else "PAIR"} " +
                "lockedBefore=$lockedBefore locked=$locked accepted=$accepted prepared=$prepared " +
                "outcome=$outcome neighbors=$neighbors"
        }
    }
}
