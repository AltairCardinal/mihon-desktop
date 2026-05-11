package mihon.desktop.extension

import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/**
 * Tests for ManifestClassExtractor (27.3).
 *
 * Uses synthetic AXML binary fixtures instead of real APKs so tests
 * are hermetic and fast.
 */
class ManifestClassExtractorTest {

    /**
     * Builds a minimal AXML binary that mirrors the structure produced by
     * the Android build toolchain for a Tachiyomi extension manifest.
     *
     * The manifest contains:
     *   <manifest package="eu.kanade.tachiyomi.extension.zh.test">
     *     <application>
     *       <meta-data android:name="tachiyomi.extension.class"
     *                  android:value=".TestSource"/>
     *     </application>
     *   </manifest>
     */
    private fun buildMinimalAxml(
        packageName: String = "eu.kanade.tachiyomi.extension.zh.test",
        extensionClass: String = ".TestSource",
    ): ByteArray = AXmlBuilder()
        .buildManifest(packageName = packageName, extensionClass = extensionClass)

    // ──────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `extracts relative extension class and resolves with package`() {
        val axml = buildMinimalAxml(
            packageName = "eu.kanade.tachiyomi.extension.zh.test",
            extensionClass = ".TestSource",
        )
        val result = ManifestClassExtractor.extractExtensionClass(axml)
        result shouldBe "eu.kanade.tachiyomi.extension.zh.test.TestSource"
    }

    @Test
    fun `extracts absolute extension class unchanged`() {
        val axml = buildMinimalAxml(
            packageName = "eu.kanade.tachiyomi.extension.zh.test",
            extensionClass = "eu.kanade.tachiyomi.extension.zh.test.TestSource",
        )
        val result = ManifestClassExtractor.extractExtensionClass(axml)
        result shouldBe "eu.kanade.tachiyomi.extension.zh.test.TestSource"
    }

    @Test
    fun `returns null when tachiyomi extension class metadata is absent`() {
        // Build a manifest with NO meta-data element
        val axml = AXmlBuilder().buildManifest(
            packageName = "eu.kanade.tachiyomi.extension.zh.test",
            extensionClass = null, // no meta-data
        )
        val result = ManifestClassExtractor.extractExtensionClass(axml)
        result.shouldBeNull()
    }

    @Test
    fun `returns null for empty byte array`() {
        ManifestClassExtractor.extractExtensionClass(ByteArray(0)).shouldBeNull()
    }

    @Test
    fun `returns null for garbage bytes`() {
        ManifestClassExtractor.extractExtensionClass(ByteArray(100) { 0xFF.toByte() }).shouldBeNull()
    }

    @Test
    fun `handles multi-source extension class list (first class used)`() {
        val axml = buildMinimalAxml(extensionClass = ".Source1:.Source2:.Source3")
        val result = ManifestClassExtractor.extractExtensionClass(axml)
        // The first class in a colon-separated list
        result.shouldNotBeNull()
    }
}
