package eu.kanade.tachiyomi.ui.reader.viewer

import android.app.Application
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import io.mockk.mockk
import mihon.domain.reader.ReaderAdjacentChapterEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderAdjacentChapterViewerProductionWiringTest {

    private lateinit var previousInjekt: InjektScope

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val preferences = InMemoryPreferenceStore()
        Injekt.addSingleton(ReaderPreferences(preferences))
        Injekt.addSingleton(UiPreferences(preferences))
        Injekt.addSingleton(
            BasePreferences(
                RuntimeEnvironment.getApplication() as Application,
                preferences,
            ),
        )
    }

    @After
    fun tearDown() {
        Injekt = previousInjekt
    }

    @Test
    fun `pager dual and webtoon selections dispatch the same typed page-list effect`() {
        val activity = recordingReaderActivity()
        val current = loadedChapter(id = 1, pageCount = 10)
        val next = ReaderChapter(chapter(id = 2))
        val chapters = ViewerChapters(current, prevChapter = null, nextChapter = next)
        val pages = requireNotNull(current.pages)
        val beforeAnchor = pages[4]
        val anchor = pages[5]
        val transition = ChapterTransition.Next(current, next)

        viewerCases().forEach { case ->
            val viewer = case.create(activity)
            try {
                case.setChapters(viewer, chapters)
                activity.requests.clear()

                case.selectPage(viewer, beforeAnchor)

                assertEquals(0, activity.requests.size)

                case.selectPage(viewer, anchor)

                assertSingleTypedRequest(activity, next)
                activity.requests.clear()

                case.selectTransition(viewer, transition)

                assertSingleTypedRequest(activity, next)
            } catch (failure: AssertionError) {
                throw AssertionError("${case.name} did not dispatch through the typed Activity seam", failure)
            } finally {
                case.destroy(viewer)
            }
        }
    }

    private fun recordingReaderActivity(): RecordingReaderActivity {
        val activity = Robolectric.buildActivity(RecordingReaderActivity::class.java).get()
        activity.binding = mockk<ReaderActivityBinding>(relaxed = true)
        return activity
    }

    private fun assertSingleTypedRequest(
        activity: RecordingReaderActivity,
        expectedChapter: ReaderChapter,
    ) {
        assertEquals(1, activity.requests.size)
        val request = activity.requests.single()
        assertSame(expectedChapter, request.chapter)
        assertSame(ReaderAdjacentChapterEffect.LoadAdjacentChapterPageList, request.effect)
    }

    private fun loadedChapter(id: Long, pageCount: Int): ReaderChapter {
        val chapter = ReaderChapter(chapter(id))
        val pages = List(pageCount) { index ->
            ReaderPage(index).apply { this.chapter = chapter }
        }
        chapter.publishLoadedPageListForTest(pages)
        return chapter
    }

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        chapterNumber = id.toDouble(),
        name = "Chapter $id",
    )

    private fun viewerCases(): List<ViewerCase> = listOf(
        ViewerCase(
            name = "PagerViewer",
            create = { activity -> R2LPagerViewer(activity) },
            setChapters = { viewer, chapters -> (viewer as PagerViewer).setChapters(chapters) },
            selectPage = { viewer, page ->
                (viewer as PagerViewer).onReaderPageSelected(page, allowPreload = true, forward = true)
            },
            selectTransition = { viewer, transition ->
                (viewer as PagerViewer).onTransitionSelected(transition)
            },
            destroy = { viewer -> (viewer as PagerViewer).destroy() },
        ),
        ViewerCase(
            name = "DualPageR2LPagerViewer",
            create = { activity -> DualPageR2LPagerViewer(activity) },
            setChapters = { viewer, chapters -> (viewer as DualPageR2LPagerViewer).setChapters(chapters) },
            selectPage = { viewer, page ->
                (viewer as DualPageR2LPagerViewer).onDisplayPageSelected(DisplayPage.Single(page))
            },
            selectTransition = { viewer, transition ->
                (viewer as DualPageR2LPagerViewer).onTransitionSelected(transition)
            },
            destroy = { viewer -> (viewer as DualPageR2LPagerViewer).destroy() },
        ),
        ViewerCase(
            name = "WebtoonViewer",
            create = { activity -> WebtoonViewer(activity) },
            setChapters = { viewer, chapters -> (viewer as WebtoonViewer).setChapters(chapters) },
            selectPage = { viewer, page ->
                (viewer as WebtoonViewer).onPageSelected(page, allowPreload = true)
            },
            selectTransition = { viewer, transition ->
                (viewer as WebtoonViewer).onTransitionSelected(transition)
            },
            destroy = { viewer -> (viewer as WebtoonViewer).destroy() },
        ),
    )

    private data class ViewerCase(
        val name: String,
        val create: (ReaderActivity) -> Any,
        val setChapters: (Any, ViewerChapters) -> Unit,
        val selectPage: (Any, ReaderPage) -> Unit,
        val selectTransition: (Any, ChapterTransition) -> Unit,
        val destroy: (Any) -> Unit,
    )
}

internal class RecordingReaderActivity : ReaderActivity() {
    val requests = mutableListOf<RecordedAdjacentRequest>()

    override fun onPageSelected(page: ReaderPage) = Unit

    override fun requestPreloadChapter(
        chapter: ReaderChapter,
        effect: ReaderAdjacentChapterEffect,
    ) {
        requests += RecordedAdjacentRequest(chapter, effect)
    }
}

internal data class RecordedAdjacentRequest(
    val chapter: ReaderChapter,
    val effect: ReaderAdjacentChapterEffect,
)
