package mihon.desktop.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WindowsReleaseConfigurationTest {

    private val repoRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().parent

    @Test
    fun `native distribution includes MSI and derives package version from AppVersion`() {
        val buildFile = repoRoot.resolve("app-desktop/build.gradle.kts")
        val text = Files.readString(buildFile)

        assertTrue(
            text.contains("TargetFormat.Msi"),
            "Windows release must keep MSI as a native distribution target",
        )
        assertFalse(
            text.contains("""packageVersion = "1.0.0""""),
            "Windows package version must not stay on the Compose template default",
        )
        assertTrue(
            text.contains("desktopNativePackageVersion"),
            "Native package version must be derived from the desktop AppVersion source",
        )
        assertTrue(
            text.contains("AppVersion.kt") && text.contains("STAGE") &&
                text.contains("FEATURE") && text.contains("BUILD"),
            "Native package version should read stage, feature, and build",
        )
        assertTrue(
            text.contains("readAppVersionConstant(\"BUILD\")"),
            "Native package version must include the per-build number",
        )
        assertTrue(
            text.contains("\"jdk.zipfs\""),
            "The published runtime must include the ZIP filesystem provider used by APK conversion",
        )
    }

    @Test
    fun `windows build script produces and validates unpackaged executable`() {
        val script = repoRoot.resolve("scripts/build-windows.ps1")
        assertTrue(Files.exists(script), "Windows build script must exist")

        val text = Files.readString(script)
        val testIndex = text.indexOf(":app-desktop:jvmTest")
        val unpackagedIndex = text.lastIndexOf(":app-desktop:createDistributable")

        assertTrue(testIndex >= 0, "Windows build script must run desktop JVM tests")
        assertTrue(unpackagedIndex >= 0, "Windows build script must create the unpackaged app")
        assertTrue(
            testIndex < unpackagedIndex,
            "Windows build script must run tests before creating the unpackaged app",
        )
        assertTrue(
            text.contains("Mihon Desktop.exe"),
            "Windows build script must validate the canonical unpackaged executable",
        )
        assertTrue(text.contains("ExpectedVersion"), "Windows script must validate the expected runtime version")
        assertTrue(
            text.contains("--rerun-tasks") && text.indexOf("--rerun-tasks") < unpackagedIndex,
            "Canonical unpackaged build must bypass stale Gradle UP-TO-DATE state",
        )
        assertTrue(
            text.contains("validate-windows-extension-runtime.ps1"),
            "Windows build must run the published executable through extension installation acceptance",
        )
        assertTrue(
            text.contains("keiyoushi-manhuagui-1.4.28.apk"),
            "Windows build must use the immutable redistributable DEX fixture",
        )
        assertFalse(text.contains("MainWindowTitle"), "Build acceptance must not depend on Windows UI automation")
        assertFalse(text.contains("Get-CimInstance Win32_Process"), "The build orchestrator must not inspect windows")
        assertTrue(
            text.contains("package-windows-distributable.ps1"),
            "Windows script must package the complete unpackaged runtime for delivery",
        )
        assertTrue(
            text.contains("publish-windows-unpacked.ps1"),
            "Windows script must publish the validated runtime outside Gradle's temporary output",
        )
        assertTrue(
            text.contains("Final unpacked EXE:"),
            "Windows script must report the durable executable path for completion reports",
        )
        assertTrue(text.contains("Deliverable ZIP:"), "Windows script must report the durable ZIP artifact")
        assertTrue(text.contains("Deliverable SHA-256:"), "Windows script must report the ZIP checksum")
        assertFalse(text.contains("/Applications"), "Windows script must not deploy to macOS Applications")
        assertFalse(text.contains("/private/tmp"), "Windows script must not depend on macOS temp paths")

        val validator = Files.readString(repoRoot.resolve("scripts/validate-windows-extension-runtime.ps1"))
        assertTrue(validator.contains("Start-Process"), "Runtime acceptance must launch the real executable")
        assertTrue(
            validator.contains("--test-extension-runtime"),
            "Runtime acceptance must enter the guarded production extension transaction",
        )
        assertTrue(validator.contains("ExpectedVersion"), "Runtime acceptance must verify the packaged app version")
        assertTrue(validator.contains("sourceIds"), "Runtime acceptance must verify that production loading exposed sources")
    }

    @Test
    fun `windows unpackaged publisher copies validated runtime to durable versioned directory`(
        @TempDir tempDir: Path,
    ) {
        val publisher = repoRoot.resolve("scripts/publish-windows-unpacked.ps1")
        assertTrue(Files.exists(publisher), "Windows unpackaged publisher must exist")

        val source = tempDir.resolve("build-output/Mihon Desktop")
        Files.createDirectories(source.resolve("app"))
        Files.createDirectories(source.resolve("runtime"))
        Files.writeString(source.resolve("Mihon Desktop.exe"), "launcher")
        Files.writeString(source.resolve("app/application.jar"), "application")
        Files.writeString(source.resolve("runtime/java.dll"), "runtime")

        val outputRoot = tempDir.resolve("artifacts/windows")
        val version = "0.12.3.4.abcdef0"
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            publisher.toString(),
            "-SourceDirectory",
            source.toString(),
            "-OutputRoot",
            outputRoot.toString(),
            "-FullVersion",
            version,
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()

        assertEquals(0, exitCode, output)
        val finalApp = outputRoot.resolve("Mihon-Desktop-$version-unpacked")
        val finalExe = finalApp.resolve("Mihon Desktop.exe")
        assertTrue(Files.isRegularFile(finalExe), "Durable unpackaged launcher must be copied")
        assertTrue(Files.isRegularFile(finalApp.resolve("app/application.jar")), "Application files must be copied")
        assertTrue(Files.isRegularFile(finalApp.resolve("runtime/java.dll")), "Runtime files must be copied")
        assertTrue(
            output.contains("Final unpacked EXE: ${finalExe.toAbsolutePath()}"),
            "Publisher must report the durable executable path for completion reports",
        )
        assertFalse(
            output.contains("app-desktop${System.getProperty("file.separator")}tmp"),
            "Publisher output must not present a temporary Gradle path as the final executable",
        )
    }

    @Test
    fun `windows MSI mode regenerates unpackaged app last`() {
        val text = Files.readString(repoRoot.resolve("scripts/build-windows.ps1"))

        assertTrue(text.contains("[switch]${'$'}PackageMsi"), "MSI packaging must be an explicit switch")
        val packageIndex = text.lastIndexOf(":app-desktop:packageMsi")
        val unpackagedIndex = text.lastIndexOf(":app-desktop:createDistributable")
        assertTrue(packageIndex >= 0, "Windows script must retain explicit MSI support")
        assertTrue(
            unpackagedIndex > packageIndex,
            "createDistributable must run after packageMsi so MSI cleanup cannot remove the validation EXE",
        )
    }

    @Test
    fun `windows build script supports test only mode without packaging MSI`() {
        val script = repoRoot.resolve("scripts/build-windows.ps1")
        val text = Files.readString(script)

        assertTrue(text.contains("[switch]${'$'}TestOnly"), "Windows script must expose a TestOnly switch")
        assertTrue(text.contains("[switch]${'$'}FullTests"), "Windows script must expose a FullTests switch")
        assertTrue(
            text.contains("-PincludeIntegrationTests=true"),
            "FullTests mode must opt in to integration tests explicitly",
        )
        assertTrue(
            text.contains("Windows test validation completed without building artifacts"),
            "TestOnly mode must clearly report that no artifacts were built",
        )

        val testOnlyIndex = text.indexOf("if (${ '$' }TestOnly)")
        val packageIndex = text.indexOf(":app-desktop:packageMsi")
        assertTrue(testOnlyIndex >= 0, "Windows script must branch on TestOnly")
        assertTrue(packageIndex >= 0, "Windows script must still support explicit packageMsi release builds")
        assertTrue(testOnlyIndex < packageIndex, "TestOnly branch must return before packaging MSI")
    }

    @Test
    fun `desktop build script dispatches to windows powershell script on Windows shells`() {
        val script = repoRoot.resolve("scripts/build-desktop.sh")
        val text = Files.readString(script)

        assertTrue(text.contains("uname -s"), "Desktop build script must detect the host platform")
        assertTrue(text.contains("MINGW") && text.contains("MSYS") && text.contains("CYGWIN"))
        assertTrue(text.contains("scripts/build-windows.ps1"), "Windows shell path must call the Windows build script")
        assertTrue(text.contains("powershell.exe") || text.contains("pwsh"), "Windows shell path must invoke PowerShell")
        assertTrue(text.contains("-ExecutionPolicy") && text.contains("Bypass"))
        assertTrue(
            text.contains("WINDOWS_PS_SCRIPT=\"scripts/build-windows.ps1\""),
            "Git Bash/MSYS should pass a relative script path to PowerShell to avoid /c/... path conversion bugs",
        )
        assertTrue(
            text.contains("-TestOnly"),
            "Unified desktop script should expose Windows TestOnly dispatch",
        )
    }

    @Test
    fun `desktop build script allocates per-build version and exposes explicit MSI mode`() {
        val text = Files.readString(repoRoot.resolve("scripts/build-desktop.sh"))

        assertTrue(text.contains("read_version_constant BUILD"), "Unified script must read BUILD")
        assertTrue(text.contains("replace_version_constant BUILD"), "Unified script must persist BUILD")
        assertTrue(text.contains("BUILD=${'$'}((BUILD + 1))"), "Default and MSI builds must increment BUILD")
        assertTrue(text.contains("BUILD=1"), "Feature and stage builds must reset BUILD to 1")
        assertTrue(text.contains("0.${'$'}STAGE.${'$'}FEATURE.${'$'}BUILD.${'$'}GIT_HASH"))
        assertTrue(text.contains("msi)"), "Unified script must expose an explicit MSI mode")
        assertTrue(text.contains("-PackageMsi"), "MSI mode must be passed explicitly to PowerShell")
        assertTrue(text.contains("-VersionAllocated"), "PowerShell must not allocate the version twice")
        assertTrue(text.contains("-ExpectedVersion"), "PowerShell must receive the expected runtime version")
        assertFalse(
            text.contains("hash auto-updates at build time"),
            "Old hash-only build semantics must be removed",
        )
    }

    @Test
    fun `desktop build script keeps macOS deploy path isolated to Darwin branch`() {
        val script = repoRoot.resolve("scripts/build-desktop.sh")
        val text = Files.readString(script)

        val darwinIndex = text.indexOf("Darwin")
        val applicationsIndex = text.indexOf("/Applications/Mihon Desktop.app")
        val windowsIndex = text.indexOf("MINGW")

        assertTrue(darwinIndex >= 0, "Desktop build script must keep an explicit macOS branch")
        assertTrue(applicationsIndex > darwinIndex, "macOS deploy path must stay in the macOS branch")
        assertTrue(windowsIndex > applicationsIndex, "Windows branch must appear after the macOS deploy path")
        assertTrue(text.contains(":app-desktop:createDistributable"), "macOS branch must still build the app bundle")
    }

    @Test
    fun `desktop build script treats Linux uname with powershell exe as Windows bash`() {
        val script = repoRoot.resolve("scripts/build-desktop.sh")
        val text = Files.readString(script)

        assertTrue(
            text.contains("command -v powershell.exe") && text.contains("run_windows"),
            "WSL or Windows-hosted bash can report Linux from uname but still dispatch through powershell.exe",
        )
        assertTrue(
            text.contains("Linux") && text.contains("powershell.exe"),
            "Linux branch must check for Windows PowerShell before reporting unsupported Linux packaging",
        )
    }

    @Test
    fun `default desktop tests exclude live integration tests unless explicitly enabled`() {
        val buildFile = repoRoot.resolve("app-desktop/build.gradle.kts")
        val text = Files.readString(buildFile)

        assertTrue(
            text.contains("includeIntegrationTests"),
            "Gradle test config must expose an explicit integration-test opt-in",
        )
        assertTrue(
            text.contains("""excludeTags("integration")"""),
            "Default desktop JVM tests must exclude live integration tests",
        )
    }

    @Test
    fun `desktop documentation requires unpackaged Windows runtime validation`() {
        val testGuide = Files.readString(repoRoot.resolve("docs/automation/TEST_GUIDE.md"))

        assertTrue(hasWindowsRuntimeValidationDocumentation(testGuide))
        requiredWindowsRuntimeDocumentationTerms.forEach { term ->
            assertFalse(
                hasWindowsRuntimeValidationDocumentation(testGuide.replace(term, "")),
                "Removing '$term' must invalidate the Windows runtime validation documentation",
            )
        }
    }

    private fun hasWindowsRuntimeValidationDocumentation(text: String): Boolean =
        requiredWindowsRuntimeDocumentationTerms.all(text::contains)

    private companion object {
        val requiredWindowsRuntimeDocumentationTerms = listOf(
            "0.STAGE.FEATURE.BUILD.GIT_HASH",
            "app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-unpacked/Mihon Desktop.exe",
            "Final unpacked EXE:",
            "完成报告",
            "MSI",
            "不能替代",
            "运行版本",
            "完全一致",
            "未打包",
        )
    }
}
