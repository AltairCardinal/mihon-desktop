package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.AccessDeniedException

class DesktopUriSchemeRegistrationTest {
    @Test
    fun `Windows overwrites HKCU canonical scheme with quoted current executable`(@TempDir tempDir: File) {
        val runner = RecordingRunner()
        val executable = File(tempDir, "Mihon Desktop.exe")
        val registration = DesktopUriSchemeRegistration(
            platform = OperatingSystem.WINDOWS,
            executable = executable,
            packagedRuntime = true,
            commandRunner = runner,
        )

        assertEquals(
            DesktopUriSchemeRegistration.Result.Configured(
                DesktopUriSchemeRegistration.Mechanism.WINDOWS_CURRENT_USER_REGISTRY,
            ),
            registration.register(),
        )
        assertEquals(3, runner.commands.size)
        assertTrue(runner.commands.all { it.first() == "reg" && "/f" in it })
        assertTrue(runner.commands.all { command -> command.any { "tachiyomi" in it } })
        assertFalse(runner.commands.flatten().any { "mihon" in it.lowercase() && "mihon desktop" !in it.lowercase() })
        assertEquals("\"${executable.absolutePath}\" \"%1\"", runner.commands.last()[runner.commands.last().indexOf("/d") + 1])
    }

    @Test
    fun `Linux writes canonical desktop entry then updates databases`(@TempDir tempDir: File) {
        val runner = RecordingRunner()
        val executable = File("/opt/Mihon Desktop/bin/Mihon Desktop")
        val registration = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = executable,
            packagedRuntime = true,
            commandRunner = runner,
            linuxApplicationsDirectory = tempDir,
        )

        assertEquals(
            DesktopUriSchemeRegistration.Result.Configured(
                DesktopUriSchemeRegistration.Mechanism.LINUX_DESKTOP_ENTRY,
            ),
            registration.register(),
        )
        val desktopEntry = File(tempDir, "mihon-desktop.desktop").readText()
        assertTrue(desktopEntry.contains("Exec=\"/opt/Mihon Desktop/bin/Mihon Desktop\" %u"))
        assertTrue(desktopEntry.contains("MimeType=x-scheme-handler/tachiyomi;"))
        assertFalse(desktopEntry.contains("x-scheme-handler/mihon"))
        assertEquals(
            listOf(
                listOf("update-desktop-database", tempDir.absolutePath),
                listOf("xdg-mime", "default", "mihon-desktop.desktop", "x-scheme-handler/tachiyomi"),
            ),
            runner.commands,
        )
    }

    @Test
    fun `macOS reports packaged bundle metadata without runtime command`(@TempDir tempDir: File) {
        val runner = RecordingRunner()
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.MACOS,
            executable = File(tempDir, "Mihon Desktop.app/Contents/MacOS/Mihon Desktop"),
            packagedRuntime = true,
            commandRunner = runner,
        ).register()

        assertEquals(
            DesktopUriSchemeRegistration.Result.Configured(
                DesktopUriSchemeRegistration.Mechanism.MACOS_BUNDLE_METADATA,
            ),
            result,
        )
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `non packaged command missing and permission failures stay structured`(@TempDir tempDir: File) {
        val nonPackaged = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = File("java"),
            packagedRuntime = false,
            commandRunner = RecordingRunner(),
            linuxApplicationsDirectory = tempDir,
        ).register()
        assertEquals(
            DesktopUriSchemeRegistration.Result.Unavailable(
                DesktopUriSchemeRegistration.UnavailableReason.NON_PACKAGED_RUNTIME,
            ),
            nonPackaged,
        )

        listOf(0, 1).forEach { missingCommandIndex ->
            val commandMissing = DesktopUriSchemeRegistration(
                platform = OperatingSystem.LINUX,
                executable = File("/opt/mihon/Mihon"),
                packagedRuntime = true,
                commandRunner = FailOnInvocationRunner(missingCommandIndex),
                linuxApplicationsDirectory = File(tempDir, "commands-$missingCommandIndex"),
            ).register()
            assertEquals(
                DesktopUriSchemeRegistration.Result.Unavailable(
                    DesktopUriSchemeRegistration.UnavailableReason.COMMAND_UNAVAILABLE,
                ),
                commandMissing,
            )
        }

        val denied = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = File("/opt/mihon/Mihon"),
            packagedRuntime = true,
            commandRunner = RecordingRunner(),
            linuxApplicationsDirectory = File(tempDir, "denied"),
            writeFile = { _, _ -> throw AccessDeniedException("private") },
        ).register()
        assertEquals(
            DesktopUriSchemeRegistration.Result.Failed(
                DesktopUriSchemeRegistration.FailureReason.PERMISSION_DENIED,
            ),
            denied,
        )
    }

    @Test
    fun `nonzero command is a structured configuration failure`(@TempDir tempDir: File) {
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.WINDOWS,
            executable = File(tempDir, "Mihon.exe"),
            packagedRuntime = true,
            commandRunner = RecordingRunner(result = CommandResult(1, "", "sensitive")),
        ).register()

        val failed = assertInstanceOf(DesktopUriSchemeRegistration.Result.Failed::class.java, result)
        assertEquals(DesktopUriSchemeRegistration.FailureReason.COMMAND_FAILED, failed.reason)
        assertFalse(failed.toString().contains("sensitive"))
    }

    @Test
    fun `Linux desktop entry escapes executable metacharacters`(@TempDir tempDir: File) {
        val executable = File("Mihon ${'$'}Edition \"quoted\"%")
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = executable,
            packagedRuntime = true,
            commandRunner = RecordingRunner(),
            linuxApplicationsDirectory = tempDir,
        ).register()

        assertTrue(result is DesktopUriSchemeRegistration.Result.Configured)
        assertTrue(
            File(tempDir, "mihon-desktop.desktop").readText()
                .contains("Exec=\"Mihon \\${'$'}Edition \\\"quoted\\\"%%\" %u"),
        )
    }

    @Test
    fun `unexpected adapter exception becomes structured failure`(@TempDir tempDir: File) {
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.MACOS,
            executable = File(tempDir, "Mihon Desktop.app/Contents/MacOS/Mihon Desktop"),
            packagedRuntime = true,
            resourceLoader = { error("broken resource loader") },
        ).register()

        assertEquals(
            DesktopUriSchemeRegistration.Result.Failed(
                DesktopUriSchemeRegistration.FailureReason.UNEXPECTED_FAILURE,
            ),
            result,
        )
    }

    private class RecordingRunner(
        private val failure: RuntimeException? = null,
        private val result: CommandResult = CommandResult(0, "", ""),
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(arguments: List<String>, stdin: CharArray?): CommandResult {
            commands += arguments
            failure?.let { throw it }
            return result
        }
    }

    private class FailOnInvocationRunner(private val failureIndex: Int) : CommandRunner {
        private var invocationIndex = 0

        override fun run(arguments: List<String>, stdin: CharArray?): CommandResult {
            if (invocationIndex++ == failureIndex) throw CommandUnavailableException(arguments.first())
            return CommandResult(0, "", "")
        }
    }
}
