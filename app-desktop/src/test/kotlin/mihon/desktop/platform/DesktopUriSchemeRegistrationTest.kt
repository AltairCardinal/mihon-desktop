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
        val executable = packagedExecutable(File(tempDir, "windows"), OperatingSystem.WINDOWS).executable
        val registration = DesktopUriSchemeRegistration(
            platform = OperatingSystem.WINDOWS,
            executable = executable,
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

        val renderedTemplate = resource("platform/windows/tachiyomi-url-protocol.reg.template")
            .replace("{{EXECUTABLE}}", executable.absolutePath.replace("\\", "\\\\"))
        val commandEntries = runner.commands.map { command ->
            val key = command[2].replace("HKCU", "HKEY_CURRENT_USER")
            val valueName = if ("/ve" in command) "@" else "\"${command[command.indexOf("/v") + 1]}\""
            val data = command[command.indexOf("/d") + 1]
            RegistryEntry(key, valueName, data)
        }
        assertEquals(commandEntries.toSet(), parseRegistryTemplate(renderedTemplate).toSet())
    }

    @Test
    fun `packaged detection requires real executable marker and runtime for each jpackage layout`(@TempDir tempDir: File) {
        val ordinaryWindows = File(tempDir, "other.exe").apply { createNewFile() }
        val ordinaryLinux = File(tempDir, "ordinary-linux-binary").apply { createNewFile() }
        assertFalse(DesktopUriSchemeRegistration.isPackagedExecutable(OperatingSystem.WINDOWS, ordinaryWindows))
        assertFalse(DesktopUriSchemeRegistration.isPackagedExecutable(OperatingSystem.LINUX, ordinaryLinux))

        val notContents = File(tempDir, "NotContents")
        val misplacedMacExecutable = File(notContents, "MacOS/Mihon Desktop").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        File(notContents, "app/.jpackage.xml").apply { parentFile.mkdirs(); createNewFile() }
        File(notContents, "runtime").mkdirs()
        assertFalse(DesktopUriSchemeRegistration.isPackagedExecutable(OperatingSystem.MACOS, misplacedMacExecutable))

        OperatingSystem.entries.filter { it != OperatingSystem.UNSUPPORTED }.forEach { platform ->
            val complete = packagedExecutable(File(tempDir, "complete-$platform"), platform)
            assertTrue(DesktopUriSchemeRegistration.isPackagedExecutable(platform, complete.executable))

            val missingMarker = packagedExecutable(File(tempDir, "missing-marker-$platform"), platform)
            missingMarker.marker.delete()
            assertFalse(DesktopUriSchemeRegistration.isPackagedExecutable(platform, missingMarker.executable))

            val missingRuntime = packagedExecutable(File(tempDir, "missing-runtime-$platform"), platform)
            missingRuntime.runtime.deleteRecursively()
            assertFalse(DesktopUriSchemeRegistration.isPackagedExecutable(platform, missingRuntime.executable))
        }
    }

    @Test
    fun `Linux writes canonical desktop entry then updates databases`(@TempDir tempDir: File) {
        val runner = RecordingRunner()
        val executable = packagedExecutable(File(tempDir, "Linux App With Space"), OperatingSystem.LINUX).executable
        val registration = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = executable,
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
        assertTrue(desktopEntry.contains("Exec=\"${executable.invariantSeparatorsPath}\" %u"))
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
        val executable = packagedExecutable(File(tempDir, "macos"), OperatingSystem.MACOS).executable
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.MACOS,
            executable = executable,
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
            val executable = packagedExecutable(
                File(tempDir, "commands-layout-$missingCommandIndex"),
                OperatingSystem.LINUX,
            ).executable
            val commandMissing = DesktopUriSchemeRegistration(
                platform = OperatingSystem.LINUX,
                executable = executable,
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

        val deniedExecutable = packagedExecutable(File(tempDir, "denied-layout"), OperatingSystem.LINUX).executable
        val denied = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = deniedExecutable,
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
        val executable = packagedExecutable(File(tempDir, "failed-windows"), OperatingSystem.WINDOWS).executable
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.WINDOWS,
            executable = executable,
            commandRunner = RecordingRunner(result = CommandResult(1, "", "sensitive")),
        ).register()

        val failed = assertInstanceOf(DesktopUriSchemeRegistration.Result.Failed::class.java, result)
        assertEquals(DesktopUriSchemeRegistration.FailureReason.COMMAND_FAILED, failed.reason)
        assertFalse(failed.toString().contains("sensitive"))
    }

    @Test
    fun `Linux desktop entry escapes executable metacharacters`(@TempDir tempDir: File) {
        val executable = packagedExecutable(
            File(tempDir, "Mihon ${'$'}Edition `%"),
            OperatingSystem.LINUX,
        ).executable
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.LINUX,
            executable = executable,
            commandRunner = RecordingRunner(),
            linuxApplicationsDirectory = tempDir,
        ).register()

        assertTrue(result is DesktopUriSchemeRegistration.Result.Configured)
        val escapedExecutable = executable.invariantSeparatorsPath
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("${'$'}", "\\${'$'}")
            .replace("\"", "\\\"")
            .replace("%", "%%")
        assertTrue(
            File(tempDir, "mihon-desktop.desktop").readText()
                .contains("Exec=\"$escapedExecutable\" %u"),
        )
        assertEquals(
            "Mihon \\${'$'}Edition \\\"quoted\\\"%%",
            DesktopUriSchemeRegistration.escapeDesktopEntryExecutable("Mihon ${'$'}Edition \"quoted\"%"),
        )
    }

    @Test
    fun `unexpected adapter exception becomes structured failure`(@TempDir tempDir: File) {
        val executable = packagedExecutable(File(tempDir, "broken-macos"), OperatingSystem.MACOS).executable
        val result = DesktopUriSchemeRegistration(
            platform = OperatingSystem.MACOS,
            executable = executable,
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

    private fun resource(path: String): String = checkNotNull(javaClass.classLoader.getResourceAsStream(path))
        .bufferedReader()
        .use { it.readText() }

    private fun parseRegistryTemplate(template: String): List<RegistryEntry> {
        var key = ""
        return template.lineSequence().map(String::trim).mapNotNull { line ->
            when {
                line.startsWith('[') && line.endsWith(']') -> {
                    key = line.removeSurrounding("[", "]")
                    null
                }
                key.isEmpty() || '=' !in line -> null
                else -> {
                    val separator = line.indexOf('=')
                    RegistryEntry(
                        key,
                        line.substring(0, separator),
                        unescapeRegistryString(line.substring(separator + 1).removeSurrounding("\"")),
                    )
                }
            }
        }.toList()
    }

    private fun unescapeRegistryString(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) index++
            append(value[index++])
        }
    }

    private fun packagedExecutable(root: File, platform: OperatingSystem): PackagedLayout {
        val executable: File
        val marker: File
        val runtime: File
        when (platform) {
            OperatingSystem.WINDOWS -> {
                executable = File(root, "Mihon Desktop.exe")
                marker = File(root, "app/.jpackage.xml")
                runtime = File(root, "runtime")
            }
            OperatingSystem.MACOS -> {
                val contents = File(root, "Mihon Desktop.app/Contents")
                executable = File(contents, "MacOS/Mihon Desktop")
                marker = File(contents, "app/.jpackage.xml")
                runtime = File(contents, "runtime")
            }
            OperatingSystem.LINUX -> {
                executable = File(root, "bin/Mihon Desktop")
                marker = File(root, "lib/app/.jpackage.xml")
                runtime = File(root, "lib/runtime")
            }
            OperatingSystem.UNSUPPORTED -> error("unsupported fixture")
        }
        executable.parentFile.mkdirs()
        executable.createNewFile()
        marker.parentFile.mkdirs()
        marker.createNewFile()
        runtime.mkdirs()
        return PackagedLayout(executable, marker, runtime)
    }

    private data class PackagedLayout(val executable: File, val marker: File, val runtime: File)

    private data class RegistryEntry(val key: String, val valueName: String, val data: String)
}
