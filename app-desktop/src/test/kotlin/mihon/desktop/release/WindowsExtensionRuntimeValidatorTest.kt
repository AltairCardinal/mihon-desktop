package mihon.desktop.release

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path

@EnabledOnOs(OS.WINDOWS)
class WindowsExtensionRuntimeValidatorTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().parent
    private val validator = repoRoot.resolve("scripts/validate-windows-extension-runtime.ps1")

    @Test
    fun `validator computes fixture digest without Get-FileHash`(@TempDir tempDir: Path) {
        val executable = tempDir.resolve("Mihon Desktop.exe")
        val artifact = tempDir.resolve("fixture.apk")
        Files.writeString(executable, "launcher")
        Files.writeString(artifact, "fixture")

        val command = """
            |${'$'}PSModuleAutoLoadingPreference = 'None'
            |Import-Module Microsoft.PowerShell.Management
            |Import-Module Microsoft.PowerShell.Utility
            |Remove-Item Function:\Get-FileHash -Force -ErrorAction SilentlyContinue
            |if (Get-Command Get-FileHash -ErrorAction SilentlyContinue) {
            |    Write-Error 'Fixture failed: Get-FileHash is still available'
            |    exit 3
            |}
            |& '${validator.toPowerShellLiteral()}' `
            |    -Executable '${executable.toPowerShellLiteral()}' `
            |    -ArtifactPath '${artifact.toPowerShellLiteral()}' `
            |    -PackageName 'fixture.package' `
            |    -DisplayName 'Fixture' `
            |    -VersionName '1.0' `
            |    -VersionCode 1 `
            |    -RepositoryFingerprint '$SIXTY_FOUR_ZEROES' `
            |    -ArtifactSha256 '$SIXTY_FOUR_ZEROES' `
            |    -ExpectedVersion '0.0.0.0.test'
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
        val exitCode = process.waitFor()

        assertNotEquals(0, exitCode, output)
        assertTrue(output.contains("Extension runtime fixture digest mismatch"), output)
        assertFalse(output.contains("Get-FileHash"), output)
        assertFalse(output.contains("CommandNotFoundException"), output)
    }

    private fun Path.toPowerShellLiteral(): String = toString().replace("'", "''")

    private companion object {
        const val SIXTY_FOUR_ZEROES =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
