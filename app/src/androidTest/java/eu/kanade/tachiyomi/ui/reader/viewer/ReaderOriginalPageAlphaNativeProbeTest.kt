package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderSpreadSamplesOwner
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Direct RGBA8 PNG bytes form the independent oracle, rather than a second native decode. */
class ReaderOriginalPageAlphaNativeProbeTest {
    @Test
    fun originalMixedAlphaPngExposesStraightArgb() = runBlocking {
        val external =
            requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val file = File(external, "pairing-r3-inputs/mixed-alpha-original.png")
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("726b5195d7ac1cf33aec83498aa44a543a30f3ee730d11cfb387fb8806389721", hash)
        val page = ReaderPage(0, stream = { file.inputStream() })
        val id = ReaderPageId(ReaderChapterId(85), 0)
        val owner = ReaderSpreadSamplesOwner(
            ReaderDualPageIdentity(1, 1, listOf(ReaderDualPageContentIdentity(id, page.encodedContentIdentity()))),
        )
        val premultiplied = mutableListOf<Boolean>()
        try {
            val result = requireNotNull(
                AndroidReaderSpreadSampler.sampleOriginal(
                    page,
                    owner.request(id),
                    400,
                    600,
                    { encoded ->
                        val decoder = AndroidReaderSpreadSampler.nativeDecoder(encoded)
                        object : ReaderPageRegionDecoder {
                            override fun decode(region: Rect): Bitmap = decoder.decode(region).also {
                                premultiplied += it.isPremultiplied
                            }
                            override fun recycle() = decoder.recycle()
                        }
                    },
                ),
            )
            val actual = requireNotNull(result.edges)
            val report = JSONObject().put("sha256", hash).put("nativePremultiplied", JSONArray(premultiplied))
                .put("expectedLeftArgb", 0x804080c0.toInt()).put("expectedRightArgb", 0xefffffff.toInt())
                .put("actualLeft", JSONArray(actual.left)).put("actualRight", JSONArray(actual.right))
                .put("sideHint", result.sideHint.name)
            File(external, "pairing-r3-native-alpha.json").writeText(report.toString(2), Charsets.UTF_8)
            assertEquals(List(50) { 0x804080c0.toInt() }, actual.left)
            assertEquals(List(50) { 0xefffffff.toInt() }, actual.right)
            assertEquals(ReaderPageSideHint.UNKNOWN, result.sideHint)
        } finally {
            owner.close()
        }
    }
}
