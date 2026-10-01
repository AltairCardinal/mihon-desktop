package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedDualPageSessionContractTest {
    private fun identity(count: Int, generation: Long = 3, revision: Long = 2, version: String = "synthetic-v1") =
        ReaderDualPageIdentity(
            generation,
            revision,
            List(count) {
                ReaderDualPageContentIdentity(ReaderPageId(ReaderChapterId(42), it), "$version-$it")
            },
        )
    private fun known(session: SharedDualPageSession, spread: Set<Int> = emptySet()) {
        for (page in session.identity.pageIds) {
            assertTrue(
                session.acceptFact(
                    session.requestFact(page),
                    if (page.sourcePageIndex in spread) {
                        ReaderPageFact.dimensions(1800, 1000)
                    } else {
                        ReaderPageFact.dimensions(900, 1300)
                    },
                ),
            )
        }
    }
    private fun event(
        session: SharedDualPageSession,
        reason: ReaderDualPageSettlementReason = ReaderDualPageSettlementReason.USER_NAVIGATION,
    ) =
        ReaderDualPageSettlement(
            session.identity, session.layoutRevision, session.location!!.sequence, session.targetUnit!!.id,
            session.targetUnit!!.pageIds.toSet(), true, reason, 1,
            session.captureViewport()!!,
        )

    @Test
    fun `disabling matching or switching single rejects cancelled samples even after reopening`() {
        for (single in listOf(false, true)) {
            val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
            known(session)
            session.configure(ReaderDirection.RTL, automaticMatching = true)
            val pages = session.identity.pageIds
            val artwork = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
            val samples = ReaderSpreadEdgeSamples(artwork, artwork)
            for (page in pages.subList(1, 3)) {
                assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(page)), samples))
            }
            val original = requireNotNull(session.requestSpreadSample(pages[3]))
            val header = session.requestFact(pages[4])
            val location = session.location
            if (single) {
                session.configurePresentationMode(ReaderPresentationMode.SINGLE)
            } else {
                session.configure(ReaderDirection.RTL, automaticMatching = false)
            }
            assertFalse(session.ownsSpreadSampleRequest(original), "Cancelled sample work is not current")
            assertFalse(session.acceptSpreadSample(original, samples))
            assertTrue(session.ownsFactRequest(header))
            if (single) {
                session.configurePresentationMode(ReaderPresentationMode.DUAL)
            } else {
                session.configure(ReaderDirection.RTL, automaticMatching = true)
            }
            assertFalse(
                session.ownsSpreadSampleRequest(original),
                "Reopening must not resurrect an original capability",
            )
            assertFalse(session.acceptSpreadSample(original, samples))
            assertEquals(location, session.location)
            assertEquals(
                listOf(pages[3]),
                session.spreadSampleDependencies(),
                "Confirmed edge samples survive cancellation",
            )
            assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(pages[3])), samples))
            session.close()
        }
    }

    @Test
    fun `session owns complete sample selection across direction manual restore and physical replacement`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL, mangaId = 88)
        known(session)
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        val pages = session.identity.pageIds
        session.goToPage(pages[2])
        val location = session.location
        val viewport = event(session)
        val artwork = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        val shifted = artwork.map { it + 0x050505 }
        val other = artwork.map { it + 0x202020 }
        val samples = listOf(
            ReaderSpreadEdgeSamples(artwork, List(50) { 0xffee2020.toInt() }),
            ReaderSpreadEdgeSamples(artwork, shifted),
            ReaderSpreadEdgeSamples(other, artwork),
            ReaderSpreadEdgeSamples(List(50) { 0xff2020ee.toInt() }, other),
        )
        assertEquals(listOf(pages[1], pages[2]), session.spreadSampleDependencies())
        val header = session.requestFact(pages[4])
        samples.forEachIndexed { index, edges ->
            assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(pages[index + 1])), edges))
        }
        assertTrue(session.ownsFactRequest(header), "Sample I/O cannot replace an original header capability")
        assertEquals(listOf(pages[2], pages[3]), session.targetUnit!!.pageIds)
        assertEquals(location, session.location)
        assertNull(session.settle(viewport, false, "old-layout"))
        assertNotNull(session.settle(event(session), false, "sample-layout"))
        session.configure(ReaderDirection.LTR, automaticMatching = true)
        assertEquals(listOf(pages[1], pages[2]), session.targetUnit!!.pageIds)
        assertFalse(session.captureViewport()!!.readingEligible)
        session.configure(ReaderDirection.RTL, automaticMatching = false)
        assertEquals(emptyList<ReaderPageId>(), session.spreadSampleDependencies())
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        assertEquals(listOf(pages[2], pages[3]), session.targetUnit!!.pageIds)
        assertTrue(
            session.acceptPairingRestore(
                session.beginPairingRestore()!!,
                ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(3)), 1),
            ),
        )
        assertEquals(listOf(pages[1], pages[2]), session.targetUnit!!.pageIds)
        val oldSample = requireNotNull(session.requestSpreadSample(pages[1]))
        val save = requireNotNull(session.adjustPairing())
        assertEquals(setOf(1, 3), save.adjustment.forcedSinglePages)
        assertEquals(listOf(pages[1], pages[2]), session.targetUnit!!.pageIds, "Pending save is not a manual boundary")
        assertTrue(session.acceptPairingSave(save, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1, 3)), 2)))
        assertEquals(listOf(pages[2]), session.targetUnit!!.pageIds)
        val replacement = session.rebindContent(
            session.identity.copy(
                content = session.identity.content.map {
                    if (it.pageId == pages[1]) it.copy(contentVersion = "replaced") else it
                },
            ),
        )
        assertEquals(location, replacement.location)
        assertFalse(replacement.acceptSpreadSample(oldSample, samples[0]))
        assertFalse(session.acceptSpreadSample(oldSample, samples[0]))
        assertFalse(replacement.captureViewport()!!.readingEligible)
        val latest = requireNotNull(replacement.requestSpreadSample(pages[2]))
        replacement.close()
        assertFalse(replacement.acceptSpreadSample(latest, samples[1]))
    }

    @Test
    fun `sample metadata keeps unchanged original work but revokes changed pixels and viewport`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        val pages = session.identity.pageIds
        val first = requireNotNull(session.requestSpreadSample(pages[1]))
        val second = requireNotNull(session.requestSpreadSample(pages[2]))
        val viewport = event(session)
        val samples = ReaderSpreadEdgeSamples(
            List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() },
            List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() },
        )
        assertNotNull(
            session.acceptOrientedFact(
                session.requestFact(pages[1]),
                ReaderPageFact.dimensions(900, 1300, sourceOrientation = ReaderImageOrientation.MIRROR_HORIZONTAL),
            ),
        )
        assertFalse(session.acceptSpreadSample(first, samples))
        assertTrue(session.ownsSpreadSampleRequest(second))
        assertTrue(session.acceptSpreadSample(second, samples))
        assertNull(session.settle(viewport, false, "old-orientation"))
        val fresh = requireNotNull(session.requestSpreadSample(pages[1]))
        assertEquals(ReaderImageOrientation.MIRROR_HORIZONTAL, fresh.content.sourceOrientation)
        assertTrue(session.acceptSpreadSample(fresh, samples))
        assertEquals(emptyList<ReaderPageId>(), session.spreadSampleDependencies().filter { it in pages.take(3) })
    }

    @Test
    fun `dual save completion in single mode commits the record while retaining its logical target`() {
        val session = SharedDualPageSession(identity(6), ReaderDirection.RTL, mangaId = 88)
        known(session)
        assertTrue(
            session.acceptPairingRestore(
                session.beginPairingRestore()!!,
                ChapterPairingSnapshot(ChapterPairingRecord(1, 6, setOf(1)), 7),
            ),
        )
        session.goToPage(session.identity.pageIds[3])
        assertEquals(listOf(2, 3), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val save = requireNotNull(session.adjustPairing())
        assertEquals(1, save.adjustment.currentPage)
        val originalLocation = session.location
        session.configurePresentationMode(ReaderPresentationMode.SINGLE)
        assertTrue(session.acceptPairingSave(save, ChapterPairingSnapshot(null, 8)))
        assertEquals(originalLocation, session.location)
        assertEquals(8, session.pairingState.revision)
        assertEquals(save.adjustment.forcedSinglePages, session.pairingState.committedForcedSinglePages)
        assertFalse(session.captureViewport()!!.readingEligible)
        session.configurePresentationMode(ReaderPresentationMode.DUAL)
        assertEquals(originalLocation, session.location)
        assertEquals(listOf(3, 4), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `single header arrival preserves the original unchanged physical presentation capability`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        session.configurePresentationMode(ReaderPresentationMode.SINGLE)
        session.goToPage(session.identity.pageIds[4])
        val original = event(session)
        val revision = session.layoutRevision
        known(session, setOf(0))
        assertEquals(revision, session.layoutRevision)
        assertSame(original.ticket, session.captureViewport())
        assertNotNull(session.settle(original, false, "test"))
    }

    @Test
    fun `single mode presents the logical target and revokes dual capability without another navigation`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds[4])
        val original = event(session)
        assertEquals(listOf(3, 4), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val location = session.location
        session.configurePresentationMode(ReaderPresentationMode.SINGLE)
        assertEquals(location, session.location)
        assertEquals(listOf(4), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(ReaderPortraitSingleSlot.FULL, session.targetUnit!!.slots.single().position)
        assertNull(session.settle(original, false, "test"))
        assertNull(session.adjustPairing())
        assertTrue(session.factDependencies().isEmpty())
        assertNotNull(session.settle(event(session).copy(settlementSequence = 2), false, "test"))
        session.configurePresentationMode(ReaderPresentationMode.DUAL)
        assertEquals(location, session.location)
        assertEquals(listOf(3, 4), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertFalse(session.captureViewport()!!.readingEligible)
        assertNull(session.settle(event(session).copy(settlementSequence = 3), false, "test"))
    }

    @Test
    fun `unconsumed reading and single mode survive content replacement and old presenter disposal`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds[4])
        val dual = event(session)
        session.configurePresentationMode(ReaderPresentationMode.SINGLE)
        val single = event(session)
        assertFalse(session.releaseViewport(dual.ticket))
        val next = session.rebindContent(replacedPage(session.identity, 4))
        known(next)
        assertEquals(listOf(4), next.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertNull(next.settle(single, false, "test"))
        assertNotNull(next.settle(event(next).copy(settlementSequence = 2), false, "test"))
    }

    @Test
    fun `explicit retry renews fact work without creating navigation or reading`() {
        val session = SharedDualPageSession(identity(1), ReaderDirection.RTL)
        known(session)
        assertNotNull(session.settle(event(session), false, "test"))
        val location = session.location
        val oldBudget = session.factBudget
        val oldRequest = session.requestFact(session.identity.pageIds.single())
        assertTrue(session.retryFacts())
        assertEquals(location, session.location)
        assertTrue(oldBudget !== session.factBudget)
        assertFalse(session.acceptFact(oldRequest, ReaderPageFact.dimensions(900, 1300)))
        assertFalse(session.captureViewport()!!.readingEligible)
        assertNull(session.settle(event(session).copy(settlementSequence = 2), false, "test"))
        session.close()
        assertFalse(session.retryFacts())
    }

    @Test
    fun `content rebind retains successful pairing restoration without another read`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL, mangaId = 88)
        known(session)
        val record = ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(3)), 7)
        assertTrue(session.acceptPairingRestore(session.beginPairingRestore()!!, record))
        val next = session.rebindContent(replacedPage(session.identity, 2))
        assertEquals(ReaderPairingPersistenceStatus.READY, next.pairingState.status)
        assertEquals(setOf(3), next.pairingState.committedForcedSinglePages)
        assertEquals(7, next.pairingState.revision)
    }

    @Test
    fun `content rebind cannot reissue a consumed reading cause after real settlement`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds.last())
        assertNotNull(session.settle(event(session), false, "test"))
        val next = session.rebindContent(replacedPage(session.identity, 2))
        known(next)
        assertNull(next.settle(event(next).copy(settlementSequence = 2), false, "test"))
        next.goToPage(next.identity.pageIds.first())
        assertNotNull(next.settle(event(next).copy(settlementSequence = 3), false, "test"))
    }

    @Test
    fun `content rebind preserves unsettled target seventeen and rejects earlier capabilities`() {
        val session = SharedDualPageSession(identity(17), ReaderDirection.RTL)
        session.goToPage(session.identity.pageIds[16])
        val oldLocation = session.location!!
        val oldFact = session.requestFact(session.identity.pageIds[16])
        val oldViewport = event(session)
        val next = session.rebindContent(replacedPage(session.identity, 9))
        assertEquals(oldLocation, next.location)
        assertFalse(next.acceptFact(oldFact, ReaderPageFact.dimensions(900, 1300)))
        assertNull(next.settle(oldViewport, false, "test"))
        known(next, setOf(9))
        assertEquals(16, next.location!!.pageId.sourcePageIndex)
        assertNotNull(next.settle(event(next).copy(settlementSequence = 2), false, "test"))
    }

    private fun replacedPage(identity: ReaderDualPageIdentity, ordinal: Int): ReaderDualPageIdentity = identity.copy(
        pageListRevision = identity.pageListRevision + 1,
        content = identity.content.mapIndexed { index, content ->
            if (index == ordinal) content.copy(contentVersion = "replacement-$index") else content
        },
    )

    @Test
    fun `detaching a viewport revokes its original capability without creating or losing reading cause`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds.last())
        val old = event(session)
        val location = session.location
        assertTrue(session.releaseViewport(old.ticket))
        val attached = event(session)
        assertEquals(location, session.location)
        assertEquals(old.identity, attached.identity)
        assertEquals(old.layoutRevision, attached.layoutRevision)
        assertEquals(old.unitId, attached.unitId)
        assertFalse(old.ticket === attached.ticket)
        assertFalse(session.releaseViewport(old.ticket))
        assertNull(session.settle(old, false, "test"))
        assertNotNull(session.settle(attached.copy(settlementSequence = 2), false, "test"))
        assertTrue(session.releaseViewport(attached.ticket))
        val consumed = event(session)
        assertFalse(consumed.ticket.readingEligible)
        assertNull(session.settle(consumed.copy(settlementSequence = 3), false, "test"))
    }

    @Test
    fun `restored presentation rejects settled callback from earlier identical owner`() {
        val identity = identity(5)
        val earlier = SharedDualPageSession(identity, ReaderDirection.RTL)
        known(earlier)
        val old = event(earlier)
        earlier.close()
        val restored = SharedDualPageSession(identity, ReaderDirection.RTL)
        known(restored)
        assertEquals(old.identity, restored.identity)
        assertEquals(old.layoutRevision, restored.layoutRevision)
        assertEquals(old.locationSequence, restored.location!!.sequence)
        assertEquals(old.unitId, restored.targetUnit!!.id)
        assertNull(restored.settle(old, false, "test"))
        assertNotNull(restored.settle(event(restored), false, "test"))
    }

    @Test
    fun `database backed default after failed restore can retry without overwriting old record`() {
        val owner = ReaderPairingPersistenceOwner(identity(5), 88)
        val load = owner.beginRestore()!!
        owner.failRestore(load)
        assertTrue(owner.useDefaultForSession())
        assertNull(owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1))
        val retry = owner.beginRestore()
        assertNotNull(retry)
        assertTrue(owner.acceptRestore(retry!!, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(3)), 8)))
        assertEquals(setOf(3), owner.state.committedForcedSinglePages)
        assertEquals(8, owner.state.revision)
    }

    @Test
    fun `shared owner exposes invalid restored record notice rather than silent default`() {
        for (record in listOf(
            ChapterPairingRecord(1, 4, setOf(1)),
            ChapterPairingRecord(1, 5, setOf(1), corrupt = true),
            ChapterPairingRecord(2, 5, setOf(1)),
        )) {
            val owner = ReaderPairingPersistenceOwner(identity(5), 88)
            assertTrue(owner.acceptRestore(owner.beginRestore()!!, ChapterPairingSnapshot(record, 4)))
            assertEquals(emptySet<Int>(), owner.state.committedForcedSinglePages)
            assertEquals(ReaderPairingPersistenceNotice.INVALID_RECORD, owner.state.notice)
            assertEquals(ReaderPairingPersistenceStatus.READY, owner.state.status)
        }
    }

    @Test
    fun `manual saved and temporary layout adjustment cannot manufacture an unsettled navigation read`() {
        for (mangaId in listOf(null, 88L)) {
            val session = SharedDualPageSession(identity(5), ReaderDirection.RTL, mangaId = mangaId)
            known(session)
            if (mangaId != null) {
                assertTrue(
                    session.acceptPairingRestore(
                        session.beginPairingRestore()!!,
                        ChapterPairingSnapshot(null, 7),
                    ),
                )
            }
            session.goToPage(session.identity.pageIds[1])
            val save = session.adjustPairing()
            if (save != null) {
                assertTrue(
                    session.acceptPairingSave(
                        save,
                        ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1)), 8),
                    ),
                )
            }
            assertNull(session.settle(event(session), false, "test"))
            session.goToPage(session.identity.pageIds.last())
            assertNotNull(session.settle(event(session).copy(settlementSequence = 2), false, "test"))
        }
        val naturalSingle = SharedDualPageSession(identity(1), ReaderDirection.RTL)
        known(naturalSingle)
        assertNull(naturalSingle.adjustPairing())
        assertNotNull(naturalSingle.settle(event(naturalSingle), false, "test"))
    }

    @Test
    fun `pairing restore gates adjustment and only successful save changes committed layout`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL, mangaId = 88)
        known(session)
        session.goToPage(session.identity.pageIds[1])
        assertNull(session.adjustPairing())
        val restore = session.beginPairingRestore()
        assertNotNull(restore)
        assertNull(session.beginPairingRestore())
        assertTrue(session.acceptPairingRestore(restore!!, ChapterPairingSnapshot(null, 7)))
        val original = session.targetUnit!!.pageIds
        val save = session.adjustPairing()!!
        assertEquals(setOf(1), save.adjustment.forcedSinglePages)
        assertEquals(original, session.targetUnit!!.pageIds)
        assertEquals(emptySet<Int>(), session.pairingState.committedForcedSinglePages)
        assertTrue(session.failPairingSave(save))
        assertEquals(original, session.targetUnit!!.pageIds)
        val retry = session.adjustPairing()!!
        assertTrue(session.acceptPairingSave(retry, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1)), 8)))
        assertEquals(setOf(1), session.pairingState.committedForcedSinglePages)
        assertEquals(session.identity.pageIds[2], session.location!!.pageId)
        assertEquals(listOf(session.identity.pageIds[2], session.identity.pageIds[3]), session.targetUnit!!.pageIds)
        val next = session.adjustPairing()!!
        session.goToPage(session.identity.pageIds[4])
        assertTrue(session.acceptPairingSave(next, ChapterPairingSnapshot(null, 9)))
        assertEquals(session.identity.pageIds[4], session.location!!.pageId)
        assertEquals(emptySet<Int>(), session.pairingState.committedForcedSinglePages)
    }

    @Test
    fun `explicit reload supersedes delayed save delivery while retaining committed state`() {
        for (written in listOf(false, true)) {
            val owner = ReaderPairingPersistenceOwner(identity(5), 88)
            assertTrue(owner.acceptRestore(owner.beginRestore()!!, ChapterPairingSnapshot(null, 0)))
            val save = owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1)!!
            val load = owner.beginRestore()
            assertNotNull(load, "Returning to a chapter must issue a new load even while old save delivery is pending")
            assertEquals(ReaderPairingPersistenceStatus.RESTORING, owner.state.status)
            assertEquals(emptySet<Int>(), owner.state.committedForcedSinglePages)
            assertNull(owner.beginSave(ReaderPairingAdjustment(setOf(3), 4), 2))
            val snapshot = if (written) {
                ChapterPairingSnapshot(
                    ChapterPairingRecord(1, 5, setOf(1)),
                    1,
                )
            } else {
                ChapterPairingSnapshot(null, 0)
            }
            assertFalse(owner.acceptSave(save, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1)), 1)))
            assertFalse(owner.failSave(save, false))
            assertTrue(owner.acceptRestore(load!!, snapshot))
            assertEquals(if (written) setOf(1) else emptySet<Int>(), owner.state.committedForcedSinglePages)
            assertEquals(ReaderPairingPersistenceStatus.READY, owner.state.status)
            assertFalse(owner.failSave(save, true))
            assertEquals(snapshot.revision, owner.state.revision)
        }
    }

    @Test
    fun `shared persistence owner restores saves fails and reloads conflict without losing record`() {
        val owner = ReaderPairingPersistenceOwner(identity(5), 88)
        assertNull(owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1))
        val load = owner.beginRestore()!!
        assertEquals(88, load.mangaId)
        assertEquals(ReaderPairingPersistenceStatus.RESTORING, owner.state.status)
        assertNull(owner.beginRestore())
        assertTrue(owner.acceptRestore(load, ChapterPairingSnapshot(null, 7)))
        val adjustment = ReaderPairingAdjustment(setOf(1), 2)
        val save = owner.beginSave(adjustment, 1)!!
        assertEquals(7, save.expectedRevision)
        assertEquals(emptySet<Int>(), owner.state.committedForcedSinglePages)
        assertEquals(ReaderPairingPersistenceStatus.SAVING, owner.state.status)
        assertNull(owner.beginSave(adjustment, 1))
        assertTrue(owner.failSave(save, false))
        assertEquals(ReaderPairingPersistenceStatus.SAVE_FAILED, owner.state.status)
        assertEquals(emptySet<Int>(), owner.state.committedForcedSinglePages)
        val retry = owner.beginSave(adjustment, 1)!!
        assertFalse(owner.acceptSave(save, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1)), 8)))
        assertTrue(owner.acceptSave(retry, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1)), 8)))
        assertEquals(setOf(1), owner.state.committedForcedSinglePages)
        val conflict = owner.beginSave(ReaderPairingAdjustment(emptySet(), 1), 2)!!
        assertTrue(owner.failSave(conflict, true))
        assertEquals(ReaderPairingPersistenceStatus.CONFLICT, owner.state.status)
        assertEquals(setOf(1), owner.state.committedForcedSinglePages)
        assertNull(owner.beginSave(adjustment, 2))
        val reload = owner.beginRestore()!!
        assertTrue(owner.acceptRestore(reload, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(3)), 9)))
        assertEquals(setOf(3), owner.state.committedForcedSinglePages)
        assertEquals(9, owner.beginSave(ReaderPairingAdjustment(emptySet(), 3), 2)!!.expectedRevision)
    }

    @Test
    fun `failed restore default and temporary readers never issue a save or erase stored records`() {
        val owner = ReaderPairingPersistenceOwner(identity(5), 88)
        val load = owner.beginRestore()!!
        assertTrue(owner.failRestore(load))
        assertEquals(ReaderPairingPersistenceStatus.RESTORE_FAILED, owner.state.status)
        assertNull(owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1))
        assertTrue(owner.useDefaultForSession())
        assertEquals(ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED, owner.state.status)
        assertNull(owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1))
        assertEquals(setOf(1), owner.state.committedForcedSinglePages)
        val temporary = ReaderPairingPersistenceOwner(identity(5), null)
        assertNull(temporary.beginRestore())
        assertNull(temporary.beginSave(ReaderPairingAdjustment(setOf(3), 4), 1))
        assertEquals(setOf(3), temporary.state.committedForcedSinglePages)
    }

    @Test
    fun `same identity owner restoration rejects stale load and malformed CAS save response`() {
        val identity = identity(5)
        val earlier = ReaderPairingPersistenceOwner(identity, 88)
        val old = earlier.beginRestore()!!
        earlier.close()
        val owner = ReaderPairingPersistenceOwner(identity, 88)
        val load = owner.beginRestore()!!
        assertFalse(owner.acceptRestore(old, ChapterPairingSnapshot(null, 2)))
        assertTrue(owner.acceptRestore(load, ChapterPairingSnapshot(ChapterPairingRecord(1, 4, setOf(1)), 2)))
        assertEquals(emptySet<Int>(), owner.state.committedForcedSinglePages)
        val save = owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1)!!
        assertFalse(owner.acceptSave(save, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(3)), 3)))
        assertEquals(ReaderPairingPersistenceStatus.SAVE_FAILED, owner.state.status)
        assertEquals(emptySet<Int>(), owner.state.committedForcedSinglePages)
        val retry = owner.beginSave(ReaderPairingAdjustment(setOf(1), 2), 1)!!
        assertFalse(owner.acceptSave(retry, ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(1)), 2)))
        assertEquals(ReaderPairingPersistenceStatus.SAVE_FAILED, owner.state.status)
    }

    @Test
    fun `explicit seventeen target survives provisional layout late width match and stable anchor`() {
        val identity = identity(17)
        val session = SharedDualPageSession(identity, ReaderDirection.RTL)
        assertTrue(session.goToPage(identity.pageIds[16]))
        assertEquals(listOf(identity.pageIds[16]), session.targetUnit!!.pageIds)
        assertEquals(ReaderPortraitSingleSlot.FULL, session.targetUnit!!.slots.single().position)
        known(session, setOf(13))
        session.configure(
            ReaderDirection.RTL,
            automaticMatching = true,
        )
        val artwork = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        val edges = ReaderSpreadEdgeSamples(artwork, artwork)
        for (page in identity.pageIds.subList(14, 16)) {
            assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(page)), edges))
        }
        assertEquals(identity.pageIds[16], session.location!!.pageId)
        assertEquals(listOf(identity.pageIds[16]), session.targetUnit!!.pageIds)
        assertTrue(session.navigate(false))
        assertEquals(listOf(identity.pageIds[14], identity.pageIds[15]), session.targetUnit!!.pageIds)
        assertEquals(identity.pageIds[14], session.targetUnit!!.anchor)
    }

    @Test
    fun `latest navigation invalidates old requests while duplicate facts are idempotent`() {
        val identity = identity(17)
        val session = SharedDualPageSession(identity, ReaderDirection.RTL)
        session.goToPage(identity.pageIds[16])
        val old = session.requestFact(identity.pageIds[13])
        session.goToPage(identity.pageIds[4])
        assertFalse(session.acceptFact(old, ReaderPageFact.dimensions(1800, 1000)))
        val request = session.requestFact(identity.pageIds[4])
        val fact = ReaderPageFact.dimensions(900, 1300)
        assertTrue(session.acceptFact(request, fact))
        val revision = session.layoutRevision
        assertTrue(session.acceptFact(request, fact))
        assertEquals(revision, session.layoutRevision)
        assertEquals(identity.pageIds[4], session.location!!.pageId)
    }

    @Test
    fun `restored session rejects earlier owner ticket with identical identity and request sequence`() {
        val identity = identity(4)
        val earlier = SharedDualPageSession(identity, ReaderDirection.RTL)
        val old = earlier.requestFact(identity.pageIds[1])
        earlier.close()
        val restored = SharedDualPageSession(identity, ReaderDirection.RTL)
        val fresh = restored.requestFact(identity.pageIds[1])
        assertEquals(old.requestId, fresh.requestId)
        assertEquals(old.identity, fresh.identity)
        val fact = ReaderPageFact.dimensions(900, 1300)
        assertFalse(restored.acceptFact(old, fact))
        assertTrue(restored.acceptFact(fresh, fact))
    }

    @Test
    fun `old generation reordered list replaced content rotation and forged request are rejected`() {
        val session = SharedDualPageSession(identity(4), ReaderDirection.RTL)
        val request = session.requestFact(session.identity.pageIds[1])
        val fact = ReaderPageFact.dimensions(900, 1300)
        val identities = listOf(
            session.identity.copy(generation = 4),
            session.identity.copy(pageListRevision = 3),
            session.identity.copy(content = session.identity.content.reversed()),
            identity(4, version = "replacement"),
            session.identity.copy(
                content = session.identity.content.map {
                    it.copy(rotation = PageRotation.CLOCKWISE_90)
                },
            ),
        )
        for (identity in identities) assertFalse(session.acceptFact(request.copy(identity = identity), fact))
        assertFalse(session.acceptFact(request.copy(requestId = request.requestId + 100), fact))
        assertTrue(session.acceptFact(request, fact))
        session.close()
        assertFalse(session.acceptFact(request, fact))
        assertFalse(session.goToPage(session.identity.pageIds[2]))
    }

    @Test
    fun `direction and mode restoration preserve intent rather than replacing it with group first`() {
        val identity = identity(5)
        val target = identity.pageIds[2]
        val session = SharedDualPageSession(identity, ReaderDirection.RTL, target)
        known(session)
        assertEquals(identity.pageIds[1], session.targetUnit!!.anchor)
        session.configure(ReaderDirection.LTR)
        assertEquals(target, session.location!!.pageId)
        val restored = SharedDualPageSession(identity, ReaderDirection.RTL, session.location!!.pageId)
        known(restored)
        assertEquals(target, restored.location!!.pageId)
        assertFalse(restored.goToPage(ReaderPageId(ReaderChapterId(99), 2)))
    }

    @Test
    fun `progress requires current real settled entire rendered unit and user cause`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds.last())
        val good = event(session)
        assertNull(session.settle(good.copy(settled = false), false, "test"))
        assertNull(session.settle(good.copy(renderedPageIds = setOf(session.identity.pageIds.last())), false, "test"))
        assertNull(session.settle(good.copy(layoutRevision = good.layoutRevision - 1), false, "test"))
        assertNull(session.settle(good.copy(locationSequence = good.locationSequence - 1), false, "test"))
        assertNull(session.settle(good.copy(identity = good.identity.copy(generation = 4)), false, "test"))
        assertNull(session.settle(good.copy(reason = ReaderDualPageSettlementReason.LAYOUT_ONLY), false, "test"))
        val effect = session.settle(good, false, "test")
        assertNotNull(effect)
        assertTrue(effect!!.reachedLastPage)
        assertEquals(session.identity.pageIds[3], session.targetUnit!!.anchor)
        assertEquals(4, effect.lastPageRead)
        assertNull(session.settle(good, false, "test"))
    }

    @Test
    fun `actually rendered unresolved target can count while later layout does not create reading`() {
        val session = SharedDualPageSession(identity(17), ReaderDirection.RTL)
        session.goToPage(session.identity.pageIds[16])
        val effect = session.settle(event(session), false, "test")
        assertNotNull(effect)
        assertTrue(effect!!.reachedLastPage)
        known(session, setOf(13))
        assertNull(session.settle(event(session, ReaderDualPageSettlementReason.LAYOUT_ONLY), false, "test"))
    }
}
