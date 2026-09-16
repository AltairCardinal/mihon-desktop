package eu.kanade.tachiyomi.extension

import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexFile
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.Source
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Exercises the binary API used by separately compiled extensions, without AGP reference rewriting. */
class ExtensionZstdReleaseAbiInstrumentationTest {
    @Test
    @Suppress("DEPRECATION")
    fun publishedZstdApiDecodesFixedFrameFromReleaseHost() {
        ExtensionReleaseParityInstrumentationTest.verifyReleaseArtifact()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testInfo = instrumentation.context.applicationInfo
        val testApks = listOf(testInfo.sourceDir) + testInfo.splitSourceDirs.orEmpty()
        testApks.forEach { apk ->
            val dex = DexFile(apk)
            try {
                assertFalse(
                    "The test APK must not supply the host's Zstd implementation",
                    dex.entries().asSequence().any { it.startsWith("com.squareup.zstd.") },
                )
            } finally {
                dex.close()
            }
        }

        // Fixed Zstandard frame: single segment, five-byte content, final raw block "hello".
        // Format: https://github.com/facebook/zstd/blob/dev/doc/zstd_compression_format.md
        val frame = "28b52ffd200529000068656c6c6f".decodeHex()
        val facade = instrumentation.targetContext.classLoader.loadClass("com.squareup.zstd.okio.OkioZstd")
        val decompress = facade.getMethod("zstdDecompress", Source::class.java)
        Buffer().write(frame).use { compressed ->
            val decoded = decompress.invoke(null, compressed) as Source
            decoded.buffer().use { assertEquals("hello", it.readUtf8()) }
        }
    }
}
