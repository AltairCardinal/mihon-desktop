package mihon.desktop.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The packaged runtime carries a class-data-sharing archive with the classes of the
 * start up, so the JVM does not have to parse and verify them on every start.
 *
 * The archive is created by the build from the class list of the start up warm up.
 * It is optional: a build without it still produces a working application, it only
 * starts slower.
 */
class WindowsCdsArchiveConfigurationTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().parent
    private val buildScript = repoRoot.resolve("scripts/build-windows.ps1")
    private val installerScript = repoRoot.resolve("scripts/install-desktop-cds-archive.ps1")
    private val classList = repoRoot.resolve("app-desktop/src/main/resources/desktop-startup-warmup.txt")

    @Test
    fun `the build creates the class-data-sharing archive of the application`() {
        assertTrue(Files.isRegularFile(installerScript), "the archive installer must exist")
        assertTrue(Files.isRegularFile(classList), "the class list of the start up must exist")

        val classNames = Files.readAllLines(classList).map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(classNames.size > 1000, "the class list must carry the classes of the start up")

        val installer = Files.readString(installerScript)
        assertTrue(
            installer.contains("""-XX:SharedArchiveFile=${'$'}archive"""),
            "the archive must hold the classes of the application",
        )
        assertTrue(
            installer.contains("""-XX:SharedClassListFile=${'$'}ClassList"""),
            "the archive must be created from the class list, so the build does not start the application",
        )
        assertTrue(
            installer.contains("""bin\server""") || installer.contains("""bin/server"""),
            "the archive must be placed where the virtual machine looks for its default archive",
        )

        val build = Files.readString(buildScript)
        assertTrue(
            build.contains("install-desktop-cds-archive.ps1"),
            "the build must create the class-data-sharing archive",
        )
        assertTrue(
            build.contains("The class-data-sharing archive was skipped"),
            "a missing archive must not stop the build",
        )
    }

    @Test
    fun `the archive installer reports a broken runtime instead of leaving a broken archive`(@TempDir tempDir: Path) {
        val distributable = tempDir.resolve("Mihon Desktop")
        Files.createDirectories(distributable.resolve("app"))
        Files.createDirectories(distributable.resolve("runtime/bin/server"))
        Files.writeString(
            distributable.resolve("app/Mihon Desktop.cfg"),
            "app.classpath=\$APPDIR\\application.jar\napp.mainclass=mihon.desktop.MainKt\n",
        )
        // A launcher that is not a program at all: the dump cannot succeed with it.
        Files.writeString(distributable.resolve("runtime/bin/java.exe"), "not a program")

        val result = runInstaller(distributable)

        assertFalse(
            Files.exists(distributable.resolve("runtime/bin/server/classes.jsa")),
            "a broken dump must not leave an archive behind",
        )
        assertTrue(result.exitCode != 0, "the installer must report the failure")
        assertTrue(
            result.output.contains("archive", ignoreCase = true),
            "the installer must explain what happened: ${result.output}",
        )
    }

    private fun runInstaller(distributable: Path): ProcessResult {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            installerScript.toString(),
            "-DistPath",
            distributable.toString(),
            "-Verify",
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        process.waitFor(120, TimeUnit.SECONDS)
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
