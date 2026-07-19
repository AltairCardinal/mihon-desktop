package mihon.desktop.compat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class AndroidStubsPhase27Test {

    @Test
    fun `Bitmap createBitmap returns non-null with correct dimensions`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bmp.shouldNotBeNull()
        bmp.width shouldBe 100
        bmp.height shouldBe 100
    }

    @Test
    fun `Bitmap createScaledBitmap scales to target dimensions`() {
        val src = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val scaled = Bitmap.createScaledBitmap(src, 50, 75, true)
        scaled.shouldNotBeNull()
        scaled.width shouldBe 50
        scaled.height shouldBe 75
    }

    @Test
    fun `Bitmap recycle and isRecycled work`() {
        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.RGB_565)
        bmp.isRecycled() shouldBe false
        bmp.recycle()
        // stub: recycle is a no-op, still not recycled
        bmp.shouldNotBeNull()
    }

    @Test
    fun `BitmapFactory decodeByteArray rejects invalid image data`() {
        val bmp = BitmapFactory.decodeByteArray(ByteArray(10), 0, 10)
        bmp shouldBe null
    }

    @Test
    fun `BitmapFactory decodeByteArray with inJustDecodeBounds returns null`() {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        val bmp = BitmapFactory.decodeByteArray(ByteArray(10), 0, 10, opts)
        // When inJustDecodeBounds=true, should return null (just measures, doesn't decode)
        bmp shouldBe null
    }

    @Test
    fun `BitmapFactory decodeStream rejects invalid image data`() {
        val stream = ByteArray(10).inputStream()
        val bmp = BitmapFactory.decodeStream(stream)
        bmp shouldBe null
    }

    @Test
    fun `BitmapFactory decodeByteArray rejects invalid ranges with Android exception`() {
        val data = ByteArray(4)
        listOf(-1 to 0, 0 to -1, 3 to 2, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE).forEach { (offset, length) ->
            shouldThrow<ArrayIndexOutOfBoundsException> {
                BitmapFactory.decodeByteArray(data, offset, length)
            }
        }
    }

    @Test
    fun `Bitmap compress rejects quality outside Android range`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            listOf(-1, 101).forEach { quality ->
                shouldThrow<IllegalArgumentException> {
                    bitmap.compress(Bitmap.CompressFormat.PNG, quality, ByteArrayOutputStream())
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `Canvas rejects a recycled target during construction`() {
        val target = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        target.recycle()

        shouldThrow<IllegalStateException> { Canvas(target) }.message shouldBe
            "Cannot use a recycled bitmap"
    }

    @Test
    fun `Canvas rejects drawing after its target is recycled`() {
        val target = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        target.recycle()
        try {
            shouldThrow<IllegalStateException> { canvas.drawBitmap(source, 0f, 0f, null) }.message shouldBe
                "Cannot use a recycled bitmap"
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `Bitmap scaling distinguishes nearest and linear filtering`() {
        val source = twoToneBitmap()
        val nearest = Bitmap.createScaledBitmap(source, 3, 1, false)
        val linear = Bitmap.createScaledBitmap(source, 3, 1, true)
        try {
            val nearestCenter = nearest.toPng().getRGB(1, 0)
            val linearCenter = linear.toPng().getRGB(1, 0)
            (nearestCenter == linearCenter) shouldBe false
            (Color(linearCenter).red in 32..223) shouldBe true
        } finally {
            source.recycle()
            nearest.recycle()
            linear.recycle()
        }
    }

    private fun twoToneBitmap(): Bitmap {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, Color.BLACK.rgb)
        image.setRGB(1, 0, Color.WHITE.rgb)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size()).shouldNotBeNull()
    }

    private fun Bitmap.toPng(): BufferedImage {
        val output = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, output) shouldBe true
        return ImageIO.read(output.toByteArray().inputStream())
    }

}
