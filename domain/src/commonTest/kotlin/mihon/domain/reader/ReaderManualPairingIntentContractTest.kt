package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderManualPairingIntentContractTest {
    @Test
    fun `background restore replaces an empty opening proposal before any physical slot is locked`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            val owner = owner(mangaId = 1, initialIndex = 0)
            val ids = owner.identity.pageIds
            owner.configure(direction)
            val restore = owner.beginPairingRestore()!!
            val pending = owner.captureViewport(preparingPageIds = emptySet())!!
            assertEquals(emptySet<ReaderPageId>(), owner.frame(pending)!!.lockedPageIds)
            assertTrue(owner.acceptPairingRestore(restore, firstPairSnapshot()))
            assertNull(owner.frame(pending), "An empty proposal must not preserve a default first-page layout")
            val current = owner.captureViewport()!!
            assertEquals(listOf(ids[0], ids[1]), owner.frame(current)!!.unit.pageIds)
            assertEquals(
                if (direction == ReaderDirection.RTL) ReaderPortraitSingleSlot.RIGHT else ReaderPortraitSingleSlot.LEFT,
                owner.frame(current)!!.unit.slots.first().position,
            )
            assertEquals(emptySet<ReaderPageId>(), owner.frame(current)!!.drawnPageIds)
        }
    }

    @Test
    fun `late restore replaces an unlocked loading proposal without changing the logical target`() {
        val owner = owner(mangaId = 1, initialIndex = 0)
        val ids = owner.identity.pageIds
        val restore = owner.beginPairingRestore()!!
        assertTrue(owner.navigate(true))
        val current = owner.captureViewport(preparingPageIds = emptySet())!!
        val before = owner.frame(current)!!
        assertEquals(listOf(ids[1], ids[2]), before.unit.pageIds)
        assertTrue(before.lockedPageIds.isEmpty())
        assertTrue(before.drawnPageIds.isEmpty())
        val location = owner.location
        assertTrue(owner.acceptPairingRestore(restore, firstPairSnapshot()))
        assertNull(owner.frame(current), "An empty proposal has no physical slot promise")
        assertEquals(location, owner.location)
        assertEquals(ids[1], owner.location!!.pageId)
        assertTrue(!owner.preparePage(current, ids[1]))
        assertTrue(!owner.reportDrawn(current, setOf(ids[1])))
        assertNull(
            owner.settle(
                ReaderDualPageSettlement(
                    current.identity, current.layoutRevision, current.locationSequence, current.unitId,
                    setOf(ids[1]), true, ReaderDualPageSettlementReason.USER_NAVIGATION, 1, current,
                ),
                false,
                "expired-proposal",
            ),
        )
        val restored = owner.captureViewport(preparingPageIds = emptySet())!!
        assertEquals(listOf(ids[0], ids[1]), owner.frame(restored)!!.unit.pageIds)
        assertTrue(owner.frame(restored)!!.lockedPageIds.isEmpty())
        assertTrue(owner.frame(restored)!!.drawnPageIds.isEmpty())
        assertTrue(owner.navigate(true))
        assertEquals(ids[2], owner.location!!.pageId)
        assertEquals(listOf(ids[2], ids[3]), owner.targetUnit!!.pageIds)
    }

    @Test
    fun `background restore preserves a physically locked opening even before draw acknowledgement`() {
        for (drawn in listOf(false, true)) {
            val owner = owner(mangaId = 1, initialIndex = 0)
            val ids = owner.identity.pageIds
            val restore = owner.beginPairingRestore()!!
            val current = owner.captureViewport()!!
            if (drawn) assertTrue(owner.reportDrawn(current, setOf(ids[0])))
            val before = owner.frame(current)!!
            assertTrue(owner.acceptPairingRestore(restore, firstPairSnapshot()))
            assertEquals(before, owner.frame(current))
            assertEquals(ids[0], owner.location!!.pageId)
            owner.goToPage(ids[3])
            owner.releaseViewport(current)
            owner.goToPage(ids[0])
            assertEquals(listOf(ids[0], ids[1]), owner.targetUnit!!.pageIds)
        }
    }

    private fun firstPairSnapshot() = ChapterPairingSnapshot(
        ChapterPairingRecord(2, 5, emptySet(), openingMode = ReaderChapterOpeningMode.FIRST_PAIR),
        1,
    )

    @Test
    fun `general adjustment pairs cover in both directions and retains legal unknown or failed partners`() {
        for (direction in listOf(ReaderDirection.RTL, ReaderDirection.LTR)) {
            for (partnerState in listOf(
                ReaderPageFactState.PORTRAIT,
                ReaderPageFactState.UNKNOWN,
                ReaderPageFactState.FAILED,
            )) {
                val ids = List(5) { ReaderPageId(ReaderChapterId(7), it) }
                val session = SharedDualPageSession(
                    ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "fixture") }),
                    direction,
                    ids[0],
                )
                session.acceptFact(session.requestFact(ids[0]), ReaderPageFact.dimensions(100, 200))
                session.acceptFact(session.requestFact(ids[1]), ReaderPageFact(partnerState, 100, 200))
                assertEquals(listOf(ids[0]), session.targetUnit!!.pageIds)
                session.adjustPairing()
                assertEquals(listOf(ids[0], ids[1]), session.targetUnit!!.pageIds)
                assertEquals(
                    if (direction ==
                        ReaderDirection.RTL
                    ) {
                        ReaderPortraitSingleSlot.RIGHT
                    } else {
                        ReaderPortraitSingleSlot.LEFT
                    },
                    session.targetUnit!!.slots[0].position,
                )
                session.acceptFact(session.requestFact(ids[1]), ReaderPageFact.dimensions(200, 100))
                assertEquals(listOf(ids[0]), session.targetUnit!!.pageIds)
                session.goToPage(ids[1])
                assertEquals(ReaderPortraitSingleSlot.FULL, session.targetUnit!!.slots.single().position)
                session.close()
            }
        }
    }

    @Test
    fun `legacy following pair respects true spread and a later explicit relationship`() {
        for (wide in listOf(4, 5)) {
            val ids = List(8) { ReaderPageId(ReaderChapterId(7), it) }
            val owner =
                SharedDualPageSession(
                    ReaderDualPageIdentity(
                        1,
                        1,
                        ids.map {
                            ReaderDualPageContentIdentity(it, "fixture")
                        },
                    ),
                    ReaderDirection.RTL,
                    ids[4],
                    1,
                )
            ids.forEach {
                owner.acceptFact(
                    owner.requestFact(it),
                    if (it.sourcePageIndex ==
                        wide
                    ) {
                        ReaderPageFact.dimensions(200, 100)
                    } else {
                        ReaderPageFact.dimensions(100, 200)
                    },
                )
            }
            owner.acceptPairingRestore(
                owner.beginPairingRestore()!!,
                ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(3)), 1),
            )
            assertEquals(false, owner.targetUnit!!.pageIds.containsAll(listOf(ids[4], ids[5])))
            owner.goToPage(ids[wide])
            assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
        }
        val ids = List(8) { ReaderPageId(ReaderChapterId(7), it) }
        val owner =
            SharedDualPageSession(
                ReaderDualPageIdentity(
                    1,
                    1,
                    ids.map {
                        ReaderDualPageContentIdentity(it, "fixture")
                    },
                ),
                ReaderDirection.RTL,
                ids[4],
                1,
            )
        ids.forEach { owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 200)) }
        owner.acceptPairingRestore(
            owner.beginPairingRestore()!!,
            ChapterPairingSnapshot(ChapterPairingRecord(2, 8, setOf(3), manualPairStarts = setOf(5)), 1),
        )
        assertEquals(listOf(4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        owner.goToPage(ids[5])
        assertEquals(listOf(5, 6), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `explicit legacy boundary restore releases new partner while keeping distant history`() {
        val ids = List(10) { ReaderPageId(ReaderChapterId(7), it) }
        val owner = SharedDualPageSession(
            ReaderDualPageIdentity(1, 1, ids.map { ReaderDualPageContentIdentity(it, "fixture") }),
            ReaderDirection.RTL,
            ids[3],
            1,
        )
        ids.forEach { owner.acceptFact(owner.requestFact(it), ReaderPageFact.dimensions(100, 200)) }
        owner.acceptPairingRestore(owner.beginPairingRestore()!!, ChapterPairingSnapshot(null, 0))
        draw(owner)
        owner.goToPage(ids[5])
        draw(owner)
        owner.goToPage(ids[7])
        draw(owner)
        val distant = owner.displayedHistory.single { it.unit.anchor == ids[7] }
        owner.goToPage(ids[5])
        owner.acceptPairingRestore(
            owner.beginPairingRestore(explicit = true)!!,
            ChapterPairingSnapshot(ChapterPairingRecord(1, 10, setOf(3)), 1),
        )
        assertEquals(listOf(4, 5), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(distant, owner.displayedHistory.single { it.unit.anchor == ids[7] })
        assertEquals(emptySet<Int>(), owner.pairingState.manualPairStarts)
    }

    @Test
    fun `explicit restore arriving after navigation cannot revoke newly exposed frame`() {
        val owner = owner(mangaId = 1)
        owner.acceptPairingRestore(owner.beginPairingRestore()!!, ChapterPairingSnapshot(null, 0))
        draw(owner)
        val restore = owner.beginPairingRestore(explicit = true)!!
        owner.goToPage(owner.identity.pageIds[3])
        val current = owner.captureViewport()!!
        owner.reportDrawn(current, owner.targetUnit!!.pageIds.toSet())
        val frame = owner.frame(current)!!
        owner.acceptPairingRestore(restore, ChapterPairingSnapshot(ChapterPairingRecord(2, 5, setOf(3)), 1))
        assertEquals(frame, owner.frame(current))
        assertEquals(3, owner.location!!.pageId.sourcePageIndex)
    }

    @Test
    fun `one page chapter ignores opening modes without creating a saved layout`() {
        val id = ReaderPageId(ReaderChapterId(7), 0)
        val one =
            SharedDualPageSession(
                ReaderDualPageIdentity(1, 1, listOf(ReaderDualPageContentIdentity(id, "fixture"))),
                ReaderDirection.RTL,
                id,
            )
        one.acceptFact(one.requestFact(id), ReaderPageFact.dimensions(100, 200))
        one.setOpeningMode(ReaderChapterOpeningMode.FIRST_PAIR)
        one.adjustPairing()
        assertEquals(listOf(id), one.targetUnit!!.pageIds)
        assertEquals(ReaderPortraitSingleSlot.FULL, one.targetUnit!!.slots.single().position)
        assertEquals(ReaderChapterOpeningMode.AUTO, one.pairingState.openingMode)
    }

    @Test
    fun `cold second page keeps manual partner and renders before unknown anchor arrives`() {
        for (opening in listOf(false, true)) {
            for (lateWide in listOf(false, true)) {
                val ids = List(6) { ReaderPageId(ReaderChapterId(7), it) }
                val index = if (opening) 1 else 3
                val owner =
                    SharedDualPageSession(
                        ReaderDualPageIdentity(
                            1,
                            1,
                            ids.map {
                                ReaderDualPageContentIdentity(it, "fixture")
                            },
                        ),
                        ReaderDirection.RTL,
                        ids[index],
                        1,
                    )
                val record = ChapterPairingRecord(
                    2,
                    6,
                    emptySet(),
                    openingMode = if (opening) ReaderChapterOpeningMode.FIRST_PAIR else ReaderChapterOpeningMode.AUTO,
                    manualPairStarts = if (opening) emptySet() else setOf(2),
                )
                owner.acceptPairingRestore(owner.beginPairingRestore()!!, ChapterPairingSnapshot(record, 1))
                owner.acceptFact(owner.requestFact(ids[index]), ReaderPageFact.dimensions(100, 200))
                owner.acceptFact(owner.requestFact(ids[index + 1]), ReaderPageFact.dimensions(100, 200))
                assertEquals(listOf(index - 1, index), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                val ticket = owner.captureViewport()!!
                assertEquals(setOf(ids[index]), owner.frame(ticket)!!.lockedPageIds)
                owner.reportDrawn(ticket, setOf(ids[index]))
                owner.acceptFact(
                    owner.requestFact(ids[index - 1]),
                    if (lateWide) ReaderPageFact.dimensions(200, 100) else ReaderPageFact.dimensions(100, 200),
                )
                if (lateWide) {
                    assertEquals(listOf(index), owner.frame(ticket)!!.unit.pageIds.map { it.sourcePageIndex })
                    assertEquals(false, owner.preparePage(ticket, ids[index - 1]))
                    owner.goToPage(ids[index - 1])
                    assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
                } else {
                    assertEquals(listOf(index - 1, index), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                }
            }
        }
    }

    @Test
    fun `cold restored separate cover blocks wrong automatic opening hints`() {
        val owner = owner(mangaId = 1)
        val load = owner.beginPairingRestore()!!
        owner.acceptPairingRestore(
            load,
            ChapterPairingSnapshot(
                ChapterPairingRecord(2, 5, emptySet(), openingMode = ReaderChapterOpeningMode.FIRST_SINGLE),
                1,
            ),
        )
        owner.configure(ReaderDirection.RTL, true)
        owner.identity.pageIds.take(2).forEach {
            owner.acceptFact(
                owner.requestFact(it),
                ReaderPageFact.dimensions(100, 200).copy(
                    sideHint = if (it.sourcePageIndex ==
                        0
                    ) {
                        ReaderPageSideHint.RIGHT
                    } else {
                        ReaderPageSideHint.LEFT
                    },
                ),
            )
        }
        assertEquals(false, 0 in owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `restored manual opening replaces released history and keeps visible history until release`() {
        val owner = owner(mangaId = 1)
        val load = owner.beginPairingRestore()!!
        draw(owner)
        owner.acceptPairingRestore(
            load,
            ChapterPairingSnapshot(
                ChapterPairingRecord(2, 5, emptySet(), openingMode = ReaderChapterOpeningMode.FIRST_PAIR),
                1,
            ),
        )
        assertEquals(listOf(0, 1), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `late manual save cannot steal a still mounted partner after new navigation`() {
        val owner = owner(mangaId = 1)
        owner.acceptPairingRestore(owner.beginPairingRestore()!!, ChapterPairingSnapshot(null, 0))
        draw(owner)
        val save = owner.adjustPairing()!!
        owner.goToPage(owner.identity.pageIds[3])
        val mounted = owner.captureViewport()!!
        owner.reportDrawn(mounted, owner.targetUnit!!.pageIds.toSet())
        val proposed = save.adjustment
        owner.acceptPairingSave(
            save,
            ChapterPairingSnapshot(
                ChapterPairingRecord(
                    2,
                    5,
                    proposed.forcedSinglePages,
                    openingMode = proposed.openingMode,
                    manualPairStarts = proposed.manualPairStarts,
                ),
                1,
            ),
        )
        owner.goToPage(owner.identity.pageIds[2])
        owner.releaseViewport(mounted)
        owner.goToPage(owner.identity.pageIds[3])
        assertEquals(listOf(2, 3), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `explicit separate cover blocks automatic evidenced opening when entering page two`() {
        val owner = owner()
        owner.setOpeningMode(ReaderChapterOpeningMode.FIRST_SINGLE)
        owner.configure(ReaderDirection.RTL, true)
        owner.identity.pageIds.take(2).forEach {
            owner.acceptFact(
                owner.requestFact(it),
                ReaderPageFact.dimensions(100, 200).copy(
                    sideHint = if (it.sourcePageIndex ==
                        0
                    ) {
                        ReaderPageSideHint.RIGHT
                    } else {
                        ReaderPageSideHint.LEFT
                    },
                ),
            )
        }
        owner.goToPage(owner.identity.pageIds[1])
        assertEquals(false, 0 in owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `chapter opening pair is explicit RTL and protected against wrong automatic hints`() {
        val owner = owner()
        owner.setOpeningMode(ReaderChapterOpeningMode.FIRST_PAIR)
        owner.goToPage(owner.identity.pageIds[0])
        assertEquals(listOf(0, 1), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(
            listOf(ReaderPortraitSingleSlot.RIGHT, ReaderPortraitSingleSlot.LEFT),
            owner.targetUnit!!.slots.map {
                it.position
            },
        )
        draw(owner)
        owner.configure(ReaderDirection.RTL, true)
        owner.identity.pageIds.take(2).forEach {
            owner.acceptFact(
                owner.requestFact(it),
                ReaderPageFact.dimensions(100, 200).copy(sideHint = ReaderPageSideHint.LEFT),
            )
        }
        assertEquals(listOf(0, 1), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    @Test
    fun `explicit opening never forces a real spread or a one page chapter into halves`() {
        val owner = owner()
        owner.setOpeningMode(ReaderChapterOpeningMode.FIRST_PAIR)
        val page = owner.identity.pageIds[0]
        owner.acceptFact(owner.requestFact(page), ReaderPageFact.dimensions(200, 100))
        owner.goToPage(page)
        assertEquals(listOf(0), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(ReaderPortraitSingleSlot.FULL, owner.targetUnit!!.slots.single().position)
    }

    @Test
    fun `manual split after returning from next group releases old partner and ignores reverse history`() {
        val owner = owner()
        draw(owner)
        owner.navigate(true)
        draw(owner)
        owner.navigate(false)
        draw(owner)
        owner.adjustPairing()
        assertEquals(listOf(2, 3), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)),
            owner.units.map { u ->
                u.pageIds.map { it.sourcePageIndex }
            },
        )
    }

    @Test
    fun `natural remainder single can explicitly restore an adjacent pair`() {
        // Six portraits have a cover, two ordinary pairs, then a genuine natural remainder.
        val owner = owner(count = 6)
        val ids = owner.identity.pageIds
        assertTrue(owner.goToPage(ids[5]))
        assertEquals(listOf(ids[5]), owner.targetUnit!!.pageIds)
        assertEquals(ReaderDualPageSingleReason.CHAPTER_TAIL, owner.targetUnit!!.singleReason)
        draw(owner)
        assertNull(owner.adjustPairing(), "A session-only adjustment does not create a persistence request")
        assertEquals(listOf(ids[4], ids[5]), owner.targetUnit!!.pageIds)
        assertEquals(ids[4], owner.location!!.pageId)
        assertEquals(setOf(4), owner.pairingState.manualPairStarts)
    }

    private fun draw(owner: SharedDualPageSession) {
        val ticket = owner.captureViewport()!!
        owner.reportDrawn(ticket, owner.targetUnit!!.pageIds.toSet())
        owner.releaseViewport(ticket)
    }

    private fun owner(
        mangaId: Long? = null,
        initialIndex: Int = 1,
        count: Int = 5,
    ): SharedDualPageSession {
        val ids = List(count) { ReaderPageId(ReaderChapterId(7), it) }
        return SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                ids.map {
                    ReaderDualPageContentIdentity(it, "fixture")
                },
            ),
            ReaderDirection.RTL,
            ids[initialIndex],
            mangaId,
        ).also { session ->
            ids.forEach { session.acceptFact(session.requestFact(it), ReaderPageFact.dimensions(100, 200)) }
        }
    }
}
