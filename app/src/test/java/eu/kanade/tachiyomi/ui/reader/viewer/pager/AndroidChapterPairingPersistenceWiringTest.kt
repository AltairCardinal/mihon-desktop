package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.children
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import eu.kanade.tachiyomi.ui.reader.AndroidChapterPairingCoordinator
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import mihon.domain.reader.ChapterPairingRepository
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderAdjacentChapterEffect
import mihon.domain.reader.ReaderPairingPersistenceStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterPairingRepositoryImpl
import tachiyomi.data.reader.SqlDelightReadingProgressRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidChapterPairingPersistenceWiringTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var previousInjekt: InjektScope

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        ReaderOpenLatencyProbe.configure(false, -1, -1, "off")
        Injekt = previousInjekt
        Dispatchers.resetMain()
    }

    @Test
    fun `real reader appbar offers general adjustment without chapter opening menu`() = runBlocking {
        openDatabase(temporaryFolder.newFile("reader-appbar.db"), create = true).use { fixture ->
            seed(fixture.driver)
            session(fixture).use { reader ->
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.host.setContent {
                    MaterialTheme { reader.activity.AppBars(reader.model.state.value.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithText("Chapter opening").assertDoesNotExist()
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().assertIsEnabled()
                Unit
            }
        }
    }

    @Test
    fun `general adjust button saves first pair from real reader appbar and restores after reopen`() = runBlocking {
        val file = temporaryFolder.newFile("general-opening-adjustment.db")
        val receiverFile = temporaryFolder.newFile("general-opening-receiver.db")
        openDatabase(file, create = true).use { first ->
            seed(first.driver)
            val journal = mihon.data.sync.journal.SyncLocalJournal(first.handler)
            journal.connect(
                "layout-space",
                1,
                mihon.domain.sync.transport.SyncRepository("owner", "sync", "sync"),
                "android",
                1,
            )
            session(first).use { reader ->
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val chapter = requireNotNull(reader.model.state.value.currentChapter)
                reader.viewer.moveToPage(requireNotNull(chapter.pages)[0])
                assertEquals(setOf(0), visiblePair(reader.viewer))
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsEnabled().performClick()
                withTimeout(5_000) {
                    while (reader.model.state.value.pairingSaving) yield()
                }
                assertEquals(
                    mihon.domain.reader.ReaderChapterOpeningMode.FIRST_PAIR,
                    first.repository.load(2, 1).record?.openingMode,
                )
                val events = journal.pendingEvents("layout-space", 1)
                val event = events.single { it.effects.any { effect -> effect.field.name == "CHAPTER_PAIRING" } }
                assertEquals(2, event.protocolVersion)
                assertEquals(mihon.domain.sync.SyncOrigin.USER, event.origin)
                assertEquals(mihon.domain.sync.SyncCategory.READING, event.category)
                val effect = event.effects.single()
                assertEquals("SET_PAIRING", effect.kind.name)
                assertEquals("/chapter", effect.objectKey.originalUrl)
                openDatabase(receiverFile, create = true).use { receiver ->
                    seed(receiver.driver)
                    receivePairing(receiver.handler, event)
                }
                assertEquals(setOf(0, 1), visiblePair(reader.viewer))
                assertFalse(first.database.chaptersQueries.getChapterById(2).executeAsOne().read)
            }
        }
        openDatabase(receiverFile).use { reopened ->
            session(reopened).use { reader ->
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.moveToPage(pages[1])
                assertEquals(setOf(0, 1), visiblePair(reader.viewer))
            }
        }
    }

    @Test
    fun `adjust button joins evidenced opening pages and persists a legal split with the same anchor`() = runBlocking {
        val file = temporaryFolder.newFile("evidenced-opening-pair.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(1))
            session(fixture, matching = true, readingMode = 6, reuseLoadedPages = true).use { reader ->
                reader.viewer.adapter.spreadDecoderFactory = {
                    eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(it)
                }
                val chapter = requireNotNull(reader.model.state.value.currentChapter)
                val pages = requireNotNull(chapter.pages)
                awaitPairingStatus(
                    reader,
                    chapter,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.READY,
                )
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[0])
                val owner = requireNotNull(reader.viewer.adapter.session(chapter))
                val ids = owner.identity.pageIds
                val cover = requireNotNull(owner.captureViewport())
                assertTrue(owner.reportDrawn(cover, setOf(ids[0])))
                owner.releaseViewport(cover)
                reader.viewer.moveToPage(pages[1])
                val proposal = requireNotNull(owner.captureViewport(preparingPageIds = emptySet()))
                assertTrue(
                    owner.acceptDecodedFact(proposal, ids[1], 100, 150, mihon.domain.reader.ReaderPageSideHint.LEFT),
                )
                reader.viewer.adapter.refreshDecodedSide(pages[1], owner)
                assertEquals(setOf(1), visiblePair(reader.viewer))
                assertEquals(
                    mihon.domain.reader.ReaderPortraitSingleSlot.LEFT,
                    owner.targetUnit!!.slots.single().position,
                )
                val current = requireNotNull(owner.captureViewport())
                assertTrue(owner.reportDrawn(current, setOf(ids[1])))
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                fun adjust() {
                    compose.onNodeWithContentDescription(
                        reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).assertIsEnabled().performClick()
                }
                adjust()
                withTimeout(5_000) {
                    while (reader.model.state.value.pairingSaving ||
                        fixture.repository.load(2, 1).record?.openingMode !=
                        mihon.domain.reader.ReaderChapterOpeningMode.FIRST_PAIR
                    ) {
                        yield()
                    }
                }
                assertEquals(setOf(0, 1), visiblePair(reader.viewer))
                assertEquals(
                    listOf(
                        mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT,
                        mihon.domain.reader.ReaderPortraitSingleSlot.LEFT,
                    ),
                    owner.targetUnit!!.slots.map { it.position },
                )
                assertEquals(1, owner.location!!.pageId.sourcePageIndex)
                assertEquals(2, reader.model.state.value.currentPage)
                val firstPair = requireNotNull(owner.captureViewport())
                assertTrue(owner.reportDrawn(firstPair, setOf(ids[0], ids[1])))
                compose.mainClock.advanceTimeBy(500)
                adjust()
                withTimeout(5_000) {
                    while (reader.model.state.value.pairingSaving ||
                        fixture.repository.load(2, 1).record?.forcedSinglePages != setOf(1)
                    ) {
                        yield()
                    }
                }
                assertEquals(setOf(1), visiblePair(reader.viewer))
                assertEquals(1, owner.location!!.pageId.sourcePageIndex)
                val projected = reader.viewer.adapter.items.filterIsInstance<DisplayPage>().flatMap {
                    it.visiblePages.map(ReaderPage::index)
                }
                assertEquals(pages.indices.toSet(), projected.toSet())
                assertEquals(pages.size, projected.size)
            }
        }
    }

    @Test
    fun `dual reading activates and source navigation remains available while original restore is held`() =
        runBlocking {
            val file = temporaryFolder.newFile("nonblocking-dual-restore.db")
            openDatabase(file, create = true).use { fixture ->
                seed(fixture.driver)
                fixture.repository.replace(2, 1, 0, 8, setOf(3))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var model: ReaderViewModel? = null
                val repository = object : ChapterPairingRepository by fixture.repository {
                    override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                        entered.complete(Unit)
                        release.await()
                        return fixture.repository.load(chapterId, mangaId)
                    }
                }
                val coordinator = AndroidChapterPairingCoordinator(repository)
                val opening = async {
                    session(
                        fixture,
                        repository,
                        coordinator = coordinator,
                        readingMode = 6,
                        onModelCreated = { model = it },
                    )
                }
                var reader: ReaderSession? = null
                try {
                    withTimeout(5_000) { entered.await() }
                    reader = withTimeoutOrNull(2_000) { opening.await() }
                    assertTrue("Dual activation must not await the actual held repository read", reader != null)
                    val active = requireNotNull(reader)
                    val chapter = requireNotNull(active.model.state.value.viewerChapters).currChapter
                    assertTrue(chapter === active.model.state.value.currentChapter)
                    active.viewer.setChapters(requireNotNull(active.model.state.value.viewerChapters))
                    active.viewer.moveToPage(requireNotNull(chapter.pages)[3])
                    val owner = requireNotNull(active.model.dualPagePairings.chapters[chapter]).session
                    assertEquals(
                        mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING,
                        owner.pairingState.status,
                    )
                    assertNull(owner.pairingState.revision)
                    assertNull(active.viewer.pairingAdjustmentRequest())
                    assertEquals(3, owner.location?.pageId?.sourcePageIndex)
                    active.host.setContent {
                        val state by active.model.state.collectAsState()
                        MaterialTheme { active.activity.AppBars(state.copy(menuVisible = true)) }
                    }
                    compose.mainClock.advanceTimeBy(500)
                    compose.onNodeWithContentDescription(
                        active.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).assertIsNotEnabled()
                    compose.onNodeWithContentDescription(active.host.stringResource(MR.strings.action_retry))
                        .assertDoesNotExist()
                    release.complete(Unit)
                    withTimeout(5_000) {
                        while (owner.pairingState.status != mihon.domain.reader.ReaderPairingPersistenceStatus.READY) {
                            kotlinx.coroutines.delay(5)
                        }
                    }
                    assertEquals(setOf(3), owner.pairingState.committedForcedSinglePages)
                    assertTrue(active.model.dualPagePairings.chapters[chapter]?.session === owner)
                    assertEquals(3, owner.location?.pageId?.sourcePageIndex)
                    compose.mainClock.advanceTimeBy(500)
                    compose.onNodeWithContentDescription(
                        active.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).assertIsEnabled()
                } finally {
                    release.complete(Unit)
                    opening.cancelAndJoin()
                    reader?.close()
                    model?.viewModelScope?.cancel()
                    coordinator.awaitAcceptedOperations()
                }
            }
            Unit
        }

    @Test
    fun `dual adjacent page list remains usable while original pairing restore is held`() = runBlocking {
        val file = temporaryFolder.newFile("nonblocking-adjacent-restore.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.repository.replace(3, 1, 0, 8, setOf(3))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (chapterId == 3L) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                includeAdjacent = true,
                reuseLoadedPages = true,
                readingMode = 6,
            ).use { reader ->
                val window = requireNotNull(reader.model.state.value.viewerChapters)
                val next = requireNotNull(window.nextChapter)
                val preloading = async {
                    reader.model.consumeAdjacentChapterEffect(
                        next,
                        ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
                    )
                }
                try {
                    withTimeout(5_000) { entered.await() }
                    assertTrue(
                        "Normal adjacent page-list request must not await its held record",
                        withTimeoutOrNull(2_000) {
                            preloading.await()
                            true
                        } == true,
                    )
                    reader.viewer.setChapters(window)
                    val owner = requireNotNull(reader.model.dualPagePairings.chapters[next]).session
                    assertEquals(
                        mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING,
                        owner.pairingState.status,
                    )
                    assertNull(owner.pairingState.revision)
                    assertTrue(
                        reader.viewer.adapter.items.filterIsInstance<DisplayPage>()
                            .any { it.visiblePages.any { page -> page.chapter === next } },
                    )
                    assertTrue(reader.model.state.value.currentChapter === window.currChapter)
                    release.complete(Unit)
                    withTimeout(5_000) {
                        while (owner.pairingState.status != mihon.domain.reader.ReaderPairingPersistenceStatus.READY) {
                            kotlinx.coroutines.delay(5)
                        }
                    }
                    assertEquals(setOf(3), owner.pairingState.committedForcedSinglePages)
                    assertTrue(reader.model.state.value.currentChapter === window.currChapter)
                } finally {
                    release.complete(Unit)
                    preloading.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `cancelled actual restore releases its original request for retained owner retry`() = runBlocking {
        val file = temporaryFolder.newFile("cancelled-shared-restore.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var model: ReaderViewModel? = null
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            val coordinator = AndroidChapterPairingCoordinator(repository)
            val opening =
                async { session(fixture, repository, coordinator = coordinator, onModelCreated = { model = it }) }
            try {
                withTimeout(5_000) { entered.await() }
                val owner = requireNotNull(model).dualPagePairings.chapters.values.single().session
                assertEquals(mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING, owner.pairingState.status)
                opening.cancelAndJoin()
                assertEquals(
                    "A retained owner must not remain blocked by its cancelled load",
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                    owner.pairingState.status,
                )
                assertTrue(owner.beginPairingRestore() != null)
            } finally {
                release.complete(Unit)
                opening.cancelAndJoin()
                coordinator.awaitAcceptedOperations()
                model?.viewModelScope?.cancel()
            }
        }
    }

    @Test
    fun `actual repository restore begins with the shared original request before reading`() = runBlocking {
        val file = temporaryFolder.newFile("shared-restore-lifecycle.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            var openingModel: ReaderViewModel? = null
            val observed = mutableListOf<mihon.domain.reader.ReaderPairingPersistenceStatus?>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    observed += openingModel?.dualPagePairings?.chapters?.values?.singleOrNull()
                        ?.session?.pairingState?.status
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository, onModelCreated = { openingModel = it }).use { reader ->
                assertEquals(
                    "The actual I/O must consume the already-issued shared restore request",
                    listOf(mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING),
                    observed,
                )
                val owner = reader.model.dualPagePairings.chapters.values.single().session
                assertEquals(mihon.domain.reader.ReaderPairingPersistenceStatus.READY, owner.pairingState.status)
                assertEquals(setOf(3), owner.pairingState.committedForcedSinglePages)
            }
        }
    }

    @Test
    fun `real adjust button saves chapter boundary and new model viewer restores containing pair`() = runBlocking {
        val file = temporaryFolder.newFile("android-pairing.db")
        openDatabase(file, create = true).use { first ->
            seed(first.driver)
            session(first).use { session ->
                val pages = requireNotNull(session.model.state.value.currentChapter?.pages)
                session.viewer.setChapters(requireNotNull(session.model.state.value.viewerChapters))
                session.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(session.viewer))
                session.host.setContent {
                    MaterialTheme {
                        session.activity.AppBars(session.model.state.value.copy(menuVisible = true))
                    }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    session.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().performClick()
                withTimeout(5_000) {
                    while (first.repository.load(2, 1).record?.forcedSinglePages != setOf(3) ||
                        session.model.state.value.pairingSaving
                    ) {
                        yield()
                    }
                }
                assertEquals(setOf(4, 5), visiblePair(session.viewer))
                assertEquals(5L, first.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
        openDatabase(file).use { reopened ->
            session(reopened).use { session ->
                session.viewer.setChapters(requireNotNull(session.model.state.value.viewerChapters))
                assertTrue(visiblePair(session.viewer).contains(5))
                assertEquals(setOf(3), reopened.repository.load(2, 1).record?.forcedSinglePages)
                val pages = requireNotNull(session.model.state.value.currentChapter?.pages)
                session.viewer.moveToPage(pages[3])
                assertEquals(setOf(3), visiblePair(session.viewer))
                assertEquals(5L, reopened.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `real unknown partner adjustment button remains legal without disabling matching`() = runBlocking<Unit> {
        openDatabase(temporaryFolder.newFile("unknown-partner.db"), create = true).use { database ->
            seed(database.driver)
            session(database, readingMode = 6, pendingPage = 2, matching = true).use { reader ->
                val chapter = requireNotNull(reader.model.state.value.currentChapter)
                val pages = requireNotNull(chapter.pages)
                withTimeout(5_000) {
                    while (!reader.model.state.value.pairingRecordReady) yield()
                }
                // Windows Robolectric uses the existing encoded region adapter; ART verifies native JNI separately.
                reader.viewer.adapter.spreadDecoderFactory = {
                    eu.kanade.tachiyomi.ui.reader.viewer.decodedRegionFactory(it)
                }
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[1])
                val owner = requireNotNull(reader.viewer.adapter.session(chapter))
                assertEquals(Page.State.Queue, pages[2].status)
                assertEquals(
                    mihon.domain.reader.ReaderPageFactState.UNKNOWN,
                    owner.pageFact(owner.identity.pageIds[2])?.state,
                )
                assertEquals(setOf(1, 2), visiblePair(reader.viewer))
                assertFalse(requireNotNull(owner.targetUnit).certain)
                assertTrue(reader.viewer.config.automaticSpreadMatching)
                reader.host.setContent {
                    MaterialTheme {
                        val state by reader.model.state.collectAsState()
                        reader.activity.AppBars(state.copy(menuVisible = true))
                    }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsEnabled().performClick()
                withTimeout(5_000) {
                    while (database.repository.load(2, 1).record?.forcedSinglePages != setOf(1) ||
                        reader.model.state.value.pairingSaving
                    ) {
                        yield()
                    }
                }
                assertTrue(reader.viewer.config.automaticSpreadMatching)
                assertEquals(Page.State.Queue, pages[2].status)
                assertEquals(setOf(1), owner.pairingState.committedForcedSinglePages)
                assertEquals(5L, database.database.chaptersQueries.getChapterById(2).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `Android AppModule resolves production chapter pairing repository and coordinator`() = runBlocking {
        val file = temporaryFolder.newFile("app-module-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            Injekt = InjektScope(DefaultRegistrar())
            Injekt.importModule(AppModule(RuntimeEnvironment.getApplication() as Application))
            Injekt.addSingleton<DatabaseHandler>(fixture.handler)
            val repository = Injekt.get<ChapterPairingRepository>()
            assertTrue(repository is ChapterPairingRepositoryImpl)
            val coordinator = Injekt.get<AndroidChapterPairingCoordinator>()
            coordinator.submit(2) { replace(2, 1, 0, 8, setOf(3)) }.await()
            assertEquals(setOf(3), repository.load(2, 1).record?.forcedSinglePages)
        }
    }

    @Test
    fun `restored manual boundary survives a late wide image dimension callback`() = runBlocking {
        val file = temporaryFolder.newFile("wide-image-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            session(fixture).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val owner = requireNotNull(reader.viewer.adapter.session(pages[0].chapter))
                // The entered group already owns these pages before the late wide fact arrives.
                assertEquals(listOf(4, 5), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                reader.viewer.adapter.updatePageDimensions(pages[2], 2200, 1000)
                reader.viewer.moveToPage(pages[2])
                assertEquals(setOf(2), visiblePair(reader.viewer))
                assertEquals(setOf(3), reader.viewer.adapter.appliedForcedSinglePages())
                reader.viewer.moveToPage(pages[4])
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                assertEquals(listOf(4, 5), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                assertEquals(
                    4,
                    reader.viewer.adapter.session(
                        requireNotNull(
                            reader.model.state.value.currentChapter,
                        ),
                    )?.location?.pageId?.sourcePageIndex,
                )
                assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
                reader.viewer.moveToPage(pages[3])
                assertEquals(setOf(3), visiblePair(reader.viewer))
            }
        }
    }

    @Test
    fun `adjacent prefetch restores pairing before next chapter first becomes visible`() = runBlocking {
        val file = temporaryFolder.newFile("adjacent-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.repository.replace(3, 1, 0, 8, setOf(3))
            session(fixture, includeAdjacent = true, reuseLoadedPages = true).use { reader ->
                val currentWindow = requireNotNull(reader.model.state.value.viewerChapters)
                val next = requireNotNull(currentWindow.nextChapter)
                assertEquals(3L, next.chapter.id)
                reader.model.consumeAdjacentChapterEffect(
                    next,
                    ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList,
                )
                awaitPairingStatus(reader, next, mihon.domain.reader.ReaderPairingPersistenceStatus.READY)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val prefetchedPage = requireNotNull(next.pages)[4]
                val prefetchedUnit = reader.viewer.adapter.items.filterIsInstance<DisplayPage>()
                    .first { it.containsPage(prefetchedPage) }
                assertTrue(prefetchedUnit.visiblePages.contains(prefetchedPage))
                assertEquals(setOf(3), fixture.repository.load(3, 1).record?.forcedSinglePages)
                reader.model.loadNextChapter()
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(prefetchedPage)
                assertTrue(visiblePair(reader.viewer).contains(4))
                reader.viewer.moveToPage(requireNotNull(next.pages)[3])
                assertEquals(setOf(3), visiblePair(reader.viewer))
                assertEquals(0L, fixture.database.chaptersQueries.getChapterById(3).executeAsOne().last_page_read)
            }
        }
    }

    @Test
    fun `failed adjacent prefetch activates readable current chapter and Settings reloads its owner`() = runBlocking {
        openDatabase(temporaryFolder.newFile("nonblocking-adjacent-failure.db"), create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val saved = fixture.repository.replace(3, 1, 0, 8, setOf(3))
            val fail = AtomicBoolean(true)
            val loads = AtomicInteger()
            val last = AtomicReference<Long>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    loads.incrementAndGet()
                    last.set(chapterId)
                    if (chapterId == 3L && fail.get()) throw IOException("Adjacent metadata unavailable")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository, includeAdjacent = true, reuseLoadedPages = true).use { reader ->
                val next = requireNotNull(reader.model.state.value.viewerChapters?.nextChapter)
                reader.model.consumeAdjacentChapterEffect(next, ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList)
                awaitPairingStatus(reader, next, mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED)
                reader.model.loadNextChapter()
                withTimeout(5_000) { while (reader.model.state.value.currentChapter !== next) yield() }
                awaitPairingStatus(reader, next, mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED)
                assertNull(reader.model.state.value.dialog)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(requireNotNull(next.pages)[3])
                openPairingSettings(reader)
                fail.set(false)
                invokeSettingsReload(reader, loads, last)
                awaitPairingStatus(reader, next, mihon.domain.reader.ReaderPairingPersistenceStatus.READY)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                assertTrue(visiblePair(reader.viewer).contains(3))
                assertEquals(saved, fixture.repository.load(3, 1))
            }
        }
    }

    @Test
    fun `restored RTL last spread settles the last source page and marks chapter read`() = runBlocking {
        val file = temporaryFolder.newFile("last-spread.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 8, setOf(3))
            session(fixture).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                val owner = requireNotNull(reader.viewer.adapter.session(pages[0].chapter))
                assertTrue(owner.displayedHistory.isEmpty())
                reader.viewer.moveToPage(pages[7])
                assertEquals(7, owner.location!!.pageId.sourcePageIndex)
                assertEquals(listOf(7), owner.targetUnit!!.pageIds.map { it.sourcePageIndex })
                val spread = reader.viewer.adapter.items[reader.viewer.pager.currentItem] as DisplayPage
                assertEquals(setOf(7), spread.visiblePages.map(ReaderPage::index).toSet())
                assertEquals(Page.State.Ready, pages[6].status)
                spread.visiblePages.forEach { it.status = Page.State.Ready }
                reader.model.onPageSelected(spread.visiblePages.first(), spread.visiblePages, recordProgress = false)
                reader.model.onDualViewportSettled(spread.visiblePages.first(), spread.visiblePages)
                withTimeout(5_000) {
                    while (!fixture.database.chaptersQueries.getChapterById(2).executeAsOne().read) yield()
                }
                val chapter = fixture.database.chaptersQueries.getChapterById(2).executeAsOne()
                assertTrue(chapter.read)
                assertEquals(7L, chapter.last_page_read)
            }
        }
    }

    @Test
    fun `failed adjustment click keeps old spread and record and emits failure feedback`() = runBlocking {
        val file = temporaryFolder.newFile("save-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val attempts = AtomicInteger()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replaceLayout(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    adjustment: mihon.domain.reader.ReaderPairingAdjustment,
                ): ChapterPairingSnapshot {
                    if (attempts.incrementAndGet() == 1) throw IOException("pairing database unavailable")
                    return fixture.repository.replaceLayout(
                        chapterId,
                        mangaId,
                        expectedRevision,
                        pageCount,
                        adjustment,
                    )
                }
            }
            session(fixture, repository).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(reader.viewer))
                val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        reader.model.eventFlow.first { it == ReaderViewModel.Event.ChapterPairingSaveFailed }
                    }
                }
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().performClick()
                assertEquals(ReaderViewModel.Event.ChapterPairingSaveFailed, feedback.await())
                assertFalse(reader.model.state.value.pairingSaving)
                val owner = reader.model.dualPagePairings.chapters.values.single().session
                assertEquals(mihon.domain.reader.ReaderPairingPersistenceStatus.SAVE_FAILED, owner.pairingState.status)
                assertEquals(emptySet<Int>(), owner.pairingState.committedForcedSinglePages)
                assertEquals(setOf(3, 4), visiblePair(reader.viewer))
                assertNull(fixture.repository.load(2, 1).record)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().assertIsEnabled().performClick()
                withTimeout(5_000) {
                    while (owner.pairingState.status != mihon.domain.reader.ReaderPairingPersistenceStatus.READY ||
                        reader.model.state.value.pairingSaving
                    ) {
                        yield()
                    }
                }
                assertEquals(2, attempts.get())
                assertEquals(setOf(3), owner.pairingState.committedForcedSinglePages)
                assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
            }
        }
        Unit
    }

    @Test
    fun `actual adjustment CAS conflict reloads original record before the next click`() = runBlocking {
        val file = temporaryFolder.newFile("save-conflict.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val reloadEntered = CompletableDeferred<Unit>()
            val releaseReload = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (loads.incrementAndGet() > 1) {
                        reloadEntered.complete(Unit)
                        releaseReload.await()
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            try {
                session(fixture, repository).use { reader ->
                    val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                    reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                    reader.viewer.moveToPage(pages[3])
                    val external = fixture.repository.replace(2, 1, 0, 8, setOf(7))
                    val owner = reader.model.dualPagePairings.chapters.values.single().session
                    reader.host.setContent {
                        val state by reader.model.state.collectAsState()
                        MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                    }
                    compose.mainClock.advanceTimeBy(500)
                    val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) {
                            reader.model.eventFlow.first { it == ReaderViewModel.Event.ChapterPairingSaveFailed }
                        }
                    }
                    compose.onNodeWithContentDescription(
                        reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).performClick()
                    feedback.await()
                    assertTrue(
                        "CAS conflict must reload through the actual repository before admitting a new save",
                        withTimeoutOrNull(5_000) {
                            reloadEntered.await()
                            true
                        } == true,
                    )
                    assertEquals(emptySet<Int>(), owner.pairingState.committedForcedSinglePages)
                    assertEquals(external, fixture.repository.load(2, 1))
                    releaseReload.complete(Unit)
                    withTimeout(5_000) {
                        while (owner.pairingState.status !=
                            mihon.domain.reader.ReaderPairingPersistenceStatus.READY
                        ) {
                            yield()
                        }
                    }
                    assertEquals(setOf(7), owner.pairingState.committedForcedSinglePages)
                    assertEquals(external.revision, owner.pairingState.revision)
                    compose.onNodeWithContentDescription(
                        reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).performClick()
                    withTimeout(5_000) {
                        while (fixture.repository.load(2, 1).revision == external.revision ||
                            reader.model.state.value.pairingSaving
                        ) {
                            yield()
                        }
                    }
                    assertEquals(setOf(3, 7), fixture.repository.load(2, 1).record?.forcedSinglePages)
                    assertEquals(setOf(4, 5), visiblePair(reader.viewer))
                }
            } finally {
                releaseReload.complete(Unit)
            }
        }
    }

    @Test
    fun `invalid saved page count does not suppress an adjustment matching its old boundary`() = runBlocking {
        val file = temporaryFolder.newFile("invalid-pairing.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            fixture.repository.replace(2, 1, 0, 9, setOf(3))
            val attempts = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val observed = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replaceLayout(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    adjustment: mihon.domain.reader.ReaderPairingAdjustment,
                ): ChapterPairingSnapshot {
                    attempts.incrementAndGet()
                    assertEquals(setOf(3), adjustment.forcedSinglePages)
                    return try {
                        fixture.repository.replaceLayout(chapterId, mangaId, expectedRevision, pageCount, adjustment)
                    } catch (error: Throwable) {
                        failure.set(error)
                        throw error
                    }
                }
            }
            session(fixture, observed).use { session ->
                val pages = requireNotNull(session.model.state.value.currentChapter?.pages)
                session.viewer.setChapters(requireNotNull(session.model.state.value.viewerChapters))
                session.viewer.moveToPage(pages[3])
                assertEquals(setOf(3, 4), visiblePair(session.viewer))
                assertEquals(
                    emptySet<Int>(),
                    session.model.dualPagePairings.chapters.values.single()
                        .session.pairingState.committedForcedSinglePages,
                )
                assertEquals(
                    1L,
                    session.model.dualPagePairings.snapshot(
                        requireNotNull(session.model.state.value.currentChapter),
                        pages,
                    )?.revision,
                )
                session.host.setContent {
                    MaterialTheme {
                        session.activity.AppBars(session.model.state.value.copy(menuVisible = true))
                    }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    session.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsDisplayed().performClick()
                withTimeout(5_000) {
                    while (attempts.get() == 0 || session.model.state.value.pairingSaving) yield()
                }
                assertNull(failure.get()?.stackTraceToString(), failure.get())
                assertEquals(8, fixture.repository.load(2, 1).record?.pageCount)
                assertEquals(setOf(4, 5), visiblePair(session.viewer))
            }
        }
    }

    @Test
    fun `failed conflict restore retains known manual boundary and stored record without a modal`() = runBlocking {
        val file = temporaryFolder.newFile("known-boundary-restore-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
            val loads = AtomicInteger()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (loads.incrementAndGet() > 1) throw IOException("known record refresh failed")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, repository, readingMode = 6).use { reader ->
                val chapter = requireNotNull(reader.model.state.value.currentChapter)
                awaitPairingStatus(
                    reader,
                    chapter,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.READY,
                )
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(requireNotNull(chapter.pages)[5])
                val owner = requireNotNull(reader.model.dualPagePairings.chapters[chapter]).session
                val external = fixture.repository.replace(2, 1, saved.revision, 8, setOf(7))
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000) {
                        reader.model.eventFlow.first { it == ReaderViewModel.Event.ChapterPairingSaveFailed }
                    }
                }
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                feedback.await()
                awaitPairingStatus(
                    reader,
                    chapter,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                )
                assertNull(reader.model.state.value.dialog)
                assertEquals(setOf(3), owner.pairingState.committedForcedSinglePages)
                assertEquals(saved.revision, owner.pairingState.revision)
                assertEquals(external, fixture.repository.load(2, 1))
                reader.viewer.moveToPage(requireNotNull(chapter.pages)[3])
                assertEquals(setOf(3), visiblePair(reader.viewer))
            }
        }
    }

    @Test
    fun `AUTO stays readable for both failure and viewer switch orders`() = runBlocking {
        for (failFirst in listOf(false, true)) {
            openDatabase(temporaryFolder.newFile("auto-order-$failFirst.db"), create = true).use { fixture ->
                seed(fixture.driver)
                val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val loads = AtomicInteger()
                val last = AtomicReference<Long>()
                val repository = object : ChapterPairingRepository by fixture.repository {
                    override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                        last.set(chapterId)
                        if (loads.incrementAndGet() == 1) {
                            entered.complete(Unit)
                            release.await()
                            throw IOException("AUTO original read failed")
                        }
                        return fixture.repository.load(chapterId, mangaId)
                    }
                }
                session(fixture, repository, readingMode = 7, reuseLoadedPages = true).use { reader ->
                    try {
                        withTimeout(5_000) { entered.await() }
                        updateActualViewer(reader)
                        assertTrue(reader.model.state.value.viewer is R2LPagerViewer)
                        val chapter = requireNotNull(reader.model.state.value.currentChapter)
                        val window = reader.model.state.value.viewerChapters
                        if (failFirst) {
                            release.complete(Unit)
                            awaitPairingStatus(
                                reader,
                                chapter,
                                mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                            )
                            assertNull(reader.model.state.value.dialog)
                        }
                        updateActualViewer(reader, 1800, 900)
                        assertTrue(reader.model.state.value.viewer is DualPageR2LPagerViewer)
                        if (!failFirst) release.complete(Unit)
                        awaitPairingStatus(
                            reader,
                            chapter,
                            mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                        )
                        withTimeout(5_000) { while (!reader.model.state.value.pairingReadUnavailable) yield() }
                        assertTrue(window === reader.model.state.value.viewerChapters)
                        assertNull(reader.model.state.value.dialog)
                        val viewer = reader.model.state.value.viewer as DualPageR2LPagerViewer
                        viewer.moveToPage(requireNotNull(chapter.pages)[3])
                        val source = reader.model.currentReaderPage
                        openPairingSettings(reader)
                        invokeSettingsReload(reader, loads, last)
                        awaitPairingStatus(
                            reader,
                            chapter,
                            mihon.domain.reader.ReaderPairingPersistenceStatus.READY,
                        )
                        reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                        assertTrue(source === reader.model.currentReaderPage)
                        assertTrue(window === reader.model.state.value.viewerChapters)
                        assertTrue(visiblePair(viewer).contains(3))
                        assertEquals(saved, fixture.repository.load(2, 1))
                    } finally {
                        release.complete(Unit)
                    }
                }
            }
        }
    }

    @Test
    fun `activated adjacent prefetch failure reloads current owner from Settings`() = runBlocking {
        val file = temporaryFolder.newFile("activated-prefetch-failure.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val saved = fixture.repository.replace(3, 1, 0, 8, setOf(3))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldEntered = CompletableDeferred<Unit>()
            val oldRelease = CompletableDeferred<Unit>()
            val loads = AtomicInteger()
            val lastChapter = AtomicReference<Long>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    loads.incrementAndGet()
                    lastChapter.set(chapterId)
                    if (chapterId == 2L && !oldEntered.isCompleted) {
                        oldEntered.complete(Unit)
                        oldRelease.await()
                        throw IOException("Old chapter read fails after B activates")
                    }
                    if (chapterId == 3L && !entered.isCompleted) {
                        entered.complete(Unit)
                        release.await()
                        throw IOException("Ordinary adjacent restore fails after chapter activation")
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                includeAdjacent = true,
                reuseLoadedPages = true,
                readingMode = 6,
            ).use { reader ->
                withTimeout(5_000) { oldEntered.await() }
                val original = requireNotNull(reader.model.state.value.viewerChapters)
                val next = requireNotNull(original.nextChapter)
                reader.model.consumeAdjacentChapterEffect(next, ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList)
                try {
                    withTimeout(5_000) { entered.await() }
                    reader.model.loadNextChapter()
                    withTimeout(5_000) { while (reader.model.state.value.currentChapter !== next) yield() }
                    reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                    reader.viewer.moveToPage(requireNotNull(next.pages)[3])
                    val window = reader.model.state.value.viewerChapters
                    val owner = requireNotNull(reader.model.dualPagePairings.chapters[next]).session
                    val location = owner.location
                    assertEquals(3, location?.pageId?.sourcePageIndex)
                    release.complete(Unit)
                    awaitPairingStatus(reader, next, mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED)
                    withTimeout(5_000) { while (!reader.model.state.value.pairingReadUnavailable) yield() }
                    oldRelease.complete(Unit)
                    awaitPairingStatus(
                        reader,
                        original.currChapter,
                        mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                    )
                    assertTrue(
                        "Late A failure must not change current B",
                        reader.model.state.value.currentChapter === next,
                    )
                    assertTrue(window === reader.model.state.value.viewerChapters)
                    assertEquals(location, owner.location)
                    assertNull(owner.pairingState.revision)
                    assertNull(reader.model.state.value.dialog)
                    assertTrue(reader.model.state.value.pairingReadUnavailable)
                    assertFalse(reader.model.state.value.pairingRecordReady)
                    assertEquals(saved, fixture.repository.load(3, 1))
                    openPairingSettings(reader)
                    invokeSettingsReload(reader, loads, lastChapter)
                    awaitPairingStatus(reader, next, mihon.domain.reader.ReaderPairingPersistenceStatus.READY)
                    assertTrue(window === reader.model.state.value.viewerChapters)
                    assertTrue(owner === reader.model.dualPagePairings.chapters[next]?.session)
                    assertEquals(location, owner.location)
                    reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                    // This JVM path checks production rebind/navigation; painted-frame locking
                    // remains covered by the unchanged native/shared presentation contracts.
                    assertTrue(visiblePair(reader.viewer).contains(3))
                    reader.model.closeDialog()
                    assertTrue(reader.viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)))
                    assertTrue(
                        "Next source after the unpainted current source must remain reachable",
                        visiblePair(reader.viewer).contains(4),
                    )
                    assertEquals(4, owner.location?.pageId?.sourcePageIndex)
                    assertNull(reader.model.state.value.dialog)
                    assertEquals(saved, fixture.repository.load(3, 1))
                } finally {
                    release.complete(Unit)
                    oldRelease.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `dual restore failure stays nonmodal until existing settings are opened`() = runBlocking {
        val file = temporaryFolder.newFile("nonmodal-restore.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
            val failRead = AtomicBoolean(true)
            val restoreLoads = AtomicInteger()
            val lastRestoreChapter = AtomicReference<Long>()
            val unreadable = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    restoreLoads.incrementAndGet()
                    lastRestoreChapter.set(chapterId)
                    if (failRead.get()) throw IOException("pairing database unavailable")
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(fixture, unreadable, readingMode = 6).use { reader ->
                val chapter = requireNotNull(reader.model.state.value.currentChapter)
                awaitPairingStatus(
                    reader,
                    chapter,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                )
                assertNull(
                    "Background restore failure must not automatically open Activity dialog",
                    reader.model.state.value.dialog,
                )
                assertTrue(reader.model.state.value.viewerChapters != null)
                assertNull(reader.model.dualPagePairings.chapters[chapter]?.session?.pairingState?.revision)
                assertEquals(saved, fixture.repository.load(2, 1))
                val originalWindow = requireNotNull(reader.model.state.value.viewerChapters)
                reader.viewer.setChapters(originalWindow)
                reader.viewer.moveToPage(requireNotNull(chapter.pages)[1])
                val originalPage = reader.model.state.value.currentPage
                val originalSource = requireNotNull(
                    reader.model.dualPagePairings.chapters[chapter]?.session?.location,
                ).pageId
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme {
                        reader.activity.AppBars(state.copy(menuVisible = true))
                        if (state.dialog is ReaderViewModel.Dialog.Settings) {
                            reader.activity.ReaderSettingsControls(
                                reader.settingsScreenModel,
                            )
                        }
                    }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_settings),
                ).performClick()
                assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.Settings)
                compose.onNodeWithText(
                    reader.host.stringResource(MR.strings.desktop_reader_pairing_load_failed),
                ).assertDoesNotExist()
                compose.onNodeWithText(reader.host.stringResource(MR.strings.action_retry)).assertDoesNotExist()
                compose.onNodeWithText(
                    reader.host.stringResource(MR.strings.desktop_reader_pairing_use_default),
                ).assertDoesNotExist()
                compose.onAllNodesWithText(
                    reader.host.stringResource(MR.strings.action_reload_page_pairing),
                ).assertCountEquals(1)
                invokeSettingsReload(reader, restoreLoads, lastRestoreChapter)
                awaitPairingStatus(
                    reader,
                    chapter,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                )
                assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.Settings)
                assertNull(reader.model.dualPagePairings.chapters[chapter]?.session?.pairingState?.revision)
                assertTrue(
                    "Reload failure must retain the activated window",
                    originalWindow === reader.model.state.value.viewerChapters,
                )
                assertEquals(originalPage, reader.model.state.value.currentPage)
                assertEquals(originalSource, reader.model.dualPagePairings.chapters[chapter]?.session?.location?.pageId)
                assertEquals(saved, fixture.repository.load(2, 1))
                assertFalse(reader.model.state.value.pairingRecordReady)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).assertIsNotEnabled()
                failRead.set(false)
                invokeSettingsReload(reader, restoreLoads, lastRestoreChapter)
                awaitPairingStatus(
                    reader,
                    chapter,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.READY,
                )
                assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.Settings)
                assertEquals(saved, fixture.repository.load(2, 1))
                assertEquals(
                    setOf(3),
                    reader.model.dualPagePairings.chapters[chapter]?.session?.pairingState?.committedForcedSinglePages,
                )
            }
        }
    }

    @Test
    fun `Settings reread remains disabled after returning to the same retained owner`() = runBlocking {
        openDatabase(temporaryFolder.newFile("return-pending-recovery.db"), create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
            val loads = AtomicInteger()
            val aReads = AtomicInteger()
            val last = AtomicReference<Long>()
            val release = CompletableDeferred<Unit>()
            val bEntered = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    loads.incrementAndGet()
                    last.set(chapterId)
                    if (chapterId == 2L) {
                        when (aReads.incrementAndGet()) {
                            1 -> throw IOException("A initial failure")
                            2 -> {
                                release.await()
                                throw IOException("A reread failure")
                            }
                            3 -> throw CancellationException("Actual user reread cancelled")
                        }
                    }
                    if (chapterId == 3L && !bEntered.isCompleted) {
                        bEntered.complete(Unit)
                        bRelease.await()
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                readingMode = 1,
                includeAdjacent = true,
                reuseLoadedPages = true,
            ).use { reader ->
                val chapter = requireNotNull(reader.model.state.value.currentChapter)
                awaitPairingStatus(reader, chapter, ReaderPairingPersistenceStatus.RESTORE_FAILED)
                updateActualViewer(reader)
                val pages = requireNotNull(chapter.pages)
                val owner = requireNotNull(reader.model.dualPagePairings.chapters[chapter]).session
                val label = reader.host.stringResource(MR.strings.action_reload_page_pairing)
                chapter.ref()
                try {
                    openPairingSettings(reader)
                    invokeSettingsReload(reader, loads, last)
                    compose.onNodeWithText(label).assertIsNotEnabled()
                    reader.model.closeDialog()
                    navigateRecoveryChapter(reader, forward = true)
                    withTimeout(5_000) { bEntered.await() }
                    val adjacent = requireNotNull(reader.model.state.value.currentChapter)
                    openPairingSettings(reader, expectRecovery = false)
                    bRelease.complete(Unit)
                    awaitPairingStatus(reader, adjacent, ReaderPairingPersistenceStatus.READY)
                    reader.model.closeDialog()
                    navigateRecoveryChapter(reader, forward = false)
                    assertTrue(chapter === reader.model.state.value.currentChapter)
                    assertTrue(pages === chapter.pages)
                    assertTrue(owner === reader.model.dualPagePairings.chapters[chapter]?.session)
                    assertFalse(owner.isClosed)
                    assertEquals(2, aReads.get())
                    openPairingSettings(reader)
                    compose.onNodeWithText(label).assertIsNotEnabled()
                    reader.model.retryPairingRestore()
                    reader.model.retryPairingRestore()
                    assertEquals(2, aReads.get())
                    val window = reader.model.state.value.viewerChapters
                    val position = reader.model.currentReaderPage
                    release.complete(Unit)
                    awaitPairingStatus(reader, chapter, ReaderPairingPersistenceStatus.RESTORE_FAILED)
                    compose.waitUntil(5_000) { !reader.model.state.value.pairingRestoring }
                    compose.onNodeWithText(label).assertIsEnabled()
                    invokeSettingsReload(reader, loads, last)
                    awaitPairingStatus(reader, chapter, ReaderPairingPersistenceStatus.RESTORE_FAILED)
                    compose.waitUntil(5_000) { !reader.model.state.value.pairingRestoring }
                    compose.onNodeWithText(label).assertIsEnabled()
                    assertNull(owner.pairingState.revision)
                    invokeSettingsReload(reader, loads, last)
                    awaitPairingStatus(reader, chapter, ReaderPairingPersistenceStatus.READY)
                    compose.waitUntil(5_000) { !reader.model.state.value.pairingReadUnavailable }
                    compose.onNodeWithText(label).assertDoesNotExist()
                    assertTrue(window === reader.model.state.value.viewerChapters)
                    assertTrue(position === reader.model.currentReaderPage)
                    assertEquals(4, aReads.get())
                    assertEquals(saved, fixture.repository.load(2, 1))
                } finally {
                    release.complete(Unit)
                    bRelease.complete(Unit)
                    Injekt.get<AndroidChapterPairingCoordinator>().awaitAcceptedOperations()
                    chapter.unref()
                }
            }
        }
    }

    @Test
    fun `Settings rereads for two chapters retain independent pending feedback`() = runBlocking {
        openDatabase(temporaryFolder.newFile("two-pending-recoveries.db"), create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            // Keep A's ordinary viewport outside the last-five-page adjacent preload window.
            fixture.driver.execute(null, "UPDATE chapters SET last_page_read = 0 WHERE _id = 2", 0)
            val loads = AtomicInteger()
            val reads = mapOf(2L to AtomicInteger(), 3L to AtomicInteger())
            val last = AtomicReference<Long>()
            val aRelease = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    loads.incrementAndGet()
                    last.set(chapterId)
                    when (reads.getValue(chapterId).incrementAndGet()) {
                        1 -> throw IOException("Initial failure $chapterId")
                        2 -> if (chapterId == 2L) {
                            aRelease.await()
                        } else {
                            bRelease.await()
                            throw IOException("B reread failed")
                        }
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                readingMode = 1,
                includeAdjacent = true,
                reuseLoadedPages = true,
            ).use { reader ->
                val a = requireNotNull(reader.model.state.value.currentChapter)
                awaitPairingStatus(reader, a, ReaderPairingPersistenceStatus.RESTORE_FAILED)
                updateActualViewer(reader)
                a.ref()
                try {
                    openPairingSettings(reader)
                    invokeSettingsReload(reader, loads, last)
                    reader.model.closeDialog()
                    navigateRecoveryChapter(reader, forward = true)
                    val b = requireNotNull(reader.model.state.value.currentChapter)
                    awaitPairingStatus(reader, b, ReaderPairingPersistenceStatus.RESTORE_FAILED)
                    b.ref()
                    try {
                        openPairingSettings(reader)
                        invokeSettingsReload(reader, loads, last)
                        reader.model.closeDialog()
                        navigateRecoveryChapter(reader, forward = false)
                        openPairingSettings(reader)
                        val label = reader.host.stringResource(MR.strings.action_reload_page_pairing)
                        compose.onNodeWithText(label).assertIsNotEnabled()
                        assertEquals(2, reads.getValue(2L).get())
                        assertEquals(2, reads.getValue(3L).get())
                        reader.model.closeDialog()
                        navigateRecoveryChapter(reader, forward = true)
                        openPairingSettings(reader)
                        aRelease.complete(Unit)
                        awaitPairingStatus(reader, a, ReaderPairingPersistenceStatus.READY)
                        compose.onNodeWithText(label).assertIsNotEnabled()
                        assertTrue(reader.model.state.value.pairingRestoring)
                        reader.model.closeDialog()
                        navigateRecoveryChapter(reader, forward = false)
                        awaitPairingStatus(reader, a, ReaderPairingPersistenceStatus.READY)
                        openPairingSettings(reader, expectRecovery = false)
                        val window = reader.model.state.value.viewerChapters
                        val position = reader.model.currentReaderPage
                        bRelease.complete(Unit)
                        awaitPairingStatus(reader, b, ReaderPairingPersistenceStatus.RESTORE_FAILED)
                        assertTrue(window === reader.model.state.value.viewerChapters)
                        assertTrue(position === reader.model.currentReaderPage)
                        compose.onNodeWithText(label).assertDoesNotExist()
                        reader.model.closeDialog()
                        navigateRecoveryChapter(reader, forward = true)
                        awaitPairingStatus(reader, b, ReaderPairingPersistenceStatus.READY)
                        // The new chapter activation may reread a failed owner automatically;
                        // it is not a user request and must not inherit either prior pending marker.
                        openPairingSettings(reader, expectRecovery = false)
                    } finally {
                        b.unref()
                    }
                } finally {
                    aRelease.complete(Unit)
                    bRelease.complete(Unit)
                    Injekt.get<AndroidChapterPairingCoordinator>().awaitAcceptedOperations()
                    a.unref()
                }
            }
        }
    }

    @Test
    fun `Settings reread replacement ignores old completion while a new request is pending`() = runBlocking {
        openDatabase(temporaryFolder.newFile("replacement-pending-recovery.db"), create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val loads = AtomicInteger()
            val aReads = AtomicInteger()
            val last = AtomicReference<Long>()
            val oldRelease = CompletableDeferred<Unit>()
            val newEntered = CompletableDeferred<Unit>()
            val newRelease = CompletableDeferred<Unit>()
            val scheduler = TestCoroutineScheduler()
            val dispatcher = HeldRecoveryDispatcher(scheduler)
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    loads.incrementAndGet()
                    last.set(chapterId)
                    if (chapterId == 2L) {
                        when (aReads.incrementAndGet()) {
                            1, 3 -> throw IOException("Failure for current A owner")
                            2 -> oldRelease.await()
                            4 -> {
                                newEntered.complete(Unit)
                                newRelease.await()
                            }
                        }
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                readingMode = 1,
                includeAdjacent = true,
                reuseLoadedPages = true,
                pairingCallbackDispatcher = dispatcher,
            ).use { reader ->
                val a = requireNotNull(reader.model.state.value.currentChapter)
                awaitPairingStatus(reader, a, ReaderPairingPersistenceStatus.RESTORE_FAILED, scheduler)
                updateActualViewer(reader)
                a.ref()
                try {
                    openPairingSettings(reader)
                    val before = reader.model.viewModelScope.coroutineContext[Job]!!.children.toSet()
                    clickSettingsReload(reader)
                    scheduler.runCurrent()
                    withTimeout(5_000) { while (aReads.get() < 2) yield() }
                    val oldJob = reader.model.viewModelScope.coroutineContext[Job]!!.children.single { it !in before }
                    dispatcher.heldJob = oldJob
                    val oldOwner = requireNotNull(reader.model.dualPagePairings.chapters[a]).session
                    val replacementPages = requireNotNull(a.pages).map { old ->
                        ReaderPage(old.index).apply {
                            chapter = a
                            stream = old.stream
                            status = Page.State.Ready
                            publishHeaderFact()
                        }
                    }
                    a.publishLoadedPageListForTest(replacementPages)
                    reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                    assertTrue(oldOwner.isClosed)
                    reader.model.closeDialog()
                    reader.model.loadNextChapter()
                    reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                    reader.model.loadPreviousChapter()
                    reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                    scheduler.runCurrent()
                    oldRelease.complete(Unit)
                    withTimeout(5_000) { while (!dispatcher.hasHeldCallback) yield() }
                    awaitPairingStatus(reader, a, ReaderPairingPersistenceStatus.RESTORE_FAILED, scheduler)
                    val owner = requireNotNull(reader.model.dualPagePairings.chapters[a]).session
                    assertTrue(owner !== oldOwner)
                    openPairingSettings(reader)
                    clickSettingsReload(reader)
                    scheduler.runCurrent()
                    withTimeout(5_000) { newEntered.await() }
                    val label = reader.host.stringResource(MR.strings.action_reload_page_pairing)
                    compose.onNodeWithText(label).assertIsNotEnabled()
                    val window = reader.model.state.value.viewerChapters
                    val position = reader.model.currentReaderPage
                    dispatcher.releaseHeld()
                    scheduler.runCurrent()
                    withTimeout(5_000) { oldJob.join() }
                    compose.onNodeWithText(label).assertIsNotEnabled()
                    assertEquals(4, aReads.get())
                    assertTrue(owner === reader.model.dualPagePairings.chapters[a]?.session)
                    newRelease.complete(Unit)
                    awaitPairingStatus(reader, a, ReaderPairingPersistenceStatus.READY, scheduler)
                    compose.waitUntil(5_000) { !reader.model.state.value.pairingReadUnavailable }
                    compose.onNodeWithText(label).assertDoesNotExist()
                    assertTrue(window === reader.model.state.value.viewerChapters)
                    assertTrue(position === reader.model.currentReaderPage)
                } finally {
                    oldRelease.complete(Unit)
                    newRelease.complete(Unit)
                    dispatcher.releaseHeld()
                    Injekt.get<AndroidChapterPairingCoordinator>().awaitAcceptedOperations()
                    scheduler.runCurrent()
                    a.unref()
                }
            }
        }
    }

    // Synthetic layouts verify diagnostic wiring only; real AUTO opening samples decide O5 applicability.
    @Test
    fun `O5 actual AUTO viewer construction records invalid single before valid landscape dual`() = runBlocking {
        openDatabase(temporaryFolder.newFile(), create = true).use { fixture ->
            seed(fixture.driver)
            session(fixture, readingMode = ReadingMode.AUTO.flagValue).use { reader ->
                val request = prepareO5ActivityProbe(reader)
                updateActualViewer(reader, 0, 0)
                assertTrue(reader.model.state.value.viewer is R2LPagerViewer)
                updateActualViewer(reader, 1800, 900)
                assertTrue(reader.model.state.value.viewer is DualPageR2LPagerViewer)
                assertEquals(ReadingMode.AUTO.flagValue, reader.model.getMangaReadingMode())
                val events = ReaderOpenLatencyProbe.capturedEvents().filter { it.event.startsWith("AUTO_") }
                assertEquals(
                    listOf("AUTO_SINGLE_CREATED_INVALID", "AUTO_DUAL_CREATED_LANDSCAPE"),
                    events.map { it.event },
                )
                assertTrue(events.all { it.request == request && it.chapterId == 2L && it.page == 4 })
                assertTrue(events[0].monotonicNanos < events[1].monotonicNanos)
            }
        }
    }

    @Test
    fun `O5 actual AUTO viewer starts directly dual with first valid landscape viewport`() = runBlocking {
        openDatabase(temporaryFolder.newFile(), create = true).use { fixture ->
            seed(fixture.driver)
            session(fixture, readingMode = ReadingMode.AUTO.flagValue).use { reader ->
                prepareO5ActivityProbe(reader)
                updateActualViewer(reader, 1800, 900)
                assertTrue(reader.model.state.value.viewer is DualPageR2LPagerViewer)
                assertEquals(ReadingMode.AUTO.flagValue, reader.model.getMangaReadingMode())
                assertEquals(
                    listOf("AUTO_DUAL_CREATED_LANDSCAPE"),
                    ReaderOpenLatencyProbe.capturedEvents().filter { it.event.startsWith("AUTO_") }.map { it.event },
                )
            }
        }
    }

    @Test
    fun `O5 fixed modes stale Activity and disabled probe emit no AUTO markers`() = runBlocking {
        val cases = listOf(
            Triple(ReadingMode.RIGHT_TO_LEFT, false, true),
            Triple(ReadingMode.DUAL_PAGE_R2L, false, true),
            Triple(ReadingMode.AUTO, true, true),
            Triple(ReadingMode.AUTO, false, false),
        )
        for ((mode, oldRequest, enabled) in cases) {
            openDatabase(temporaryFolder.newFile(), create = true).use { fixture ->
                seed(fixture.driver)
                session(fixture, readingMode = mode.flagValue).use { reader ->
                    prepareO5ActivityProbe(reader, enabled)
                    if (oldRequest) ReaderOpenLatencyProbe.startChapter(1, 2)
                    updateActualViewer(reader, 1800, 900)
                    if (mode == ReadingMode.RIGHT_TO_LEFT) {
                        assertTrue(reader.model.state.value.viewer is R2LPagerViewer)
                    } else {
                        assertTrue(reader.model.state.value.viewer is DualPageR2LPagerViewer)
                    }
                    assertTrue(ReaderOpenLatencyProbe.capturedEvents().none { it.event.startsWith("AUTO_") })
                }
            }
        }
    }

    private fun prepareO5ActivityProbe(reader: ReaderSession, enabled: Boolean = true): Long {
        ReaderOpenLatencyProbe.configure(enabled, 1, 2, "o5-activity-wiring", targetPage = 4)
        val request = ReaderOpenLatencyProbe.startChapter(1, 2)
        reader.activity.intent = Intent().putExtra("manga", 1L).putExtra("chapter", 2L)
        // The partial Activity fixture omits onCreate; retain its one captured diagnostic request.
        ReflectionHelpers.setField(reader.activity, "latencyRequest", request)
        return request
    }

    @Test
    fun `O4 init queries original chapters once for small and large lists`() = runBlocking {
        for (additional in listOf(0, 300)) {
            openDatabase(temporaryFolder.newFile("o4-query-$additional.db"), create = true).use { fixture ->
                seed(fixture.driver)
                fixture.driver.execute(null, "UPDATE mangas SET chapter_flags = ? WHERE _id = 1", 1) {
                    bindLong(0, Manga.CHAPTER_SORTING_ALPHABET)
                }
                fixture.driver.execute(null, "UPDATE chapters SET last_page_read = 2 WHERE _id = 2", 0)
                session(fixture, additionalChapters = additional).use { reader ->
                    val query = ReflectionHelpers.getField<GetChaptersByMangaId>(reader.model, "getChaptersByMangaId")
                    coVerify(exactly = 1) { query.await(1, applyScanlatorFilter = true) }
                    val cache = ReflectionHelpers.getField<List<ReaderChapter>>(reader.model, "chapterListCache")
                    assertEquals(additional + 1, cache.size)
                    assertEquals(2L, reader.model.state.value.currentChapter?.chapter?.id)
                    assertEquals("C", reader.model.state.value.currentChapter?.chapter?.name)
                    assertEquals("M", reader.model.state.value.manga?.title)
                    assertEquals(Manga.CHAPTER_SORTING_ALPHABET, reader.model.state.value.manga?.chapterFlags)
                    assertEquals(2, reader.model.state.value.currentChapter?.chapter?.last_page_read)
                    assertEquals(
                        "Atomic alphabetical sorting must override the preliminary source order",
                        2L,
                        cache.first().chapter.id,
                    )
                }
            }
        }
    }

    @Test
    fun `O4 SHOW_ALL init skips per chapter download lookup`() = runBlocking {
        for (additional in listOf(0, 300)) {
            openDatabase(temporaryFolder.newFile("o4-download-$additional.db"), create = true).use { fixture ->
                seed(fixture.driver)
                session(fixture, additionalChapters = additional).use { reader ->
                    assertEquals(Manga.SHOW_ALL, reader.model.state.value.manga?.downloadedFilterRaw)
                    val downloads = ReflectionHelpers.getField<DownloadManager>(reader.model, "downloadManager")
                    verify(exactly = 0) { downloads.isChapterDownloaded(any(), any(), any(), any(), any()) }
                    val cache = ReflectionHelpers.getField<List<ReaderChapter>>(reader.model, "chapterListCache")
                    assertEquals(additional + 1, cache.size)
                }
            }
        }
    }

    @Test
    fun `O4 download filters preserve downloaded and not downloaded selection independently of downloadedOnly`() =
        runBlocking {
            val filters = listOf(Manga.SHOW_ALL, Manga.CHAPTER_SHOW_DOWNLOADED, Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
            val cases = filters.flatMap { filter ->
                listOf(Triple(filter, false, false), Triple(filter, true, false), Triple(filter, true, true))
            } + Triple(Manga.SHOW_ALL, false, true)
            for ((filter, skipFiltered, downloadedOnly) in cases) {
                openDatabase(temporaryFolder.newFile(), create = true).use { fixture ->
                    seed(fixture.driver)
                    fixture.driver.execute(null, "UPDATE mangas SET chapter_flags = ? WHERE _id = 1", 1) {
                        bindLong(0, filter)
                    }
                    val downloads = mockk<DownloadManager>(relaxed = true)
                    every { downloads.isChapterDownloaded(any(), any(), any(), any(), any(), any()) } answers {
                        thirdArg<String>() != "/o4-extra-1"
                    }
                    val downloadCache = mockk<DownloadCache>(relaxed = true)
                    every { downloadCache.isChapterDownloaded(any(), any(), any(), any(), any(), any()) } answers {
                        thirdArg<String>() != "/o4-extra-1"
                    }
                    session(
                        fixture,
                        additionalChapters = 2,
                        downloadManager = downloads,
                        downloadCache = downloadCache,
                        downloadedOnly = downloadedOnly,
                        skipFiltered = skipFiltered,
                    ).use { reader ->
                        assertEquals(filter, reader.model.state.value.manga?.downloadedFilterRaw)
                        val expected = when {
                            !skipFiltered || filter == Manga.SHOW_ALL -> setOf(2L, 10L, 11L)
                            filter == Manga.CHAPTER_SHOW_DOWNLOADED -> setOf(2L, 10L)
                            else -> setOf(2L, 11L)
                        }.let { if (downloadedOnly) it - 11L else it }
                        val cache = ReflectionHelpers.getField<List<ReaderChapter>>(reader.model, "chapterListCache")
                        assertEquals(
                            "filter=$filter skip=$skipFiltered downloadedOnly=$downloadedOnly",
                            expected,
                            cache.map { requireNotNull(it.chapter.id) }.toSet(),
                        )
                        assertEquals(2L, reader.model.state.value.currentChapter?.chapter?.id)
                        if (skipFiltered && filter != Manga.SHOW_ALL) {
                            verify(atLeast = 1) {
                                downloads.isChapterDownloaded(any(), any(), any(), any(), any(), any())
                            }
                        } else {
                            verify(exactly = 0) {
                                downloads.isChapterDownloaded(any(), any(), any(), any(), any(), any())
                            }
                        }
                        if (downloadedOnly) {
                            verify(atLeast = 1) {
                                downloadCache.isChapterDownloaded(any(), any(), any(), any(), any(), any())
                            }
                        } else {
                            verify(exactly = 0) {
                                downloadCache.isChapterDownloaded(any(), any(), any(), any(), any(), any())
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `O3 accepted repository records expose empty valid and invalid presence without extra IO`() = runBlocking {
        for (kind in listOf("EMPTY", "VALID", "INVALID")) {
            assertO3RecordPresence(kind)
        }
    }

    @Test
    fun `O3 accepted restore cannot mark a newer probe request and disabled observation stays silent`() = runBlocking {
        assertO3RecordPresence("VALID", replaceProbeRequest = true)
        assertO3RecordPresence("VALID", enabled = false)
    }

    private suspend fun assertO3RecordPresence(
        kind: String,
        replaceProbeRequest: Boolean = false,
        enabled: Boolean = true,
    ) {
        val probe = ReaderOpenLatencyProbe
        probe.configure(enabled, 1, 2, "record-presence")
        probe.startChapter(1, 2)
        try {
            openDatabase(temporaryFolder.newFile(), create = true).use { fixture ->
                seed(fixture.driver)
                if (kind != "EMPTY") fixture.repository.replace(2, 1, 0, if (kind == "INVALID") 9 else 8, setOf(3))
                val expected = fixture.repository.load(2, 1)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val loads = AtomicInteger()
                val writes = AtomicInteger()
                val scheduler = TestCoroutineScheduler()
                val repository = object : ChapterPairingRepository by fixture.repository {
                    override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                        loads.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        return fixture.repository.load(chapterId, mangaId)
                    }

                    override suspend fun replaceLayout(
                        chapterId: Long,
                        mangaId: Long,
                        expectedRevision: Long,
                        pageCount: Int,
                        adjustment: mihon.domain.reader.ReaderPairingAdjustment,
                    ): ChapterPairingSnapshot {
                        writes.incrementAndGet()
                        return fixture.repository.replaceLayout(
                            chapterId,
                            mangaId,
                            expectedRevision,
                            pageCount,
                            adjustment,
                        )
                    }

                    override suspend fun replace(
                        chapterId: Long,
                        mangaId: Long,
                        expectedRevision: Long,
                        pageCount: Int,
                        forcedSinglePages: Set<Int>,
                    ): ChapterPairingSnapshot {
                        writes.incrementAndGet()
                        return fixture.repository.replace(
                            chapterId,
                            mangaId,
                            expectedRevision,
                            pageCount,
                            forcedSinglePages,
                        )
                    }
                }
                try {
                    session(
                        fixture,
                        repository,
                        readingMode = 1,
                        pairingCallbackDispatcher = StandardTestDispatcher(scheduler),
                    ).use { reader ->
                        try {
                            scheduler.runCurrent()
                            withTimeout(5_000) { entered.await() }
                            val chapter = requireNotNull(reader.model.state.value.currentChapter)
                            if (replaceProbeRequest) probe.startChapter(1, 2)
                            release.complete(Unit)
                            awaitPairingStatus(reader, chapter, ReaderPairingPersistenceStatus.READY, scheduler)
                            val markers = probe.capturedEvents().filter { it.event.startsWith("PAIRING_RECORD_") }
                            if (enabled && !replaceProbeRequest) {
                                assertEquals(listOf("PAIRING_RECORD_$kind"), markers.map { it.event })
                            } else {
                                assertTrue(markers.isEmpty())
                            }
                            assertEquals(1, loads.get())
                            assertEquals(0, writes.get())
                            assertEquals(expected, fixture.repository.load(2, 1))
                            assertTrue(reader.model.state.value.pairingRecordReady)
                        } finally {
                            release.complete(Unit)
                            scheduler.runCurrent()
                        }
                    }
                } finally {
                    release.complete(Unit)
                    scheduler.runCurrent()
                }
            }
        } finally {
            probe.configure(false, 1, 2, "off")
        }
    }

    @Test
    fun `all modes activate before held pairing read and retain content after failure`() = runBlocking {
        // Reuse the existing Windows JVM PNG fixture boundary: the native format probe is
        // unavailable, while BitmapFactory, the original holder and SSIV still draw real pixels.
        mockkObject(ImageUtil)
        every { ImageUtil.isAnimatedAndSupported(any()) } returns false
        try {
            for (mode in listOf(1, 4, 6)) {
                openDatabase(temporaryFolder.newFile("all-mode-held-$mode.db"), create = true).use { fixture ->
                    seed(fixture.driver)
                    val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val repository = object : ChapterPairingRepository by fixture.repository {
                        override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                            entered.complete(Unit)
                            release.await()
                            throw IOException("Original record unavailable")
                        }
                    }
                    var model: ReaderViewModel? = null
                    val opening =
                        async {
                            session(
                                fixture,
                                repository,
                                readingMode = mode,
                                imageBytes = opaquePairingPage(),
                                onModelCreated = { model = it },
                            )
                        }
                    var reader: ReaderSession? = null
                    try {
                        withTimeout(5_000) { entered.await() }
                        reader = withTimeoutOrNull(1_500) { opening.await() }
                        assertTrue("Mode $mode must activate without awaiting pairing I/O", reader != null)
                        val active = requireNotNull(reader)
                        val chapter = requireNotNull(active.model.state.value.currentChapter)
                        val window = active.model.state.value.viewerChapters
                        val owner = requireNotNull(active.model.dualPagePairings.chapters[chapter]).session
                        assertEquals(
                            mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING,
                            owner.pairingState.status,
                        )
                        assertNull(owner.pairingState.revision)
                        assertNull(active.model.state.value.dialog)
                        updateActualViewer(active)
                        if (mode == 1) paintActualSingle(active)
                        if (mode == 4) paintActualWebtoon(active)
                        release.complete(Unit)
                        awaitPairingStatus(
                            active,
                            chapter,
                            mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                        )
                        withTimeout(5_000) { while (!active.model.state.value.pairingReadUnavailable) yield() }
                        assertTrue(window === active.model.state.value.viewerChapters)
                        assertNull(active.model.state.value.dialog)
                        assertNull(owner.pairingState.revision)
                        assertFalse(active.model.state.value.pairingRecordReady)
                        assertEquals(saved, fixture.repository.load(2, 1))
                        if (mode == 1) paintActualSingle(active)
                        if (mode == 4) paintActualWebtoon(active)
                    } finally {
                        release.complete(Unit)
                        opening.cancelAndJoin()
                        reader?.close()
                        model?.viewModelScope?.cancel()
                    }
                }
            }
        } finally {
            unmockkObject(ImageUtil)
        }
    }

    @Test
    fun `all mode Settings reload retains disabled action and current owner through mode changes`() = runBlocking {
        for (mode in listOf(1, 4, 6)) {
            openDatabase(temporaryFolder.newFile("all-mode-settings-$mode.db"), create = true).use { fixture ->
                seed(fixture.driver)
                val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
                val loads = AtomicInteger()
                val last = AtomicReference<Long>()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val repository = object : ChapterPairingRepository by fixture.repository {
                    override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                        last.set(chapterId)
                        when (loads.incrementAndGet()) {
                            1 -> throw IOException("Initial record unavailable")
                            2 -> {
                                entered.complete(Unit)
                                release.await()
                                throw IOException("Reread unavailable")
                            }
                        }
                        return fixture.repository.load(chapterId, mangaId)
                    }
                }
                // Start from the already supported dual opening to isolate Settings mode wiring in RED.
                session(fixture, repository, readingMode = 6, reuseLoadedPages = true).use { reader ->
                    val chapter = requireNotNull(reader.model.state.value.currentChapter)
                    awaitPairingStatus(
                        reader,
                        chapter,
                        mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                    )
                    changeActualMode(reader, ReadingMode.fromPreference(mode))
                    val window = reader.model.state.value.viewerChapters
                    val owner = requireNotNull(reader.model.dualPagePairings.chapters[chapter]).session
                    openPairingSettings(reader)
                    val label = reader.host.stringResource(MR.strings.action_reload_page_pairing)
                    try {
                        invokeSettingsReload(reader, loads, last)
                        withTimeout(5_000) { entered.await() }
                        compose.onNodeWithText(label).assertIsNotEnabled()
                        val before = loads.get()
                        val selected = requireNotNull(chapter.pages)[3]
                        reader.model.state.value.viewer!!.moveToPage(selected)
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                        assertTrue(selected === reader.model.currentReaderPage)
                        reader.model.retryPairingRestore()
                        reader.model.retryPairingRestore()
                        changeActualMode(reader, if (mode == 4) ReadingMode.RIGHT_TO_LEFT else ReadingMode.WEBTOON)
                        reader.model.closeDialog()
                        openPairingSettings(reader)
                        compose.onNodeWithText(label).assertIsNotEnabled()
                        assertEquals(before, loads.get())
                        assertTrue(window === reader.model.state.value.viewerChapters)
                        assertTrue(owner === reader.model.dualPagePairings.chapters[chapter]?.session)
                        release.complete(Unit)
                        awaitPairingStatus(
                            reader,
                            chapter,
                            mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                        )
                        compose.waitUntil(5_000) { reader.model.state.value.pairingReadUnavailable }
                        compose.onNodeWithText(label).assertIsEnabled()
                        invokeSettingsReload(reader, loads, last)
                        awaitPairingStatus(
                            reader,
                            chapter,
                            mihon.domain.reader.ReaderPairingPersistenceStatus.READY,
                        )
                        compose.waitUntil(5_000) { !reader.model.state.value.pairingReadUnavailable }
                        compose.onNodeWithText(label).assertDoesNotExist()
                        assertTrue(window === reader.model.state.value.viewerChapters)
                        assertTrue(owner === reader.model.dualPagePairings.chapters[chapter]?.session)
                        assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.Settings)
                        reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                        assertTrue(
                            "Metadata success must retain the source selected during reread",
                            selected === reader.model.currentReaderPage,
                        )
                        assertEquals(saved, fixture.repository.load(2, 1))
                    } finally {
                        release.complete(Unit)
                    }
                }
            }
        }
    }

    @Test
    fun `rejected restore releases pending operation when a retained chapter leaves the window`() = runBlocking {
        ReaderOpenLatencyProbe.configure(true, 1, 2, "rejected-restore")
        ReaderOpenLatencyProbe.startChapter(1, 2)
        openDatabase(temporaryFolder.newFile("retained-outside-window.db"), create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            fixture.driver.execute(
                null,
                "INSERT INTO chapters(_id,manga_id,url,name,read,bookmark,last_page_read," +
                    "chapter_number,source_order,date_fetch,date_upload) " +
                    "VALUES(4,1,'/chapter-4','C4',0,0,0,3,0,0,0)",
                0,
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            val scheduler = TestCoroutineScheduler()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (chapterId == 2L && reads.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                includeFarAdjacent = true,
                reuseLoadedPages = true,
                readingMode = 1,
                pairingCallbackDispatcher = StandardTestDispatcher(scheduler),
            ).use { reader ->
                scheduler.runCurrent()
                withTimeout(5_000) { entered.await() }
                val original = requireNotNull(reader.model.state.value.currentChapter)
                val pages = requireNotNull(original.pages)
                updateActualViewer(reader)
                val owner = requireNotNull(reader.model.dualPagePairings.chapters[original]).session
                // A legitimate in-flight holder keeps the original pages alive outside the window.
                original.ref()
                try {
                    reader.model.loadNextChapter()
                    reader.model.loadNextChapter()
                    assertEquals(4L, reader.model.state.value.currentChapter?.chapter?.id)
                    val window = reader.model.state.value.viewerChapters
                    reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                    reader.model.state.value.viewer!!.moveToPage(requireNotNull(window?.currChapter?.pages)[1])
                    val position = reader.model.currentReaderPage
                    assertTrue(pages === original.pages)
                    assertFalse("The retained original owner must still be live", owner.isClosed)
                    assertTrue(owner === reader.model.dualPagePairings.chapters[original]?.session)
                    release.complete(Unit)
                    Injekt.get<AndroidChapterPairingCoordinator>().awaitAcceptedOperations()
                    assertTrue(
                        "Rejecting an out-of-window result must release its original pending Load",
                        withTimeoutOrNull(1_000) {
                            while (owner.pairingState.status ==
                                mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING
                            ) {
                                scheduler.runCurrent()
                                yield()
                            }
                            true
                        } == true,
                    )
                    assertTrue(window === reader.model.state.value.viewerChapters)
                    assertTrue(position === reader.model.currentReaderPage)
                    assertTrue(ReaderOpenLatencyProbe.capturedEvents().none { it.event.startsWith("PAIRING_RECORD_") })
                    reader.model.loadPreviousChapter()
                    reader.model.loadPreviousChapter()
                    assertTrue(original === reader.model.state.value.currentChapter)
                    withTimeout(5_000) {
                        while (owner.pairingState.status != mihon.domain.reader.ReaderPairingPersistenceStatus.READY) {
                            scheduler.runCurrent()
                            yield()
                        }
                    }
                    assertEquals(2, reads.get())
                    assertTrue(owner === reader.model.dualPagePairings.chapters[original]?.session)
                } finally {
                    release.complete(Unit)
                    original.unref()
                }
            }
        }
    }

    @Test
    fun `Settings reload ignores replaced pages and old chapter callbacks`() = runBlocking {
        openDatabase(temporaryFolder.newFile("settings-stale-content.db"), create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val saved = fixture.repository.replace(2, 1, 0, 8, setOf(3))
            val loads = AtomicInteger()
            val last = AtomicReference<Long>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    last.set(chapterId)
                    if (chapterId == 2L) {
                        when (loads.incrementAndGet()) {
                            1 -> throw IOException("A failed")
                            2 -> {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }
                    return fixture.repository.load(chapterId, mangaId)
                }
            }
            session(
                fixture,
                repository,
                readingMode = 1,
                includeAdjacent = true,
                reuseLoadedPages = true,
            ).use { reader ->
                val original = requireNotNull(reader.model.state.value.currentChapter)
                awaitPairingStatus(reader, original, mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED)
                updateActualViewer(reader)
                openPairingSettings(reader)
                try {
                    invokeSettingsReload(reader, loads, last)
                    withTimeout(5_000) { entered.await() }
                    val oldOwner = requireNotNull(reader.model.dualPagePairings.chapters[original]).session
                    val pages = requireNotNull(original.pages).map { old ->
                        ReaderPage(old.index).apply {
                            chapter = original
                            stream = old.stream
                            status = Page.State.Ready
                            publishHeaderFact()
                        }
                    }
                    original.publishLoadedPageListForTest(pages)
                    val replacement = reader.model.dualPagePairings.ensure(original, pages).session
                    assertTrue(oldOwner.isClosed)
                    assertTrue(replacement !== oldOwner)
                    reader.activity.handleReaderEvent(ReaderViewModel.Event.ReloadViewerChapters)
                    compose.waitUntil(5_000) { !reader.model.state.value.pairingReadUnavailable }
                    compose.onNodeWithText(reader.host.stringResource(MR.strings.action_reload_page_pairing))
                        .assertDoesNotExist()
                    reader.model.loadNextChapter()
                    withTimeout(5_000) { while (reader.model.state.value.currentChapter === original) yield() }
                    val current = requireNotNull(reader.model.state.value.currentChapter)
                    awaitPairingStatus(reader, current, mihon.domain.reader.ReaderPairingPersistenceStatus.READY)
                    val window = reader.model.state.value.viewerChapters
                    release.complete(Unit)
                    withTimeout(5_000) {
                        while (!entered.isCompleted ||
                            reader.model.state.value.pairingRestoring
                        ) {
                            yield()
                        }
                    }
                    // Joining the accepted coordinator work makes the rejected original result observable.
                    Injekt.get<AndroidChapterPairingCoordinator>().awaitAcceptedOperations()
                    assertTrue(window === reader.model.state.value.viewerChapters)
                    assertTrue(current === reader.model.state.value.currentChapter)
                    assertNull(replacement.pairingState.revision)
                    assertFalse(reader.model.state.value.pairingReadUnavailable)
                    compose.onNodeWithText(
                        reader.host.stringResource(MR.strings.action_reload_page_pairing),
                    ).assertDoesNotExist()
                    assertEquals(saved, fixture.repository.load(2, 1))
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `accepted save updates replacement viewer after Activity recreation`() = runBlocking {
        val file = temporaryFolder.newFile("replace-viewer.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replaceLayout(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    adjustment: mihon.domain.reader.ReaderPairingAdjustment,
                ): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return fixture.repository.replaceLayout(
                        chapterId,
                        mangaId,
                        expectedRevision,
                        pageCount,
                        adjustment,
                    )
                }
            }
            session(fixture, delayed).use { reader ->
                val pages = requireNotNull(reader.model.state.value.currentChapter?.pages)
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(pages[3])
                reader.host.setContent {
                    val state by reader.model.state.collectAsState()
                    MaterialTheme { reader.activity.AppBars(state.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                entered.await()
                try {
                    compose.onNodeWithContentDescription(
                        reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                    ).assertIsNotEnabled().assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            reader.host.stringResource(MR.strings.desktop_reader_pairing_saving),
                        ),
                    )
                } catch (error: Throwable) {
                    release.complete(Unit)
                    throw error
                }
                val replacement = DualPageR2LPagerViewer(reader.activity, reader.model.dualPagePairings)
                try {
                    replacement.config.navigationModeChangedListener = null
                    reader.model.onViewerLoaded(replacement)
                    reader.model.onViewerUnloaded(reader.viewer)
                    assertTrue(reader.model.state.value.viewer === replacement)
                    replacement.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                    replacement.moveToPage(pages[4])
                    assertEquals(setOf(3, 4), visiblePair(replacement))
                    reader.viewer.destroy()
                    release.complete(Unit)
                    withTimeout(5_000) {
                        while (reader.model.state.value.pairingSaving) yield()
                    }
                    assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
                    assertTrue(visiblePair(replacement).contains(4))
                    assertEquals(
                        4,
                        replacement.adapter.session(
                            requireNotNull(
                                reader.model.state.value.currentChapter,
                            ),
                        )?.location?.pageId?.sourcePageIndex,
                    )
                    replacement.moveToPage(pages[3])
                    assertEquals(setOf(3), visiblePair(replacement))
                } finally {
                    release.complete(Unit)
                    replacement.destroy()
                }
            }
        }
    }

    @Test
    fun `accepted save survives old ViewModel cancellation and precedes new ViewModel read`() = runBlocking {
        val file = temporaryFolder.newFile("new-view-model.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loadedNewPages = CompletableDeferred<Unit>()
            val prematureRead = CompletableDeferred<Unit>()
            val delayed = object : ChapterPairingRepository by fixture.repository {
                override suspend fun load(chapterId: Long, mangaId: Long): ChapterPairingSnapshot {
                    if (entered.isCompleted && !release.isCompleted) prematureRead.complete(Unit)
                    return fixture.repository.load(chapterId, mangaId)
                }

                override suspend fun replaceLayout(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    adjustment: mihon.domain.reader.ReaderPairingAdjustment,
                ): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    return fixture.repository.replaceLayout(
                        chapterId,
                        mangaId,
                        expectedRevision,
                        pageCount,
                        adjustment,
                    )
                }
            }
            val coordinator = AndroidChapterPairingCoordinator(delayed)
            session(fixture, delayed, coordinator = coordinator).use { old ->
                val pages = requireNotNull(old.model.state.value.currentChapter?.pages)
                old.viewer.setChapters(requireNotNull(old.model.state.value.viewerChapters))
                old.viewer.moveToPage(pages[3])
                old.host.setContent {
                    MaterialTheme { old.activity.AppBars(old.model.state.value.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    old.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                entered.await()
                old.model.viewModelScope.cancel()
                val opening = async {
                    session(fixture, delayed, coordinator = coordinator, onPageListLoaded = {
                        loadedNewPages.complete(Unit)
                    })
                }
                try {
                    loadedNewPages.await()
                    assertNull(withTimeoutOrNull(500) { prematureRead.await() })
                    assertFalse(opening.isCompleted)
                    release.complete(Unit)
                    withTimeout(5_000) { opening.await() }.use { fresh ->
                        fresh.viewer.setChapters(requireNotNull(fresh.model.state.value.viewerChapters))
                        assertTrue(visiblePair(fresh.viewer).contains(5))
                        val pages = requireNotNull(fresh.model.state.value.currentChapter?.pages)
                        fresh.viewer.moveToPage(pages[3])
                        assertEquals(setOf(3), visiblePair(fresh.viewer))
                    }
                    assertEquals(setOf(3), fixture.repository.load(2, 1).record?.forcedSinglePages)
                } finally {
                    release.complete(Unit)
                }
            }
        }
    }

    @Test
    fun `late failed save from A cannot notify after A to B to A with reused page objects`() = runBlocking {
        val file = temporaryFolder.newFile("chapter-return.db")
        openDatabase(file, create = true).use { fixture ->
            seed(fixture.driver)
            seedAdjacent(fixture.driver)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scheduler = TestCoroutineScheduler()
            val failing = object : ChapterPairingRepository by fixture.repository {
                override suspend fun replaceLayout(
                    chapterId: Long,
                    mangaId: Long,
                    expectedRevision: Long,
                    pageCount: Int,
                    adjustment: mihon.domain.reader.ReaderPairingAdjustment,
                ): ChapterPairingSnapshot {
                    entered.complete(Unit)
                    release.await()
                    throw IOException("accepted A save failed")
                }
            }
            session(
                fixture,
                failing,
                includeAdjacent = true,
                reuseLoadedPages = true,
                pairingCallbackDispatcher = StandardTestDispatcher(scheduler),
            ).use { reader ->
                val originalA = requireNotNull(reader.model.state.value.currentChapter)
                val originalPages = requireNotNull(originalA.pages)
                withTimeout(5_000) {
                    while (reader.model.dualPagePairings.chapters[originalA]?.session?.pairingState?.status !=
                        mihon.domain.reader.ReaderPairingPersistenceStatus.READY
                    ) {
                        scheduler.runCurrent()
                        yield()
                    }
                }
                reader.viewer.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
                reader.viewer.moveToPage(originalPages[3])
                reader.host.setContent {
                    MaterialTheme { reader.activity.AppBars(reader.model.state.value.copy(menuVisible = true)) }
                }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithContentDescription(
                    reader.host.stringResource(MR.strings.action_adjust_page_pairing),
                ).performClick()
                withTimeout(5_000) { entered.await() }
                assertEquals(3L, reader.model.state.value.viewerChapters?.nextChapter?.chapter?.id)
                reader.model.loadNextChapter()
                assertEquals(
                    reader.model.state.value.chapterWindow.toString(),
                    3L,
                    reader.model.state.value.currentChapter?.chapter?.id,
                )
                assertFalse(reader.model.state.value.pairingSaving)
                val returnToA = async { reader.model.loadPreviousChapter() }
                release.complete(Unit)
                withTimeout(5_000) { returnToA.await() }
                assertTrue(reader.model.state.value.currentChapter === originalA)
                assertTrue(reader.model.state.value.currentChapter?.pages === originalPages)
                val staleFailure = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(500) {
                        reader.model.eventFlow.first { it == ReaderViewModel.Event.ChapterPairingSaveFailed }
                    }
                }
                scheduler.runCurrent()
                assertFalse(reader.model.state.value.pairingSaving)
                assertNull(staleFailure.await())
                assertNull(fixture.repository.load(2, 1).record)
            }
        }
    }

    private suspend fun session(
        database: DatabaseFixture,
        repository: ChapterPairingRepository = database.repository,
        includeAdjacent: Boolean = false,
        includeFarAdjacent: Boolean = false,
        additionalChapters: Int = 0,
        reuseLoadedPages: Boolean = false,
        readingMode: Int = 0,
        pendingPage: Int? = null,
        imageBytes: ByteArray? = null,
        matching: Boolean = false,
        downloadManager: DownloadManager = mockk(relaxed = true),
        downloadCache: DownloadCache = mockk(relaxed = true),
        downloadedOnly: Boolean = false,
        skipFiltered: Boolean = true,
        pairingCallbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        coordinator: AndroidChapterPairingCoordinator = AndroidChapterPairingCoordinator(repository),
        onPageListLoaded: () -> Unit = {},
        onModelCreated: (ReaderViewModel) -> Unit = {},
    ): ReaderSession {
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("reader_auto_spread_matching", matching, false),
                InMemoryPreferenceStore.InMemoryPreference("skip_filtered", skipFiltered, true),
            ),
        )
        val readerPreferences = ReaderPreferences(preferences)
        Injekt.addSingleton<ChapterPairingRepository>(repository)
        Injekt.addSingleton(coordinator)
        Injekt.addSingleton(readerPreferences)
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(BasePreferences(RuntimeEnvironment.getApplication() as Application, preferences))
        Injekt.addSingleton(downloadManager)
        Injekt.addSingleton(downloadCache)

        // Atomic production opening reads viewer flags from SQLite, not the preliminary GetManga mock.
        database.driver.execute(null, "UPDATE mangas SET viewer = ? WHERE _id = 1", 1) {
            bindLong(0, readingMode.toLong())
        }
        val manga = Manga.create().copy(
            id = 1,
            source = 7,
            url = "/",
            title = "Pairing manga",
            viewerFlags = readingMode.toLong(),
        )
        val chapter = Chapter.create().copy(
            id = 2,
            mangaId = 1,
            url = "/chapter",
            name = "Chapter 2",
            lastPageRead = 5,
            sourceOrder = 2,
        )
        val adjacent = Chapter.create().copy(
            id = 3,
            mangaId = 1,
            url = "/chapter-3",
            name = "Chapter 3",
            sourceOrder = 1,
        )
        val source = mockk<Source>()
        val sourceManager = mockk<SourceManager> {
            every { isInitialized } returns MutableStateFlow(true)
            every { getOrStub(7) } returns source
        }
        Injekt.addSingleton(sourceManager)
        var configuredManga = manga
        val getManga = mockk<GetManga>()
        coEvery { getManga.await(1) } coAnswers { configuredManga }
        val viewerFlags = mockk<SetMangaViewerFlags>(relaxed = true)
        coEvery { viewerFlags.awaitSetReadingMode(1, any()) } coAnswers {
            configuredManga = configuredManga.copy(viewerFlags = secondArg())
        }
        val getChapters = mockk<GetChaptersByMangaId>()
        coEvery { getChapters.await(1, applyScanlatorFilter = true) } returns
            (
                when {
                    includeFarAdjacent -> listOf(
                        chapter,
                        adjacent,
                        adjacent.copy(id = 4, url = "/chapter-4", sourceOrder = 0),
                    )
                    includeAdjacent -> listOf(chapter, adjacent)
                    else -> listOf(chapter)
                } + List(additionalChapters) { index ->
                    chapter.copy(
                        id = 10L + index,
                        url = "/o4-extra-$index",
                        name = "Extra $index",
                        sourceOrder = 10L + index,
                    )
                }
                )
        val loader = mockk<ChapterLoader>()

        // These persistence scenarios use legal synthetic portrait content, independently of the
        // real B1/B2 dimension fixtures; Ready by itself never supplies a portrait fact.
        fun loadedPages(current: ReaderChapter): List<ReaderPage> {
            val encoded = imageBytes ?: readerTestPng(40, 60)
            return List(8) { index ->
                ReaderPage(index).apply {
                    this.chapter = current
                    stream = { encoded.inputStream() }
                    status = if (index == pendingPage) Page.State.Queue else Page.State.Ready
                    if (index != pendingPage) publishHeaderFact()
                }
            }
        }
        coEvery { loader.loadChapter(any()) } coAnswers {
            firstArg<ReaderChapter>().let { current ->
                if (!reuseLoadedPages || current.pages == null) {
                    current.publishLoadedPageListForTest(
                        loadedPages(current),
                    )
                    onPageListLoaded()
                }
            }
        }
        coEvery { loader.loadChapter(any(), any()) } coAnswers {
            firstArg<ReaderChapter>().let { current ->
                if (!reuseLoadedPages || current.pages == null) {
                    current.publishLoadedPageListForTest(
                        loadedPages(current),
                    )
                    onPageListLoaded()
                }
            }
        }
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        every { downloadPreferences.autoDownloadWhileReading().get() } returns 0
        every { downloadPreferences.removeAfterReadSlots().get() } returns -1
        val basePreferences = mockk<BasePreferences>(relaxed = true)
        every { basePreferences.downloadedOnly().get() } returns downloadedOnly
        val incognito = mockk<GetIncognitoState>()
        every { incognito.await(any()) } returns false
        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.markDuplicateReadChapterAsRead().get() } returns emptySet()
        val trackPreferences = mockk<TrackPreferences>(relaxed = true)
        every { trackPreferences.autoUpdateTrack().get() } returns false
        val model = ReaderViewModel(
            savedState = SavedStateHandle(),
            sourceManager = sourceManager,
            downloadManager = downloadManager,
            downloadProvider = mockk(relaxed = true),
            imageSaver = mockk(relaxed = true),
            readerPreferences = readerPreferences,
            basePreferences = basePreferences,
            downloadPreferences = downloadPreferences,
            trackPreferences = trackPreferences,
            trackChapter = mockk(relaxed = true),
            getManga = getManga,
            getChaptersByMangaId = getChapters,
            getNextChapters = mockk(relaxed = true),
            upsertHistory = mockk(relaxed = true),
            updateChapter = mockk(relaxed = true),
            recordReadingProgress = RecordReadingProgress(SqlDelightReadingProgressRepository(database.database)),
            setMangaViewerFlags = viewerFlags,
            getIncognitoState = incognito,
            progressCoordinator = eu.kanade.tachiyomi.ui.reader.emptyReaderProgressCoordinator(),
            libraryPreferences = libraryPreferences,
            pairingCallbackDispatcher = pairingCallbackDispatcher,
            chapterLoaderFactory = { _: Manga, _: Source -> loader },
        )
        onModelCreated(model)
        val initialized = model.init(1, 2)
        assertTrue(initialized.exceptionOrNull()?.stackTraceToString(), initialized.isSuccess)
        val activity = Robolectric.buildActivity(ReaderActivity::class.java).get()
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        ReflectionHelpers.setField(activity, "viewModel\$delegate", lazyOf(model))
        val viewer = DualPageR2LPagerViewer(activity, model.dualPagePairings)
        viewer.config.navigationModeChangedListener = null
        model.onViewerLoaded(viewer)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get()
        return ReaderSession(
            model,
            activity,
            viewer,
            host,
            (pairingCallbackDispatcher as? kotlinx.coroutines.test.TestDispatcher)?.scheduler
                ?: (pairingCallbackDispatcher as? HeldRecoveryDispatcher)?.scheduler,
        )
    }

    private fun updateActualViewer(reader: ReaderSession, width: Int = 900, height: Int = 1600) {
        if (ReflectionHelpers.getField<Any?>(reader.activity, "adaptiveViewport") == null) {
            reader.activity.binding = ReaderActivityBinding.inflate(reader.activity.layoutInflater)
        }
        reader.activity.binding.viewerContainer.layout(0, 0, width, height)
        ReflectionHelpers.callInstanceMethod<Unit>(reader.activity, "updateViewer")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }

    private suspend fun changeActualMode(reader: ReaderSession, mode: ReadingMode) {
        reader.model.setMangaReadingMode(mode)
        withTimeout(5_000) { while (reader.model.getMangaReadingMode() != mode.flagValue) yield() }
        updateActualViewer(reader)
    }

    private fun opaquePairingPage(): ByteArray {
        val pixels = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        try {
            pixels.eraseColor(Color.MAGENTA)
            return ByteArrayOutputStream().use {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            pixels.recycle()
        }
    }

    /** Observe actual SSIV draw commands and their decoded original content, not Ready/preparation. */
    private fun assertPairingBitmapDrawn(image: ReaderPageImageView) {
        val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val drawn = mutableListOf<Bitmap>()
        try {
            image.draw(object : Canvas(output) {
                override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
                    drawn += bitmap
                    super.drawBitmap(bitmap, matrix, paint)
                }
            })
            assertTrue(
                "The actual holder must draw its opaque magenta original bitmap",
                drawn.any {
                    !it.isRecycled && it.width == 40 && it.height == 60 && it.getPixel(20, 30) == Color.MAGENTA
                },
            )
        } finally {
            output.recycle()
        }
    }

    private suspend fun awaitBoundNativeImage(image: ReaderPageImageView, holder: Any, encoded: ByteArray) {
        fun native() = ReflectionHelpers.getField<Any?>(image, "pageView")
        fun error() = ReflectionHelpers.getField<ReaderErrorBinding?>(holder, "errorLayout")
        withTimeoutOrNull(5_000) {
            while (native() == null && error() == null) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                kotlinx.coroutines.delay(5)
            }
        }
        val dimensions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, dimensions)
        assertTrue(
            "Actual image must bind its native child; bytes=${encoded.size} " +
                "magic=${encoded.take(8).map { it.toInt() and 255 }} " +
                "dimensions=${dimensions.outWidth}x${dimensions.outHeight} " +
                "error=${error()?.errorMessage?.text?.take(220)}",
            native() != null,
        )
    }

    private fun layoutBoundImage(image: ReaderPageImageView) {
        image.requestLayout()
        image.measure(
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        image.layout(0, 0, 900, 1600)
        val native = ReflectionHelpers.getField<View>(image, "pageView")
        assertTrue("The original bound native child must be laid out", native.width > 0 && native.height > 0)
    }

    private suspend fun paintActualSingle(reader: ReaderSession) {
        val single = reader.model.state.value.viewer as PagerViewer
        val chapter = requireNotNull(reader.model.state.value.currentChapter)
        val page = requireNotNull(chapter.pages)[1]
        (single.pager.parent as? android.view.ViewGroup)?.removeView(single.pager)
        reader.host.setContentView(single.pager)
        single.setChapters(requireNotNull(reader.model.state.value.viewerChapters))
        single.config.navigationModeChangedListener = null
        single.moveToPage(page)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        single.pager.measure(
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        single.pager.layout(0, 0, 900, 1600)
        val holder = single.pager.children.filterIsInstance<PagerPageHolder>().first { it.page === page }
        val bind = PagerPageHolder::class.java.declaredMethods.single {
            it.name == "setImage" && it.parameterCount == 1
        }.apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            if (bind.invoke(holder, continuation) === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }
        awaitBoundNativeImage(holder, holder, requireNotNull(page.stream).invoke().use { it.readBytes() })
        layoutBoundImage(holder)
        paintReaderImage(holder, requireNotNull(page.stream).invoke().use { it.readBytes() })
        assertPairingBitmapDrawn(holder)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue("The actual single holder must have rendered its image", holder.hasRenderedPage())
    }

    private suspend fun paintActualWebtoon(reader: ReaderSession) {
        val viewer = reader.model.state.value.viewer as WebtoonViewer
        val page = requireNotNull(reader.model.state.value.currentChapter?.pages)[1]
        viewer.moveToPage(page)
        assertTrue(page === reader.model.currentReaderPage)
        val image = ReaderPageImageView(reader.activity)
        val holder = WebtoonPageHolder(image, viewer)
        try {
            reader.host.setContentView(image)
            holder.bind(page)
            val bind = WebtoonPageHolder::class.java.declaredMethods.single {
                it.name == "setImage" && it.parameterCount == 1
            }.apply { isAccessible = true }
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                if (bind.invoke(holder, continuation) === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
            image.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            )
            image.layout(0, 0, 900, 1600)
            awaitBoundNativeImage(image, holder, requireNotNull(page.stream).invoke().use { it.readBytes() })
            layoutBoundImage(image)
            paintReaderImage(image, requireNotNull(page.stream).invoke().use { it.readBytes() })
            assertPairingBitmapDrawn(image)
        } finally {
            holder.recycle()
        }
    }

    private suspend fun navigateRecoveryChapter(reader: ReaderSession, forward: Boolean) {
        val window = requireNotNull(reader.model.state.value.viewerChapters)
        val expected = requireNotNull(if (forward) window.nextChapter else window.prevChapter)
        if (forward) reader.model.loadNextChapter() else reader.model.loadPreviousChapter()
        assertTrue(
            "Actual VM navigation must activate chapter ${expected.chapter.id}",
            expected === reader.model.state.value.currentChapter,
        )
        updateActualViewer(reader)
        assertTrue(
            "The actual Single viewer must retain chapter ${expected.chapter.id}",
            expected === reader.model.state.value.currentChapter,
        )
    }

    private fun openPairingSettings(reader: ReaderSession, expectRecovery: Boolean = true) {
        reader.host.setContent {
            val state by reader.model.state.collectAsState()
            MaterialTheme {
                reader.activity.AppBars(state.copy(menuVisible = true))
                if (state.dialog is ReaderViewModel.Dialog.Settings) {
                    reader.activity.ReaderSettingsControls(reader.settingsScreenModel)
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val settingsLabel = reader.host.stringResource(MR.strings.action_settings)
        val matchingSettings = compose.onAllNodesWithContentDescription(settingsLabel).fetchSemanticsNodes()
        assertEquals(
            "Actual AppBars Settings must be unique in mode ${reader.model.getMangaReadingMode()}; " +
                "bounds=${matchingSettings.map { it.boundsInRoot }} dialog=${reader.model.state.value.dialog}",
            1,
            matchingSettings.size,
        )
        val settings = compose.onNodeWithContentDescription(settingsLabel)
        settings.assertIsDisplayed()
        val settingsNode = settings.fetchSemanticsNode()
        assertFalse("Actual Settings action must have visible bounds", settingsNode.boundsInRoot.isEmpty)
        val settingsAction = settingsNode.config[SemanticsActions.OnClick].action
        assertTrue(
            "Actual AppBars Settings action must open the production dialog",
            requireNotNull(settingsAction).invoke(),
        )
        assertTrue(reader.model.state.value.dialog is ReaderViewModel.Dialog.Settings)
        compose.onAllNodesWithText(
            reader.host.stringResource(MR.strings.action_reload_page_pairing),
        ).assertCountEquals(if (expectRecovery) 1 else 0)
        compose.onNodeWithText(reader.host.stringResource(MR.strings.desktop_reader_pairing_load_failed))
            .assertDoesNotExist()
        compose.onNodeWithText(reader.host.stringResource(MR.strings.desktop_reader_pairing_use_default))
            .assertDoesNotExist()
        compose.onNodeWithText(reader.host.stringResource(MR.strings.action_retry)).assertDoesNotExist()
        val adjust = compose.onNodeWithContentDescription(
            reader.host.stringResource(MR.strings.action_adjust_page_pairing),
        )
        if (reader.model.state.value.viewer is DualPageR2LPagerViewer) {
            adjust.assertIsNotEnabled()
        } else {
            adjust.assertDoesNotExist()
        }
    }

    private fun clickSettingsReload(reader: ReaderSession) {
        val label = reader.host.stringResource(MR.strings.action_reload_page_pairing)
        var action: (() -> Boolean)? = null
        compose.waitUntil(5_000) {
            action = compose.onAllNodesWithText(label).fetchSemanticsNodes().firstNotNullOfOrNull { node ->
                if (node.config.contains(
                        SemanticsActions.OnClick,
                    )
                ) {
                    node.config[SemanticsActions.OnClick].action
                } else {
                    null
                }
            }
            action != null
        }
        assertTrue(requireNotNull(action).invoke())
    }

    private suspend fun invokeSettingsReload(
        reader: ReaderSession,
        loads: AtomicInteger,
        lastChapter: AtomicReference<Long>,
    ) {
        val expectedChapter = requireNotNull(reader.model.state.value.currentChapter?.chapter?.id)
        val before = loads.get()
        clickSettingsReload(reader)
        assertTrue(
            "Actual Settings Reload must reread current chapter $expectedChapter; loads stayed at $before",
            withTimeoutOrNull(5_000) {
                while (loads.get() <= before) yield()
                true
            } == true,
        )
        assertEquals(expectedChapter, lastChapter.get())
    }

    private suspend fun awaitPairingStatus(
        reader: ReaderSession,
        chapter: ReaderChapter,
        status: mihon.domain.reader.ReaderPairingPersistenceStatus,
        scheduler: TestCoroutineScheduler? = null,
    ) {
        withTimeout(5_000) {
            scheduler?.runCurrent()
            while (reader.model.dualPagePairings.chapters[chapter]?.session?.pairingState?.status != status) {
                scheduler?.runCurrent()
                yield()
            }
        }
    }

    private fun visiblePair(viewer: DualPageR2LPagerViewer): Set<Int> =
        (viewer.adapter.items[viewer.pager.currentItem] as DisplayPage).visiblePages.map(ReaderPage::index).toSet()

    private fun openDatabase(file: File, create: Boolean = false): DatabaseFixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        if (create) Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        return DatabaseFixture(driver, database, ChapterPairingRepositoryImpl(handler), handler)
    }

    private suspend fun receivePairing(
        handler: tachiyomi.data.DatabaseHandler,
        event: mihon.domain.sync.SyncEventEnvelope,
    ) {
        val journal = mihon.data.sync.journal.SyncLocalJournal(handler)
        journal.connect(
            "layout-space",
            1,
            mihon.domain.sync.transport.SyncRepository("owner", "sync", "sync"),
            "receiver",
            1,
        )
        val bootstrap = tachiyomi.data.creator.CreatorArchiveLegacyBootstrap(
            tachiyomi.data.creator.CreatorArchiveLegacyBridge(handler),
        )
        val creators = tachiyomi.data.creator.CreatorRepositoryImpl(handler, bootstrap = bootstrap)
        val writer = mihon.data.sync.projection.SyncRemoteProjectionWriter(
            handler,
            creators,
            creators,
            bootstrap,
            { true },
        )
        val batch = mihon.domain.sync.SyncBatch(1, "layout-space", 1, requireNotNull(event.batchId), listOf(event))
        assertTrue(mihon.data.sync.inbox.SyncInboxStore(handler).ingest(batch).accepted)
        assertEquals(1, mihon.data.sync.inbox.SyncInboxProjector(handler, writer).project("layout-space", 1))
        assertTrue(journal.pendingEvents("layout-space", 1).isEmpty())
    }

    private fun seed(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) VALUES (1, 7, '/', 'M', 0, 0, 0, 0, 0, 0, 0)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (2, 1, '/chapter', 'C', 0, 0, 5, 1, 2, 0, 0)",
            0,
        )
    }

    private fun seedAdjacent(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, read, bookmark, last_page_read, " +
                "chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES (3, 1, '/chapter-3', 'C3', 0, 0, 0, 2, 1, 0, 0)",
            0,
        )
    }

    private data class DatabaseFixture(
        val driver: JdbcSqliteDriver,
        val database: Database,
        val repository: ChapterPairingRepository,
        val handler: AndroidDatabaseHandler,
    ) : AutoCloseable {
        override fun close() = driver.close()
    }

    private class HeldRecoveryDispatcher(val scheduler: TestCoroutineScheduler) : CoroutineDispatcher() {
        private val delegate = StandardTestDispatcher(scheduler)
        private val held = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()

        @Volatile var heldJob: Job? = null
        val hasHeldCallback: Boolean get() = held.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (context[Job] === heldJob) held.add(context to block) else delegate.dispatch(context, block)
        }

        fun releaseHeld() {
            heldJob = null
            while (true) {
                val (context, block) = held.poll() ?: return
                delegate.dispatch(context, block)
            }
        }
    }

    private data class ReaderSession(
        val model: ReaderViewModel,
        val activity: ReaderActivity,
        val viewer: DualPageR2LPagerViewer,
        val host: ComponentActivity,
        val callbackScheduler: TestCoroutineScheduler?,
    ) : AutoCloseable {
        private val settingsHost = ScreenModelTestHost()
        val settingsScreenModel by lazy {
            settingsHost.create {
                ReaderSettingsScreenModel(model.state, model::setMangaReadingMode, model::setMangaOrientationType)
            }
        }

        override fun close() {
            host.setContent { }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            settingsHost.close()
            model.state.value.viewer?.takeIf { it !== viewer }?.destroy()
            viewer.destroy()
            model.viewModelScope.cancel()
            callbackScheduler?.runCurrent()
            runBlocking {
                model.viewModelScope.coroutineContext[Job]?.children?.toList()?.joinAll()
            }
            host.finish()
        }
    }
}
