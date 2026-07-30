package mihon.desktop.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

@EnabledOnOs(OS.WINDOWS)
class WindowsDistributablePackagingTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().parent
    private val packager = repoRoot.resolve("scripts/package-windows-distributable.ps1")

    @Test
    fun `packager archives launcher app and runtime with checksum`(@TempDir tempDir: Path) {
        val source = completeDistributable(tempDir)
        val archive = tempDir.resolve("artifacts/Mihon-Desktop-test-windows.zip")

        val result = runPackager(source, archive)

        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.isRegularFile(archive))
        assertTrue(Files.size(archive) > 0)
        assertTrue(Files.isRegularFile(Path.of("$archive.sha256")))
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("Mihon Desktop/Mihon Desktop.exe" in entries)
            assertTrue("Mihon Desktop/app/mihon.jar" in entries)
            assertTrue("Mihon Desktop/runtime/bin/java.exe" in entries)
        }
    }

    @Test
    fun `packager rejects launcher without complete runtime`(@TempDir tempDir: Path) {
        val source = tempDir.resolve("Mihon Desktop")
        Files.createDirectories(source)
        Files.writeString(source.resolve("Mihon Desktop.exe"), "launcher")
        val archive = tempDir.resolve("artifacts/incomplete.zip")

        val result = runPackager(source, archive)

        assertTrue(result.exitCode != 0, result.output)
        assertTrue(result.output.contains("runtime", ignoreCase = true), result.output)
        assertFalse(Files.exists(archive))
    }

    private fun completeDistributable(tempDir: Path): Path {
        val source = tempDir.resolve("Mihon Desktop")
        Files.createDirectories(source.resolve("app"))
        Files.createDirectories(source.resolve("runtime/bin"))
        Files.writeString(source.resolve("Mihon Desktop.exe"), "launcher")
        Files.writeString(source.resolve("app/mihon.jar"), "application")
        Files.writeString(source.resolve("runtime/bin/java.exe"), "runtime")
        return source
    }

    private fun runPackager(source: Path, archive: Path): ProcessResult {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            packager.toString(),
            "-SourceDirectory",
            source.toString(),
            "-OutputArchive",
            archive.toString(),
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
