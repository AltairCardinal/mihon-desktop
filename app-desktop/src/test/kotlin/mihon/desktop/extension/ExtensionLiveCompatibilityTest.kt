package mihon.desktop.extension

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Stage 27.0 — Live extension compatibility baseline.
 *
 * Loads real JARs from ~/.mihon/extensions/ and reports the compatibility rate.
 * This test is SKIPPED automatically when no JARs are present (clean CI environment).
 * Run it locally after placing dex2jar-converted APKs in the extensions directory.
 *
 * Output includes:
 *  - Total extension count
 *  - Success rate
 *  - Failure breakdown by category (STUB_MISSING vs DI_FAILURE vs LOAD_ERROR)
 *  - List of missing stub classes
 *
 * Note: This test does NOT call initDesktopDI() so DI_FAILURE results correctly
 * identify sources that depend on Injekt bindings at construction time.
 * A follow-up test with DI initialized would isolate pure compat failures.
 */
class ExtensionLiveCompatibilityTest {

    private val extensionsDir = File(System.getProperty("user.home"), ".mihon/extensions")

    @Test
    fun `report extension compatibility without DI`() {
        val jarFiles = extensionsDir.listFiles { f -> f.isFile && f.extension == "jar" }
            ?: emptyArray()

        assumeTrue(
            jarFiles.isNotEmpty(),
            "No JAR files found in ${extensionsDir.absolutePath} — test skipped (expected in CI)",
        )

        val loader = DesktopExtensionLoader(extensionsDir)
        val report = CompatibilityReport()

        for (jar in jarFiles.sorted()) {
            val (category, error) = tryLoad(loader, jar)
            report.record(jar.name, category, error)
        }

        println("\n=== Extension Compatibility Report (no DI) ===")
        println(report.fullReport())
        println("=================================================\n")

        // Soft assertion: at least measure the baseline, don't enforce a number
        // (the purpose of this test is to produce a report, not to gate on a threshold)
        assertTrue(report.total > 0, "Should have loaded at least one extension")
    }

    @Test
    fun `categorize all failure modes seen in live extensions`() {
        val jarFiles = extensionsDir.listFiles { f -> f.isFile && f.extension == "jar" }
            ?: emptyArray()

        assumeTrue(jarFiles.isNotEmpty(), "No JARs in ${extensionsDir.absolutePath} — skipped")

        val loader = DesktopExtensionLoader(extensionsDir)
        val stubMissing = mutableSetOf<String>()
        val diFailures = mutableListOf<String>()
        val loadErrors = mutableListOf<Pair<String, String>>()

        for (jar in jarFiles.sorted()) {
            val (category, error) = tryLoad(loader, jar)
            when (category) {
                ExtensionFailureCategory.STUB_MISSING -> {
                    val clazz = (error as? NoClassDefFoundError)?.message ?: "unknown"
                    stubMissing.add(clazz)
                }
                ExtensionFailureCategory.DI_FAILURE ->
                    diFailures.add("${jar.name}: ${error?.message}")
                ExtensionFailureCategory.LOAD_ERROR ->
                    loadErrors.add(jar.name to (error?.message ?: error?.javaClass?.simpleName ?: "?"))
                ExtensionFailureCategory.LOAD_OK -> { /* success */ }
            }
        }

        if (stubMissing.isNotEmpty()) {
            println("\nStubs to add (${stubMissing.size}):")
            stubMissing.sorted().forEach { println("  $it") }
        }
        if (diFailures.isNotEmpty()) {
            println("\nDI failures (${diFailures.size}) — expected without initDesktopDI():")
            diFailures.take(5).forEach { println("  $it") }
        }
        if (loadErrors.isNotEmpty()) {
            println("\nLoad errors requiring investigation (${loadErrors.size}):")
            loadErrors.take(10).forEach { (name, msg) -> println("  $name: $msg") }
        }
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private fun tryLoad(
        loader: DesktopExtensionLoader,
        jar: File,
    ): Pair<ExtensionFailureCategory, Throwable?> {
        return try {
            val results = loader.loadFromSingleJar(jar)
            if (results.isNotEmpty()) {
                ExtensionFailureCategory.LOAD_OK to null
            } else {
                // No sources found — treat as load error (no specific exception)
                ExtensionFailureCategory.LOAD_ERROR to RuntimeException("No sources found in ${jar.name}")
            }
        } catch (t: Throwable) {
            ExtensionFailureCategory.from(t) to t
        }
    }
}
