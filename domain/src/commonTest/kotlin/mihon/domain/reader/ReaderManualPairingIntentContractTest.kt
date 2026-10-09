package mihon.domain.reader

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ReaderManualPairingIntentContractTest {
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
        val owner = owner()
        owner.goToPage(owner.identity.pageIds[4])
        draw(owner)
        owner.adjustPairing()
        assertEquals(listOf(3, 4), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
    }

    private fun draw(owner: SharedDualPageSession) {
        val ticket = owner.captureViewport()!!
        owner.reportDrawn(ticket, owner.targetUnit!!.pageIds.toSet())
        owner.releaseViewport(ticket)
    }

    private fun owner(mangaId: Long? = null): SharedDualPageSession {
        val ids = List(5) { ReaderPageId(ReaderChapterId(7), it) }
        return SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                ids.map {
                    ReaderDualPageContentIdentity(it, "fixture")
                },
            ),
            ReaderDirection.RTL,
            ids[1],
            mangaId,
        ).also { session ->
            ids.forEach { session.acceptFact(session.requestFact(it), ReaderPageFact.dimensions(100, 200)) }
        }
    }
}
