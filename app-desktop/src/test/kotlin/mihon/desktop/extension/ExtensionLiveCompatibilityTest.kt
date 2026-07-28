package mihon.desktop.extension

import kotlinx.coroutines.runBlocking
import mihon.desktop.di.initDesktopDIForTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import uy.kohesive.injekt.Injekt
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.jar.JarFile

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
 * The compatibility gate initializes production Desktop DI before loading. A
 * separate diagnostic test intentionally runs without DI to categorize missing
 * bindings without weakening the production-path assertion.
 */
@Isolated
class ExtensionLiveCompatibilityTest {

    private val extensionsDir = File(System.getProperty("user.home"), ".mihon/extensions")

    @Test
    fun `report extension compatibility with production DI`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val jarFiles = extensionsDir.listFiles { f -> f.isFile && f.extension == "jar" }
            ?: emptyArray()

        assumeTrue(
            jarFiles.isNotEmpty(),
            "No JAR files found in ${extensionsDir.absolutePath} — test skipped (expected in CI)",
        )

        val previousInjekt = Injekt
        val diContext = initDesktopDIForTest(
            appDir = tempDir.resolve("app").toFile(),
            preferenceStore = DesktopPreferenceStore(),
        )
        try {
            val loader = DesktopExtensionLoader(extensionsDir)
            val report = CompatibilityReport()

            for (jar in jarFiles.sorted()) {
                val (category, error) = tryLoad(loader, jar)
                report.record(jar.name, category, error)
            }

            println("\n=== Extension Compatibility Report (production DI) ===")
            println(report.fullReport())
            println("========================================================\n")

            assertTrue(
                report.okCount > 0,
                "At least one live extension must load successfully:\n${report.fullReport()}",
            )
        } finally {
            diContext.closeAndJoin()
            Injekt = previousInjekt
        }
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
                val error = directSourceInstantiationFailure(jar)
                    ?: RuntimeException("No sources found in ${jar.name}")
                ExtensionFailureCategory.from(error) to error
            }
        } catch (t: Throwable) {
            ExtensionFailureCategory.from(t) to t
        }
    }

    private fun directSourceInstantiationFailure(jar: File): Throwable? {
        ExtensionClassLoader(jar.toURI().toURL(), javaClass.classLoader).use { classLoader ->
            JarFile(jar).use { archive ->
                archive.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                    .forEach { entry ->
                        val className = entry.name.removeSuffix(".class").replace('/', '.')
                        val candidate = try {
                            classLoader.loadClass(className)
                        } catch (_: Throwable) {
                            return@forEach
                        }
                        if (!eu.kanade.tachiyomi.source.Source::class.java.isAssignableFrom(candidate) ||
                            candidate.isInterface ||
                            Modifier.isAbstract(candidate.modifiers)
                        ) {
                            return@forEach
                        }
                        try {
                            candidate.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        } catch (error: Throwable) {
                            return generateSequence(error) { it.cause }.last()
                        }
                    }
            }
        }
        return null
    }
}
