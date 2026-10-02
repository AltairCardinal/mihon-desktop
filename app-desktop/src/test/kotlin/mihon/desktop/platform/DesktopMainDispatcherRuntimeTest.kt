package mihon.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SortMode
import mihon.desktop.library.LibraryScreenModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class DesktopMainDispatcherRuntimeTest {
    @Test
    fun `production runtime Main runs Voyager sorting on Swing without a test dispatcher`(@TempDir directory: File) {
        val probeClasses = File(directory, "probe-classes")
        val packagePath = "mihon/desktop/platform"
        val source = File(DesktopMainDispatcherProbe::class.java.protectionDomain.codeSource.location.toURI())
        val target = File(probeClasses, packagePath).apply { mkdirs() }
        File(source, packagePath).listFiles()!!.filter {
            it.name.startsWith("DesktopMainDispatcherProbe") && it.extension == "class"
        }.forEach { it.copyTo(File(target, it.name)) }
        val classpath =
            File(
                requireNotNull(System.getProperty("mihon.test.productionRuntimeClasspathFile")),
            ).readText(Charsets.UTF_8) +
                File.pathSeparator + probeClasses.absolutePath
        fun quote(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val arguments = File(directory, "main.args")
        arguments.writeText(
            listOf(
                "-Xmx256m",
                "-Djava.awt.headless=true",
                "-Duser.home=${File(directory, "fallback-home").absolutePath}",
                "-Djava.util.prefs.PreferencesFactory=${IsolatedDesktopPreferencesFactory::class.java.name}",
                "-cp",
                classpath,
                DesktopMainDispatcherProbe::class.java.name,
                File(directory, "profile").absolutePath,
            ).joinToString("\n", transform = ::quote),
            Charsets.UTF_8,
        )
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")
        val output = File(directory, "main.log")
        val process = ProcessBuilder(java.absolutePath, "@${arguments.absolutePath}")
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Production runtime Main probe timed out")
            assertEquals(0, process.exitValue(), output.readText(Charsets.UTF_8))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Only these probe class bytes accompany the production runtime, never the test worker classpath. */
object DesktopMainDispatcherProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking<Unit> {
        check(runCatching { Class.forName("kotlinx.coroutines.test.TestCoroutineScheduler") }.isFailure)
        DesktopTestProfile.configure(arrayOf("--test-mode", "--test-profile=${args[0]}"))
        withContext(Dispatchers.Main.immediate) { check(SwingUtilities.isEventDispatchThread()) }
        val context = initDesktopDIForTest(File(args[0]), DesktopPreferenceStore())
        val model = LibraryScreenModelFactory.create()
        try {
            withContext(Dispatchers.Main.immediate) {
                model.setSortModeAndDirectionForCategory(null, SortMode.UNREAD_COUNT, ascending = false)
            }
            withTimeout(10_000) {
                model.state.first { it.sortMode == SortMode.UNREAD_COUNT && !it.sortAscending }
            }
            check(
                Injekt.get<LibraryPreferences>().sortingMode().get() ==
                    LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Descending),
            )
            println("Production Main EDT and Voyager sorting persisted successfully")
        } finally {
            model.onDispose()
            context.closeAndJoin()
        }
    }
}
