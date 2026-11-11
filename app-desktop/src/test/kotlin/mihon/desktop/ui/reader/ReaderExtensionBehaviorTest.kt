package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.ReaderDisplayOptions
import mihon.desktop.reader.ReaderFlashColor
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReaderRippleStyle
import mihon.desktop.reader.ReaderZoomStart
import mihon.domain.reader.NavigationInversion
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

class ReaderExtensionBehaviorTest {
    @Test
    fun `all extension settings survive real preference and screen model reconstruction`() {
        val node = Preferences.userRoot().node("/mihon/test/reader-extensions-${System.nanoTime()}")
        val legacy = node.node("legacy")
        try {
            val prefs = ReaderPreferences(DesktopPreferenceStore(node), legacy)
            val options = ReaderDisplayOptions(
                pagerInversion = NavigationInversion.BOTH, webtoonNavigation = NavigationMode.Kindle,
                webtoonInversion = NavigationInversion.VERTICAL, zoomStart = ReaderZoomStart.RIGHT,
                landscapeZoom = false, navigateToPan = false, splitInvert = true, rotateWide = true,
                rotateInvert = true, webtoonPadding = 17, webtoonDoubleTapZoom = false, webtoonDisableZoomOut = true,
                showPageNumber = false, fullscreen = true, keepScreenOn = false, longTap = false,
                alwaysShowChapterTransition = false, pageTransitions = false, flash = true, flashMillis = 700,
                flashInterval = 3, flashColor = ReaderFlashColor.WHITE_BLACK, ripple = true,
                rippleStyle = ReaderRippleStyle.SMOOTH,
            )
            val model = ReaderScreenModel(prefs = prefs)
            try {
                model.setDisplayOptions(options, prefs)
                assertEquals(options, model.state.value.displayOptions)
            } finally {
                model.onDispose()
            }
            val reread = ReaderPreferences(DesktopPreferenceStore(node), legacy)
            assertEquals(options, reread.loadDisplayOptions())
            val reopened = ReaderScreenModel(prefs = reread)
            try {
                assertEquals(options, reopened.state.value.displayOptions)
                assertTrue(reopened.state.value.isFullscreen)
            } finally {
                reopened.onDispose()
            }
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `tap inversion reaches production shared navigation without changing menu taps`() {
        assertEquals(
            TapNavRegion.NEXT,
            tapNavRegion(10f, 50f, 100f, 100f, NavigationMode.RightAndLeft, inversion = NavigationInversion.HORIZONTAL),
        )
        assertEquals(
            TapNavRegion.MENU,
            tapNavRegion(50f, 50f, 100f, 100f, NavigationMode.RightAndLeft, inversion = NavigationInversion.BOTH),
        )
    }

    @Test
    fun `saved split is inactive outside explicit single page and returns without losing value`() {
        val saved =
            ReaderState(autoSplitPages = true, readerSettings = ReaderSettings(layout = ReaderSettingsLayout.SINGLE))
        assertTrue(saved.effectiveAutoSplitPages)
        assertFalse(
            saved.copy(readerSettings = ReaderSettings(layout = ReaderSettingsLayout.AUTO)).effectiveAutoSplitPages,
        )
        assertFalse(
            saved.copy(readerSettings = ReaderSettings(layout = ReaderSettingsLayout.DOUBLE)).effectiveAutoSplitPages,
        )
        assertFalse(
            saved.copy(
                readerSettings = ReaderSettings(
                    mode = ReaderSettingsMode.VERTICAL,
                    layout = ReaderSettingsLayout.SINGLE,
                ),
            ).effectiveAutoSplitPages,
        )
        assertTrue(saved.autoSplitPages)
    }

    @Test
    fun `eink effect counter ignores first paint duplicate notifications and chapter entry`() {
        val counter = ReaderPageEffectCounter()
        assertFalse(counter.changed(1, 0, 2))
        assertFalse(counter.pageChanged)
        assertFalse(counter.changed(1, 0, 2, "initial-slot"))
        assertFalse(counter.pageChanged)
        assertFalse(counter.changed(1, 1, 2))
        assertTrue(counter.pageChanged)
        assertFalse(counter.changed(1, 1, 2))
        assertFalse(counter.pageChanged)
        assertTrue(counter.changed(1, 2, 2))
        assertFalse(counter.changed(2, 0, 2))
        assertFalse(counter.pageChanged)
    }

    @Test
    fun `clockwise and reverse wide page rotations preserve pixels`() {
        val source = org.jetbrains.skia.Bitmap().apply {
            check(allocN32Pixels(4, 2))
            erase(0xFFFF0000.toInt())
        }
        org.jetbrains.skia.Canvas(source).use { canvas ->
            org.jetbrains.skia.Paint().use { paint ->
                paint.color = 0xFF0000FF.toInt()
                canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(2f, 0f, 2f, 2f), paint)
            }
        }
        try {
            for (rotation in listOf(90, -90)) {
                val image = rotateReaderBitmap(source.asComposeImageBitmap(), rotation).asSkiaBitmap()
                try {
                    assertEquals(2, image.width)
                    assertEquals(4, image.height)
                    assertEquals(if (rotation == 90) 0xFFFF0000.toInt() else 0xFF0000FF.toInt(), image.getColor(0, 0))
                    assertEquals(if (rotation == 90) 0xFF0000FF.toInt() else 0xFFFF0000.toInt(), image.getColor(0, 3))
                } finally {
                    image.close()
                }
            }
        } finally {
            source.close()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun `eink flash paints white then black and clears without flashing first paint`() = runTest {
        var state by androidx.compose.runtime.mutableStateOf(
            ReaderState(
                displayOptions = ReaderDisplayOptions(
                    keepScreenOn = false,
                    showPageNumber = false,
                    flash = true,
                    flashMillis = 100,
                    flashColor = ReaderFlashColor.WHITE_BLACK,
                ),
            ),
        )
        val scene = androidx.compose.ui.ImageComposeScene(
            100,
            100,
            coroutineContext = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        ) {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Blue),
            ) {
                ReaderGeneralEffects(state)
            }
        }
        fun pixel(): Int {
            repeat(3) {
                scene.render(testScheduler.currentTime * 1_000_000).close()
                runCurrent()
            }
            return scene.render(testScheduler.currentTime * 1_000_000).use { image ->
                image.encodeToData()!!.use { javax.imageio.ImageIO.read(it.bytes.inputStream()).getRGB(50, 50) }
            }
        }
        try {
            assertEquals(0xFF0000FF.toInt(), pixel())
            state = state.copy(currentPage = 1)
            runCurrent()
            assertEquals(0xFFFFFFFF.toInt(), pixel())
            state = state.copy(
                currentDisplayUnitId = mihon.desktop.ui.reader.presentation.DisplayUnitId(
                    mihon.desktop.ui.reader.presentation.ReaderPresentationMode.SINGLE_PAGED,
                    listOf(
                        mihon.desktop.ui.reader.presentation.DisplaySlotId(
                            mihon.domain.reader.session.ReaderPageId(
                                mihon.domain.reader.session.ReaderChapterId(state.context.chapterId),
                                1,
                            ),
                        ),
                    ),
                ),
            )
            assertEquals(0xFFFFFFFF.toInt(), pixel(), "Late display identity must not cancel the active flash")
            advanceTimeBy(51)
            runCurrent()
            assertEquals(0xFF000000.toInt(), pixel())
            advanceTimeBy(51)
            runCurrent()
            assertEquals(0xFF0000FF.toInt(), pixel())
        } finally {
            scene.close()
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    fun `native pointer navigation observes reader inversion options`() {
        var inversion by androidx.compose.runtime.mutableStateOf(NavigationInversion.NONE)
        var vertical by androidx.compose.runtime.mutableStateOf(false)
        var mode by androidx.compose.runtime.mutableStateOf(NavigationMode.RightAndLeft)
        val actions = mutableListOf<TapNavRegion>()
        val scene = androidx.compose.ui.ImageComposeScene(
            300,
            300,
            coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalReaderViewportOptions provides ReaderViewportOptions(
                    display = ReaderDisplayOptions(pagerInversion = inversion),
                ),
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize()
                        .readerPrimaryTapInput(1f, mode, false, isVertical = vertical) { actions += it },
                )
            }
        }
        fun click(point: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(20f, 150f)) {
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Press,
                point,
                button = androidx.compose.ui.input.pointer.PointerButton.Primary,
            )
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Release,
                point,
                button = androidx.compose.ui.input.pointer.PointerButton.Primary,
            )
            scene.render().close()
        }
        try {
            scene.render().close()
            click()
            inversion = NavigationInversion.HORIZONTAL
            scene.render().close()
            click()
            assertEquals(listOf(TapNavRegion.PREV, TapNavRegion.NEXT), actions)
            actions.clear()
            vertical = true
            inversion = NavigationInversion.NONE
            scene.render().close()
            click()
            inversion = NavigationInversion.HORIZONTAL
            scene.render().close()
            click()
            assertEquals(
                listOf(TapNavRegion.PREV, TapNavRegion.NEXT),
                actions,
                "Horizontal tap inversion refers to physical left/right in vertical paged mode too",
            )
            actions.clear()
            mode = NavigationMode.Default
            inversion = NavigationInversion.NONE
            scene.render().close()
            click(androidx.compose.ui.geometry.Offset(150f, 20f))
            assertEquals(listOf(TapNavRegion.PREV), actions, "Vertical default uses Android's L navigation")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `zoom start remains active for automatic dual and vertical paged readers`() {
        val options = ReaderViewportOptions(
            configured = true,
            singlePage = false,
            display = ReaderDisplayOptions(zoomStart = ReaderZoomStart.RIGHT),
        )
        assertEquals(
            androidx.compose.ui.Alignment.CenterEnd,
            readerZoomStartAlignment(options, androidx.compose.ui.Alignment.Center),
        )
        assertEquals(
            androidx.compose.ui.Alignment.Center,
            readerZoomStartAlignment(options.copy(webtoon = true), androidx.compose.ui.Alignment.Center),
        )
        assertEquals(
            androidx.compose.ui.Alignment.Center,
            readerZoomStartAlignment(
                options.copy(
                    verticalPaged = true,
                    display = options.display.copy(zoomStart = ReaderZoomStart.AUTO),
                ),
                androidx.compose.ui.Alignment.Center,
            ),
        )
    }

    @Test
    fun `wide page auto zoom preserves explicit scaling and works in a wide viewport`() {
        val options = ReaderViewportOptions(configured = true, singlePage = true)
        val wide = androidx.compose.ui.unit.IntSize(1200, 400)
        val portrait = androidx.compose.ui.unit.IntSize(400, 800)
        val landscape = androidx.compose.ui.unit.IntSize(800, 600)
        assertEquals(
            mihon.desktop.reader.ScaleType.FIT_WIDTH,
            readerImageScaleType(mihon.desktop.reader.ScaleType.FIT_WIDTH, options, wide, portrait),
        )
        assertEquals(
            mihon.desktop.reader.ScaleType.FIT_HEIGHT,
            readerImageScaleType(mihon.desktop.reader.ScaleType.FIT_SCREEN, options, wide, landscape),
        )
        assertEquals(
            mihon.desktop.reader.ScaleType.FIT_SCREEN,
            readerImageScaleType(
                mihon.desktop.reader.ScaleType.FIT_SCREEN,
                options.copy(singlePage = false),
                wide,
                portrait,
            ),
        )
    }
}
