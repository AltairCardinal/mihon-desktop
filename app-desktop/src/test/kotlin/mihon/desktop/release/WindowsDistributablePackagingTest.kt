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
import java.security.MessageDigest
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
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("Mihon Desktop/Mihon Desktop.exe" in entries)
            assertTrue("Mihon Desktop/app/mihon.jar" in entries)
            assertTrue("Mihon Desktop/runtime/bin/java.exe" in entries)
        }
        val checksumFile = Path.of("$archive.sha256")
        assertTrue(Files.isRegularFile(checksumFile))
        val expectedHash = sha256Hex(Files.readAllBytes(archive))
        val expectedLine = "$expectedHash  ${archive.fileName}"
        assertEquals("$expectedLine\n", Files.readString(checksumFile))
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

    /**
     * Runs the real production packager in a PowerShell subprocess that reproduces the
     * GitHub windows-2022 runner environment: module auto-loading is disabled and the
     * `Get-FileHash` command has been removed, while every other command the script uses
     * (`Add-Type`, `Write-Host`, Management cmdlets) remains available.
     */
    private fun runPackager(source: Path, archive: Path): ProcessResult {
        val command = """
            |${'$'}PSModuleAutoLoadingPreference = 'None'
            |Import-Module Microsoft.PowerShell.Management
            |Import-Module Microsoft.PowerShell.Utility
            |Remove-Item Function:\Get-FileHash -Force -ErrorAction SilentlyContinue
            |if (Get-Command Get-FileHash -ErrorAction SilentlyContinue) {
            |    Write-Error 'Fixture failed: Get-FileHash is still available'
            |    exit 3
            |}
            |& '${packager.toPowerShellLiteral()}' -SourceDirectory '${source.toPowerShellLiteral()}' -OutputArchive '${archive.toPowerShellLiteral()}'
            |exit 0
        """.trimMargin()
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            command,
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun Path.toPowerShellLiteral(): String = toString().replace("'", "''")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
