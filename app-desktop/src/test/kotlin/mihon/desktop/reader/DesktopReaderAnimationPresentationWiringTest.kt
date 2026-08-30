package mihon.desktop.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.ui.reader.ReaderPresentationImage
import mihon.desktop.ui.reader.ZoomablePageBox
import mihon.desktop.ui.reader.rememberReaderPresentationImage
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopReaderAnimationPresentationWiringTest {

    @Test
    fun `mounted Zoomable presentation automatically advances a real animation and stops after detach`() = runTest {
        val fixture = Fixture(this, animatedGifBytes())
        var presentation: ReaderPresentationImage? = null
        val scene = ImageComposeScene(96, 64, coroutineContext = coroutineContext) {}
        var sceneClosed = false
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                val image = rememberReaderPresentationImage(
                    owner = fixture.owner,
                    page = PAGE,
                    generation = GENERATION,
                )
                presentation = image
                ZoomablePageBox(
                    presentationImage = image,
                    pageLabel = "animated page",
                    zoomState = ZoomState(),
                    onZoomChange = {},
                )
            }

            val firstPixel = pumpUntilPixel(scene, Color.RED.rgb)
            val firstPresentation = requireNotNull(presentation)
            val firstToken = requireNotNull(firstPresentation.animationDrawToken)
            assertEquals(0, firstToken.frameIndex)
            assertEquals(0, firstPresentation.renderedAnimationFrameIndex)

            advanceTimeBy(40)
            val secondPixel = pumpUntilPixel(scene, Color.BLUE.rgb)
            val secondPresentation = requireNotNull(presentation)
            val secondToken = requireNotNull(secondPresentation.animationDrawToken)

            assertEquals(Color.RED.rgb, firstPixel)
            assertEquals(Color.BLUE.rgb, secondPixel)
            assertEquals(1, secondToken.frameIndex)
            assertEquals(1, secondPresentation.renderedAnimationFrameIndex)
            assertNotEquals(firstToken, secondToken, "Every drawn frame needs a frame-specific identity")
            assertEquals(1, fixture.contentReads.get(), "Animation frames must share one physical encoded open")
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE })
            assertEquals(
                listOf(PageDecodePurpose.FULL_PAGE, PageDecodePurpose.ANIMATION_FRAME, PageDecodePurpose.ANIMATION_FRAME),
                fixture.decodedKeys.map(ReaderPageDecodeKey::purpose),
            )
            assertEquals(listOf(0, 1), fixture.decodedKeys.mapNotNull(ReaderPageDecodeKey::frameIndex))

            scene.close()
            sceneClosed = true
            val decodeCountAtDetach = fixture.decodedKeys.size
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(decodeCountAtDetach, fixture.decodedKeys.size, "Detached presentation must stop its frame job")
        } finally {
            if (!sceneClosed) scene.close()
            fixture.close()
        }
    }

    @Test
    fun `mounted static PNG stays on the full page path without starting a frame job`() = runTest {
        val fixture = Fixture(this, pngBytes(Color.GREEN))
        var presentation: ReaderPresentationImage? = null
        val scene = ImageComposeScene(96, 64, coroutineContext = coroutineContext) {}
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                val image = rememberReaderPresentationImage(fixture.owner, PAGE, GENERATION)
                presentation = image
                ZoomablePageBox(
                    presentationImage = image,
                    pageLabel = "static page",
                    zoomState = ZoomState(),
                    onZoomChange = {},
                )
            }

            assertEquals(Color.GREEN.rgb, pumpUntilPixel(scene, Color.GREEN.rgb))
            advanceTimeBy(1_000)
            repeat(PUMP_LIMIT) {
                runCurrent()
                scene.render().toComposeImageBitmap().asSkiaBitmap().close()
            }

            assertNull(presentation?.animationDrawToken)
            assertNull(presentation?.renderedAnimationFrameIndex)
            assertEquals(1, fixture.contentReads.get())
            assertEquals(listOf(PageDecodePurpose.FULL_PAGE), fixture.decodedKeys.map(ReaderPageDecodeKey::purpose))
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.OPEN_PAGE })
            assertEquals(1, fixture.events.count { it.type == ReaderIoEventType.DECODE })
        } finally {
            scene.close()
            fixture.close()
        }
    }

    @Test
    fun `same page generation and ref remounts the presentation holder for a newer Retry attempt`() = runTest {
        val fixture = Fixture(this, pngBytes(Color.GREEN))
        val page = mutableStateOf(PAGE.copy(attemptGeneration = 1L))
        var presentation: ReaderPresentationImage? = null
        val scene = ImageComposeScene(96, 64, coroutineContext = coroutineContext) {}
        try {
            fixture.owner.beginGeneration(GENERATION)
            scene.setContent {
                presentation = rememberReaderPresentationImage(
                    owner = fixture.owner,
                    page = page.value,
                    generation = GENERATION,
                )
            }

            runCurrent()
            scene.render().toComposeImageBitmap().asSkiaBitmap().close()
            runCurrent()
            val first = requireNotNull(presentation)
            assertEquals(1L, first.holder.identity.attemptGeneration)
            assertEquals(1L, first.holder.decodeKey.contentKey.attemptGeneration)

            page.value = PAGE.copy(attemptGeneration = 2L)
            runCurrent()
            scene.render().toComposeImageBitmap().asSkiaBitmap().close()
            runCurrent()
            val second = requireNotNull(presentation)

            assertNotSame(first.holder, second.holder)
            assertEquals(2L, second.holder.identity.attemptGeneration)
            assertEquals(2L, second.holder.decodeKey.contentKey.attemptGeneration)
            assertEquals(listOf(1L, 2L), fixture.decodedKeys.map { it.contentKey.attemptGeneration })
        } finally {
            scene.close()
            fixture.close()
        }
    }

    private suspend fun TestScope.pumpUntilPixel(scene: ImageComposeScene, expected: Int): Int {
        var actual = 0
        repeat(PUMP_LIMIT) {
            runCurrent()
            val rendered = scene.render().toComposeImageBitmap().asSkiaBitmap()
            try {
                actual = rendered.getColor(rendered.width / 2, rendered.height / 2)
            } finally {
                rendered.close()
            }
            if (sameRgb(actual, expected)) return actual
        }
        assertTrue(sameRgb(actual, expected), "Expected RGB ${expected.rgbHex()}, got ${actual.rgbHex()}")
        return actual
    }

    private class Fixture(
        scope: TestScope,
        encoded: ByteArray,
    ) : AutoCloseable {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val decodedKeys = CopyOnWriteArrayList<ReaderPageDecodeKey>()
        val contentReads = AtomicInteger()
        private val clock = AtomicLong()
        private val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock(clock::incrementAndGet),
        )
        private val contentOwner = DesktopReaderPageContentOwner(
            scope = scope,
            encodedPageReader = {
                contentReads.incrementAndGet()
                encoded
            },
            ioReporter = reporter,
        )
        private val pipeline = DesktopReaderPageImagePipeline(
            scope = scope,
            pageContentOwner = contentOwner,
            ioReporter = reporter,
            decoder = DesktopReaderPageImageDecoder { bytes, key ->
                decodedKeys += key
                SkiaDesktopReaderPageImageDecoder().decode(bytes, key)
            },
        )
        val owner = DesktopReaderPresentationImageOwner(
            scope = scope,
            pageImagePipeline = pipeline,
            pageIoObserver = ReaderPageIoObserver(reporter),
        )

        override fun close() {
            owner.close()
            pipeline.close()
            contentOwner.close()
        }
    }

    private fun animatedGifBytes(): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        return ByteArrayOutputStream().also { bytes ->
            ImageIO.createImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                writer.writeToSequence(gifFrame(Color.RED, 4, loopCount = 0), null)
                writer.writeToSequence(gifFrame(Color.BLUE, 9), null)
                writer.endWriteSequence()
            }
            writer.dispose()
        }.toByteArray()
    }

    private fun gifFrame(color: Color, delayCentiseconds: Int, loopCount: Int? = null): IIOImage {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                this.color = color
                fillRect(0, 0, width, height)
                dispose()
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
        writer.dispose()
        val format = metadata.nativeMetadataFormatName
        val root = metadata.getAsTree(format) as IIOMetadataNode
        (root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode).apply {
            setAttribute("disposalMethod", "none")
            setAttribute("userInputFlag", "FALSE")
            setAttribute("transparentColorFlag", "FALSE")
            setAttribute("delayTime", delayCentiseconds.toString())
            setAttribute("transparentColorIndex", "0")
        }
        if (loopCount != null) {
            val extensions = (root.getElementsByTagName("ApplicationExtensions").item(0) as? IIOMetadataNode)
                ?: IIOMetadataNode("ApplicationExtensions").also(root::appendChild)
            extensions.appendChild(
                IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(1, (loopCount and 0xFF).toByte(), ((loopCount ushr 8) and 0xFF).toByte())
                },
            )
        }
        metadata.setFromTree(format, root)
        return IIOImage(image, null, metadata)
    }

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                this.color = color
                fillRect(0, 0, width, height)
                dispose()
            }
        }
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
    }

    private fun sameRgb(first: Int, second: Int): Boolean = (first and RGB_MASK) == (second and RGB_MASK)
    private fun Int.rgbHex(): String = "#%06X".format(this and RGB_MASK)

    private companion object {
        const val GENERATION = 1L
        const val PUMP_LIMIT = 40
        const val RGB_MASK = 0x00FFFFFF
        val PAGE = ReaderPageSession(
            id = ReaderPageId(ReaderChapterId(1L), 0),
            url = "/page/0",
            imageUrl = null,
            encodedPageRef = EncodedPageRef("test:animated-presentation"),
            loadState = ReaderPageLoadState.Ready,
        )
    }
}
