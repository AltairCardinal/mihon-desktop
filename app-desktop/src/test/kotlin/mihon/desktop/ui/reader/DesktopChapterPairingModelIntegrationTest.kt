package mihon.desktop.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopChapterPairingCoordinator
import mihon.desktop.reader.desktopReaderSessionState
import mihon.domain.reader.ChapterPairingRecord
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.StaleChapterPairingException
import mihon.domain.reader.MissingChapterPairingIdentityException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChapterPairingModelIntegrationTest {
    @Test
    fun `late read from prior chapter cannot replace the new chapter pairing`() = runTest {
        val oldReadGate = CompletableDeferred<Unit>()
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                if (chapterId == 11L) oldReadGate.await()
                return ChapterPairingSnapshot(
                    ChapterPairingRecord(1, 8, if (chapterId == 11L) setOf(1) else setOf(4)),
                    1,
                )
            }

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot = error("No save expected")
        }
        val coordinator = DesktopChapterPairingCoordinator(repository, backgroundScope)
        val old = loaded(11, generation = 1)
        val model = ReaderScreenModel(initialSessionState = old, dualPageOverride = true, pairingCoordinator = coordinator)
        assertEquals(ReaderViewportBody.LOADING, readerViewportBody(model.state.value))
        runCurrent()

        model.acceptSessionState(loaded(12, generation = 2))
        runCurrent()
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        oldReadGate.complete(Unit)
        runCurrent()

        assertEquals(12L, model.state.value.context.chapterId)
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        assertEquals(ReaderViewportBody.CONTENT, readerViewportBody(model.state.value))
    }

    @Test
    fun `accepted save finishes after reader dispose and same chapter reopen waits for it`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        var snapshot = ChapterPairingSnapshot(null, 0)
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long) = snapshot

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                saveGate.await()
                if (snapshot.revision != expectedRevision) throw StaleChapterPairingException()
                snapshot = ChapterPairingSnapshot(ChapterPairingRecord(1, pageCount, forcedSinglePages), 1)
                return snapshot
            }
        }
        val coordinator = DesktopChapterPairingCoordinator(repository, backgroundScope)
        val chapter = loaded(11, generation = 1)
        val old = ReaderScreenModel(initialSessionState = chapter, dualPageOverride = true, pairingCoordinator = coordinator)
        runCurrent()
        old.goToPage(1)
        old.adjustSpread()
        runCurrent()
        assertTrue(old.state.value.pairingSaving)
        assertEquals(emptySet<Int>(), old.state.value.forcedSinglePages)
        old.onDispose()

        val reopened = ReaderScreenModel(initialSessionState = chapter, dualPageOverride = true, pairingCoordinator = coordinator)
        runCurrent()
        assertEquals(PairingLoad.LOADING, reopened.state.value.pairingLoad)
        saveGate.complete(Unit)
        runCurrent()

        assertEquals(setOf(1), snapshot.record?.forcedSinglePages)
        assertEquals(setOf(1), reopened.state.value.forcedSinglePages)
        assertFalse(reopened.state.value.pairingSaving)
        assertEquals(ReaderViewportBody.CONTENT, readerViewportBody(reopened.state.value))
    }

    @Test
    fun `old chapter save completion cannot change the newly activated chapter`() = runTest {
        val oldSaveGate = CompletableDeferred<Unit>()
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long) =
                if (chapterId == 11L) ChapterPairingSnapshot(null, 0)
                else ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(4)), 3)

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                oldSaveGate.await()
                return ChapterPairingSnapshot(ChapterPairingRecord(1, pageCount, forcedSinglePages), 1)
            }
        }
        val coordinator = DesktopChapterPairingCoordinator(repository, backgroundScope)
        val model = ReaderScreenModel(
            initialSessionState = loaded(11, generation = 1),
            dualPageOverride = true,
            pairingCoordinator = coordinator,
        )
        runCurrent()
        model.goToPage(1)
        model.adjustSpread()
        runCurrent()
        assertTrue(model.state.value.pairingSaving)

        model.acceptSessionState(loaded(12, generation = 2))
        runCurrent()
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        oldSaveGate.complete(Unit)
        runCurrent()

        assertEquals(12L, model.state.value.context.chapterId)
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        assertEquals(3L, model.state.value.pairingRevision)
        assertFalse(model.state.value.pairingSaving)
    }

    @Test
    fun `same chapter page count change gates new list and rejects old save callback`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val newReadGate = CompletableDeferred<Unit>()
        var reads = 0
        var snapshot = ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(4)), 1)
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                if (++reads > 1) newReadGate.await()
                return snapshot
            }

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                saveGate.await()
                snapshot = ChapterPairingSnapshot(ChapterPairingRecord(1, pageCount, forcedSinglePages), expectedRevision + 1)
                return snapshot
            }
        }
        val model = ReaderScreenModel(
            initialSessionState = loaded(11, generation = 1),
            dualPageOverride = true,
            pairingCoordinator = DesktopChapterPairingCoordinator(repository, backgroundScope),
        )
        runCurrent()
        assertEquals(setOf(4), model.state.value.forcedSinglePages)
        model.goToPage(1)
        model.adjustSpread()
        runCurrent()
        assertTrue(model.state.value.pairingSaving)

        model.acceptSessionState(loaded(11, generation = 1, pageCount = 9))
        assertEquals(9, model.state.value.session.activeChapter.pages.size)
        assertEquals(PairingLoad.LOADING, model.state.value.pairingLoad)
        assertEquals(ReaderViewportBody.LOADING, readerViewportBody(model.state.value))
        assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)
        assertFalse(model.state.value.pairingSaving)

        runCurrent()
        saveGate.complete(Unit)
        runCurrent()
        assertEquals(PairingLoad.LOADING, model.state.value.pairingLoad)
        assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)
        newReadGate.complete(Unit)
        runCurrent()
        assertEquals(PairingLoad.READY, model.state.value.pairingLoad)
        assertEquals(PairingNotice.INVALID, model.state.value.pairingNotice)
        assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)
    }

    @Test
    fun `old save cannot cross manga switch before pairing epoch advances`() = runTest {
        val saveGate = CompletableDeferred<Unit>()
        val newReadGate = CompletableDeferred<Unit>()
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                if (mangaId == 20L) newReadGate.await()
                return ChapterPairingSnapshot(null, 0)
            }

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                saveGate.await()
                return ChapterPairingSnapshot(ChapterPairingRecord(1, pageCount, forcedSinglePages), 1)
            }
        }
        val coordinator = DesktopChapterPairingCoordinator(repository, backgroundScope)
        val chapter = loaded(11, generation = 1)
        val model = ReaderScreenModel(
            initialSessionState = chapter,
            dualPageOverride = true,
            pairingCoordinator = coordinator,
        )
        runCurrent()
        model.goToPage(1)
        model.adjustSpread()
        runCurrent()
        assertTrue(model.state.value.pairingSaving)

        val epoch = ReaderScreenModel::class.java.getDeclaredField("pairingEpoch").apply { isAccessible = true }
        val beforeSwitch = epoch.getLong(model)
        try {
            model.acceptSessionState(chapter.copy(context = chapter.context.copy(mangaId = 20L)))
            // Recreate the interval after new state publication but before the epoch increment.
            epoch.setLong(model, beforeSwitch)
            runCurrent()
            saveGate.complete(Unit)
            runCurrent()
            assertEquals(20L, model.state.value.context.mangaId)
            assertEquals(PairingLoad.LOADING, model.state.value.pairingLoad)
            assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)
        } finally {
            newReadGate.complete(Unit)
        }
    }

    @Test
    fun `wrong manga identity is a restore error while direct temporary file remains session only`() = runTest {
        var reads = 0
        var writes = 0
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                reads++
                throw MissingChapterPairingIdentityException()
            }

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                writes++
                error("A missing identity cannot write")
            }
        }
        val coordinator = DesktopChapterPairingCoordinator(repository, backgroundScope)
        val wrongManga = loaded(11, generation = 1).let {
            it.copy(context = it.context.copy(localChapterPath = "/local/chapter"))
        }
        val wrong = ReaderScreenModel(initialSessionState = wrongManga, dualPageOverride = true, pairingCoordinator = coordinator)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(PairingLoad.ERROR, wrong.state.value.pairingLoad)
        assertFalse(wrong.state.value.pairingSessionOnly)
        wrong.goToPage(1)
        wrong.adjustSpread()
        assertEquals(0, writes)

        val direct = desktopReaderSessionState(chapterId = 99, pageCount = 8).let {
            it.copy(context = it.context.copy(mangaId = 0, localChapterPath = "/local/file"))
        }
        val temporary = ReaderScreenModel(initialSessionState = direct, dualPageOverride = true, pairingCoordinator = coordinator)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(temporary.state.value.pairingSessionOnly)
        assertEquals(PairingNotice.SESSION_ONLY, temporary.state.value.pairingNotice)
        temporary.goToPage(1)
        temporary.adjustSpread()
        assertEquals(setOf(1), temporary.state.value.forcedSinglePages)
        assertEquals(0, writes)
    }

    @Test
    fun `read failure default session cannot overwrite unknown record until retry succeeds`() = runTest {
        var failRead = true
        var writes = 0
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                if (failRead) error("injected read failure")
                return ChapterPairingSnapshot(ChapterPairingRecord(1, 8, setOf(2)), 7)
            }

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                writes++
                return ChapterPairingSnapshot(ChapterPairingRecord(1, pageCount, forcedSinglePages), 8)
            }
        }
        val model = ReaderScreenModel(
            initialSessionState = loaded(11, generation = 1),
            dualPageOverride = true,
            pairingCoordinator = DesktopChapterPairingCoordinator(repository, backgroundScope),
        )
        runCurrent()
        assertEquals(ReaderViewportBody.PAIRING_ERROR, readerViewportBody(model.state.value))
        model.useDefaultPairingThisSession()
        assertEquals(ReaderViewportBody.CONTENT, readerViewportBody(model.state.value))
        model.goToPage(1)
        model.adjustSpread()
        assertEquals(0, writes)
        assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)

        failRead = false
        model.retryPairingRestore()
        runCurrent()
        assertEquals(setOf(2), model.state.value.forcedSinglePages)
        assertEquals(7L, model.state.value.pairingRevision)
    }

    @Test
    fun `failed save keeps prior pairing and retry replaces invalid stored record`() = runTest {
        var failSave = true
        var snapshot = ChapterPairingSnapshot(ChapterPairingRecord(1, 9, setOf(2)), 3)
        val repository = object : ChapterPairingRepository {
            override suspend fun load(chapterId: Long, mangaId: Long) = snapshot

            override suspend fun replace(
                chapterId: Long,
                mangaId: Long,
                expectedRevision: Long,
                pageCount: Int,
                forcedSinglePages: Set<Int>,
            ): ChapterPairingSnapshot {
                if (failSave) error("injected write failure")
                snapshot = ChapterPairingSnapshot(ChapterPairingRecord(1, pageCount, forcedSinglePages), expectedRevision + 1)
                return snapshot
            }
        }
        val model = ReaderScreenModel(
            initialSessionState = loaded(11, generation = 1),
            dualPageOverride = true,
            pairingCoordinator = DesktopChapterPairingCoordinator(repository, backgroundScope),
        )
        runCurrent()
        assertEquals(PairingNotice.INVALID, model.state.value.pairingNotice)
        assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)
        model.goToPage(1)
        model.adjustSpread()
        runCurrent()
        assertEquals(PairingNotice.SAVE_FAILED, model.state.value.pairingNotice)
        assertEquals(emptySet<Int>(), model.state.value.forcedSinglePages)
        assertFalse(model.state.value.pairingSaving)

        failSave = false
        model.adjustSpread()
        runCurrent()
        assertEquals(setOf(1), model.state.value.forcedSinglePages)
        assertEquals(setOf(1), snapshot.record?.forcedSinglePages)
        assertEquals(4L, model.state.value.pairingRevision)
    }

    private fun loaded(chapterId: Long, generation: Long, pageCount: Int = 8) =
        desktopReaderSessionState(chapterId = chapterId, generation = generation, pageCount = pageCount)
            .let { it.copy(context = it.context.copy(mangaId = 10L)) }
}
