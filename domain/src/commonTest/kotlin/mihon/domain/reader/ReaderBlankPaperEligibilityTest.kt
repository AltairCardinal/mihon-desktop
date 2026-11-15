package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderBlankPaperEligibilityTest {
    @Test
    fun `default wide remainder waits for proof and existing default frame upgrades without changing identity`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        ids.forEachIndexed { index, id -> owner.acceptFact(owner.requestFact(id), fact(index)) }
        val ticket = requireNotNull(owner.capturePresentableViewport(ids[3]))
        assertEquals(ReaderDualPagePresentationSource.DEFAULT, ticket.source)
        assertEquals(ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD, owner.frame(ticket)!!.unit.singleReason)
        assertNull(owner.frame(ticket)!!.blankPaperSlot)
        assertTrue(owner.preparePage(ticket, ids[3]))
        assertTrue(owner.reportDrawn(ticket, setOf(ids[3])))
        publishOriginals(owner)
        assertTrue(owner.certifyPairing(ids[3]) is ReaderPairingCertification.Certified)
        val certified = requireNotNull(owner.frame(ticket))
        assertEquals(ticket.frameId, certified.id)
        assertEquals(ReaderPortraitSingleSlot.LEFT, certified.blankPaperSlot)
        assertEquals(
            ReaderDualPagePresentationSource.DEFAULT,
            ticket.source,
            "The original ticket source is not the current proof",
        )
        val nextIdentity = owner.identity.copy(
            content = owner.identity.content.mapIndexed { index, content ->
                if (index ==
                    1
                ) {
                    content.copy(contentVersion = "changed-prefix")
                } else {
                    content
                }
            },
        )
        val rebound = owner.rebindContent(nextIdentity)
        val invalidated = rebound.presentationFrames.single { it.frameId == ticket.frameId }
        assertNull(invalidated.blankPaperSlot, "A replaced proof dependency cannot keep a decorative page")
        rebound.close()
    }

    @Test
    fun `only explicitly forced own portrait may fill while original certification is pending`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        assertTrue(
            owner.acceptPairingRestore(
                requireNotNull(owner.beginPairingRestore(explicit = true)),
                ChapterPairingSnapshot(ChapterPairingRecord(1, 7, setOf(3)), 1),
            ),
        )
        assertNull(owner.presentationFrames.first { ids[3] in it.unit.pageIds }.blankPaperSlot)
        owner.acceptFact(owner.requestFact(ids[3]), ReaderPageFact.dimensions(100, 150))
        val forced = owner.presentationFrames.first { ids[3] in it.unit.pageIds }
        assertEquals(ReaderPortraitSingleSlot.LEFT, forced.blankPaperSlot)
        owner.acceptFact(owner.requestFact(ids[2]), ReaderPageFact.dimensions(100, 150))
        assertNull(
            owner.presentationFrames.first {
                ids[2] in it.unit.pageIds
            }.blankPaperSlot,
            "A neighbour boundary reason is not an explicit single for that neighbour",
        )
        owner.acceptFact(owner.requestFact(ids[3]), ReaderPageFact.dimensions(200, 100))
        assertNull(owner.presentationFrames.first { ids[3] in it.unit.pageIds }.blankPaperSlot)
        owner.close()
    }

    @Test
    fun `default early wide boundary is not proof when an earlier wide changes the pairing phase`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        ids.forEachIndexed { index, id -> owner.acceptFact(owner.requestFact(id), fact(index)) }
        val before = owner.presentationFrames.first { ids[3] in it.unit.pageIds }
        assertEquals(1, before.unit.pageIds.size)
        assertTrue(before.unit.certain)
        assertNotNull(before.unit.singleReason)
        assertNull(before.blankPaperSlot)
        owner.acceptFact(owner.requestFact(ids[1]), ReaderPageFact.dimensions(200, 100))
        val after = owner.presentationFrames.first { ids[3] in it.unit.pageIds }
        assertEquals(listOf(ids[2], ids[3]), after.unit.pageIds)
        assertNull(after.blankPaperSlot)
        owner.close()
    }

    @Test
    fun `a certified pair trimmed after one real pixel lock never becomes decorative paper`() {
        val owner = owner()
        owner.configure(ReaderDirection.RTL, automaticMatching = false)
        val ids = owner.identity.pageIds
        ids.forEach { owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 150)) }
        val ticket = requireNotNull(owner.capturePresentableViewport(ids[3]))
        assertEquals(listOf(ids[3], ids[4]), owner.frame(ticket)!!.unit.pageIds)
        assertTrue(owner.preparePage(ticket, ids[3]))
        assertTrue(owner.reportDrawn(ticket, setOf(ids[3])))
        owner.acceptFact(owner.requestFact(ids[4]), ReaderPageFact.dimensions(200, 100))
        assertTrue(owner.certifyPairing(ids[3]) is ReaderPairingCertification.Certified)
        repeat(2) {
            val retained = requireNotNull(owner.frame(ticket))
            assertEquals(ticket.frameId, retained.id)
            assertEquals(listOf(ids[3]), retained.unit.pageIds)
            assertNull(retained.blankPaperSlot)
        }
        owner.close()
    }

    @Test
    fun `valid manual outgoing and unmounted frames retain paper without a target requirement or certification work`() {
        val owner = owner()
        val ids = owner.identity.pageIds
        owner.acceptPairingRestore(
            requireNotNull(owner.beginPairingRestore(explicit = true)),
            ChapterPairingSnapshot(ChapterPairingRecord(1, 7, setOf(3)), 1),
        )
        owner.acceptFact(owner.requestFact(ids[3]), ReaderPageFact.dimensions(100, 150))
        val unmounted = owner.presentationFrames.first { ids[3] in it.unit.pageIds }
        assertNull(unmounted.frameId)
        assertEquals(ReaderPortraitSingleSlot.LEFT, unmounted.blankPaperSlot)
        val ticket = requireNotNull(owner.capturePresentableViewport(ids[3]))
        owner.preparePage(ticket, ids[3])
        owner.reportDrawn(ticket, setOf(ids[3]))
        owner.goToPage(ids[5])
        owner.capturePresentableViewport(ids[5])
        val runs = owner.certificationMetrics.engineRuns
        repeat(3) {
            assertEquals(
                ReaderPortraitSingleSlot.LEFT,
                owner.presentationFrames.single {
                    it.frameId == ticket.frameId
                }.blankPaperSlot,
            )
        }
        assertEquals(runs, owner.certificationMetrics.engineRuns)
        owner.close()
    }

    private fun fact(index: Int) = if (index ==
        4
    ) {
        ReaderPageFact.dimensions(200, 100)
    } else {
        ReaderPageFact.dimensions(100, 150)
    }

    private fun publishOriginals(owner: SharedDualPageSession) {
        owner.identity.pageIds.forEachIndexed { index, id ->
            val fact = fact(index)
            val plan = requireNotNull(ReaderOriginalPageSampling.plan(fact.width, fact.height))
            assertTrue(
                owner.acceptOriginalAnalysis(
                    requireNotNull(owner.requestOriginalAnalysis(id)),
                    fact,
                    plan.samples(
                        plan.left.map {
                            0xff334455.toInt()
                        },
                        plan.right.map { 0xff334455.toInt() },
                    ),
                ),
            )
        }
    }

    private fun owner(): SharedDualPageSession {
        val identity =
            ReaderDualPageIdentity(
                1,
                1,
                List(7) {
                    ReaderDualPageContentIdentity(ReaderPageId(ReaderChapterId(42), it), "page-$it")
                },
            )
        return SharedDualPageSession(identity, ReaderDirection.RTL, identity.pageIds[3], mangaId = 88).also {
            it.acceptPairingRestore(requireNotNull(it.beginPairingRestore()), ChapterPairingSnapshot(null, 0))
            it.configure(ReaderDirection.RTL, automaticMatching = true)
        }
    }
}
