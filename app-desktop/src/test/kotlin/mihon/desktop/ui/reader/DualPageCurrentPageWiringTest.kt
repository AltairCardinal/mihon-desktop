package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DualPageCurrentPageWiringTest {
    @ParameterizedTest
    @EnumSource(value = ReadingMode::class, names = ["AUTO", "RTL", "LTR"])
    fun `normal dual current is leading page and single return does not report layout progress`(mode: ReadingMode) = runTest {
        Fixture(this, mode, initialPage = 2).use { fixture ->
            fixture.frames()
            assertEquals(2, fixture.state.currentPage)
            val reports = fixture.reports.toList()
            fixture.dual(true)
            fixture.frames()
            assertEquals(1, fixture.state.currentPage)
            assertEquals(if (mode == ReadingMode.LTR) listOf(1, 2) else listOf(2, 1), fixture.slotIndices())
            assertEquals(reports, fixture.reports)
            fixture.dual(false)
            fixture.frames()
            assertEquals(1, fixture.state.currentPage)
            assertEquals(reports, fixture.reports)
            assertEquals(0, fixture.chapterRequests)
        }
    }

    @ParameterizedTest
    @CsvSource("AUTO,true", "RTL,true", "LTR,true", "RTL,false", "LTR,false")
    fun `mounted adjust spread button alternates paired units without runaway paging`(mode: ReadingMode, animated: Boolean) = runTest {
        Fixture(this, mode, initialPage = 1, animated = animated).use { fixture ->
            fixture.frames()
            fixture.dual(true)
            fixture.frames()
            val reports = fixture.reports.toList()
            repeat(6) { index ->
                fixture.adjust()
                fixture.frames(90)
                val firstPage = if (index % 2 == 0) 2 else 1
                assertEquals(firstPage, fixture.state.currentPage)
                assertEquals(setOf(firstPage, firstPage + 1), fixture.state.visiblePageIds.map { it.sourcePageIndex }.toSet())
                assertEquals(reports, fixture.reports)
                assertEquals(0, fixture.chapterRequests)
            }
            fixture.adjust()
            fixture.adjust()
            fixture.frames(120)
            assertEquals(1, fixture.state.currentPage)
            assertEquals(setOf(1, 2), fixture.state.visiblePageIds.map { it.sourcePageIndex }.toSet())
            assertEquals(reports, fixture.reports)
            repeat(4) {
                fixture.adjust()
                fixture.frames(2)
                fixture.adjust()
                fixture.frames(90)
                assertEquals(1, fixture.state.currentPage)
                assertEquals(setOf(1, 2), fixture.state.visiblePageIds.map { it.sourcePageIndex }.toSet())
                assertEquals(reports, fixture.reports)
            }
            fixture.nextTap()
            fixture.frames(90)
            assertEquals(3, fixture.state.currentPage)
            assertEquals(reports + 3, fixture.reports)
            assertEquals(0, fixture.chapterRequests)
        }
    }

    @ParameterizedTest
    @EnumSource(value = ReadingMode::class, names = ["AUTO", "RTL", "LTR"])
    fun `portrait cover stays uniquely current at its directional edge and adjust has no effect`(mode: ReadingMode) = runTest {
        Fixture(this, mode, initialPage = 0).use { fixture ->
            fixture.frames()
            fixture.dual(true)
            fixture.frames()
            val expectedSlots = if (mode == ReadingMode.LTR) listOf(null, 0) else listOf(0, null)
            assertEquals(expectedSlots, fixture.slotIndices())
            assertEquals(0, fixture.state.currentPage)
            fixture.adjust()
            fixture.frames()
            assertEquals(expectedSlots, fixture.slotIndices())
            fixture.dual(false)
            fixture.frames()
            assertEquals(0, fixture.state.currentPage)
            assertEquals(0, fixture.chapterRequests)
        }
    }

    private class Fixture(
        private val scope: TestScope,
        private val mode: ReadingMode,
        initialPage: Int,
        animated: Boolean = true,
    ) : AutoCloseable {
        private val root = Preferences.userRoot().node("/mihon/dual-current-test/${System.nanoTime()}")
        private val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("reader")), root.node("legacy")).apply {
            readingMode = mode
        }
        val reports = mutableListOf<Int>()
        var chapterRequests = 0
        private val model = ReaderScreenModel(
            prefs = prefs,
            initialSessionState = desktopReaderSessionState(pageCount = 9, initialPage = initialPage),
            onViewportSettled = { _, active -> reports += active.sourcePageIndex },
        )
        val state get() = model.state.value
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        private val content = DesktopReaderPageContentOwner(scope, { byteArrayOf(1) }, reporter)
        private val pipeline = DesktopReaderPageImagePipeline(scope, content, reporter, decoder = DesktopReaderPageImageDecoder { _, _ ->
            val bitmap = Bitmap().apply { allocN32Pixels(10, 14) }
            DesktopReaderImageAsset(bitmap.asComposeImageBitmap(), 10, 14, 560L, false, disposer = bitmap::close)
        })
        private val owner = DesktopReaderPresentationImageOwner(scope, pipeline, ReaderPageIoObserver(reporter))
        private val scene = ImageComposeScene(700, 400, coroutineContext = scope.coroutineContext) {}
        private var width by mutableStateOf(400.dp)

        init {
            owner.beginGeneration(1L)
            model.toggleUI()
            val appPrefs = DesktopAppPreferences(DesktopPreferenceStore(root.node("app")), root.node("legacy-app"))
            appPrefs.pageTurnAnimation.set(animated)
            val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns appPrefs }
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    MaterialTheme {
                        val state by model.state.collectAsState()
                        val focus = remember { FocusRequester() }
                        Box(Modifier.requiredSize(width, 400.dp)) {
                            ReaderViewport(
                                state, model, mockk(relaxed = true), focus, scope, "", "", owner, null,
                                { chapterRequests++ }, { chapterRequests++ },
                            )
                        }
                    }
                }
            }
        }

        fun frames(count: Int = 35) = repeat(count) {
            scene.render(scope.testScheduler.currentTime * 1_000_000).close()
            scope.runCurrent()
            scope.advanceTimeBy(16)
        }

        fun dual(enabled: Boolean) {
            if (mode == ReadingMode.AUTO) width = if (enabled) 700.dp else 400.dp
            else model.setDualPageMode(enabled)
        }

        fun slotIndices(): List<Int?> = requireNotNull(state.currentDisplayUnitId).slots.map { it.pageId?.sourcePageIndex }

        fun nextTap() {
            val x = if (mode == ReadingMode.LTR) width.value - 50f else 50f
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 200f), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 200f), button = PointerButton.Primary)
        }

        fun adjust() {
            val label = MR.strings.desktop_ui_adjust_spread.localized()
            val button = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }.first {
                it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child ->
                    child.config.contains(SemanticsProperties.ContentDescription) && label in child.config[SemanticsProperties.ContentDescription]
                }
            }
            assertTrue(requireNotNull(button.config[SemanticsActions.OnClick].action).invoke())
        }

        override fun close() {
            scene.close()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
            root.removeNode()
        }
    }

    companion object {
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    }
}
