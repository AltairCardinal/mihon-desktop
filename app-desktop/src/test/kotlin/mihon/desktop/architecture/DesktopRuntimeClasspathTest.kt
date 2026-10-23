package mihon.desktop.architecture

import mihon.desktop.test.http.TestModeServerReflectionProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The desktop runtime must not carry the full Kotlin reflection implementation.
 *
 * The navigation library asks the screen model for its qualified name while the first
 * user interface is composed. With the full reflection implementation on the class path
 * that answer deserialises the class metadata and loads several hundred reflection
 * classes. The implementation reaches the runtime class path only through the Ktor
 * server stack, and that stack exists solely for the test mode server.
 *
 * These tests keep the implementation out and prove that the test mode server still
 * works without it.
 */
class DesktopRuntimeClasspathTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().parent

    @Test
    fun `the desktop runtime keeps the full reflection implementation off the class path`() {
        val buildFile = repoRoot.resolve("app-desktop/build.gradle.kts")
        val text = Files.readString(buildFile)

        assertTrue(
            text.contains("""exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")"""),
            "The Ktor server stack must not drag the full Kotlin reflection implementation " +
                "onto the desktop runtime class path",
        )
    }

    @Test
    fun `the start up warm up does not name the reflection implementation`() {
        val list = repoRoot.resolve("app-desktop/src/main/resources/desktop-startup-warmup.txt")
        val offenders = Files.readAllLines(list)
            .map { it.trim() }
            .filter { it.startsWith("kotlin.reflect.jvm.internal.") }

        assertTrue(
            offenders.isEmpty(),
            "The warm up must not ask for classes of an implementation that is not on the " +
                "class path any more: $offenders",
        )
    }

    @Test
    fun `the test mode server answers without the full reflection implementation`(@TempDir dir: File) {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }
            .map { File(it.toURI()).absolutePath }
            .plus(System.getProperty("java.class.path").split(File.pathSeparator))
            .distinct()
            .filterNot { File(it).name.startsWith("kotlin-reflect") }
            .joinToString(File.pathSeparator)

        val output = File(dir, "probe.log")
        val java = File(System.getProperty("java.home"), "bin/java${if (File.separatorChar == '\\') ".exe" else ""}")

        // The class path of the desktop module is longer than the command line limit of
        // Windows, so the arguments go into a file that the launcher reads.
        fun quote(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val arguments = File(dir, "probe.args")
        arguments.writeText(
            listOf("-Djava.awt.headless=true", "-cp", classpath, TestModeServerReflectionProbe::class.java.name)
                .joinToString("\n", transform = ::quote),
            Charsets.UTF_8,
        )

        val process = ProcessBuilder(java.absolutePath, "@${arguments.absolutePath}")
            .redirectErrorStream(true).redirectOutput(output).start()

        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "The test mode server probe timed out")
            val log = output.readText(Charsets.UTF_8)
            assertEquals(0, process.exitValue(), log)
            assertTrue(log.contains("PROBE_OK"), "The test mode server did not answer: $log")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
