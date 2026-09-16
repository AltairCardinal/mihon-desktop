package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class AdaptiveReaderViewportTest {
    @Test
    fun `production reader resize preserves page and progress and real next tap resumes reporting`() = runTest {
        val root = Preferences.userRoot().node("/mihon/viewport-test/${System.nanoTime()}")
        val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy"))
        prefs.readingMode = ReadingMode.DEFAULT
        val reports = mutableListOf<Int>()
        val model = ReaderScreenModel(
            prefs = prefs, initialSessionState = desktopReaderSessionState(pageCount = 5, initialPage = 2),
            onViewportSettled = { _, active -> reports += active.sourcePageIndex },
        )
        val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        val content = DesktopReaderPageContentOwner(this, { byteArrayOf(1) }, reporter)
        val pipeline = DesktopReaderPageImagePipeline(this, content, reporter, decoder = DesktopReaderPageImageDecoder { _, _ ->
            val bitmap = Bitmap().apply { allocN32Pixels(10, 14) }
            DesktopReaderImageAsset(bitmap.asComposeImageBitmap(), 10, 14, 560L, false, disposer = bitmap::close)
        })
        val owner = DesktopReaderPresentationImageOwner(this, pipeline, ReaderPageIoObserver(reporter))
        owner.beginGeneration(1L)
        val appPrefs = DesktopAppPreferences(DesktopPreferenceStore(root.node("app")), root.node("legacy-app"))
        val dependencies = mockk<DesktopUiDependencies> { every { appPreferences } returns appPrefs }
        var width by mutableStateOf(100.dp)
        val scene = ImageComposeScene(160, 100, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    val state by model.state.collectAsState()
                    Box(Modifier.requiredSize(width, 100.dp)) {
                        ReaderContent(state, model, this@runTest, "", "", owner, null, {}, {})
                    }
                }
            }
            fun frames() = repeat(30) {
                scene.render(testScheduler.currentTime * 1_000_000).close()
                runCurrent()
                advanceTimeBy(16)
            }
            frames()
            assertFalse(model.state.value.dualPageMode)
            assertEquals(2, model.state.value.currentPage)
            assertEquals(listOf(2), reports)
            width = 160.dp
            frames()
            assertTrue(model.state.value.dualPageMode)
            assertEquals(1, model.state.value.currentPage)
            assertEquals(listOf(2), reports)
            model.toggleUI()
            frames()
            assertTrue(model.state.value.dualPageMode)
            width = 100.dp
            frames()
            assertFalse(model.state.value.dualPageMode)
            assertEquals(1, model.state.value.currentPage)
            assertEquals(listOf(2), reports)
            val pageBounds = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                .first { node ->
                    node.config.contains(ReaderDisplayUnitIdKey) &&
                        node.config[ReaderDisplayUnitIdKey].slots.any { it.pageId?.sourcePageIndex == 1 }
                }.boundsInRoot
            val nextTap = Offset(pageBounds.left + 5f, pageBounds.center.y)
            scene.sendPointerEvent(PointerEventType.Press, nextTap, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, nextTap, button = PointerButton.Primary)
            frames()
            assertEquals(2, model.state.value.currentPage)
            scene.sendPointerEvent(PointerEventType.Press, nextTap, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, nextTap, button = PointerButton.Primary)
            frames()
            assertEquals(3, model.state.value.currentPage)
            assertEquals(listOf(2, 3), reports)
        } finally {
            scene.close()
            owner.close()
            pipeline.close()
            content.close()
            model.onDispose()
            root.removeNode()
        }
    }

    @Test
    fun `mounted reader settings separate inheritance default feedback and disabled manual layout`() = runTest {
        val root = Preferences.userRoot().node("/mihon/mode-controls-test/${System.nanoTime()}")
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val prefs = ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy"))
        prefs.readingMode = ReadingMode.LTR
        val model = ReaderScreenModel(prefs = prefs)
        model.updateViewportSize(1600, 1000, this)
        val scene = ImageComposeScene(700, 1200, coroutineContext = coroutineContext) {}
        fun render() = repeat(4) { scene.render().close(); runCurrent() }
        fun choice(label: String) = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> label in text(child) }
        }
        fun click(label: String) { requireNotNull(choice(label).config[SemanticsActions.OnClick].action).invoke(); render() }
        fun selected(label: String) = flatten(choice(label)).any {
            it.config.contains(SemanticsProperties.Selected) && it.config[SemanticsProperties.Selected]
        }
        try {
            scene.setContent {
                MaterialTheme {
                    Column {
                        val state by model.state.collectAsState()
                        GeneralTab(
                            currentMode = if (state.automaticLayout) ReadingMode.DEFAULT else state.readingMode,
                            followsGlobal = state.followsGlobalReadingMode,
                            onFollowGlobal = { model.followGlobalReadingMode(prefs) },
                            isDualPage = state.dualPageMode,
                            autoSplitPages = state.autoSplitPages, isAutoSpreadMatching = state.autoSpreadMatching,
                            backgroundTheme = state.backgroundTheme, navigationMode = state.navigationMode,
                            zoomState = state.zoomState,
                            onModeChange = { model.setReadingMode(it, prefs) },
                            onDualPageChange = { model.setDualPageMode(it, prefs) },
                            onAutoSplitPagesChange = {}, onAutoSpreadMatchingChange = {},
                            onBackgroundThemeChange = {}, onNavigationModeChange = {}, onZoomChange = {},
                        )
                    }
                }
            }
            render()
            val inherited = MR.strings.desktop_reader_follow_global.localized()
            val default = MR.strings.label_default.localized()
            val dual = MR.strings.desktop_ui_dual_page_side_by_side.localized()
            assertTrue(selected(inherited))
            click(default)
            assertTrue(selected(default))
            assertFalse(selected(inherited))
            assertTrue(nodes(scene).any { MR.strings.desktop_reader_default_dual.localized() in text(it) })
            assertTrue(choice(dual).config.contains(SemanticsProperties.Disabled))
            click(readingModeLabel(ReadingMode.RTL))
            assertFalse(choice(dual).config.contains(SemanticsProperties.Disabled))
            click(inherited)
            assertTrue(selected(inherited))
            assertEquals(ReadingMode.LTR, model.state.value.readingMode)
            assertEquals(ReadingMode.LTR, prefs.readingMode)
        } finally {
            scene.close()
            model.onDispose()
            root.removeNode()
            Locale.setDefault(previousLocale)
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun text(node: SemanticsNode): List<String> = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map { it.text }
    } else emptyList()

}
