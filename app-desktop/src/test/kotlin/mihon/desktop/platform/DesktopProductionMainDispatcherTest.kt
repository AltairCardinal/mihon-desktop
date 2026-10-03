package mihon.desktop.platform

import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.ui.library.LibraryScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class DesktopProductionMainDispatcherTest {
    @Test
    fun `production Main uses Swing EDT and real library update observation completes`(@TempDir directory: File) {
        val productionClasspath = requireNotNull(System.getProperty("mihon.test.productionRuntimeClasspath"))
        assertTrue(productionClasspath.split(File.pathSeparator).none { File(it).name.contains("coroutines-test") })
        // Copy only this executable probe, never the test output directory or test dispatcher factory.
        val probeRoot = File(directory, "probe")
        val packagePath = DesktopProductionMainDispatcherProbe::class.java.packageName.replace('.', '/')
        val classes = File(javaClass.protectionDomain.codeSource.location.toURI()).resolve(packagePath)
        classes.listFiles().orEmpty().filter { it.name.startsWith("DesktopProductionMainDispatcherProbe") }.forEach {
            val target = probeRoot.resolve("$packagePath/${it.name}")
            target.parentFile.mkdirs()
            it.copyTo(target)
        }
        val arguments = File(directory, "probe.args")
        val log = File(directory, "probe.log")
        val options = listOf(
            "-Djava.awt.headless=true",
            "-cp",
            probeRoot.absolutePath + File.pathSeparator + productionClasspath,
            DesktopProductionMainDispatcherProbe::class.java.name,
        )
        arguments.writeText(
            options.joinToString("\n") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" },
            Charsets.UTF_8,
        )
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val process = ProcessBuilder(java.absolutePath, "@${arguments.absolutePath}")
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Production dispatcher child timed out")
            val output = log.readText(Charsets.UTF_8)
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.contains("PRODUCTION_MAIN_EDT_LIBRARY_COMPLETE"))
            println(output.trim())
            assertTrue(
                productionClasspath.split(File.pathSeparator).none {
                    File(it).name.contains("coroutines-android")
                },
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Executes exclusively against Gradle's actual jvmMain runtime, with no coroutines-test provider. */
object DesktopProductionMainDispatcherProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        withTimeout(10_000) {
            withContext(Dispatchers.Main.immediate) {
                check(SwingUtilities.isEventDispatchThread()) {
                    "Production Main must dispatch to the actual Swing EDT"
                }
                val update = Job()
                val model = LibraryScreenModel(backgroundUpdateJob = { update })
                try {
                    check(model.state.value.isUpdating) { "Real LibraryScreenModel must observe its active update job" }
                    update.complete()
                    while (model.state.value.isUpdating) delay(5)
                    check(SwingUtilities.isEventDispatchThread())
                    println("PRODUCTION_MAIN_EDT_LIBRARY_COMPLETE thread=${Thread.currentThread().name}")
                } finally {
                    model.screenModelScope.cancel()
                }
            }
        }
    }
}
