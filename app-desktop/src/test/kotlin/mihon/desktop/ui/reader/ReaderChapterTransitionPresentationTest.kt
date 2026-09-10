package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderImageAsset
import mihon.desktop.reader.DesktopReaderPageContentOwner
import mihon.desktop.reader.DesktopReaderPageImageDecoder
import mihon.desktop.reader.DesktopReaderPageImagePipeline
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderPageIoObserver
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.readerChapterSession
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReaderChapterTransitionPresentationTest {

    @Test
    fun `mounted boundary includes original chapter section around the boundary card`() = runTest {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val context = DesktopReaderChapterContext(
            chapterId = 41L,
            sourceId = 9L,
            chapterUrl = "/chapter/41",
            mangaTitle = "Manga",
            chapterTitle = "第 41 章：在漫长夏日里寻找答案的故事",
            chapterNumber = 41.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = true,
            scanlator = "中文汉化组",
            isDownloaded = true,
        )
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalReaderChapterTransitionContext provides context,
                        LocalReaderChapterTransitionContentColor provides readerChapterTransitionContentColor(
                            ReaderBackgroundTheme.GRAY,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF444444))) {
                            ReaderChapterTransitionItem(
                                direction = ReaderTransitionDirection.NEXT,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp),
                            )
                        }
                    }
                }
            }
            repeat(6) {
                scene.render().close()
                runCurrent()
            }

            val renderedText = nodes(scene).flatMap { node ->
                if (node.config.contains(SemanticsProperties.Text)) {
                    node.config[SemanticsProperties.Text].map { it.text }
                } else {
                    emptyList()
                }
            }
            assertTrue(
                MR.strings.transition_finished.localized() in renderedText,
                "Mounted production transition is missing the current chapter header: $renderedText",
            )
            assertTrue(renderedText.any { context.chapterTitle in it })
            assertTrue(MR.strings.transition_no_next.localized() in renderedText)
            assertTrue(
                renderedText.indexOf(MR.strings.transition_finished.localized()) <
                    renderedText.indexOf(MR.strings.transition_no_next.localized()),
                "Finished chapter section must precede the no-next card: $renderedText",
            )
            assertTrue(MR.strings.label_downloaded.localized() in contentDescriptions(scene))
            writePng(scene, "reader-boundary-layout-red.png")
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `production reader transition color keeps gray background text readable`() = runTest {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val context = DesktopReaderChapterContext(
            chapterId = 41L,
            sourceId = 9L,
            chapterUrl = "/chapter/41",
            mangaTitle = "Manga",
            chapterTitle = "灰底章节标题",
            chapterNumber = 41.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = true,
            scanlator = "汉化组",
        )
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        try {
            scene.setContent {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalReaderChapterTransitionContext provides context,
                        LocalReaderChapterTransitionContentColor provides readerChapterTransitionContentColor(
                            ReaderBackgroundTheme.GRAY,
                        ),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF444444))) {
                            ReaderChapterTransitionItem(
                                direction = ReaderTransitionDirection.PREVIOUS,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp),
                            )
                        }
                    }
                }
            }
            repeat(6) {
                scene.render().close()
                runCurrent()
            }
            assertTrue(MR.strings.transition_current.localized() in text(scene))
            assertTrue("灰底章节标题" in text(scene))
            assertTrue("汉化组" in text(scene))
            assertTrue(
                text(scene).indexOf(MR.strings.transition_no_previous.localized()) <
                    text(scene).indexOf(MR.strings.transition_current.localized()),
                "No-previous card must precede the current chapter section: ${text(scene)}",
            )
            writePng(scene, "reader-boundary-layout-gray-previous.png")
        } finally {
            scene.close()
            Locale.setDefault(previousLocale)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "SINGLE_PAGED,false,BLACK",
        "SINGLE_PAGED,true,BLACK",
        "DUAL_PAGED,false,BLACK",
        "DUAL_PAGED,true,BLACK",
        "WEBTOON,false,BLACK",
        "WEBTOON,true,BLACK",
        "SINGLE_PAGED,false,WHITE",
    )
    fun `ReaderContent mounts transition metadata in every viewer direction`(
        modeName: String,
        rtl: Boolean,
        backgroundThemeName: String,
    ) = runTest {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        val mode = ReaderPresentationMode.valueOf(modeName)
        val backgroundTheme = ReaderBackgroundTheme.valueOf(backgroundThemeName)
        val readingMode = when (mode) {
            ReaderPresentationMode.SINGLE_PAGED -> if (rtl) ReadingMode.RTL else ReadingMode.LTR
            ReaderPresentationMode.DUAL_PAGED -> if (rtl) ReadingMode.RTL else ReadingMode.LTR
            ReaderPresentationMode.WEBTOON -> ReadingMode.WEBTOON
        }
        val chapter = readerChapterSession(chapterId = 41L, pageCount = 1)
        val context = DesktopReaderChapterContext(
            chapterId = 41L,
            sourceId = 9L,
            chapterUrl = "/chapter/41",
            mangaTitle = "漫画",
            chapterTitle = "第 41 章：这是一个很长很长的章节标题，用来验证原版五行省略和白底可读性",
            chapterNumber = 41.0,
            chapterIndex = 0,
            initialPage = 0,
            wasRead = true,
            scanlator = "中文汉化组",
            isDownloaded = true,
        )
        val request = desktopReaderPresentationRequest(
            chapter = chapter,
            direction = if (rtl) ReaderDirection.RTL else ReaderDirection.LTR,
            hasPreviousChapter = false,
            hasNextChapter = false,
        )
        val presentation = DesktopReaderPresentationRegistry.require(mode).present(request)
        val transition = presentation.displayUnits.first { it.transitionDirection == ReaderTransitionDirection.NEXT }
        val initialState = desktopReaderSessionState(chapterId = 41L, pageCount = 1)
        val state = ReaderState(
            context = context,
            session = initialState.snapshot.copy(activeChapter = chapter),
            currentPage = 0,
            currentDisplayUnitId = transition.id,
            webtoonScrollAnchor = transition.id.takeIf { mode == ReaderPresentationMode.WEBTOON }
                ?.let { mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor(it, 0) },
            readingMode = readingMode,
            dualPageMode = mode == ReaderPresentationMode.DUAL_PAGED,
            backgroundTheme = backgroundTheme,
        )
        val model = ReaderScreenModel(
            initialSessionState = initialState,
            prefs = ReaderPreferences(InMemoryPreferenceStore()),
        )
        var nextChapterRequests = 0
        val appPreferences = DesktopAppPreferences(
            InMemoryPreferenceStore(),
            Preferences.userRoot().node("/mihon/transition-mounted/${System.nanoTime()}"),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.appPreferences } returns appPreferences
        }
        val scene = ImageComposeScene(640, 480, coroutineContext = currentCoroutineContext()) {}
        val testScope = this
        val owner = TransitionPresentationImageFixture(testScope)
        var frameTime = 0L
        fun renderFrame() {
            frameTime += 16_000_000L
            scene.render(frameTime).close()
            runCurrent()
        }
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Box(
                        Modifier.fillMaxSize().background(
                            if (backgroundTheme == ReaderBackgroundTheme.WHITE) Color.White else Color.Black,
                        ),
                    ) {
                        ReaderContent(
                            state = state,
                            model = model,
                            contextMenuScope = testScope,
                            mangaTitle = context.mangaTitle,
                            chapterTitle = context.chapterTitle,
                            presentationImageOwner = owner.presentationImageOwner,
                            readerNav = null,
                            onPrevChapter = {},
                            onNextChapter = { nextChapterRequests++ },
                        )
                    }
                }
            }
            repeat(60) { renderFrame() }
            val renderedText = text(scene)
            if (mode == ReaderPresentationMode.WEBTOON) {
                writePng(scene, "reader-boundary-layout-black-webtoon.png")
            }
            assertTrue(MR.strings.transition_finished.localized() in renderedText, "mode=$modeName rtl=$rtl rendered=$renderedText")
            assertTrue(renderedText.any { context.chapterTitle in it }, "mode=$modeName rtl=$rtl rendered=$renderedText")
            assertTrue(MR.strings.transition_no_next.localized() in renderedText, "mode=$modeName rtl=$rtl rendered=$renderedText")
            val titleNode = nodes(scene).first { node ->
                node.config.contains(SemanticsProperties.Text) &&
                    node.config[SemanticsProperties.Text].any { it.text.contains(context.chapterTitle) }
            }
            if (backgroundTheme == ReaderBackgroundTheme.WHITE) {
                assertTrue(
                    hasDarkPixel(scene, titleNode),
                    "ReaderContent must map WHITE reader background to readable transition text",
                )
            } else {
                assertTrue(
                    hasBrightPixel(scene, titleNode),
                    "ReaderContent must map BLACK reader background to readable transition text",
                )
            }
            assertTrue(
                MR.strings.label_downloaded.localized() in contentDescriptions(scene),
                "mode=$modeName rtl=$rtl descriptions=${contentDescriptions(scene)}",
            )
            if (mode == ReaderPresentationMode.SINGLE_PAGED && !rtl) {
                writePng(
                    scene,
                    if (backgroundTheme == ReaderBackgroundTheme.WHITE) {
                        "reader-boundary-layout-white-next.png"
                    } else {
                        "reader-boundary-layout-black-next.png"
                    },
                )
            }
            if (mode != ReaderPresentationMode.WEBTOON) {
                val nextX = if (rtl) 20f else 620f
                scene.sendPointerEvent(PointerEventType.Press, Offset(nextX, 240f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(nextX, 240f), button = PointerButton.Primary)
                repeat(40) { renderFrame() }
                assertEquals(1, nextChapterRequests)

                val previousX = if (rtl) 620f else 20f
                scene.sendPointerEvent(PointerEventType.Press, Offset(previousX, 240f), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(previousX, 240f), button = PointerButton.Primary)
                repeat(60) { renderFrame() }
                val noNextNodes = nodes(scene).filter { node ->
                    node.config.contains(SemanticsProperties.Text) &&
                        node.config[SemanticsProperties.Text].any {
                            it.text == MR.strings.transition_no_next.localized()
                        }
                }
                assertTrue(
                    noNextNodes.isNotEmpty() && noNextNodes.all {
                        it.boundsInRoot.right <= 0f || it.boundsInRoot.left >= 640f
                    },
                    "Settled manga page must move the no-next transition outside the viewport: " +
                        noNextNodes.map { it.boundsInRoot },
                )
                val pageNodes = nodes(scene).filter { node ->
                    val hasPageId = when (mode) {
                        ReaderPresentationMode.SINGLE_PAGED -> node.config.contains(ReaderDisplayUnitIdKey)
                        ReaderPresentationMode.DUAL_PAGED -> node.config.contains(DualPageDisplayUnitIdKey)
                        ReaderPresentationMode.WEBTOON -> node.config.contains(WebtoonDisplayUnitIdKey)
                    }
                    hasPageId && !node.config.contains(ReaderDisplayUnitTransitionDirectionKey) &&
                        node.boundsInRoot.right > 0f && node.boundsInRoot.left < 640f
                }
                assertTrue(pageNodes.isNotEmpty(), "Settled manga page must remain visible: $pageNodes")
            }
        } finally {
            scene.close()
            owner.close()
            model.onDispose()
            Locale.setDefault(previousLocale)
        }
    }

    private class TransitionPresentationImageFixture(
        scope: kotlinx.coroutines.CoroutineScope,
    ) : AutoCloseable {
        private val reporter = ReaderIoReporter(ReaderIoProbe.None, ReaderMonotonicClock { 0L })
        private val content = DesktopReaderPageContentOwner(scope, { byteArrayOf(1) }, reporter)
        private val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = content,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { _, key ->
                val bitmap = Bitmap().apply { allocN32Pixels(32, 32) }
                Canvas(bitmap).use { canvas -> canvas.clear(Color.White.toArgb()) }
                DesktopReaderImageAsset(
                    bitmap.asComposeImageBitmap(),
                    32,
                    32,
                    4096L,
                    false,
                    disposer = bitmap::close,
                )
            },
        )
        val presentationImageOwner = DesktopReaderPresentationImageOwner(
            scope,
            pipeline,
            ReaderPageIoObserver(reporter),
        )

        override fun close() {
            presentationImageOwner.close()
            pipeline.close()
            content.close()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun contentDescriptions(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.ContentDescription)) {
            node.config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
    }

    private fun text(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun hasBrightPixel(scene: ImageComposeScene, node: SemanticsNode): Boolean {
        val bitmap = scene.render().toComposeImageBitmap().asSkiaBitmap()
        try {
            val bounds = node.boundsInRoot
            val left = bounds.left.toInt().coerceAtLeast(0)
            val top = bounds.top.toInt().coerceAtLeast(0)
            val right = bounds.right.toInt().coerceAtMost(bitmap.width)
            val bottom = bounds.bottom.toInt().coerceAtMost(bitmap.height)
            return (top until bottom).any { y ->
                (left until right).any { x ->
                    val color = bitmap.getColor(x, y)
                    color shr 16 and 0xFF > 180 && color shr 8 and 0xFF > 180 && color and 0xFF > 180
                }
            }
        } finally {
            bitmap.close()
        }
    }

    private fun hasDarkPixel(scene: ImageComposeScene, node: SemanticsNode): Boolean {
        val bitmap = scene.render().toComposeImageBitmap().asSkiaBitmap()
        try {
            val bounds = node.boundsInRoot
            val left = bounds.left.toInt().coerceAtLeast(0)
            val top = bounds.top.toInt().coerceAtLeast(0)
            val right = bounds.right.toInt().coerceAtMost(bitmap.width)
            val bottom = bounds.bottom.toInt().coerceAtMost(bitmap.height)
            return (top until bottom).any { y ->
                (left until right).any { x ->
                    val color = bitmap.getColor(x, y)
                    color shr 16 and 0xFF < 100 && color shr 8 and 0xFF < 100 && color and 0xFF < 100
                }
            }
        } finally {
            bitmap.close()
        }
    }

    private fun writePng(scene: ImageComposeScene, name: String) {
        val outputDirectory = System.getenv("MIHON_READER_SAMPLE_DIR")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File("build/reader-boundary-samples")
        val bitmap = scene.render().toComposeImageBitmap().asSkiaBitmap()
        try {
            val bytes = SkiaImage.makeFromBitmap(bitmap).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)
            } ?: error("Failed to encode reader transition frame")
            outputDirectory.resolve(name).apply { parentFile?.mkdirs() }.writeBytes(bytes.bytes)
        } finally {
            bitmap.close()
        }
    }
}
