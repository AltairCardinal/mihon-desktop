package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDualPageContentIdentity
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest

/** Explicit local research inputs are pushed into the isolated .dev instance; missing inputs fail. */
class ReaderOriginalPageSamplingNativeProbeTest {
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
