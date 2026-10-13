package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageFactBudget
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderOriginalPageSampling
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderPageSideHints
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.ReaderSpreadSamplesOwner
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit local research inputs are pushed into the isolated .dev instance; missing inputs fail. */
class ReaderOriginalPageSamplingNativeProbeTest {
    @Test
    fun actualChoreographerCallbackPeriodsAreMeasured() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val periods = mutableListOf<Long>()
        val completion = CountDownLatch(1)
        var choreographer: Choreographer? = null
        var previous = 0L
        var completed = false
        var active = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!active) return
                if (previous != 0L) periods += frameTimeNanos - previous
                previous = frameTimeNanos
                if (periods.size == 120) completion.countDown() else choreographer?.postFrameCallback(this)
            }
        }
        try {
            instrumentation.runOnMainSync {
                choreographer = Choreographer.getInstance().also { it.postFrameCallback(callback) }
            }
            completed = completion.await(10, TimeUnit.SECONDS)
            assertTrue("120 adjacent Choreographer intervals must arrive before timeout", completed)
            assertEquals(120, periods.size)
            assertTrue("Adjacent frameTimeNanos values must advance", periods.all { it > 0 })
        } finally {
            instrumentation.runOnMainSync {
                active = false
                choreographer?.removeFrameCallback(callback)
            }
            val mode = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.mode
            val report = JSONObject().put("api", android.os.Build.VERSION.SDK_INT)
                .put("metric", "Android Choreographer frameTimeNanos callback intervals")
                .put("units", "nanoseconds").put("requestedIntervals", 120).put("completed", completed)
                .put("periodsNs", JSONArray(periods)).put("displayModeIsOnlyAControl", true)
                .put("displayModeRefreshRateHz", mode?.refreshRate).put("displayModeId", mode?.modeId)
                .put("userFrameJankMeasured", false).put("readerFirstPixelPass", false)
            File(context.filesDir, "pairing-r3-choreographer-periods.json")
                .writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    @Test
    fun readyFilesRespectSharedBytePermitThroughActualJni() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateFiles = context.filesDir
        val report = JSONObject().put("clock", "System.nanoTime").put("componentOnly", true)
            .put("adapterTypedResource", "NOT_EXECUTED; real adapter JVM coverage is separate")
            .put("nativePeakBytes", "NOT_AVAILABLE")
        val files = mutableListOf<File>()
        val counters = List(2) { ReadCounter() }
        val budget = ReaderDualPageFactBudget()
        var owner: ReaderSpreadSamplesOwner? = null
        var passed = false
        try {
            val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff8c8c8c.toInt())
            }
            val encoded = try {
                ByteArrayOutputStream().also {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }.toByteArray()
            } finally {
                bitmap.recycle()
            }
            repeat(2) {
                val file = File.createTempFile("r3-jni-budget-", ".png", context.cacheDir)
                files += file
                RandomAccessFile(file, "rw").use { output ->
                    output.write(encoded)
                    output.setLength(17L * 1024 * 1024)
                }
            }
            val pages = files.mapIndexed { index, file -> measuredPage(file, index, counters[index]) }
            val ids = pages.indices.map { ReaderPageId(ReaderChapterId(85), it) }
            val originalOwner = ReaderSpreadSamplesOwner(
                ReaderDualPageIdentity(
                    1,
                    1,
                    ids.mapIndexed { index, id ->
                        ReaderDualPageContentIdentity(id, pages[index].encodedContentIdentity())
                    },
                ),
            )
            owner = originalOwner
            assertEquals(ids, budget.reserve(ids, SystemClock.uptimeMillis()))
            val permits = ids.map { ReaderDualPageFactReadPermit(budget, it, SystemClock::uptimeMillis) }
            report.put("stage", "FIRST_REAL_JNI_SAMPLE")
            try {
                val first = AndroidReaderSpreadSampler.sampleOriginal(
                    pages[0],
                    originalOwner.request(ids[0]),
                    80,
                    120,
                    readPermit = permits[0],
                )
                assertTrue("The first valid PNG must reach EOF and decode", first != null)
                assertEquals(17L * 1024 * 1024, counters[0].bytes)
                assertTrue(counters[0].eofReads > 0)
                assertFalse("The first active permit still has byte capacity", permits[0].exhausted)
            } finally {
                budget.complete(ids[0], 0, cacheHit = true)
            }
            report.put("stage", "SECOND_REAL_JNI_SAMPLE")
            var failure: Exception? = null
            try {
                AndroidReaderSpreadSampler.sampleOriginal(
                    pages[1],
                    originalOwner.request(ids[1]),
                    80,
                    120,
                    readPermit = permits[1],
                )
            } catch (error: Exception) {
                failure = error
                report.put("secondException", error.javaClass.name).put("secondMessage", error.message)
            } finally {
                // Capture while still active: complete() alone would also make exhausted true.
                report.put("secondPermitExhaustedWhileActive", permits[1].exhausted)
                report.put("callerCoroutineStillActive", currentCoroutineContext().isActive)
                budget.complete(ids[1], 0, cacheHit = true)
            }
            assertTrue("Real JNI must fail when its read-to-EOF crosses the byte permit", failure != null)
            assertTrue(report.getBoolean("secondPermitExhaustedWhileActive"))
            assertTrue(report.getBoolean("callerCoroutineStillActive"))
            assertEquals(15L * 1024 * 1024, counters[1].bytes)
            assertEquals("The limited second input must not reach its physical EOF", 0, counters[1].eofReads)
            val actualBytes = counters.sumOf { it.bytes }
            assertEquals(ReaderDualPageFactLimits.MAX_NEW_BYTES, actualBytes)
            val meteredBytes = budget.javaClass.getDeclaredField("newlyReadBytes").let {
                it.isAccessible = true
                it.getLong(budget)
            }
            report.put("meteredBytes", meteredBytes)
            assertEquals(actualBytes, meteredBytes)
            counters.forEach {
                assertEquals(1, it.opens)
                assertEquals(1, it.closes)
            }
            assertTrue(
                "The same exhausted budget cannot admit a previously unattempted page",
                budget.reserve(listOf(ReaderPageId(ReaderChapterId(85), 2)), SystemClock.uptimeMillis()).isEmpty(),
            )
            report.put("stage", "ASSERTIONS_COMPLETE")
            passed = true
        } finally {
            report.put("passed", passed).put("actualReadBytes", counters.sumOf { it.bytes })
                .put("sources", JSONArray(counters.map { it.json() }))
                .put("fileBytes", JSONArray(files.map(File::length)))
            File(privateFiles, "pairing-r3-ready-file-jni-budget.json").writeText(report.toString(2), Charsets.UTF_8)
            owner?.close()
            budget.close()
            files.forEach(File::delete)
        }
    }

    @Test
    fun sameNativeDecoderComparesTwoStripsWithExperimentalFullImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateFiles = context.filesDir
        val directory = File(privateFiles, "pairing-r3-inputs")
        val inputs = JSONArray(File(directory, "inputs.json").readText(Charsets.UTF_8))
        assertEquals(23, inputs.length())
        repeat(inputs.length()) { index ->
            val input = inputs.getJSONObject(index)
            val bytes = File(directory, input.getString("file")).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(input.getString("sha256"), hash)
            assertEquals(input.getLong("bytes"), bytes.size.toLong())
        }
        val measurements = JSONArray()
        val report = JSONObject().put("clock", "System.nanoTime").put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first()).put("buildType", "Debug")
            .put("componentOnly", true).put("osFileCache", "WARM; hashes and warm-up read the inputs")
            .put("warmupRounds", 2).put("retainedRounds", 7).put("freshNativeDecoderPerTrial", true)
            .put("sharedDecoderWithinTrial", true).put("decodeOnlyComparison", true)
            .put("independentFullLoadingPath", false).put("readerIntentToPixelMeasured", false)
            .put("productionStripCapBytes", 4L * 1024 * 1024)
            .put("experimentalFullMayExceedProductionCap", true).put("nativePeakBytes", "NOT_AVAILABLE")
            .put("argbTolerance", 0)
            .put("algorithmVersion", ReaderOriginalPageSampling.ALGORITHM_VERSION)
            .put("measurements", measurements)
        var passed = false
        try {
            repeat(9) { round ->
                repeat(inputs.length()) { index ->
                    val input = inputs.getJSONObject(index)
                    val file = File(directory, input.getString("file"))
                    val width = input.getInt("width")
                    val height = input.getInt("height")
                    val plan = requireNotNull(ReaderOriginalPageSampling.plan(width, height))
                    assertEquals(194, plan.left.size)
                    assertEquals(194, plan.right.size)
                    val fullFirst = (round + index) % 2 == 0
                    val phases = JSONObject().put("round", round).put("file", file.name)
                        .put("sha256", input.getString("sha256")).put("width", width).put("height", height)
                        .put("order", if (fullFirst) "FULL_THEN_STRIPS" else "STRIPS_THEN_FULL")
                    report.put("currentTrial", phases)
                    val counter = ReadCounter()
                    val page = measuredPage(file, index, counter)
                    val pageId = ReaderPageId(ReaderChapterId(86), index)
                    val owner = ReaderSpreadSamplesOwner(
                        ReaderDualPageIdentity(
                            1,
                            1,
                            listOf(ReaderDualPageContentIdentity(pageId, page.encodedContentIdentity())),
                        ),
                    )
                    val allocations = mutableListOf<Pair<Bitmap, Long>>()
                    var peakLiveBytes = 0L
                    var peakLiveCount = 0
                    fun track(bitmap: Bitmap) {
                        allocations += bitmap to bitmap.allocationByteCount.toLong()
                        val live = allocations.filterNot { it.first.isRecycled }
                        peakLiveBytes = maxOf(peakLiveBytes, live.sumOf { it.second })
                        peakLiveCount = maxOf(peakLiveCount, live.size)
                    }
                    var fullLeft: List<Int>? = null
                    var fullRight: List<Int>? = null
                    var stripLeft: List<Int>? = null
                    var stripRight: List<Int>? = null
                    var decodeCalls = 0
                    var fullCalls = 0
                    var nativeRecycles = 0
                    var stripDecodeNs = 0L
                    var observerExtractNs = 0L
                    fun full(decoder: ReaderPageRegionDecoder) {
                        phases.put("stage", "EXPERIMENTAL_FULL_DECODE")
                        val before = System.nanoTime()
                        val bitmap = decoder.decode(Rect(0, 0, width, height))
                        val decodedAt = System.nanoTime()
                        fullCalls++
                        track(bitmap)
                        phases.put("fullDecodeNs", decodedAt - before)
                            .put("fullBitmapBytes", bitmap.allocationByteCount)
                            .put("fullPremultiplied", bitmap.isPremultiplied)
                            .put("fullColorSpace", bitmap.colorSpace?.name)
                            .put("fullExceedsProductionCap", bitmap.allocationByteCount.toLong() > 4L * 1024 * 1024)
                        phases.put(
                            "fullLiveOutputBytes",
                            allocations.filterNot { it.first.isRecycled }.sumOf { it.second },
                        )
                        try {
                            assertFalse(bitmap.isPremultiplied)
                            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
                            val extractAt = System.nanoTime()
                            fullLeft = plan.left.map { (x, y) -> bitmap.getPixel(x, y) }
                            fullRight = plan.right.map { (x, y) -> bitmap.getPixel(x, y) }
                            phases.put("fullPointExtractNs", System.nanoTime() - extractAt)
                        } finally {
                            val releaseAt = System.nanoTime()
                            bitmap.recycle()
                            phases.put("fullBitmapReleaseNs", System.nanoTime() - releaseAt)
                        }
                    }
                    try {
                        val before = System.nanoTime()
                        val result = requireNotNull(
                            AndroidReaderSpreadSampler.sampleOriginal(
                                page,
                                owner.request(pageId),
                                width,
                                height,
                                decoderFactory = { encoded ->
                                    val createAt = System.nanoTime()
                                    val decoder = AndroidReaderSpreadSampler.nativeDecoder(encoded)
                                    phases.put("sharedNativeCreateNs", System.nanoTime() - createAt)
                                    if (fullFirst) {
                                        try {
                                            full(decoder)
                                        } catch (error: Throwable) {
                                            decoder.recycle()
                                            nativeRecycles++
                                            throw error
                                        }
                                    }
                                    object : ReaderPageRegionDecoder {
                                        override fun decode(region: Rect): Bitmap {
                                            phases.put("stage", "PRODUCTION_STRIP_DECODE")
                                            val decodeAt = System.nanoTime()
                                            val bitmap = decoder.decode(region)
                                            val duration = System.nanoTime() - decodeAt
                                            stripDecodeNs += duration
                                            decodeCalls++
                                            track(bitmap)
                                            try {
                                                assertTrue(bitmap.allocationByteCount.toLong() <= 4L * 1024 * 1024)
                                                assertFalse(bitmap.isPremultiplied)
                                                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
                                                assertTrue(decodeCalls <= 2)
                                                val points = if (decodeCalls == 1) plan.left else plan.right
                                                val extractAt = System.nanoTime()
                                                val values = points.map { (x, y) ->
                                                    bitmap.getPixel(x - region.left, y - region.top)
                                                }
                                                observerExtractNs += System.nanoTime() - extractAt
                                                if (decodeCalls == 1) stripLeft = values else stripRight = values
                                                phases.put("strip${decodeCalls}DecodeNs", duration)
                                                    .put("strip${decodeCalls}BitmapBytes", bitmap.allocationByteCount)
                                                    .put("strip${decodeCalls}Premultiplied", bitmap.isPremultiplied)
                                                    .put("strip${decodeCalls}ColorSpace", bitmap.colorSpace?.name)
                                                // In this order the full output and second strip really coexist.
                                                if (!fullFirst && decodeCalls == 2) full(decoder)
                                                return bitmap
                                            } catch (error: Throwable) {
                                                bitmap.recycle()
                                                throw error
                                            }
                                        }
                                        override fun recycle() {
                                            val recycleAt = System.nanoTime()
                                            decoder.recycle()
                                            nativeRecycles++
                                            phases.put("nativeRecycleNs", System.nanoTime() - recycleAt)
                                        }
                                    }
                                },
                            ),
                        )
                        phases.put("combinedProbeNs", System.nanoTime() - before)
                        assertEquals(2, decodeCalls)
                        assertEquals(1, fullCalls)
                        val left = requireNotNull(fullLeft)
                        val right = requireNotNull(fullRight)
                        if (left != stripLeft || right != stripRight) {
                            File(privateFiles, "pairing-r3-strip-full-pixel-difference.json").writeText(
                                JSONObject().put("trial", phases)
                                    .put("rawLeftCoordinates", JSONArray(plan.left.map { listOf(it.first, it.second) }))
                                    .put(
                                        "rawRightCoordinates",
                                        JSONArray(plan.right.map { listOf(it.first, it.second) }),
                                    )
                                    .put("stripLeft", JSONArray(stripLeft)).put("stripRight", JSONArray(stripRight))
                                    .put("fullLeft", JSONArray(left)).put("fullRight", JSONArray(right)).toString(2),
                                Charsets.UTF_8,
                            )
                        }
                        assertEquals("Exact same-JNI raw left points: ${file.name}", left, stripLeft)
                        assertEquals("Exact same-JNI raw right points: ${file.name}", right, stripRight)
                        val samplesAt = System.nanoTime()
                        val fullSamples = plan.samples(left, right)
                        phases.put("fullPlanSamplesNs", System.nanoTime() - samplesAt)
                        assertEquals(fullSamples, result)
                        assertEquals(ReaderPageSideHint.valueOf(input.getString("sideHint")), result.sideHint)
                        phases.put("exactOriginalPointCount", left.size + right.size).put("argbMismatchCount", 0)
                        phases.put("stage", "TRIAL_COMPLETE")
                    } finally {
                        phases.put("stripNativeDecodeNs", stripDecodeNs)
                            .put("observerPointExtractNs", observerExtractNs)
                            .put("outputBitmapPeakLiveBytes", peakLiveBytes)
                            .put("outputBitmapPeakLiveCount", peakLiveCount)
                            .put("outputsAllRecycled", allocations.all { it.first.isRecycled })
                            .put("nativeRecycles", nativeRecycles).put("source", counter.json())
                        owner.close()
                    }
                    assertEquals(1, counter.opens)
                    assertEquals(1, counter.closes)
                    assertEquals(input.getLong("bytes"), counter.bytes)
                    assertEquals(1, nativeRecycles)
                    assertTrue(allocations.all { it.first.isRecycled })
                    assertEquals(if (fullFirst) 1 else 2, peakLiveCount)
                    if (round >= 2) measurements.put(phases)
                }
            }
            assertEquals(161, measurements.length())
            passed = true
        } finally {
            report.put("passed", passed).put("retainedMeasurementCount", measurements.length())
            File(privateFiles, "pairing-r3-native-strip-full-cost.json").writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    private class ReadCounter {
        var opens = 0
        var closes = 0
        var bytes = 0L
        var eofReads = 0
        var openNs = 0L
        var closeNs = 0L

        fun json(): JSONObject = JSONObject().put("opens", opens).put("closes", closes).put("readBytes", bytes)
            .put("physicalEofReads", eofReads)
            .put("openNs", openNs).put("closeNs", closeNs)
    }

    private fun measuredPage(file: File, index: Int, counter: ReadCounter): ReaderPage =
        ReaderPage(index, stream = {
            val before = System.nanoTime()
            val physical = file.inputStream()
            counter.openNs += System.nanoTime() - before
            counter.opens++
            object : FilterInputStream(physical) {
                override fun read(): Int = `in`.read().also {
                    if (it >= 0) counter.bytes++ else counter.eofReads++
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    `in`.read(buffer, offset, length).also {
                        if (it > 0) {
                            counter.bytes += it
                        } else if (it == -1) {
                            counter.eofReads++
                        }
                    }
                override fun close() {
                    val closeAt = System.nanoTime()
                    counter.closes++
                    try {
                        super.close()
                    } finally {
                        counter.closeNs += System.nanoTime() - closeAt
                    }
                }
            }
        }).apply { status = Page.State.Ready }

    @Test
    fun actualNativeIccJpegRegionsUseFixedSrgb() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "pairing-r3-inputs")
        val golden = JSONObject(File(directory, "linear-rgb-golden.json").readText(Charsets.UTF_8))
        val profiled = File(directory, "linear-rgb-profile.jpg")
        val bytes = profiled.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(golden.getString("profiledSha256"), hash)
        val page = ReaderPage(0, stream = { profiled.inputStream() })
        val pageId = ReaderPageId(ReaderChapterId(84), 0)
        val owner = ReaderSpreadSamplesOwner(
            ReaderDualPageIdentity(1, 1, listOf(ReaderDualPageContentIdentity(pageId, page.encodedContentIdentity()))),
        )
        try {
            val result = requireNotNull(
                AndroidReaderSpreadSampler.sampleOriginal(
                    page,
                    owner.request(pageId),
                    400,
                    600,
                    { encoded ->
                        val decoder = AndroidReaderSpreadSampler.nativeDecoder(encoded)
                        object : ReaderPageRegionDecoder {
                            override fun decode(region: Rect): Bitmap = decoder.decode(region).also {
                                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), it.colorSpace)
                            }
                            override fun recycle() = decoder.recycle()
                        }
                    },
                ),
            )
            val fullDecoder = AndroidReaderSpreadSampler.nativeDecoder(profiled.inputStream())
            try {
                val full = fullDecoder.decode(Rect(0, 0, 400, 600))
                try {
                    assertEquals(
                        ReaderPageSideHints.classify(full.width, full.height, full::getPixel),
                        result.sideHint,
                    )
                } finally {
                    full.recycle()
                }
            } finally {
                fullDecoder.recycle()
            }
            val samples = golden.getJSONArray("samples")
            repeat(samples.length()) { index ->
                val sample = samples.getJSONObject(index)
                val edgeIndex = sample.getInt("y") * SharedSpreadMatching.SAMPLE_POINTS / 600
                val actual = requireNotNull(result.edges).right[edgeIndex]
                val expected = sample.getInt("expectedSrgbArgb")
                val untagged = sample.getInt("untaggedArgb")
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(
                        "Frozen cross-CMS rounding allowance is two channel levels",
                        kotlin.math.abs(
                            (actual ushr shift and 255) - (expected ushr shift and 255),
                        ) <= 2,
                    )
                }
                assertTrue(
                    "ICC conversion must differ clearly from the untagged input",
                    listOf(0, 8, 16).any {
                        kotlin.math.abs((actual ushr it and 255) - (untagged ushr it and 255)) >= 16
                    },
                )
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun fixedOriginalWebpEvidenceAndActualNativeRegionCosts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null))
        val directory = File(external, "pairing-r3-inputs")
        val inputs = JSONArray(File(directory, "inputs.json").readText(Charsets.UTF_8))
        assertEquals("The complete verified local fixture is required", 23, inputs.length())
        val measurements = JSONArray()
        var totalBytes = 0L
        repeat(inputs.length()) { index ->
            val input = inputs.getJSONObject(index)
            val file = File(directory, input.getString("file"))
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(input.getString("file"), input.getString("sha256"), hash)
            assertEquals(input.getLong("bytes"), bytes.size.toLong())
            totalBytes += bytes.size
        }
        assertEquals(3_849_732L, totalBytes)
        // Two warm-up rounds followed by seven retained rounds; every phase uses the actual JNI decoder.
        repeat(9) { round ->
            repeat(inputs.length()) { index ->
                val input = inputs.getJSONObject(index)
                val file = File(directory, input.getString("file"))
                val width = input.getInt("width")
                val height = input.getInt("height")
                val expected = ReaderPageSideHint.valueOf(input.getString("sideHint"))
                val phases = JSONObject().put("round", round).put("file", file.name)
                var opens = 0
                var closes = 0
                var readBytes = 0L
                var nativeRecycles = 0
                var decodedAt = 0L
                var extractionAndRegionReleaseNs = 0L
                var sourceClosedAt = 0L
                val regions = mutableListOf<Bitmap>()
                val page = ReaderPage(index, stream = {
                    val before = SystemClock.elapsedRealtimeNanos()
                    val encoded = file.inputStream()
                    phases.put("streamOpenNs", SystemClock.elapsedRealtimeNanos() - before)
                    opens++
                    object : FilterInputStream(encoded) {
                        override fun read(): Int = `in`.read().also { if (it >= 0) readBytes++ }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                            `in`.read(buffer, offset, length).also { if (it > 0) readBytes += it }
                        override fun close() {
                            closes++
                            super.close()
                            sourceClosedAt = SystemClock.elapsedRealtimeNanos()
                        }
                    }
                })
                val pageId = ReaderPageId(ReaderChapterId(83), index)
                val owner = ReaderSpreadSamplesOwner(
                    ReaderDualPageIdentity(
                        1,
                        1,
                        listOf(ReaderDualPageContentIdentity(pageId, page.encodedContentIdentity())),
                    ),
                )
                try {
                    val before = SystemClock.elapsedRealtimeNanos()
                    val result = requireNotNull(
                        AndroidReaderSpreadSampler.sampleOriginal(
                            page,
                            owner.request(pageId),
                            width,
                            height,
                            { encoded ->
                                val createBefore = SystemClock.elapsedRealtimeNanos()
                                val decoder = AndroidReaderSpreadSampler.nativeDecoder(encoded)
                                phases.put("nativeCreateNs", SystemClock.elapsedRealtimeNanos() - createBefore)
                                object : ReaderPageRegionDecoder {
                                    override fun decode(region: Rect): Bitmap {
                                        val decodeBefore = SystemClock.elapsedRealtimeNanos()
                                        if (decodedAt != 0L) extractionAndRegionReleaseNs += decodeBefore - decodedAt
                                        val bitmap = decoder.decode(region)
                                        decodedAt = SystemClock.elapsedRealtimeNanos()
                                        assertEquals(
                                            "Original JNI regions must use fixed sRGB",
                                            ColorSpace.get(ColorSpace.Named.SRGB),
                                            bitmap.colorSpace,
                                        )
                                        val label = if (regions.isEmpty()) "left" else "right"
                                        phases.put("${label}DecodeNs", decodedAt - decodeBefore)
                                        phases.put("${label}RegionBytes", bitmap.allocationByteCount)
                                        regions += bitmap
                                        return bitmap
                                    }
                                    override fun recycle() {
                                        val recycleBefore = SystemClock.elapsedRealtimeNanos()
                                        if (decodedAt != 0L) extractionAndRegionReleaseNs += recycleBefore - decodedAt
                                        decoder.recycle()
                                        nativeRecycles++
                                        val recycledNs = SystemClock.elapsedRealtimeNanos() - recycleBefore
                                        phases.put("nativeRecycleNs", recycledNs)
                                    }
                                }
                            },
                        ),
                    )
                    val returnedAt = SystemClock.elapsedRealtimeNanos()
                    phases.put("totalNs", returnedAt - before)
                    phases.put("postSourceCloseClassificationAndReturnNs", returnedAt - sourceClosedAt)
                    phases.put("extractAndRegionReleaseNs", extractionAndRegionReleaseNs)
                    phases.put("streamOpens", opens).put("streamCloses", closes).put("encodedReadBytes", readBytes)
                    assertEquals(file.name, expected, result.sideHint)
                    assertEquals(1, opens)
                    assertEquals(1, closes)
                    assertEquals(1, nativeRecycles)
                    assertEquals(2, regions.size)
                    assertTrue(regions.all(Bitmap::isRecycled))
                    if (round == 0) {
                        val legacy = requireNotNull(
                            AndroidReaderSpreadSampler.sample(
                                page,
                                owner.request(pageId),
                                width,
                                height,
                            ),
                        )
                        val fullDecoder = AndroidReaderSpreadSampler.nativeDecoder(file.inputStream())
                        try {
                            val full = fullDecoder.decode(Rect(0, 0, width, height))
                            try {
                                val edgePlan = requireNotNull(SharedSpreadMatching.plan(width, height))
                                val fullEdges = ReaderSpreadEdgeSamples(
                                    edgePlan.left.map { (x, y) -> full.getPixel(x, y) },
                                    edgePlan.right.map { (x, y) -> full.getPixel(x, y) },
                                )
                                if (fullEdges != result.edges || legacy != fullEdges) {
                                    val difference = JSONObject().put("file", file.name).put("round", round)
                                        .put("nativeOriginalLeft", JSONArray(result.edges?.left))
                                        .put("nativeOriginalRight", JSONArray(result.edges?.right))
                                        .put("nativeLegacyLeft", JSONArray(legacy.left))
                                        .put("nativeLegacyRight", JSONArray(legacy.right))
                                        .put("nativeFullLeft", JSONArray(fullEdges.left))
                                        .put("nativeFullRight", JSONArray(fullEdges.right))
                                    File(external, "pairing-r3-sampling-pixel-difference.json")
                                        .writeText(difference.toString(2), Charsets.UTF_8)
                                }
                                assertEquals(
                                    "Original regions must match exact native full pixels",
                                    fullEdges,
                                    result.edges,
                                )
                                assertEquals("Legacy regions must also use exact raw coordinates", fullEdges, legacy)
                            } finally {
                                full.recycle()
                            }
                        } finally {
                            fullDecoder.recycle()
                        }
                    }
                    val control = requireNotNull(
                        BitmapFactory.decodeFile(
                            file.absolutePath,
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                            },
                        ),
                    )
                    try {
                        assertEquals(width, control.width)
                        assertEquals(height, control.height)
                        assertEquals(
                            file.name,
                            expected,
                            ReaderPageSideHints.classify(width, height, control::getPixel),
                        )
                        val oldEdges = requireNotNull(SharedSpreadMatching.plan(width, height))
                        val controlEdges = ReaderSpreadEdgeSamples(
                            oldEdges.left.map { (x, y) -> control.getPixel(x, y) },
                            oldEdges.right.map { (x, y) -> control.getPixel(x, y) },
                        )
                        if (controlEdges != result.edges) {
                            val difference = JSONObject().put("file", file.name).put("round", round)
                                .put("nativeLeft", JSONArray(result.edges?.left))
                                .put("nativeRight", JSONArray(result.edges?.right))
                                .put("bitmapFactoryLeft", JSONArray(controlEdges.left))
                                .put("bitmapFactoryRight", JSONArray(controlEdges.right))
                            File(external, "pairing-r3-sampling-pixel-difference.json")
                                .writeText(difference.toString(2), Charsets.UTF_8)
                        }
                        assertEquals("Separate BitmapFactory decoder control", controlEdges, result.edges)
                    } finally {
                        control.recycle()
                    }
                    if (round >= 2) measurements.put(phases)
                } finally {
                    owner.close()
                }
            }
        }
        val counterexample = File(directory, "09-95.webp")
        val displayHints = JSONObject()
        for (sampleSize in listOf(1, 2, 4)) {
            val bitmap = requireNotNull(
                BitmapFactory.decodeFile(
                    counterexample.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                    },
                ),
            )
            try {
                val hint = ReaderPageSideHints.classify(bitmap.width, bitmap.height, bitmap::getPixel)
                displayHints.put(sampleSize.toString(), hint.name)
                assertEquals(
                    "The original UNKNOWN to display sample4 RIGHT counterexample remains explicit",
                    if (sampleSize == 4) ReaderPageSideHint.RIGHT else ReaderPageSideHint.UNKNOWN,
                    hint,
                )
            } finally {
                bitmap.recycle()
            }
        }
        val report = JSONObject()
            .put("algorithmVersion", ReaderOriginalPageSampling.ALGORITHM_VERSION)
            .put("clock", "elapsedRealtimeNanos")
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("buildType", "Debug")
            .put("componentOnly", true)
            .put("controlWorkOutsideMeasuredPhase", true)
            .put("extractPhaseIncludesRegionBitmapRecycle", true)
            .put("extractPhaseExcludesPostCloseClassification", true)
            .put("displayCounterexample", displayHints)
            .put("measurements", measurements)
        File(external, "pairing-r3-native-sampling.json")
            .writeText(report.toString(2), Charsets.UTF_8)
        assertEquals(161, measurements.length())
    }
}
