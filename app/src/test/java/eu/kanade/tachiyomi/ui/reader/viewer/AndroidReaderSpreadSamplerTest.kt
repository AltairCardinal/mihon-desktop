package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderPageSideHints
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidReaderSpreadSamplerTest {
    @Test
    fun `O7 diagnostics bind actual original calls and native regions only when enabled`() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val page = ReaderPage(1, stream = { encoded.inputStream() })
        val owner = session(page)
        var decodes = 0
        try {
            val request = requireNotNull(owner.requestOriginalAnalysis(id(1)))
            val lines = o7Diagnostics {
                repeat(3) { index ->
                    eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.enabled = index != 1
                    assertNotNull(
                        AndroidReaderSpreadSampler.sampleOriginal(page, request, 40, 60, { stream ->
                            val decoder = decodedRegionFactory(stream)
                            object : ReaderPageRegionDecoder {
                                override fun decode(region: Rect): Bitmap {
                                    decodes++
                                    if (index == 2) eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.enabled = false
                                    return decoder.decode(region)
                                }
                                override fun recycle() = decoder.recycle()
                            }
                        }),
                    )
                }
            }
            assertEquals(6, decodes)
            val calls = lines.filter { "event=sample_begin " in it }
            assertEquals("Only the two ON actual calls are recorded, even though source1 is not target4", 2, calls.size)
            assertTrue(calls.all { "chapter=87 sourceIndex=1 " in it })
            val serials = calls.map { requireNotNull(o7Field(it, "serial")).toInt() }
            assertEquals("OFF must not consume a diagnostic call serial", serials[0] + 1, serials[1])
            for (serial in serials) {
                val events = lines.filter { o7Field(it, "serial") == serial.toString() }
                assertEquals(1, events.count { "event=sample_end " in it && "outcome=COMPUTED" in it })
                val begins = events.filter { "event=sample_region_begin " in it }
                val ends = events.filter { "event=sample_region_end " in it }
                assertEquals(2, begins.size)
                assertEquals(listOf("1", "2"), begins.map { o7Field(it, "context") })
                assertEquals(begins.map { o7Field(it, "context") }, ends.map { o7Field(it, "context") })
                assertTrue(ends.all { "outcome=RETURNED" in it })
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun `O7 diagnostics close failed cancelled and empty original calls`() = runBlocking {
        for (case in listOf("ERROR", "CANCELLED", "CONSTRUCTOR", "NO_PLAN", "EMPTY", "NO_STREAM")) {
            val failure = if (case ==
                "CANCELLED"
            ) {
                kotlinx.coroutines.CancellationException()
            } else {
                IllegalStateException()
            }
            var recycled = 0
            var regions = 0
            val page = ReaderPage(1, stream = { byteArrayOf().inputStream() })
            val owner = session(page)
            try {
                val request = requireNotNull(owner.requestOriginalAnalysis(id(1)))
                if (case == "NO_STREAM") page.stream = null
                val lines = o7Diagnostics {
                    val result = runCatching {
                        AndroidReaderSpreadSampler.sampleOriginal(
                            page,
                            request,
                            if (case == "NO_PLAN") 0 else 40,
                            if (case == "EMPTY") 40 else 60,
                            decoderFactory = {
                                if (case == "CONSTRUCTOR") throw failure
                                object : ReaderPageRegionDecoder {
                                    override fun decode(region: Rect): Bitmap {
                                        regions++
                                        throw failure
                                    }
                                    override fun recycle() {
                                        recycled++
                                    }
                                }
                            },
                        )
                    }
                    if (case in listOf("ERROR", "CANCELLED", "CONSTRUCTOR")) {
                        org.junit.Assert.assertSame(failure, result.exceptionOrNull())
                    } else {
                        assertTrue(result.isSuccess)
                    }
                }
                assertEquals(case, 1, lines.count { "event=sample_begin " in it })
                val outcome = when (case) {
                    "CONSTRUCTOR" -> "ERROR"
                    "EMPTY" -> "COMPUTED"
                    "NO_STREAM" -> "NO_RESULT"
                    else -> case
                }
                assertEquals(case, 1, lines.count { "event=sample_end " in it && "outcome=$outcome" in it })
                val expectedRegions = if (case in listOf("ERROR", "CANCELLED")) 1 else 0
                assertEquals(expectedRegions, regions)
                assertEquals(expectedRegions, recycled)
                assertEquals(expectedRegions, lines.count { "event=sample_region_begin " in it })
                assertEquals(expectedRegions, lines.count { "event=sample_region_end " in it && "outcome=$case" in it })
            } finally {
                owner.close()
            }
        }
    }

    private fun o7Field(line: String, name: String) =
        line.split(' ').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private suspend fun o7Diagnostics(block: suspend () -> Unit): List<String> {
        val root = java.nio.file.Files.createTempDirectory("reader-o7-sampler").toFile()
        val before = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink
        val sink = eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnosticSink(directory = { root })
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = sink
        eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.configure(true, 1, 87, "o7-test", 4)
        try {
            block()
        } finally {
            eu.kanade.tachiyomi.ui.reader.ReaderOpenLatencyProbe.configure(false, -1, -1, "off")
            eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.sink = before
            sink.close()
        }
        return try {
            root.listFiles().orEmpty().flatMap { it.readLines() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `square original stays Computed without stream or region work in every rotation`() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff4080c0.toInt())
        val encoded = try {
            ByteArrayOutputStream().also {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        var opens = 0
        var streamCloses = 0
        var decoderCreations = 0
        var decoderRecycles = 0
        var regions = 0
        var returnedValues = 0
        var closedOwners = 0
        val borrowedRegions = mutableListOf<Bitmap>()
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) {
                failure = error
            } else if (error !== first) {
                first.addSuppressed(error)
            }
        }
        try {
            for (rotation in PageRotation.entries) {
                val page = ReaderPage(1, stream = {
                    opens++
                    object : java.io.ByteArrayInputStream(encoded) {
                        override fun close() {
                            streamCloses++
                            super.close()
                        }
                    }
                })
                val owner = session(page, rotation = rotation, rawWidth = 40, rawHeight = 40)
                var stepFailure: Throwable? = null
                try {
                    val request = requireNotNull(owner.requestOriginalAnalysis(id(1)))
                    val samples = requireNotNull(
                        AndroidReaderSpreadSampler.sampleOriginal(
                            page,
                            request,
                            40,
                            40,
                            decoderFactory = { stream ->
                                decoderCreations++
                                val nativeStyle = decodedRegionFactory(stream)
                                object : ReaderPageRegionDecoder {
                                    override fun decode(region: Rect): Bitmap {
                                        regions++
                                        return nativeStyle.decode(region).also(borrowedRegions::add)
                                    }
                                    override fun recycle() {
                                        nativeStyle.recycle()
                                        decoderRecycles++
                                    }
                                }
                            },
                        ),
                    )
                    returnedValues += (samples.edges?.left?.size ?: 0) + (samples.edges?.right?.size ?: 0)
                    assertEquals(ReaderPageSideHint.UNKNOWN, samples.sideHint)
                    assertTrue(owner.acceptOriginalAnalysis(request, requireNotNull(owner.pageFact(id(1))), samples))
                    assertTrue(owner.originalAnalysis(id(1)) is ReaderPageAnalysisEvidence.Computed)
                } catch (error: Throwable) {
                    stepFailure = error
                    throw error
                } finally {
                    try {
                        owner.close()
                    } catch (cleanup: Throwable) {
                        val original = stepFailure
                        if (original == null) throw cleanup
                        if (cleanup !== original) original.addSuppressed(cleanup)
                    }
                    if (owner.closed) closedOwners++
                }
            }
            println(
                "R10_SQUARE_ANDROID rotations=${PageRotation.entries.size} streamOpens=$opens " +
                    "decoderCreations=$decoderCreations regionCalls=$regions returnedEdgeValues=$returnedValues",
            )
        } catch (error: Throwable) {
            record(error)
        }
        failure?.let { throw it }
        assertEquals(PageRotation.entries.size, closedOwners)
        assertEquals(opens, streamCloses)
        assertEquals(decoderCreations, decoderRecycles)
        assertTrue(borrowedRegions.all { it.isRecycled })
        assertEquals(0, AndroidReaderSpreadSampler.optionalWork.value)
        assertTrue(requireNotNull(coroutineContext[kotlinx.coroutines.Job]).children.none { it.isActive })
        println(
            "R10_SQUARE_HEALTH case=android closedOwners=$closedOwners " +
                "streamCloses=$streamCloses decoderRecycles=$decoderRecycles " +
                "regionsRecycled=${borrowedRegions.count { it.isRecycled }} optionalWork=0 activeChildren=0",
        )
        // The synchronous sampler launches no background producer; intentional work RED is last.
        assertEquals(0, opens)
        assertEquals(0, decoderCreations)
        assertEquals(0, regions)
        assertEquals(0, returnedValues)
    }

    @Test
    fun `native even crop boundary still returns exact original spine pixels`() = runBlocking {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, 0xff000080.toInt() or ((x and 255) shl 16) or ((y and 255) shl 8))
            }
        }
        val plan = requireNotNull(SharedSpreadMatching.plan(bitmap.width, bitmap.height))
        val expected = mihon.domain.reader.ReaderSpreadEdgeSamples(
            plan.left.map { (x, y) -> bitmap.getPixel(x, y) },
            plan.right.map { (x, y) -> bitmap.getPixel(x, y) },
        )
        val encoded = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        val page = ReaderPage(1, stream = { encoded.inputStream() })
        val session = session(page)
        val nativeCrop: (InputStream) -> ReaderPageRegionDecoder = { stream ->
            val decoder = decodedRegionFactory(stream)
            object : ReaderPageRegionDecoder {
                override fun decode(region: Rect): Bitmap {
                    // Exact libwebp boundary: crop x/y are snapped down to even, retaining requested size.
                    val left = region.left / 2 * 2
                    val top = region.top / 2 * 2
                    return decoder.decode(Rect(left, top, left + region.width(), top + region.height()))
                }
                override fun recycle() = decoder.recycle()
            }
        }
        try {
            val request = requireNotNull(session.requestSpreadSample(id(1)))
            assertEquals(
                expected,
                AndroidReaderSpreadSampler.sampleOriginal(page, request, 400, 600, nativeCrop)?.edges,
            )
            assertEquals(expected, AndroidReaderSpreadSampler.sample(page, request, 400, 600, nativeCrop))
        } finally {
            session.close()
        }
    }

    @Test
    fun `native style factory closes its borrowed input without closing the owned stream twice`() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        var closed = 0
        val page = ReaderPage(1, stream = {
            object : java.io.ByteArrayInputStream(encoded) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        })
        val session = session(page)
        try {
            assertNotNull(
                AndroidReaderSpreadSampler.sampleOriginal(
                    page,
                    requireNotNull(session.requestSpreadSample(id(1))),
                    40,
                    60,
                    { stream -> stream.use { decodedRegionFactory(it) } },
                ),
            )
            assertEquals("The sampler owns the only close of the original stream", 1, closed)
        } finally {
            session.close()
        }
    }

    @Test
    fun `already cancelled wide original never returns computed UNKNOWN`() = runBlocking {
        val page = ReaderPage(1, stream = { error("A wide plan does not need pixel I/O") })
        val session = session(page)
        try {
            val request = requireNotNull(session.requestSpreadSample(id(1)))
            var returnedComputed = false
            launch {
                currentCoroutineContext().cancel()
                runCatching { AndroidReaderSpreadSampler.sampleOriginal(page, request, 600, 400) }
                    .onSuccess { returnedComputed = it != null }
            }.join()
            assertTrue("Cancellation must remain unavailable evidence even for an empty plan", !returnedComputed)
        } finally {
            session.close()
        }
    }

    @Test
    fun `large and very tall originals use bounded blocks through one decoder`() = runBlocking {
        for ((width, height) in listOf(8192 to 12000, 64 to 250000)) {
            var opened = 0
            var recycled = 0
            val decoded = mutableListOf<Rect>()
            val page = ReaderPage(1, stream = {
                opened++
                byteArrayOf().inputStream()
            })
            val session = session(page)
            try {
                val samples = requireNotNull(
                    AndroidReaderSpreadSampler.sampleOriginal(
                        page,
                        requireNotNull(session.requestSpreadSample(id(1))),
                        width,
                        height,
                        {
                            object : ReaderPageRegionDecoder {
                                override fun decode(region: Rect): Bitmap {
                                    decoded += Rect(region)
                                    assertTrue(region.width().toLong() * region.height() * 4 <= 4L * 1024 * 1024)
                                    return Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
                                        .apply { eraseColor(if (region.left < width / 2) -1 else 0xff202020.toInt()) }
                                }
                                override fun recycle() {
                                    recycled++
                                }
                            }
                        },
                    ),
                )
                assertEquals(ReaderPageSideHint.RIGHT, samples.sideHint)
                assertEquals(List(50) { -1 }, samples.edges!!.left)
                assertEquals(List(50) { 0xff202020.toInt() }, samples.edges!!.right)
                assertEquals(1, opened)
                assertEquals(1, recycled)
                assertTrue("The large input must split without full-image fallback", decoded.size > 2)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `legacy large original edges stay bounded without requesting side strips`() = runBlocking {
        var opened = 0
        var closed = 0
        var decoded = 0
        val page = ReaderPage(1, stream = {
            opened++
            object : java.io.ByteArrayInputStream(byteArrayOf()) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        })
        val session = session(page)
        try {
            val request = requireNotNull(session.requestSpreadSample(id(1)))
            val regionsOnly: (InputStream) -> ReaderPageRegionDecoder = {
                object : ReaderPageRegionDecoder {
                    override fun decode(region: Rect): Bitmap {
                        decoded++
                        assertTrue("Codec context stays bounded to at most five source columns", region.width() in 1..5)
                        return Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
                            .apply { eraseColor(-1) }
                    }
                    override fun recycle() = Unit
                }
            }
            val legacy = requireNotNull(AndroidReaderSpreadSampler.sample(page, request, 8192, 12000, regionsOnly))
            assertEquals(List(50) { -1 }, legacy.left)
            assertEquals(2, decoded)
            assertEquals(1, opened)
            assertEquals(1, closed)
        } finally {
            session.close()
        }
    }

    @Test
    fun `fixed original evidence survives display sample sizes and additional rotation`() = runBlocking {
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff202020.toInt())
        for (x in 0..14) for (y in 0 until bitmap.height) bitmap.setPixel(x, y, -1)
        val encoded = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        val page = ReaderPage(1, stream = { encoded.inputStream() })
        val session = session(page, rotation = PageRotation.HALF_TURN)
        try {
            val request = requireNotNull(session.requestSpreadSample(id(1)))
            for (sampleSize in listOf(1, 2, 4)) {
                val display = requireNotNull(
                    BitmapFactory.decodeByteArray(
                        encoded,
                        0,
                        encoded.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize },
                    ),
                )
                try {
                    ReaderPageSideHints.classify(display.width, display.height, display::getPixel)
                    val original = requireNotNull(
                        AndroidReaderSpreadSampler.sampleOriginal(
                            page,
                            request,
                            400,
                            600,
                            { decodedRegionFactory(it) },
                        ),
                    )
                    assertEquals(ReaderPageSideHint.LEFT, original.sideHint)
                    assertEquals(List(50) { 0xff202020.toInt() }, original.edges?.left)
                    assertEquals(List(50) { -1 }, original.edges?.right)
                } finally {
                    display.recycle()
                }
            }
            val legacy = requireNotNull(
                AndroidReaderSpreadSampler.sample(
                    page,
                    request,
                    400,
                    600,
                    { decodedRegionFactory(it) },
                ),
            )
            assertEquals(
                "Legacy spine contract keeps source orientation without user rotation",
                List(50) {
                    -1
                },
                legacy.left,
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `all source orientations read the same oriented page side from original encoded regions`() = runBlocking {
        for (orientation in ReaderImageOrientation.entries) {
            val quarterTurn = orientation.orientedSize(400, 600).width == 600
            val rawWidth = if (quarterTurn) 600 else 400
            val rawHeight = if (quarterTurn) 400 else 600
            val bitmap = Bitmap.createBitmap(rawWidth, rawHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff202020.toInt())
            for (x in 0..14) {
                for (y in 0 until 600) {
                    val (rawX, rawY) = orientation.sourcePixel(x, y, rawWidth, rawHeight)
                    bitmap.setPixel(rawX, rawY, -1)
                }
            }
            val encoded = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            val page = ReaderPage(1, stream = { encoded.inputStream() })
            val session = session(page, orientation)
            try {
                val samples = requireNotNull(
                    AndroidReaderSpreadSampler.sampleOriginal(
                        page,
                        requireNotNull(session.requestSpreadSample(id(1))),
                        rawWidth,
                        rawHeight,
                        { decodedRegionFactory(it) },
                    ),
                )
                assertEquals(orientation.name, ReaderPageSideHint.RIGHT, samples.sideHint)
                assertEquals(orientation.name, List(50) { -1 }, samples.edges?.left)
                assertEquals(orientation.name, List(50) { 0xff202020.toInt() }, samples.edges?.right)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `pause and content replacement release decoded regions without computed side evidence`() = runBlocking {
        for (replace in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(-1)
            val encoded = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            var closed = 0
            var recycled = 0
            var running = true
            val regions = mutableListOf<Bitmap>()
            val page = ReaderPage(1, stream = {
                object : java.io.ByteArrayInputStream(encoded) {
                    override fun close() {
                        closed++
                        super.close()
                    }
                }
            })
            val session = session(page)
            try {
                val result = runCatching {
                    AndroidReaderSpreadSampler.sampleOriginal(
                        page,
                        requireNotNull(session.requestSpreadSample(id(1))),
                        400,
                        600,
                        { stream ->
                            val decoder = decodedRegionFactory(stream) { recycled++ }
                            object : ReaderPageRegionDecoder {
                                override fun decode(region: Rect): Bitmap = decoder.decode(region).also {
                                    regions += it
                                    if (replace) page.stream = { encoded.inputStream() } else running = false
                                }
                                override fun recycle() = decoder.recycle()
                            }
                        },
                        canContinue = { running },
                    )
                }
                if (replace) {
                    assertTrue(result.isSuccess)
                    assertEquals(null, result.getOrNull())
                } else {
                    assertTrue(result.exceptionOrNull() is AndroidReaderOptionalWorkPaused)
                }
                assertEquals(1, closed)
                assertEquals(1, recycled)
                assertTrue(regions.all(Bitmap::isRecycled))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `original encoded sampling covers the inner page side points before display decode`() = runBlocking {
        val original = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        original.eraseColor(0xff202020.toInt())
        for (x in 0..14) for (y in 0 until original.height) original.setPixel(x, y, -1)
        val encoded = ByteArrayOutputStream().also {
            original.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        original.recycle()
        val page = ReaderPage(1, stream = { encoded.inputStream() })
        val session = session(page)
        val regions = mutableListOf<Rect>()
        try {
            val sampled = AndroidReaderSpreadSampler.sampleOriginal(
                page,
                requireNotNull(session.requestSpreadSample(id(1))),
                400,
                600,
                { stream ->
                    val decoder = decodedRegionFactory(stream)
                    object : ReaderPageRegionDecoder {
                        override fun decode(region: Rect): Bitmap {
                            regions += Rect(region)
                            return decoder.decode(region)
                        }
                        override fun recycle() = decoder.recycle()
                    }
                },
            )
            assertNotNull(sampled)
            assertEquals(2, regions.size)
            assertTrue("Left region must include the original 3.5% side point", regions[0].contains(14, 24))
            assertTrue("Right region must include the mirrored side point", regions[1].contains(385, 24))
        } finally {
            session.close()
        }
    }

    @Test
    fun `original encoded lease preserves mirrored alpha samples and releases regions`() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) {
            bitmap.setPixel(2, y, 0x80ff0000.toInt())
            bitmap.setPixel(37, y, 0)
        }
        val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        for (orientation in listOf(ReaderImageOrientation.NORMAL, ReaderImageOrientation.MIRROR_HORIZONTAL)) {
            var closed = 0
            var recycled = 0
            val page = ReaderPage(1, stream = {
                object : java.io.ByteArrayInputStream(encoded) {
                    override fun close() {
                        closed++
                        super.close()
                    }
                }
            })
            val session = session(page, orientation)
            val samples = AndroidReaderSpreadSampler.sample(
                page,
                requireNotNull(session.requestSpreadSample(id(1))),
                40,
                60,
                { stream ->
                    decodedRegionFactory(stream) { recycled++ }
                },
            )
            assertNotNull("Production sampler must execute the common original-coordinate plan", samples)
            val normalizedLeft = requireNotNull(samples).left.map(SharedSpreadMatching::normalizeSample)
            val normalizedRight = samples.right.map(SharedSpreadMatching::normalizeSample)
            assertEquals(
                List(50) { if (orientation == ReaderImageOrientation.NORMAL) 0xffff7f7f.toInt() else -1 },
                normalizedLeft,
            )
            assertEquals(
                List(50) { if (orientation == ReaderImageOrientation.NORMAL) -1 else 0xffff7f7f.toInt() },
                normalizedRight,
            )
            assertEquals(1, closed)
            assertEquals(1, recycled)
            assertTrue(session.acceptSpreadSample(requireNotNull(session.requestSpreadSample(id(1))), samples))
            session.close()
        }
    }

    @Test
    fun `decode failure releases original stream and decoder without a fallback full bitmap`() = runBlocking {
        var opened = 0
        var closed = 0
        var recycled = 0
        val page = ReaderPage(1, stream = {
            opened++
            object : java.io.ByteArrayInputStream(byteArrayOf()) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        })
        val session = session(page)
        val sample = runCatching {
            AndroidReaderSpreadSampler.sample(page, requireNotNull(session.requestSpreadSample(id(1))), 40, 60, {
                object : ReaderPageRegionDecoder {
                    override fun decode(region: Rect): Bitmap = error("native failure")
                    override fun recycle() {
                        recycled++
                    }
                }
            })
        }
        assertTrue(sample.isFailure)
        assertEquals(1, opened)
        assertEquals(1, closed)
        assertEquals(1, recycled)
        session.close()
    }

    private fun session(
        page: ReaderPage,
        orientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL,
        rotation: PageRotation = PageRotation.NONE,
        rawWidth: Int = 40,
        rawHeight: Int = 60,
    ) =
        SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                List(3) { index ->
                    ReaderDualPageContentIdentity(
                        id(index),
                        if (index == 1) page.encodedContentIdentity() else "other-$index",
                        rotation = rotation,
                        sourceOrientation = orientation,
                    )
                },
            ),
            ReaderDirection.RTL,
        ).apply {
            identity.pageIds.forEach {
                acceptFact(
                    requestFact(it),
                    ReaderPageFact.dimensions(
                        rawWidth,
                        rawHeight,
                        rotation = rotation,
                        sourceOrientation = orientation,
                    ),
                )
            }
            configure(ReaderDirection.RTL, automaticMatching = true)
        }

    private fun id(index: Int) = ReaderPageId(ReaderChapterId(87), index)
}

/** Windows tests replace only the unavailable JNI region boundary, using real encoded PNG pixels. */
internal fun decodedRegionFactory(stream: InputStream, onRecycle: () -> Unit = {}): ReaderPageRegionDecoder {
    val source = checkNotNull(BitmapFactory.decodeStream(stream))
    return object : ReaderPageRegionDecoder {
        override fun decode(region: Rect): Bitmap = Bitmap.createBitmap(
            source,
            region.left,
            region.top,
            region.width(),
            region.height(),
        )
        override fun recycle() {
            source.recycle()
            onRecycle()
        }
    }
}
