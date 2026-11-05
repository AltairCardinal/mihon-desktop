package mihon.domain.reader

import kotlinx.coroutines.CancellationException
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedDualPageSessionContractTest {
    @Test
    fun `O8 fact requests avoid repeated chapter scans while retaining exact capabilities`() {
        val queryVisits = linkedMapOf<Int, Int>()
        for (count in listOf(8, 187)) {
            val original = identity(count)
            // Source indices are not list ordinals; retain the original identities in reversed story order.
            val expectedContent = original.content.reversed()
            var visits = 0
            val countedContent = object : AbstractList<ReaderDualPageContentIdentity>() {
                override val size: Int get() = expectedContent.size
                override fun get(index: Int): ReaderDualPageContentIdentity {
                    visits++
                    return expectedContent[index]
                }
            }
            val issuedIdentity = original.copy(content = countedContent)
            val session = SharedDualPageSession(issuedIdentity, ReaderDirection.RTL)
            lateinit var issued: ReaderDualPageFactRequest
            try {
                // Identity validation and owner/index construction belong to initialization, not this query window.
                visits = 0
                val requests = expectedContent.map { content ->
                    session.requestFact(content.pageId).also { request ->
                        assertSame(issuedIdentity, request.identity)
                        assertSame(content, request.content)
                    }
                }
                requests.forEach { assertTrue(session.ownsFactRequest(it)) }
                queryVisits[count] = visits

                val prior = requests.last()
                val latest = session.requestFact(prior.content.pageId)
                issued = latest
                assertNotSame(prior, latest)
                assertFalse(session.ownsFactRequest(prior), "A new request revokes only that page's old ticket")
                assertTrue(session.ownsFactRequest(requests.first()), "Other pages keep their issued tickets")
                assertTrue(session.ownsFactRequest(latest))
                assertTrue(session.acceptFact(latest, ReaderPageFact.dimensions(40, 60)))
                val foreign = SharedDualPageSession(issuedIdentity, ReaderDirection.RTL)
                try {
                    assertFalse(
                        foreign.ownsFactRequest(latest),
                        "Equal identity cannot transfer another owner's ticket",
                    )
                    assertFalse(foreign.acceptFact(latest, ReaderPageFact.dimensions(40, 60)))
                } finally {
                    foreign.close()
                }
                assertThrows(IllegalArgumentException::class.java) {
                    session.requestFact(ReaderPageId(ReaderChapterId(999), prior.content.pageId.sourcePageIndex))
                }
            } finally {
                session.close()
            }
            assertFalse(session.ownsFactRequest(issued))
            assertThrows(IllegalStateException::class.java) { session.requestFact(expectedContent.first().pageId) }
        }
        println("O8_FACT_CONTENT_VISITS $queryVisits")
        // Allow bounded initialization of a reusable lookup, but not a fresh scan for every page/ticket.
        assertTrue(
            queryVisits.all { (count, visits) -> visits <= 4 * count },
            "Actual content-list visits after initialization for requestFact plus ownsFactRequest: $queryVisits",
        )
    }

    @Test
    fun `original header revision changes only for accepted changed metadata`() {
        val session = SharedDualPageSession(identity(3), ReaderDirection.RTL)
        val page = session.identity.pageIds[1]
        val portrait = ReaderPageFact.dimensions(40, 60)
        val initial = session.originalHeaderRevision
        val request = session.requestFact(page)
        assertTrue(session.acceptFact(request, portrait))
        val accepted = session.originalHeaderRevision
        assertTrue(accepted > initial)
        assertTrue(session.acceptFact(request, portrait))
        assertTrue(session.acceptFact(session.requestFact(page), portrait))
        assertEquals(accepted, session.originalHeaderRevision)
        assertFalse(session.acceptFact(request, ReaderPageFact.dimensions(60, 40)))
        assertEquals(accepted, session.originalHeaderRevision)
        assertTrue(session.acceptFact(session.requestFact(page), ReaderPageFact.dimensions(60, 40)))
        assertTrue(session.originalHeaderRevision > accepted)
    }

    @Test
    fun `same presentation configuration is idempotent for revisions cached units and active capabilities`() {
        for (matching in listOf(false, true)) {
            val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
            known(session)
            val page = session.identity.pageIds[1]
            session.goToPage(page)
            session.configure(ReaderDirection.RTL, automaticMatching = matching)
            val viewport = requireNotNull(session.captureViewport(preparingPageIds = setOf(page)))
            assertTrue(session.reportDrawn(viewport, setOf(page)))
            val frame = requireNotNull(session.frame(viewport))
            val units = session.units
            val layout = session.layoutRevision
            val samples = session.sampleWorkRevision
            val facts = session.factWorkRevision
            val location = session.location
            val originalHeader = session.requestFact(page)
            val sample = if (matching) requireNotNull(session.requestSpreadSample(page)) else null
            repeat(8) { session.configure(ReaderDirection.RTL, automaticMatching = matching) }
            assertEquals(layout, session.layoutRevision, "Same options must not feed a new layout back to state")
            assertEquals(samples, session.sampleWorkRevision)
            assertEquals(facts, session.factWorkRevision)
            assertEquals(location, session.location)
            assertSame(units, session.units)
            assertEquals(frame, session.frame(viewport))
            assertTrue(session.preparePage(viewport, page))
            assertTrue(session.ownsFactRequest(originalHeader))
            if (sample != null) assertTrue(session.ownsSpreadSampleRequest(sample))
            assertThrows(IllegalArgumentException::class.java) { session.configure(ReaderDirection.VERTICAL, matching) }
            session.configure(ReaderDirection.LTR, automaticMatching = matching)
            assertTrue(session.layoutRevision > layout, "A real direction change still establishes new geometry")
        }
    }

    @Test
    fun `decoded viewport classification preserves original header and fixed frame metadata`() {
        val session = SharedDualPageSession(identity(4), ReaderDirection.RTL)
        val page = session.identity.pageIds[1]
        session.goToPage(page)
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        val header = session.requestFact(page)
        val viewport = requireNotNull(session.captureViewport(preparingPageIds = emptySet()))
        assertTrue(session.acceptDecodedFact(viewport, page, 100, 160))
        assertTrue(session.ownsFactRequest(header), "Decode classification cannot revoke the physical header")
        assertTrue(session.preparePage(viewport, page))
        assertTrue(session.reportDrawn(viewport, setOf(page)))
        val frame = requireNotNull(session.frame(viewport))
        val sample = requireNotNull(session.requestSpreadSample(page))
        assertSame(
            session,
            session.acceptOrientedFact(
                header,
                ReaderPageFact.dimensions(100, 160, sourceOrientation = ReaderImageOrientation.CLOCKWISE),
            ),
        )
        assertEquals(ReaderImageOrientation.CLOCKWISE, session.identity.content[1].sourceOrientation)
        assertEquals(frame, session.frame(viewport), "Same-byte metadata cannot alter a painted frame")
        assertFalse(session.ownsSpreadSampleRequest(sample))
        assertEquals(
            ReaderImageOrientation.CLOCKWISE,
            requireNotNull(session.requestSpreadSample(page)).content.sourceOrientation,
        )
        assertFalse(session.acceptOrientedFact(header, ReaderPageFact.dimensions(160, 100)) === session)
        assertTrue(session.navigate(true))
        assertNotNull(session.frame(viewport), "The exposed outgoing frame keeps its geometry lease")
        assertTrue(session.reportDrawn(viewport, setOf(page)), "Existing outgoing pixels remain valid")
        assertFalse(session.acceptDecodedFact(viewport, page, 160, 100))
        assertFalse(session.preparePage(viewport, session.identity.pageIds[2]))
        assertTrue(session.releaseViewport(viewport))
        assertFalse(session.acceptDecodedFact(viewport, page, 100, 160))
        session.close()
    }

    @Test
    fun `decoded facts reject revoked foreign and replaced content viewports`() {
        val original = identity(4)
        val session = SharedDualPageSession(original, ReaderDirection.RTL)
        val page = original.pageIds[1]
        session.goToPage(page)
        val ticket = requireNotNull(session.captureViewport(preparingPageIds = emptySet()))
        assertFalse(session.acceptDecodedFact(ticket, original.pageIds[3], 100, 160))
        assertFalse(session.acceptDecodedFact(ticket, page, 0, 160))
        assertNull(session.pageFact(page))
        session.invalidateViewport()
        assertFalse(session.acceptDecodedFact(ticket, page, 100, 160))
        val fresh = requireNotNull(session.captureViewport(preparingPageIds = emptySet()))
        val next = session.rebindContent(
            original.copy(
                content = original.content.map {
                    if (it.pageId == page) it.copy(contentVersion = "new") else it
                },
            ),
        )
        assertFalse(next.acceptDecodedFact(fresh, page, 100, 160))
        assertNull(next.pageFact(page))
        val current = requireNotNull(next.captureViewport(preparingPageIds = emptySet()))
        assertTrue(next.acceptDecodedFact(current, page, 100, 160))
        assertTrue(next.navigate(true))
        assertFalse(next.acceptDecodedFact(current, page, 160, 100))
        next.close()
        session.close()
    }

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
    ): ReaderDualPageSettlement {
        val ticket = session.captureViewport()!!
        return ReaderDualPageSettlement(
            ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
            session.frame(ticket)!!.unit.pageIds.toSet(), true, reason, 1, ticket,
        )
    }

    /** Simulates the existing native prepare -> paint receipt, separately from the settlement request. */
    private fun drawnEvent(
        session: SharedDualPageSession,
        rendered: ReaderDualPageSettlement = event(session),
    ): ReaderDualPageSettlement = rendered.also {
        rendered.renderedPageIds.forEach { page -> assertTrue(session.preparePage(rendered.ticket, page)) }
        assertTrue(session.reportDrawn(rendered.ticket, rendered.renderedPageIds))
    }

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
                "Reopening uses nearby existing samples without resurrecting cancelled work",
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
        val artwork = List(50) { if (it % 2 == 0) 0xffa0a0a0.toInt() else 0xff404080.toInt() }
        val shifted = artwork.map { it + 0x050505 }
        val other = artwork.map { it + 0x202020 }
        val samples = listOf(
            ReaderSpreadEdgeSamples(artwork, List(50) { 0xffee2020.toInt() }),
            ReaderSpreadEdgeSamples(artwork, shifted),
            ReaderSpreadEdgeSamples(other, artwork),
            ReaderSpreadEdgeSamples(List(50) { 0xff2020ee.toInt() }, other),
        )
        assertEquals(listOf(pages[2], pages[3]), session.spreadSampleDependencies())
        val header = session.requestFact(pages[4])
        samples.forEachIndexed { index, edges ->
            assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(pages[index + 1])), edges))
        }
        assertTrue(session.ownsFactRequest(header), "Sample I/O cannot replace an original header capability")
        assertEquals(listOf(pages[2], pages[3]), session.targetUnit!!.pageIds)
        assertEquals(location, session.location)
        val viewport = drawnEvent(session)
        assertNotNull(session.settle(viewport, false, "sample-layout"))
        session.configure(ReaderDirection.LTR, automaticMatching = true)
        assertEquals(0.0, SharedSpreadMatching.score(samples[1], samples[2], ReaderDirection.RTL))
        assertNull(SharedSpreadMatching.score(samples[1], samples[2], ReaderDirection.LTR))
        assertEquals(listOf(pages[1], pages[2]), session.targetUnit!!.pageIds)
        assertEquals(location, session.location, "Direction changes grouping, never the explicit source target")
        assertFalse(session.preparePage(viewport.ticket, pages[2]))
        assertFalse(session.reportDrawn(viewport.ticket, setOf(pages[2])))
        assertNull(session.settle(viewport, false, "sample-layout"))
        assertFalse(session.captureViewport()!!.readingEligible)
        session.configure(ReaderDirection.RTL, automaticMatching = false)
        assertEquals(emptyList<ReaderPageId>(), session.spreadSampleDependencies())
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        assertEquals(listOf(pages[2], pages[3]), session.targetUnit!!.pageIds)
        // A direction change releases the old native viewport; mount its replacement before restore.
        val restoredViewport = requireNotNull(session.captureViewport())
        assertTrue(session.reportDrawn(restoredViewport, setOf(pages[2], pages[3])))
        assertTrue(
            session.acceptPairingRestore(
                session.beginPairingRestore()!!,
                ChapterPairingSnapshot(ChapterPairingRecord(1, 5, setOf(3)), 1),
            ),
        )
        assertEquals(
            listOf(pages[2], pages[3]),
            session.targetUnit!!.pageIds,
            "Late restore preserves already displayed combination",
        )
        val oldSample = requireNotNull(session.requestSpreadSample(pages[1]))
        val save = requireNotNull(session.adjustPairing())
        assertEquals(ReaderPairingAdjustment(setOf(2), 3, manualPairStarts = setOf(3)), save.adjustment)
        assertEquals(listOf(pages[2], pages[3]), session.targetUnit!!.pageIds, "Pending save is not a manual boundary")
        assertTrue(
            session.acceptPairingSave(
                save,
                ChapterPairingSnapshot(ChapterPairingRecord(2, 5, setOf(2), manualPairStarts = setOf(3)), 2),
            ),
        )
        assertEquals(listOf(pages[3], pages[4]), session.targetUnit!!.pageIds)
        val replacement = session.rebindContent(
            session.identity.copy(
                content = session.identity.content.map {
                    if (it.pageId == pages[1]) it.copy(contentVersion = "replaced") else it
                },
            ),
        )
        assertEquals(location!!.copy(pageId = pages[3]), replacement.location)
        assertFalse(replacement.acceptSpreadSample(oldSample, samples[0]))
        assertFalse(session.acceptSpreadSample(oldSample, samples[0]))
        assertFalse(replacement.captureViewport()!!.readingEligible)
        val latest = requireNotNull(replacement.requestSpreadSample(pages[2]))
        replacement.close()
        assertFalse(replacement.acceptSpreadSample(latest, samples[1]))
    }

    @Test
    fun `sample metadata keeps unchanged work and preserves an unrelated fixed viewport`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.configure(ReaderDirection.RTL, automaticMatching = true)
        val pages = session.identity.pageIds
        val first = requireNotNull(session.requestSpreadSample(pages[1]))
        val second = requireNotNull(session.requestSpreadSample(pages[2]))
        val viewport = drawnEvent(session)
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
        assertNotNull(session.settle(viewport, false, "unchanged-cover-orientation"))
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
                ChapterPairingSnapshot(ChapterPairingRecord(2, 6, setOf(1), manualPairStarts = setOf(3)), 7),
            ),
        )
        session.goToPage(session.identity.pageIds[3])
        assertEquals(listOf(3, 4), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
        val save = requireNotNull(session.adjustPairing())
        assertEquals(ReaderPairingAdjustment(setOf(1, 3), 4, manualPairStarts = setOf(4)), save.adjustment)
        val originalLocation = session.location
        session.configurePresentationMode(ReaderPresentationMode.SINGLE)
        assertTrue(
            session.acceptPairingSave(
                save,
                ChapterPairingSnapshot(ChapterPairingRecord(2, 6, setOf(1, 3), manualPairStarts = setOf(4)), 8),
            ),
        )
        assertEquals(originalLocation, session.location)
        assertEquals(8, session.pairingState.revision)
        assertEquals(save.adjustment.forcedSinglePages, session.pairingState.committedForcedSinglePages)
        assertFalse(session.captureViewport()!!.readingEligible)
        session.configurePresentationMode(ReaderPresentationMode.DUAL)
        assertEquals(originalLocation, session.location)
        assertEquals(listOf(3), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
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
        assertNull(session.settle(original, false, "test"), "Header readiness cannot claim native pixels")
        drawnEvent(session, original)
        assertNotNull(session.settle(original, false, "test"))
    }

    @Test
    fun `single mode presents the logical target and revokes dual capability without another navigation`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds[4])
        val original = event(session)
        val canonicalDual = SharedDualPageRules.canonicalPlan(
            session.identity.pageIds,
            session.identity.pageIds.associateWith { requireNotNull(session.pageFact(it)) },
            ReaderDirection.RTL,
            automatic = false,
        ).single { session.identity.pageIds[4] in it.pageIds }
        assertEquals(canonicalDual, session.targetUnit)
        val location = session.location
        session.configurePresentationMode(ReaderPresentationMode.SINGLE)
        assertEquals(location, session.location)
        assertEquals(listOf(4), session.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(ReaderPortraitSingleSlot.FULL, session.targetUnit!!.slots.single().position)
        assertNull(session.settle(original, false, "test"))
        assertNull(session.adjustPairing())
        assertTrue(session.factDependencies().isEmpty())
        assertNull(session.settle(event(session), false, "test"), "The new single viewport has not painted")
        assertNotNull(session.settle(drawnEvent(session).copy(settlementSequence = 2), false, "test"))
        session.configurePresentationMode(ReaderPresentationMode.DUAL)
        assertEquals(location, session.location)
        assertEquals(canonicalDual, session.targetUnit)
        assertFalse(session.captureViewport()!!.readingEligible)
        assertNull(session.settle(drawnEvent(session).copy(settlementSequence = 3), false, "test"))
    }

    @Test
    fun `pure layout cannot revive consumed reading while same frame may append its partner`() {
        for (layout in listOf("mode", "direction", "viewport", "automatic")) {
            fun change(session: SharedDualPageSession) {
                when (layout) {
                    "mode" -> session.configurePresentationMode(ReaderPresentationMode.SINGLE)
                    "direction" -> session.configure(ReaderDirection.LTR)
                    "automatic" -> session.configure(ReaderDirection.RTL, automaticMatching = true)
                    else -> session.invalidateViewport()
                }
            }
            val session = SharedDualPageSession(identity(5), ReaderDirection.RTL, identity(5).pageIds[1])
            known(session)
            val later = event(session).copy(renderedPageIds = setOf(session.identity.pageIds[2]))
            val effect = session.settle(drawnEvent(session, later), false, "layout")
            assertNotNull(effect)
            assertEquals(2, effect!!.lastPageRead)
            val location = session.location
            change(session)
            assertEquals(location, session.location)
            if (layout == "automatic") {
                // The unchanged physical frame retains this old ticket. A settings event must
                // revoke its reading cause without depending on a newly captured false ticket.
                val earlierId = session.identity.pageIds[1]
                assertTrue(session.preparePage(later.ticket, earlierId))
                assertTrue(session.reportDrawn(later.ticket, setOf(earlierId)))
                assertNull(
                    session.settle(
                        later.copy(renderedPageIds = setOf(earlierId), settlementSequence = 2),
                        false,
                        "auto",
                    ),
                    "An old live ticket cannot count a new partner after a consumed location changes settings",
                )
            }
            val earlier = drawnEvent(session, event(session).copy(renderedPageIds = setOf(session.identity.pageIds[1])))
            assertFalse(earlier.ticket.readingEligible, "Consumed location cannot be revived by $layout")
            assertNull(session.settle(earlier.copy(settlementSequence = 2), false, "layout"))

            val unconsumed = SharedDualPageSession(identity(5), ReaderDirection.RTL, identity(5).pageIds[1])
            known(unconsumed)
            change(unconsumed)
            assertNotNull(unconsumed.settle(drawnEvent(unconsumed), false, "first-real-draw"), layout)
        }
        val sameFrame = SharedDualPageSession(identity(5), ReaderDirection.RTL, identity(5).pageIds[1])
        known(sameFrame)
        val prepared = event(sameFrame)
        val later = drawnEvent(sameFrame, prepared.copy(renderedPageIds = setOf(sameFrame.identity.pageIds[2])))
        assertEquals(2, sameFrame.settle(later, false, "append")!!.lastPageRead)
        val earlier = drawnEvent(sameFrame, prepared.copy(renderedPageIds = setOf(sameFrame.identity.pageIds[1])))
        assertEquals(2, sameFrame.settle(earlier.copy(settlementSequence = 2), false, "append")!!.lastPageRead)
        assertNull(sameFrame.settle(earlier.copy(settlementSequence = 3), false, "append"))
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
        val next = session.rebindContent(replacedPage(session.identity, 4), explicitReload = true)
        known(next)
        assertEquals(listOf(4), next.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertNull(next.settle(single, false, "test"))
        assertNull(next.settle(event(next), false, "test"), "Reloaded content cannot inherit an old draw receipt")
        assertNotNull(next.settle(drawnEvent(next).copy(settlementSequence = 2), false, "test"))
    }

    @Test
    fun `explicit retry renews fact work without creating navigation or reading`() {
        val session = SharedDualPageSession(identity(1), ReaderDirection.RTL)
        known(session)
        assertNull(session.settle(event(session), false, "test"), "A settlement request is not a paint receipt")
        assertNotNull(session.settle(drawnEvent(session), false, "test"))
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
        assertNotNull(session.settle(drawnEvent(session), false, "test"))
        val next = session.rebindContent(replacedPage(session.identity, 2))
        known(next)
        assertNull(next.settle(drawnEvent(next).copy(settlementSequence = 2), false, "test"))
        next.goToPage(next.identity.pageIds.first())
        assertNotNull(next.settle(drawnEvent(next).copy(settlementSequence = 3), false, "test"))
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
        assertNull(next.settle(event(next), false, "test"), "Rebound source needs its own physical draw receipt")
        assertNotNull(next.settle(drawnEvent(next).copy(settlementSequence = 2), false, "test"))
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
        assertNull(session.settle(attached, false, "test"), "A replacement viewport must paint before it can read")
        val painted = drawnEvent(session)
        assertSame(attached.ticket, painted.ticket)
        assertNotNull(session.settle(painted.copy(settlementSequence = 2), false, "test"))
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
        assertNull(restored.settle(event(restored), false, "test"), "New owner cannot inherit an old draw receipt")
        assertNotNull(restored.settle(drawnEvent(restored), false, "test"))
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
            ChapterPairingRecord(ChapterPairingRecord.FORMAT_VERSION + 1, 5, setOf(1)),
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
            if (mangaId != null) {
                assertNotNull(save, "A restored persistent session must actually issue the layout save")
                requireNotNull(save)
                assertEquals(ReaderPairingAdjustment(setOf(1), 2, manualPairStarts = setOf(2)), save.adjustment)
                assertTrue(
                    session.acceptPairingSave(
                        save,
                        ChapterPairingSnapshot(ChapterPairingRecord(2, 5, setOf(1), manualPairStarts = setOf(2)), 8),
                    ),
                )
            } else {
                assertNull(save, "Temporary pairing must not issue a database write")
            }
            assertEquals(setOf(1), session.pairingState.committedForcedSinglePages)
            assertEquals(setOf(2), session.pairingState.manualPairStarts)
            assertNull(session.settle(event(session), false, "test"))
            session.goToPage(session.identity.pageIds.last())
            assertNotNull(session.settle(drawnEvent(session).copy(settlementSequence = 2), false, "test"))
        }
        val naturalSingle = SharedDualPageSession(identity(1), ReaderDirection.RTL)
        known(naturalSingle)
        assertNull(naturalSingle.adjustPairing())
        assertNotNull(naturalSingle.settle(drawnEvent(naturalSingle), false, "test"))
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
        assertEquals(ReaderPairingAdjustment(setOf(1), 2, manualPairStarts = setOf(2)), save.adjustment)
        assertEquals(original, session.targetUnit!!.pageIds)
        assertEquals(emptySet<Int>(), session.pairingState.committedForcedSinglePages)
        assertTrue(session.failPairingSave(save))
        assertEquals(original, session.targetUnit!!.pageIds)
        val retry = session.adjustPairing()!!
        assertTrue(
            session.acceptPairingSave(
                retry,
                ChapterPairingSnapshot(ChapterPairingRecord(2, 5, setOf(1), manualPairStarts = setOf(2)), 8),
            ),
        )
        assertEquals(setOf(1), session.pairingState.committedForcedSinglePages)
        assertEquals(session.identity.pageIds[2], session.location!!.pageId)
        assertEquals(listOf(session.identity.pageIds[2], session.identity.pageIds[3]), session.targetUnit!!.pageIds)
        val next = session.adjustPairing()!!
        assertEquals(ReaderPairingAdjustment(emptySet(), 1, manualPairStarts = setOf(1)), next.adjustment)
        session.goToPage(session.identity.pageIds[4])
        assertTrue(
            session.acceptPairingSave(
                next,
                ChapterPairingSnapshot(ChapterPairingRecord(2, 5, emptySet(), manualPairStarts = setOf(1)), 9),
            ),
        )
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
        val provisional = SharedDualPageRules.canonicalPlan(
            identity.pageIds,
            emptyMap(),
            ReaderDirection.RTL,
            automatic = false,
        ).single { identity.pageIds[16] in it.pageIds }
        assertEquals(provisional, session.targetUnit)
        assertEquals(identity.pageIds[16], session.location!!.pageId)
        assertNull(session.captureCertifiedViewport(identity.pageIds[16]), "A provisional pair is not certified pixels")
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
        val canonical = SharedDualPageRules.canonicalPlan(
            identity.pageIds,
            identity.pageIds.associateWith { requireNotNull(session.pageFact(it)) },
            ReaderDirection.RTL,
            automatic = false,
        ).single { target in it.pageIds }
        assertEquals(canonical, session.targetUnit)
        assertEquals(target, session.location!!.pageId, "A group anchor cannot replace the explicit logical target")
        session.configure(ReaderDirection.LTR)
        assertEquals(target, session.location!!.pageId)
        val restored = SharedDualPageSession(identity, ReaderDirection.RTL, session.location!!.pageId)
        known(restored)
        assertEquals(target, restored.location!!.pageId)
        assertFalse(restored.goToPage(ReaderPageId(ReaderChapterId(99), 2)))
    }

    @Test
    fun `progress requires current real settled drawn pages and user cause`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        session.goToPage(session.identity.pageIds.last())
        val good = event(session)
        assertNull(session.settle(good, false, "test"), "Preparing pixels alone is not a real draw")
        assertTrue(session.reportDrawn(good.ticket, good.renderedPageIds))
        assertNull(session.settle(good.copy(settled = false), false, "test"))
        assertNull(session.settle(good.copy(renderedPageIds = emptySet()), false, "test"))
        assertNull(session.settle(good.copy(layoutRevision = good.layoutRevision - 1), false, "test"))
        assertNull(session.settle(good.copy(locationSequence = good.locationSequence - 1), false, "test"))
        assertNull(session.settle(good.copy(identity = good.identity.copy(generation = 4)), false, "test"))
        assertNull(session.settle(good.copy(reason = ReaderDualPageSettlementReason.LAYOUT_ONLY), false, "test"))
        val effect = session.settle(good, false, "test")
        assertNotNull(effect)
        assertTrue(effect!!.reachedLastPage)
        assertEquals(session.identity.pageIds[4], session.location!!.pageId)
        assertEquals(session.identity.pageIds.slice(3..4), session.canonicalTargetUnit!!.pageIds)
        assertEquals(4, effect.lastPageRead)
        assertNull(session.settle(good, false, "test"))
    }

    @Test
    fun `actually rendered unresolved target can count while later layout does not create reading`() {
        val session = SharedDualPageSession(identity(17), ReaderDirection.RTL)
        session.goToPage(session.identity.pageIds[16])
        session.acceptFact(session.requestFact(session.identity.pageIds[16]), ReaderPageFact.dimensions(900, 1300))
        val actual = event(session).copy(renderedPageIds = setOf(session.identity.pageIds[16]))
        assertFalse(session.reportDrawn(actual.ticket, setOf(session.identity.pageIds[15])), "Unknown is not drawn")
        assertTrue(session.reportDrawn(actual.ticket, actual.renderedPageIds))
        val effect = session.settle(actual, false, "test")
        assertNotNull(effect)
        assertTrue(effect!!.reachedLastPage)
        known(session, setOf(13))
        assertNull(session.settle(event(session, ReaderDualPageSettlementReason.LAYOUT_ONLY), false, "test"))
    }

    @Test
    fun `configure replaces fact budget and clears resource failures idempotently`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        val page = session.identity.pageIds[1]
        session.goToPage(page)
        session.configure(ReaderDirection.RTL, automaticMatching = false)

        val oldBudget = session.factBudget
        val targetPage = session.identity.pageIds[2]
        assertEquals(listOf(targetPage), oldBudget.reserve(listOf(targetPage), 1000L, required = true))
        val oldPermit = ReaderDualPageFactReadPermit(oldBudget, targetPage, { 1000L }, ownerKey = 10L)
        assertFalse(oldPermit.exhausted)
        assertTrue(oldBudget.canRead(targetPage, 1000L))

        val resourcePage = session.identity.pageIds[3]
        val resourceContent = session.identity.content[3]
        val resourceRequest = requireNotNull(session.requestOriginalAnalysis(resourcePage))
        assertTrue(session.failOriginalAnalysis(resourceRequest, ReaderAnalysisFailure.RESOURCE))
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(resourceContent, ReaderAnalysisFailure.RESOURCE),
            session.originalAnalysis(resourcePage),
        )

        val readPage = session.identity.pageIds[4]
        val readContent = session.identity.content[4]
        val readRequest = requireNotNull(session.requestOriginalAnalysis(readPage))
        assertTrue(session.failOriginalAnalysis(readRequest, ReaderAnalysisFailure.READ))
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(readContent, ReaderAnalysisFailure.READ),
            session.originalAnalysis(readPage),
        )

        val oldSampleRevision = session.sampleWorkRevision
        val oldFactRevision = session.factWorkRevision

        // 1. Same configuration must remain completely idempotent
        session.configure(ReaderDirection.RTL, automaticMatching = false)
        assertSame(oldBudget, session.factBudget, "Same configuration must not replace fact budget")
        assertEquals(
            oldSampleRevision,
            session.sampleWorkRevision,
            "Same configuration must not increment sample revision",
        )
        assertEquals(oldFactRevision, session.factWorkRevision, "Same configuration must not increment fact revision")
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(resourceContent, ReaderAnalysisFailure.RESOURCE),
            session.originalAnalysis(resourcePage),
            "Same configuration must not clear resource failure",
        )
        assertFalse(oldPermit.exhausted, "Old permit must remain valid when configuration is unchanged")

        // 2. Configuration change replaces budget, closes old budget/permit and clears resource failures
        session.configure(ReaderDirection.LTR, automaticMatching = true)
        val newBudget = session.factBudget
        assertNotSame(oldBudget, newBudget, "New configuration must replace fact budget")
        assertTrue(session.factWorkRevision > oldFactRevision, "Fact revision must increment on configuration change")
        assertTrue(
            session.sampleWorkRevision > oldSampleRevision,
            "Sample revision must increment on configuration change",
        )

        assertFalse(oldBudget.canRead(targetPage, 1000L), "Old budget must be closed")
        assertTrue(oldPermit.exhausted, "Old permit must become exhausted when old budget closes")
        assertEquals(0L, oldPermit.reserve(1024L), "Old permit cannot reserve read bytes after budget closes")
        val stream = oldPermit.bound(Buffer(), nanoTime = { 1000L })
        assertThrows(CancellationException::class.java) {
            stream.read(Buffer(), 1024L)
        }

        assertEquals(
            ReaderPageAnalysisEvidence.NotAnalyzed,
            session.originalAnalysis(resourcePage),
            "Resource failure must be cleared on configuration change",
        )
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(readContent, ReaderAnalysisFailure.READ),
            session.originalAnalysis(readPage),
            "Non-resource failure must not be cleared",
        )
        assertNotNull(
            session.requestSpreadSample(resourcePage),
            "Cleared resource failure allows requesting spread sample again",
        )

        // 3. Applying the same new configuration is idempotent again
        val nextFactRevision = session.factWorkRevision
        session.configure(ReaderDirection.LTR, automaticMatching = true)
        assertSame(newBudget, session.factBudget, "Re-applying identical configuration must remain idempotent")
        assertEquals(nextFactRevision, session.factWorkRevision)

        session.close()
    }

    @Test
    fun `content rebind isolates old fact budget capabilities and admits changed page`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        val page0 = session.identity.pageIds[0]
        val page1 = session.identity.pageIds[1]
        val page2 = session.identity.pageIds[2]
        val page3 = session.identity.pageIds[3]
        session.goToPage(page0)

        // 1. Admit page 1 and page 2 as REQUIRED under initial session
        val oldBudget = session.factBudget
        assertEquals(listOf(page1, page2), oldBudget.reserve(listOf(page1, page2), 1000L, required = true))
        val oldPermit = ReaderDualPageFactReadPermit(oldBudget, page2, { 1000L }, ownerKey = 10L)
        assertFalse(oldPermit.exhausted)
        assertTrue(oldBudget.canRead(page2, 1000L))
        assertEquals(500L, oldPermit.reserve(500L))

        // Complete fact for page 1 with 1024 read bytes
        val req1 = requireNotNull(session.requestFact(page1))
        session.acceptFact(req1, ReaderPageFact.dimensions(900, 1300))
        oldBudget.complete(page1, 1024L, cacheHit = false)
        assertEquals(ReaderPageFactState.PORTRAIT, session.pageFact(page1)?.state)

        // 2. Content rebind occurs where page 2 content version changes (unexposed changed page)
        val nextIdentity = replacedPage(session.identity, 2)
        val next = session.rebindContent(nextIdentity)
        val newBudget = next.factBudget

        // Assert budget shells are separate instances
        assertNotSame(oldBudget, newBudget, "Rebind must replace the fact budget shell")

        // Unchanged page fact is preserved, changed page fact is cleared
        assertEquals(ReaderPageFactState.PORTRAIT, next.pageFact(page1)?.state)
        assertNull(next.pageFact(page2), "Changed page fact must be cleared upon rebind")

        // 3. New budget admits the changed page for analysis
        val admitted = newBudget.reserveClassified(listOf(page2), emptyList(), 1500L)
        assertEquals(
            listOf(ReaderDualPageFactBudget.ReservedCapability(page2, speculative = false)),
            admitted,
            "Changed page must be admitted in new budget",
        )
        assertTrue(newBudget.holds(page2), "New budget must hold the admitted changed page")

        // 4. Old capabilities are physically isolated and rejected
        assertFalse(oldBudget.canRead(page2, 1500L), "Old budget must be closed")
        assertTrue(oldPermit.exhausted, "Old permit must become exhausted after content rebind")
        assertEquals(0L, oldPermit.reserve(100L), "Old permit cannot reserve after content rebind")
        val stream = oldPermit.bound(Buffer(), nanoTime = { 1500L })
        assertThrows(CancellationException::class.java) {
            stream.read(Buffer(), 100L)
        }

        // 5. Late complete or defer on old budget does not tamper with new budget's active slot
        oldBudget.complete(page2, 0L, cacheHit = true)
        oldBudget.defer(page2)
        assertTrue(newBudget.holds(page2), "Late complete/defer on old budget must not remove new budget's active slot")

        // 6. Shared accounting window retains time elapsed and bytes consumed
        assertEquals(
            ReaderDualPageFactLimits.MAX_WAIT_MILLIS - (1500L - 1000L),
            newBudget.remainingWaitMillis(1500L),
            "Remaining wait millis must reflect elapsed time from shared window start",
        )

        // Attempting an excessive refund on old budget cannot credit unreserved bytes into shared window
        oldBudget.returnUnreadBytes(page2, 10_000L)

        // Window timeout shuts down admission across shells
        val expiredTime = 1000L + ReaderDualPageFactLimits.MAX_WAIT_MILLIS
        assertEquals(0L, newBudget.remainingWaitMillis(expiredTime))
        assertTrue(
            newBudget.reserveClassified(listOf(page3), emptyList(), expiredTime).isEmpty(),
            "Shared window timeout must reject admission",
        )

        next.close()
    }

    @Test
    fun `content rebind separates refund tracking per budget shell and preserves late old refund after new complete`() {
        val session = SharedDualPageSession(identity(5), ReaderDirection.RTL)
        known(session)
        val page1 = session.identity.pageIds[1]
        val page2 = session.identity.pageIds[2]
        val page3 = session.identity.pageIds[3]
        session.goToPage(page1)

        val oldBudget = session.factBudget
        assertEquals(listOf(page2), oldBudget.reserve(listOf(page2), 1000L, required = true))
        val oldPermit = ReaderDualPageFactReadPermit(oldBudget, page2, { 1000L }, ownerKey = 10L)

        var newBudgetRef: ReaderDualPageFactBudget? = null
        var nextSessionRef: SharedDualPageSession? = null
        val controllableSource = object : okio.Source {
            var onRead: (() -> Unit)? = null
            override fun read(sink: Buffer, byteCount: Long): Long {
                onRead?.invoke()
                return -1L // EOF: 0 bytes consumed out of granted reservation
            }
            override fun close() {}
            override fun timeout(): okio.Timeout = okio.Timeout.NONE
        }

        controllableSource.onRead = {
            // Rebind content while old stream is active with 100 bytes reserved
            val nextSession = session.rebindContent(replacedPage(session.identity, 2))
            nextSessionRef = nextSession
            val newBudget = nextSession.factBudget
            newBudgetRef = newBudget
            val admitted = newBudget.reserveClassified(listOf(page2), emptyList(), 1100L)
            assertEquals(1, admitted.size)
            val newPermit = ReaderDualPageFactReadPermit(newBudget, page2, { 1100L }, ownerKey = 20L)

            // New permit reserves 100 bytes, reads 50 bytes, refunds 50 bytes, and completes
            assertEquals(100L, newPermit.reserve(100L))
            newPermit.returnUnread(50L)
            newBudget.complete(page2, newlyReadBytes = 50L, cacheHit = false)
        }

        // Old permit stream reserves 100 bytes and triggers controllableSource.read
        val oldStream = oldPermit.bound(controllableSource, nanoTime = { 1000L })
        oldStream.read(Buffer(), 100L)

        val newBudget = requireNotNull(newBudgetRef)

        // After old stream read returns EOF, finally block refunds 100 - 0 = 100 unread bytes.
        // Total bytes consumed in shared window must be exactly 50 bytes (from new budget).
        // It must NOT be 150 bytes (which happens if new budget complete erased old budget's reserved tracking).
        // Admit page 3 and page 4 in new budget before byte exhaustion
        val page4 = session.identity.pageIds[4]
        assertEquals(
            listOf(page3, page4),
            newBudget.reserve(listOf(page3, page4), 1200L, required = true),
        )
        assertTrue(newBudget.holds(page4))

        val remainingCapacity = ReaderDualPageFactLimits.MAX_NEW_BYTES - 50L
        assertEquals(
            remainingCapacity,
            newBudget.reserveRead(page3, remainingCapacity, 1200L),
            "Late refund from old budget must restore shared byte budget so only 50 bytes remain consumed",
        )

        // Old complete or defer cannot remove active slot of newer budget
        oldBudget.complete(page4, 0L, cacheHit = true)
        oldBudget.defer(page4)
        assertTrue(newBudget.holds(page4), "Old complete or defer must not remove active slot from newer budget shell")

        // Monotonic byte exhaustion across multiple rebinds
        val rebind2 = nextSessionRef!!.rebindContent(replacedPage(nextSessionRef!!.identity, 3))
        val budget2 = rebind2.factBudget
        // Window now has 0 remaining bytes because remainingCapacity was reserved
        assertEquals(
            0L,
            budget2.reserveRead(page3, 100L, 1300L),
            "Exhausted byte budget must reject further reservations after rebind",
        )

        // Preserves unchanged real failure across rebind
        val failedContent = rebind2.identity.content[1]
        val failedRequest = requireNotNull(rebind2.requestOriginalAnalysis(page1))
        assertTrue(rebind2.failOriginalAnalysis(failedRequest, ReaderAnalysisFailure.READ))
        val rebind3 = rebind2.rebindContent(replacedPage(rebind2.identity, 2))
        assertEquals(
            ReaderPageAnalysisEvidence.RetryableFailure(failedContent, ReaderAnalysisFailure.READ),
            rebind3.originalAnalysis(page1),
            "Unchanged real failure must be preserved across rebind",
        )

        rebind3.close()
    }
}
