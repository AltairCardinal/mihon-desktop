package mihon.desktop.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderAnimationDecoderPlaybackTest {

    @Test
    fun `production Skia decoder discovers real GIF metadata and decodes distinct bounded frames`() = runTest {
        val bytes = animatedGifBytes(
            frames = listOf(
                GifFrame(Color.RED, durationMillis = 40),
                GifFrame(Color.BLUE, durationMillis = 90),
            ),
            repeatCount = 2,
        )
        val decoder = SkiaDesktopReaderPageImageDecoder()

        val fullLease = requireNotNull(decoder.decode(bytes, decodeKey(PageDecodePurpose.FULL_PAGE)))
        val metadata = requireNotNull(fullLease.asset.animationMetadata)
        assertEquals(2, metadata.frameCount)
        assertEquals(listOf(40L, 90L), metadata.frames.map(DesktopReaderAnimationFrameMetadata::durationMillis))
        assertEquals(
            listOf(PixelBounds(0, 0, GIF_WIDTH, GIF_HEIGHT), PixelBounds(0, 0, GIF_WIDTH, GIF_HEIGHT)),
            metadata.frames.map(DesktopReaderAnimationFrameMetadata::bounds),
        )
        assertEquals(2, metadata.repeatCount)

        val frameZero = requireNotNull(
            decoder.decode(bytes, decodeKey(PageDecodePurpose.ANIMATION_FRAME, frameIndex = 0)),
        )
        val frameOne = requireNotNull(
            decoder.decode(bytes, decodeKey(PageDecodePurpose.ANIMATION_FRAME, frameIndex = 1)),
        )
        try {
            assertEquals(GIF_WIDTH, frameZero.asset.sourceWidth)
            assertEquals(GIF_HEIGHT, frameZero.asset.sourceHeight)
            assertEquals(GIF_WIDTH, frameOne.asset.sourceWidth)
            assertEquals(GIF_HEIGHT, frameOne.asset.sourceHeight)
            assertEquals(Color.RED.rgb, frameZero.asset.bitmap.asSkiaBitmap().getColor(0, 0))
            assertEquals(Color.BLUE.rgb, frameOne.asset.bitmap.asSkiaBitmap().getColor(0, 0))
            assertNotEquals(
                frameZero.asset.bitmap.asSkiaBitmap().getColor(0, 0),
                frameOne.asset.bitmap.asSkiaBitmap().getColor(0, 0),
            )
        } finally {
            frameZero.close()
            frameOne.close()
            fullLease.close()
        }

        assertThrows(IllegalStateException::class.java) { frameZero.retain() }
        assertThrows(IllegalStateException::class.java) { frameOne.retain() }
        assertThrows(IllegalStateException::class.java) { fullLease.retain() }
    }

    @Test
    fun `production Skia decoder composes transparent dependent frames and restore previous disposal`() = runTest {
        val bytes = animatedGifBytes(
            frames = listOf(
                GifFrame(Color.RED, durationMillis = 40),
                GifFrame(
                    Color.BLUE,
                    durationMillis = 50,
                    transparentPatch = 0 to 0,
                    disposalMethod = "restoreToPrevious",
                ),
                GifFrame(Color.GREEN, durationMillis = 60, transparentPatch = 1 to 0),
            ),
            repeatCount = 0,
        )
        val decoder = SkiaDesktopReaderPageImageDecoder()
        val first = requireNotNull(
            decoder.decode(bytes, decodeKey(PageDecodePurpose.ANIMATION_FRAME, frameIndex = 0)),
        )
        val second = requireNotNull(
            decoder.decode(bytes, decodeKey(PageDecodePurpose.ANIMATION_FRAME, frameIndex = 1)),
        )
        val third = requireNotNull(
            decoder.decode(bytes, decodeKey(PageDecodePurpose.ANIMATION_FRAME, frameIndex = 2)),
        )
        try {
            val firstBitmap = first.asset.bitmap.asSkiaBitmap()
            val secondBitmap = second.asset.bitmap.asSkiaBitmap()
            val thirdBitmap = third.asset.bitmap.asSkiaBitmap()
            assertNotEquals(firstBitmap.getColor(0, 0), secondBitmap.getColor(0, 0))
            assertEquals(firstBitmap.getColor(2, 1), secondBitmap.getColor(2, 1))
            assertEquals(firstBitmap.getColor(0, 0), thirdBitmap.getColor(0, 0))
            assertNotEquals(firstBitmap.getColor(1, 0), thirdBitmap.getColor(1, 0))
            assertEquals(firstBitmap.getColor(2, 1), thirdBitmap.getColor(2, 1))
        } finally {
            first.close()
            second.close()
            third.close()
        }
    }

    @Test
    fun `sampled transparent animation frame keeps untouched output pixels transparent`() = runTest {
        val bytes = animatedGifBytes(
            frames = listOf(
                GifFrame(Color.BLUE, durationMillis = 40, transparentPatch = 0 to 0),
                GifFrame(Color.GREEN, durationMillis = 50, transparentPatch = 0 to 0),
            ),
            repeatCount = 0,
        )
        val decoder = SkiaDesktopReaderPageImageDecoder()
        val frame = requireNotNull(
            decoder.decode(
                bytes,
                decodeKey(
                    purpose = PageDecodePurpose.ANIMATION_FRAME,
                    frameIndex = 0,
                    maxWidth = 2,
                    maxHeight = 1,
                ),
            ),
        )
        try {
            val bitmap = frame.asset.bitmap.asSkiaBitmap()
            assertTrue(frame.asset.sampled)
            assertEquals(2, bitmap.width)
            assertEquals(1, bitmap.height)
            assertEquals(0, Color(bitmap.getColor(1, 0), true).alpha)
        } finally {
            frame.close()
        }
    }

    @Test
    fun `playback advances by frame duration and emits a frame specific draw token`() {
        val controller = playbackController(
            durationsMillis = listOf(40L, 90L),
            repeatCount = null,
        )
        val initial = controller.snapshot()

        controller.advanceBy(39)
        assertEquals(initial.drawToken, controller.snapshot().drawToken)
        assertEquals(0, controller.snapshot().frameIndex)

        controller.advanceBy(1)
        val secondFrame = controller.snapshot()
        assertEquals(1, secondFrame.frameIndex)
        assertNotEquals(initial.drawToken, secondFrame.drawToken)
        assertEquals(1, requireNotNull(secondFrame.drawToken).frameIndex)

        controller.advanceBy(89)
        assertEquals(secondFrame.drawToken, controller.snapshot().drawToken)
        controller.advanceBy(1)
        assertEquals(0, controller.snapshot().frameIndex)
        assertNotEquals(secondFrame.drawToken, controller.snapshot().drawToken)
        assertTrue(controller.snapshot().running)
    }

    @Test
    fun `Skia repeat count excludes the first playthrough while infinite playback keeps cycling`() {
        val finite = playbackController(durationsMillis = listOf(40L, 90L), repeatCount = 2)
        finite.advanceBy(390)

        assertEquals(1, finite.snapshot().frameIndex)
        assertEquals(3, finite.snapshot().completedIterations)
        assertFalse(finite.snapshot().running)

        val infinite = playbackController(durationsMillis = listOf(40L, 90L), repeatCount = null)
        infinite.advanceBy(260)
        assertEquals(0, infinite.snapshot().frameIndex)
        assertEquals(2, infinite.snapshot().completedIterations)
        assertTrue(infinite.snapshot().running)
    }

    @Test
    fun `zero duration is clamped to a minimum nonzero interval`() {
        val controller = playbackController(
            durationsMillis = listOf(0L, 20L),
            repeatCount = null,
            minimumFrameDurationMillis = 10L,
        )

        controller.advanceBy(9)
        assertEquals(0, controller.snapshot().frameIndex)
        controller.advanceBy(1)
        assertEquals(1, controller.snapshot().frameIndex)
    }

    @Test
    fun `detach stops playback and invalidates later time without changing the final draw token`() {
        val controller = playbackController(durationsMillis = listOf(40L, 90L), repeatCount = null)
        controller.advanceBy(40)
        val beforeDetach = controller.snapshot()

        controller.detach()
        controller.advanceBy(10_000)

        assertFalse(controller.snapshot().running)
        assertEquals(beforeDetach.frameIndex, controller.snapshot().frameIndex)
        assertEquals(beforeDetach.drawToken, controller.snapshot().drawToken)
    }

    @Test
    fun `static metadata never starts an animation controller`() {
        val controller = DesktopReaderAnimationPlaybackController(
            metadata = null,
            minimumFrameDurationMillis = 10L,
        )

        assertFalse(controller.snapshot().running)
        assertEquals(null, controller.snapshot().drawToken)
        controller.advanceBy(10_000)
        assertFalse(controller.snapshot().running)
        assertEquals(null, controller.snapshot().drawToken)
    }

    private fun playbackController(
        durationsMillis: List<Long>,
        repeatCount: Int?,
        minimumFrameDurationMillis: Long = 10L,
    ) = DesktopReaderAnimationPlaybackController(
        metadata = DesktopReaderAnimationMetadata(
            frames = durationsMillis.map { duration ->
                DesktopReaderAnimationFrameMetadata(
                    durationMillis = duration,
                    bounds = PixelBounds(0, 0, GIF_WIDTH, GIF_HEIGHT),
                )
            },
            repeatCount = repeatCount,
        ),
        minimumFrameDurationMillis = minimumFrameDurationMillis,
    )

    private fun decodeKey(
        purpose: PageDecodePurpose,
        frameIndex: Int? = null,
        maxWidth: Int = GIF_WIDTH,
        maxHeight: Int = GIF_HEIGHT,
    ) = ReaderPageDecodeKey(
        contentKey = ReaderPageContentOpenRequest(
            pageId = ReaderPageId(ReaderChapterId(1L), 0),
            generation = 1L,
            encodedPageRef = EncodedPageRef("opaque://real-animation.gif"),
        ),
        purpose = purpose,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        frameIndex = frameIndex,
    )

    private fun animatedGifBytes(
        frames: List<GifFrame>,
        repeatCount: Int,
    ): ByteArray {
        require(frames.size >= 2)
        require(repeatCount >= 0)
        val writer = checkNotNull(ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull())
        val output = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, frame ->
                val image = frame.toImage()
                val imageType = ImageTypeSpecifier.createFromRenderedImage(image)
                val metadata = writer.getDefaultImageMetadata(imageType, writer.defaultWriteParam)
                val root = metadata.getAsTree(GIF_METADATA_FORMAT) as IIOMetadataNode
                val control = root.child(GRAPHICS_CONTROL_EXTENSION)
                control.setAttribute("disposalMethod", frame.disposalMethod)
                control.setAttribute("userInputFlag", "FALSE")
                control.setAttribute("transparentColorFlag", (frame.transparentPatch != null).toString().uppercase())
                control.setAttribute("delayTime", (frame.durationMillis / 10).toString())
                control.setAttribute("transparentColorIndex", "0")
                if (index == 0) root.addLoopExtension(repeatCount)
                metadata.setFromTree(GIF_METADATA_FORMAT, root)
                writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
            }
            writer.endWriteSequence()
            imageOutput.flush()
        }
        writer.dispose()
        return output.toByteArray()
    }

    private fun IIOMetadataNode.child(name: String): IIOMetadataNode =
        (0 until length)
            .asSequence()
            .map { item(it) as IIOMetadataNode }
            .firstOrNull { it.nodeName == name }
            ?: IIOMetadataNode(name).also(::appendChild)

    private fun IIOMetadataNode.addLoopExtension(repeatCount: Int) {
        val extensions = child(APPLICATION_EXTENSIONS)
        val extension = IIOMetadataNode(APPLICATION_EXTENSION).apply {
            setAttribute("applicationID", "NETSCAPE")
            setAttribute("authenticationCode", "2.0")
            userObject = byteArrayOf(
                0x1,
                (repeatCount and 0xFF).toByte(),
                ((repeatCount ushr 8) and 0xFF).toByte(),
            )
        }
        extensions.appendChild(extension)
    }

    private data class GifFrame(
        val color: Color,
        val durationMillis: Int,
        val transparentPatch: Pair<Int, Int>? = null,
        val disposalMethod: String = "none",
    ) {
        fun toImage(): BufferedImage {
            val patch = transparentPatch
            if (patch == null) {
                return BufferedImage(GIF_WIDTH, GIF_HEIGHT, BufferedImage.TYPE_INT_ARGB).apply {
                    createGraphics().run {
                        color = this@GifFrame.color
                        fillRect(0, 0, width, height)
                        dispose()
                    }
                }
            }
            val colorModel = IndexColorModel(
                8,
                2,
                byteArrayOf(0, color.red.toByte()),
                byteArrayOf(0, color.green.toByte()),
                byteArrayOf(0, color.blue.toByte()),
                0,
            )
            return BufferedImage(GIF_WIDTH, GIF_HEIGHT, BufferedImage.TYPE_BYTE_INDEXED, colorModel).apply {
                raster.setSample(patch.first, patch.second, 0, 1)
            }
        }
    }

    private companion object {
        const val GIF_WIDTH = 3
        const val GIF_HEIGHT = 2
        const val GIF_METADATA_FORMAT = "javax_imageio_gif_image_1.0"
        const val GRAPHICS_CONTROL_EXTENSION = "GraphicControlExtension"
        const val APPLICATION_EXTENSIONS = "ApplicationExtensions"
        const val APPLICATION_EXTENSION = "ApplicationExtension"
    }
}
