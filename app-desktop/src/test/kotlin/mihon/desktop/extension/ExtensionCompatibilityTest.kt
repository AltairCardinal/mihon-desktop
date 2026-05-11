package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Stage 27.0 — Extension compatibility integration test baseline.
 *
 * Tests the full loading pipeline with synthetic JARs that simulate
 * dex2jar-converted APK extensions.  Failure categories:
 *
 *   - DI_FAILURE: class loaded but constructor throws NoSuchMethodError / InjektException
 *     because Injekt bindings weren't initialized.  These are test-infra failures, not
 *     real compat gaps.
 *
 *   - STUB_MISSING: NoClassDefFoundError for an android.* / androidx.* class that is
 *     not in our stub layer.  Real compat gap.
 *
 *   - LOAD_OK: source instantiated and the Source interface is satisfied.
 *
 *   - LOAD_ERROR: other Throwable — deserves its own analysis.
 *
 * This file covers pure-JVM test cases.  To run against real APKs, place converted
 * JARs in ~/.mihon/extensions/ and run ExtensionLiveCompatibilityTest (env-dependent,
 * not in normal CI).
 */
class ExtensionCompatibilityTest {

    // ── Failure categorization ─────────────────────────────────────────────

    @Test
    fun `categorize returns LOAD_OK for null throwable`() {
        val cat = ExtensionFailureCategory.from(null)
        assertEquals(ExtensionFailureCategory.LOAD_OK, cat)
    }

    @Test
    fun `categorize returns STUB_MISSING for NoClassDefFoundError on android class`() {
        val err = NoClassDefFoundError("android/widget/Toast")
        assertEquals(ExtensionFailureCategory.STUB_MISSING, ExtensionFailureCategory.from(err))
    }

    @Test
    fun `categorize returns STUB_MISSING for NoClassDefFoundError on androidx class`() {
        val err = NoClassDefFoundError("androidx/preference/Preference")
        assertEquals(ExtensionFailureCategory.STUB_MISSING, ExtensionFailureCategory.from(err))
    }

    @Test
    fun `categorize returns DI_FAILURE for InjektException-like message`() {
        val err = RuntimeException("Can't find binding for type NetworkHelper")
        assertEquals(ExtensionFailureCategory.DI_FAILURE, ExtensionFailureCategory.from(err))
    }

    @Test
    fun `categorize returns DI_FAILURE for injectLazy NoSuchMethodError`() {
        val err = NoSuchMethodError("uy.kohesive.injekt.InjektMain.injectLazy")
        assertEquals(ExtensionFailureCategory.DI_FAILURE, ExtensionFailureCategory.from(err))
    }

    @Test
    fun `categorize returns LOAD_ERROR for unrecognized exception`() {
        val err = IllegalStateException("Something else failed")
        assertEquals(ExtensionFailureCategory.LOAD_ERROR, ExtensionFailureCategory.from(err))
    }

    // ── CompatibilityReport ────────────────────────────────────────────────

    @Test
    fun `CompatibilityReport tracks counts correctly`() {
        val report = CompatibilityReport()
        report.record("ext1.jar", ExtensionFailureCategory.LOAD_OK, null)
        report.record("ext2.jar", ExtensionFailureCategory.LOAD_OK, null)
        report.record("ext3.jar", ExtensionFailureCategory.STUB_MISSING, NoClassDefFoundError("android/widget/Toast"))
        report.record("ext4.jar", ExtensionFailureCategory.DI_FAILURE, RuntimeException("binding"))

        assertEquals(4, report.total)
        assertEquals(2, report.okCount)
        assertEquals(1, report.stubMissingCount)
        assertEquals(1, report.diFailureCount)
        assertEquals(0, report.loadErrorCount)
        assertEquals(50.0, report.successRate, 0.1)
    }

    @Test
    fun `CompatibilityReport successRate is 100 when all load ok`() {
        val report = CompatibilityReport()
        report.record("a.jar", ExtensionFailureCategory.LOAD_OK, null)
        report.record("b.jar", ExtensionFailureCategory.LOAD_OK, null)
        assertEquals(100.0, report.successRate, 0.01)
    }

    @Test
    fun `CompatibilityReport successRate is 0 for empty report`() {
        val report = CompatibilityReport()
        assertEquals(0.0, report.successRate, 0.01)
    }

    // ── Loader integration with synthetic JARs ─────────────────────────────

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loader handles empty JAR directory gracefully`() {
        val loader = DesktopExtensionLoader(tempDir)
        val results = loader.loadExtensions()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `loader skips corrupt JAR without throwing`() {
        val corruptJar = File(tempDir, "corrupt.jar")
        corruptJar.writeBytes(ByteArray(16) { 0xFF.toByte() })

        val loader = DesktopExtensionLoader(tempDir)
        val results = loader.loadExtensions()
        assertTrue(results.isEmpty(), "Corrupt JAR should produce 0 results, not throw")
    }

    @Test
    fun `loader loads minimal source from ServiceLoader JAR`() {
        val jarFile = buildServiceLoaderJar(tempDir, MinimalTestSource::class.java)

        val loader = DesktopExtensionLoader(tempDir)
        val results = loader.loadExtensions()

        assertEquals(1, results.size, "Should load the MinimalTestSource")
        assertNotNull(results.first().source)
        assertEquals("Test Source", results.first().source.name)
    }

    @Test
    fun `loader returns empty for JAR with no Source implementation`() {
        // A JAR with only a non-Source class
        val jarFile = File(tempDir, "empty-source.jar")
        JarOutputStream(jarFile.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("com/example/NotASource.class"))
            out.write(ByteArray(0))
            out.closeEntry()
        }

        val loader = DesktopExtensionLoader(tempDir)
        val results = loader.loadExtensions()
        assertTrue(results.isEmpty())
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a JAR that exposes [sourceClass] via ServiceLoader
     * (META-INF/services/eu.kanade.tachiyomi.source.Source).
     */
    private fun buildServiceLoaderJar(dir: File, sourceClass: Class<*>): File {
        val jarFile = File(dir, "${sourceClass.simpleName}.jar")
        val serviceEntry = "META-INF/services/eu.kanade.tachiyomi.source.Source"
        JarOutputStream(jarFile.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(serviceEntry))
            out.write(sourceClass.name.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jarFile
    }
}

// ── Test Source fixture ────────────────────────────────────────────────────

/**
 * Minimal no-arg Source used by `loader loads minimal source from ServiceLoader JAR`.
 * The JAR built by the test contains only the ServiceLoader manifest pointing to this
 * class — the class itself is loaded from the test classpath (parent ClassLoader).
 */
class MinimalTestSource : Source {
    override val id: Long = 99_999L
    override val name: String = "Test Source"
    override val lang: String = "xx"
}
