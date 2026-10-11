package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageRegionPngWiringTest {
    @Test
    fun `an oversized source row fails before decoder creation or temporary file allocation`() = runBlocking {
        val directory = java.io.File(
            RuntimeEnvironment.getApplication().cacheDir,
            "region-row-${System.nanoTime()}",
        ).apply { mkdirs() }
        var created = false
        try {
            val failure = runCatching {
                encodeReaderPageRegion(Buffer(), Rect(0, 0, 1_048_577, 1), directory) {
                    created = true
                    error("decoder must never be opened")
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(!created)
            assertEquals(0, requireNotNull(directory.list()).size)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `cancelled strip write releases bitmap and decoder and removes the undelivered file`() = runBlocking {
        val directory = java.io.File(
            RuntimeEnvironment.getApplication().cacheDir,
            "region-cancel-${System.nanoTime()}",
        ).apply { mkdirs() }
        var bitmap: Bitmap? = null
        var recycled = false
        try {
            val failure = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Job()) {
                    val originalJob = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
                    encodeReaderPageRegion(Buffer(), Rect(0, 0, 10, 10), directory) {
                        object : ReaderPageRegionDecoder {
                            override fun decode(region: Rect): Bitmap {
                                val strip = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
                                bitmap = strip
                                originalJob.cancel(CancellationException("controlled region cancellation"))
                                return strip
                            }
                            override fun recycle() {
                                recycled = true
                                throw java.io.IOException("controlled release failure after cancellation")
                            }
                        }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(recycled)
            assertTrue(bitmap?.isRecycled == true)
            assertEquals(0, requireNotNull(directory.list()).size)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `region output explicitly declares sRGB and preserves transparent pixels`() = runBlocking {
        val directory = java.io.File(
            RuntimeEnvironment.getApplication().cacheDir,
            "region-color-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val output = encodeReaderPageRegion(Buffer(), Rect(0, 0, 10, 10), directory) {
                object : ReaderPageRegionDecoder {
                    override fun decode(
                        region: Rect,
                    ) = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.TRANSPARENT)
                        setPixel(5, 5, Color.RED)
                    }
                    override fun recycle() {}
                }
            }
            val chunks = mutableMapOf<String, ByteArray>()
            java.io.DataInputStream(output.inputStream()).use { input ->
                assertEquals(-8552249625308161526L, input.readLong())
                while (input.available() > 0) {
                    val size = input.readInt()
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    chunks[type] = ByteArray(size).also(input::readFully)
                    input.readInt()
                }
            }
            assertTrue(
                "A streamed intermediate is sRGB, never pixels already converted to the display profile",
                chunks["sRGB"]?.contentEquals(byteArrayOf(0)) == true,
            )
            val decoded = output.inputStream().use { requireNotNull(BitmapFactory.decodeStream(it)) }
            assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0))
            assertEquals(Color.RED, decoded.getPixel(5, 5))
            decoded.recycle()
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `original decoder release failure never leaks an encoded file before its owner receives it`() = runBlocking {
        val directory = java.io.File(
            RuntimeEnvironment.getApplication().cacheDir,
            "region-release-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val failure = runCatching {
                encodeReaderPageRegion(Buffer(), Rect(0, 0, 10, 10), directory) {
                    object : ReaderPageRegionDecoder {
                        override fun decode(region: Rect) = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
                        override fun recycle() =
                            throw java.io.IOException("controlled original decoder release failure")
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals("There is no delivered owner to clean this file", 0, requireNotNull(directory.list()).size)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `half source region streams legal PNG while every native decode stays within four MiB`() = runBlocking {
        val directory = java.io.File(
            RuntimeEnvironment.getApplication().cacheDir,
            "region-png-${System.nanoTime()}",
        ).apply { mkdirs() }
        val regions = mutableListOf<Rect>()
        var recycled = false
        try {
            val output = encodeReaderPageRegion(
                Buffer().writeUtf8("fixture encoded"),
                Rect(31, 7, 1031, 3007),
                directory,
            ) {
                object : ReaderPageRegionDecoder {
                    override fun decode(region: Rect): Bitmap {
                        regions += Rect(region)
                        assertTrue(
                            "Native decode must stay bounded before allocating",
                            region.width().toLong() * region.height() * 4 <= 4L * 1024 * 1024,
                        )
                        return Bitmap.createBitmap(
                            region.width(),
                            region.height(),
                            Bitmap.Config.ARGB_8888,
                        ).apply {
                            eraseColor(
                                if (region.top ==
                                    7
                                ) {
                                    Color.RED
                                } else {
                                    Color.GREEN
                                },
                            )
                        }
                    }
                    override fun recycle() {
                        recycled = true
                    }
                }
            }
            assertTrue(recycled)
            assertTrue(regions.size > 1)
            assertEquals(7, regions.first().top)
            assertEquals(3007, regions.last().bottom)
            regions.zipWithNext().forEach { (first, next) -> assertEquals(first.bottom, next.top) }
            val decoded = output.inputStream().use { requireNotNull(BitmapFactory.decodeStream(it)) }
            assertEquals(1000, decoded.width)
            assertEquals(3000, decoded.height)
            assertEquals(Color.RED, decoded.getPixel(0, 0))
            assertEquals(Color.GREEN, decoded.getPixel(999, 2999))
            decoded.recycle()
            assertTrue(output.delete())
            assertEquals(0, requireNotNull(directory.list()).size)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `failed region decode removes its partial file and closes the original decoder`() = runBlocking {
        val directory = java.io.File(
            RuntimeEnvironment.getApplication().cacheDir,
            "region-fail-${System.nanoTime()}",
        ).apply { mkdirs() }
        var recycled = false
        try {
            val failure = runCatching {
                encodeReaderPageRegion(Buffer(), Rect(0, 0, 100, 100), directory) {
                    object : ReaderPageRegionDecoder {
                        override fun decode(region: Rect): Bitmap =
                            throw java.io.IOException("controlled original decoder failure")
                        override fun recycle() {
                            recycled = true
                        }
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(recycled)
            assertEquals(0, requireNotNull(directory.list()).size)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }
}
