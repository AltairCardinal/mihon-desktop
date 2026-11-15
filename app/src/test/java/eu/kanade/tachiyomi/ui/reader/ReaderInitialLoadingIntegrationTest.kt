package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.HttpReaderMaterializeAdapterIntegrationTest
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.domain.reader.ReaderSettingsCodec
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real Activity lifecycle, ViewModel subscriptions and chapter loader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ReaderInitialLoadingIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun firstVisibleFrameHasFeedbackBeforeMangaLookupCompletes() = withReader(
        configure = { fixture ->
            val lookup = ReflectionHelpers.getField<GetManga>(fixture.model, "getManga")
            coEvery { lookup.await(1) } coAnswers {
                lookupEntered.complete(Unit)
                releaseLookup.await()
                null
            }
        },
    ) { reader ->
        waitUntilReader { lookupEntered.isCompleted }
        compose.runOnIdle {
            assertNull(reader.viewModel.state.value.manga)
            assertNull(reader.viewModel.state.value.viewer)
            val indicators = indicators(reader.binding.readerContainer)
            assertEquals("The first visible reader frame must show its center loading indicator", 1, indicators.size)
            val center = indicators.single()
            assertTrue(center.isAttachedToWindow && center.isShown && center.width > 0 && center.height > 0)
            assertEquals(reader.binding.readerContainer.width / 2, center.left + center.width / 2)
            assertEquals(reader.binding.readerContainer.height / 2, center.top + center.height / 2)
        }
    }

    @Test
    fun centerIndicatorIsMountedWhileActualChapterLoaderWaitsForPageList() = withReader(
        configure = { fixture ->
            val loader = ReflectionHelpers.getField<ChapterLoader>(fixture, "loader")
            ReflectionHelpers.setField(loader, "beforeBeginPageListLoad", {
                pageListEntered.countDown()
                check(releasePageList.await(20, TimeUnit.SECONDS))
            })
        },
    ) { reader ->
        waitUntilReader { pageListEntered.count == 0L }
        waitUntilReader { reader.viewModel.state.value.viewer != null }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotNull(reader.viewModel.state.value.manga)
            assertNull(reader.viewModel.state.value.viewerChapters)
            val center = indicators(reader.binding.readerContainer).single()
            assertTrue(reader.viewModel.state.value.viewer is DualPageR2LPagerViewer)
            assertTrue(center.isAttachedToWindow)
            assertTrue(center.isShown)
            assertTrue(center.width > 0 && center.height > 0)
        }
    }

    @Test
    fun pageListDoesNotDismissCenterBeforeSelectedHolderHasLayout() = withReader(
        configure = {},
        beforeVisible = { reader ->
            waitUntilReader {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                reader.viewModel.state.value.viewerChapters != null
            }
            assertTrue(reader.viewModel.state.value.viewer is DualPageR2LPagerViewer)
            val center = ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator")
            assertNotNull("A page list alone is not drawable page feedback", center.parent)
            assertEquals(0f, reader.viewModel.state.value.viewer!!.getView().alpha, 0f)
        },
    ) { reader ->
        waitUntilReader { reader.viewModel.state.value.viewer!!.getView().alpha == 1f }
        compose.runOnIdle {
            val center = ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator")
            assertNull(center.parent)
            assertTrue(indicators(reader.binding.viewerContainer).any { it.isShown && it.width > 0 && it.height > 0 })
        }
    }

    @Test
    fun initialChapterFailureReplacesCenterWithExistingRetryDialog() = withReader(
        configure = { fixture ->
            val loader = ReflectionHelpers.getField<ChapterLoader>(fixture, "loader")
            ReflectionHelpers.setField(loader, "pageLoaderFactory", { _: ReaderChapter ->
                object : PageLoader() {
                    override var isLocal = true
                    override suspend fun getPages(): List<ReaderPage> =
                        throw IOException("page list unavailable")
                }
            })
        },
    ) { reader ->
        waitUntilReader { reader.viewModel.state.value.dialog is ReaderViewModel.Dialog.ChapterError }
        compose.runOnIdle {
            val center = ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator")
            assertNull("The chapter error dialog owns feedback after an initial failure", center.parent)
        }
    }

    @Test
    fun coldHttpPageListHandsOffToTwoPhysicalSlotsBeforeImagesDownload() {
        val releaseImages = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/") {
                    pageListEntered.countDown()
                    check(releasePageList.await(20, TimeUnit.SECONDS))
                    return MockResponse(body = "pages:17")
                }
                releaseImages.await(20, TimeUnit.SECONDS)
                return MockResponse(code = 500)
            }
        }
        server.start()
        try {
            withReader(
                match = false,
                entryPage = 1,
                configure = { fixture ->
                    val source = HttpReaderMaterializeAdapterIntegrationTest.TestHttpSource(server)
                    val cache = mockk<ChapterCache>(relaxed = true) {
                        every { getPageListFromCache(any()) } throws IOException("cold cache")
                        every { isImageInCache(any()) } returns false
                    }
                    val loader = ReflectionHelpers.getField<ChapterLoader>(fixture, "loader")
                    ReflectionHelpers.setField(loader, "pageLoaderFactory", { chapter: ReaderChapter ->
                        HttpPageLoader(chapter, source, cache)
                    })
                },
            ) { reader ->
                waitUntilReader { pageListEntered.count == 0L }
                compose.runOnIdle {
                    assertEquals(
                        1,
                        indicators(reader.binding.readerContainer).count {
                            it.parent ===
                                reader.binding.readerContainer
                        },
                    )
                    reader.binding.viewerContainer.suppressLayout(true)
                }
                releasePageList.countDown()
                waitUntilReader { reader.viewModel.state.value.viewerChapters != null }
                compose.runOnUiThread {
                    assertNotNull(
                        ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator").parent,
                    )
                    reader.binding.viewerContainer.suppressLayout(false)
                    reader.binding.viewerContainer.requestLayout()
                }
                assertActualHandoff(reader)
                compose.runOnIdle {
                    val viewer = reader.viewModel.state.value.viewer
                    assertTrue(viewer is DualPageR2LPagerViewer)
                    assertNull(ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator").parent)
                    val pager = (viewer as DualPageR2LPagerViewer).pager
                    val selected = viewer.adapter.items[pager.currentItem]
                    assertTrue(selected is eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage.Double)
                    val holder = (0 until pager.childCount).map { pager.getChildAt(it) }
                        .filterIsInstance<DualPagerPageHolder>()
                        .single { it.displayPage == selected && it.left == pager.scrollX }
                    val slots = indicators(holder).filter { it.isShown }.map { indicator ->
                        val position = IntArray(2)
                        indicator.getLocationOnScreen(position)
                        position[0] + indicator.width / 2
                    }.sorted()
                    val origin = IntArray(2)
                    pager.getLocationOnScreen(origin)
                    assertEquals(listOf(origin[0] + pager.width / 4, origin[0] + pager.width * 3 / 4), slots)
                    assertTrue(holder.displayPage.visiblePages.none { it.status == Page.State.Ready })
                }
            }
        } finally {
            releasePageList.countDown()
            releaseImages.countDown()
            server.close()
        }
    }

    @Test
    fun retainedChapterFailureKeepsErrorFeedbackWhenNewActivityCreatesViewer() = withReader(
        configure = ::failChapter,
        beforeCreate = { fixture ->
            val failure = runBlocking { fixture.model.init(1, 1) }
            fixture.model.showInitialChapterError(requireNotNull(failure.exceptionOrNull()))
            assertTrue(fixture.model.state.value.dialog is ReaderViewModel.Dialog.ChapterError)
        },
    ) { reader ->
        compose.runOnIdle {
            assertTrue(
                "Recreation must retain its retryable error",
                reader.viewModel.state.value.dialog is ReaderViewModel.Dialog.ChapterError,
            )
            assertNull(ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator").parent)
            assertEquals(1f, reader.viewModel.state.value.viewer!!.getView().alpha, 0f)
        }
    }

    @Test
    fun ordinarySinglePageHandsOffThroughActualActivityPreDraw() = withReader(
        mode = ReadingMode.RIGHT_TO_LEFT,
        configure = {},
    ) { reader ->
        assertActualHandoff(reader)
        assertTrue(reader.viewModel.state.value.viewer is PagerViewer)
    }

    @Test
    fun webtoonHandsOffThroughActualActivityPreDraw() = withReader(
        mode = ReadingMode.WEBTOON,
        configure = {},
    ) { reader ->
        assertActualHandoff(reader)
        assertTrue(reader.viewModel.state.value.viewer is WebtoonViewer)
    }

    @Test
    fun replacingViewerRejectsOldViewerAndNeighborChapterUntilNewLayout() = withReader(configure = {}) { reader ->
        assertActualHandoff(reader)
        compose.runOnIdle {
            val old = reader.viewModel.state.value.viewer!!
            val chapter = reader.viewModel.state.value.viewerChapters!!.currChapter
            val neighbor = ReaderChapter(Chapter.create().copy(id = 99, mangaId = 1))
            assertTrue(old.isInitialPageFeedbackReady(chapter))
            assertTrue(!old.isInitialPageFeedbackReady(neighbor))
            ReflectionHelpers.callInstanceMethod<Unit>(reader, "updateViewer")
            assertTrue(!old.isInitialPageFeedbackReady(chapter))
            reader.binding.readerContainer.viewTreeObserver.dispatchOnPreDraw()
            assertNotNull(ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator").parent)
            assertEquals(0f, reader.viewModel.state.value.viewer!!.getView().alpha, 0f)
        }
        assertActualHandoff(reader)
    }

    @Test
    fun initialFailureRetryKeepsFeedbackUntilSuccessfulHolderLayout() {
        val retryEntered = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var calls = 0
        try {
            withReader(configure = { fixture ->
                val loader = ReflectionHelpers.getField<ChapterLoader>(fixture, "loader")
                ReflectionHelpers.setField(loader, "pageLoaderFactory", { chapter: ReaderChapter ->
                    object : PageLoader() {
                        override var isLocal = true
                        override suspend fun getPages(): List<ReaderPage> {
                            if (calls++ == 0) throw IOException("initial failure")
                            retryEntered.complete(Unit)
                            releaseRetry.await()
                            return List(3) { ReaderPage(it).apply { this.chapter = chapter } }
                        }
                    }
                })
            }) { reader ->
                waitUntilReader { reader.viewModel.state.value.dialog is ReaderViewModel.Dialog.ChapterError }
                compose.onNodeWithText(MR.strings.action_retry.getString(reader)).performClick()
                waitUntilReader { retryEntered.isCompleted }
                compose.runOnIdle {
                    assertTrue(reader.viewModel.state.value.dialog is ReaderViewModel.Dialog.Loading)
                    reader.binding.viewerContainer.suppressLayout(true)
                }
                releaseRetry.complete(Unit)
                waitUntilReader {
                    reader.viewModel.state.value.viewerChapters != null &&
                        reader.viewModel.state.value.dialog == null
                }
                compose.runOnUiThread {
                    assertNotNull(
                        "Retry success still needs initial physical feedback",
                        ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator").parent,
                    )
                    assertEquals(0f, reader.viewModel.state.value.viewer!!.getView().alpha, 0f)
                    reader.binding.viewerContainer.suppressLayout(false)
                    reader.binding.viewerContainer.requestLayout()
                }
                assertActualHandoff(reader)
            }
        } finally {
            releaseRetry.complete(Unit)
        }
    }

    @Test
    fun loadedNeighborRetainsCurrentPhysicalFeedback() {
        val neighborEntered = CompletableDeferred<Unit>()
        val releaseNeighbor = CompletableDeferred<Unit>()
        try {
            withReader(configure = { fixture ->
                val loader = ReflectionHelpers.getField<ChapterLoader>(fixture, "loader")
                ReflectionHelpers.setField(loader, "pageLoaderFactory", { chapter: ReaderChapter ->
                    object : PageLoader() {
                        override var isLocal = true
                        override suspend fun getPages(): List<ReaderPage> {
                            if (chapter.chapter.id != 1L) {
                                neighborEntered.complete(Unit)
                                releaseNeighbor.await()
                            }
                            return List(3) { ReaderPage(it).apply { this.chapter = chapter } }
                        }
                    }
                })
            }) { reader ->
                assertActualHandoff(reader)
                compose.runOnIdle {
                    reader.requestPreloadChapter(
                        reader.viewModel.state.value.viewerChapters!!.nextChapter!!,
                        mihon.domain.reader.ReaderAdjacentChapterPolicy.transitionPageEffect(),
                    )
                }
                waitUntilReader { neighborEntered.isCompleted }
                releaseNeighbor.complete(Unit)
                waitUntilReader { reader.viewModel.state.value.viewerChapters?.nextChapter?.pages != null }
                compose.runOnIdle {
                    val state = reader.viewModel.state.value
                    val viewer = state.viewer as DualPageR2LPagerViewer
                    printFeedbackState(reader)
                    assertTrue(
                        "A retained equal physical frame keeps its drawable feedback",
                        viewer.isInitialPageFeedbackReady(state.viewerChapters!!.currChapter),
                    )
                    assertTrue(!viewer.isInitialPageFeedbackReady(state.viewerChapters!!.nextChapter!!))
                }
            }
        } finally {
            releaseNeighbor.complete(Unit)
        }
    }

    private fun printFeedbackState(reader: ReaderActivity) {
        val viewer = reader.viewModel.state.value.viewer as? DualPageR2LPagerViewer ?: return
        val selected = viewer.adapter.items.getOrNull(viewer.pager.currentItem)
        val current = ReflectionHelpers.getField<Any?>(viewer, "currentPage")
        val active = ReflectionHelpers.getField<Map<Any, ReaderPage>>(viewer.imagePreparation, "active")
        val priority = ReflectionHelpers.getField<List<ReaderPage>>(viewer.imagePreparation, "priority")
        val pending = ReflectionHelpers.getField<List<Any>>(viewer.imagePreparation, "pending")
        println(
            "INITIAL_FEEDBACK queueActive=${active.values.map {
                it.chapter.chapter.id to it.index
            }} queuePriority=${priority.map {
                it.chapter.chapter.id to it.index
            }} pending=${pending.map { ReflectionHelpers.getField<ReaderPage>(it, "page").index }}",
        )
        println(
            "INITIAL_FEEDBACK selectedEqualsCurrent=${selected == current} selectedSameCurrent=${selected === current}",
        )
        (0 until viewer.pager.childCount).map {
            viewer.pager.getChildAt(it)
        }.filterIsInstance<DualPagerPageHolder>().forEach { holder ->
            val feedback = ReflectionHelpers.getField<Map<Any, android.widget.FrameLayout>>(
                holder,
                "feedbackContainers",
            )
            println(
                "INITIAL_FEEDBACK holderPages=${holder.displayPage.visiblePages.map {
                    it.index to it.status
                }} containers=${feedback.map { (side, view) ->
                    "$side:${view.width}x${view.height}/${view.isShown}/${view.isLayoutRequested}"
                }}",
            )
            indicators(holder).forEach { view ->
                println(
                    "INITIAL_FEEDBACK indicator=${view.width}x${view.height} attached=${view.isAttachedToWindow} shown=${view.isShown} layout=${view.isLayoutRequested}",
                )
            }
            println(
                "INITIAL_FEEDBACK holderEqual=${holder.displayPage == selected} holderSame=${holder.displayPage === selected} x=${holder.left} scroll=${viewer.pager.scrollX} size=${holder.width}x${holder.height} attached=${holder.isAttachedToWindow} layout=${holder.isLayoutRequested} ready=${holder.hasInitialPageFeedback()}",
            )
        }
    }

    private fun waitUntilReader(condition: () -> Boolean) = compose.waitUntil(5_000) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
        condition()
    }

    private fun assertActualHandoff(reader: ReaderActivity) {
        try {
            waitUntilReader {
                reader.viewModel.state.value.viewer?.getView()?.alpha == 1f &&
                    reader.viewModel.state.value.viewerChapters != null
            }
        } catch (error: Throwable) {
            printFeedbackState(reader)
            throw error
        }
        compose.runOnIdle {
            assertNull(ReflectionHelpers.getField<ReaderProgressIndicator>(reader, "loadingIndicator").parent)
            assertTrue(indicators(reader.binding.viewerContainer).any { it.isShown && it.width > 0 && it.height > 0 })
        }
    }

    private fun failChapter(fixture: ReaderSyncResumeWiringTest.Fixture) {
        val loader = ReflectionHelpers.getField<ChapterLoader>(fixture, "loader")
        ReflectionHelpers.setField(loader, "pageLoaderFactory", { _: ReaderChapter ->
            object : PageLoader() {
                override var isLocal = true
                override suspend fun getPages(): List<ReaderPage> = throw IOException("page list unavailable")
            }
        })
    }

    private val lookupEntered = CompletableDeferred<Unit>()
    private val releaseLookup = CompletableDeferred<Unit>()
    private val pageListEntered = CountDownLatch(1)
    private val releasePageList = CountDownLatch(1)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun withReader(
        match: Boolean = true,
        entryPage: Int = 0,
        mode: ReadingMode = ReadingMode.DUAL_PAGE_R2L,
        configure: (ReaderSyncResumeWiringTest.Fixture) -> Unit,
        beforeCreate: (ReaderSyncResumeWiringTest.Fixture) -> Unit = {},
        beforeVisible: (ReaderActivity) -> Unit = {},
        check: (ReaderActivity) -> Unit,
    ) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val application = RuntimeEnvironment.getApplication()
        val store = InMemoryPreferenceStore()
        Injekt.addSingleton(application)
        val preferences = ReaderPreferences(store)
        preferences.defaultReadingMode().set(ReadingMode.DUAL_PAGE_R2L.flagValue)
        preferences.automaticSpreadMatching().set(match)
        Injekt.addSingleton(preferences)
        Injekt.addSingleton(UiPreferences(store))
        Injekt.addSingleton(BasePreferences(application, store))
        Injekt.addSingleton(SecurityPreferences(store))
        Injekt.addSingleton<DownloadManager>(mockk(relaxed = true))
        // Keep optional app bars out of this diagnosis; the ViewModel has its own initialized source manager.
        Injekt.addSingleton<SourceManager>(mockk { every { isInitialized } returns MutableStateFlow(false) })
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        val fixture = ReaderSyncResumeWiringTest.Fixture(firstChapterLastPageRead = entryPage.toLong())
        ReflectionHelpers.setField(fixture.model, "readerPreferences", preferences)
        val manga = ReflectionHelpers.getField<Manga>(fixture, "manga").copy(
            viewerFlags = ReaderSettingsCodec.setCustom(
                0L,
                ReaderSettingsCodec.fromMode(mode.flagValue.toLong()).copy(match = match),
            ),
        )
        val lookup = ReflectionHelpers.getField<GetManga>(fixture.model, "getManga")
        coEvery { lookup.await(1) } returns manga
        fixture.repository.responses =
            fixture.repository.responses.mapValues { (_, context) -> context.copy(manga = manga) }
        configure(fixture)
        beforeCreate(fixture)
        val controller = Robolectric.buildActivity(
            ReaderActivity::class.java,
            ReaderActivity.newIntent(application, 1, 1),
        )
        val reader = controller.get()
        ReflectionHelpers.setField(reader, "viewModel\$delegate", lazyOf(fixture.model))
        try {
            controller.create().start().resume()
            beforeVisible(reader)
            controller.visible()
            check(reader)
        } finally {
            releaseLookup.complete(Unit)
            releasePageList.countDown()
            reader.binding.viewerContainer.suppressLayout(false)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
            fixture.close()
            Dispatchers.resetMain()
            Injekt = previous
        }
    }

    private fun indicators(view: View): List<ReaderProgressIndicator> = when (view) {
        is ReaderProgressIndicator -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { indicators(view.getChildAt(it)) }
        else -> emptyList()
    }
}
