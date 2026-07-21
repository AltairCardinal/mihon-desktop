package mihon.desktop.platform

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

fun interface DesktopUriSchemeRegistrar {
    fun register(): DesktopUriSchemeRegistration.Result
}

class DesktopUriSchemeRegistration(
    private val platform: OperatingSystem = OperatingSystem.detect(),
    private val executable: File = currentExecutable(),
    private val packagedRuntime: Boolean = isPackagedExecutable(platform, executable),
    private val commandRunner: CommandRunner = ProcessCommandRunner(),
    private val linuxApplicationsDirectory: File = defaultLinuxApplicationsDirectory(),
    private val resourceLoader: (String) -> String? = ::loadResource,
    private val writeFile: (File, String) -> Unit = ::writeAtomically,
) : DesktopUriSchemeRegistrar {
    override fun register(): Result {
        if (platform == OperatingSystem.UNSUPPORTED) {
            return Result.Unavailable(UnavailableReason.UNSUPPORTED_PLATFORM)
        }
        if (!packagedRuntime) return Result.Unavailable(UnavailableReason.NON_PACKAGED_RUNTIME)
        return try {
            when (platform) {
                OperatingSystem.WINDOWS -> registerWindows()
                OperatingSystem.MACOS -> registerMacOs()
                OperatingSystem.LINUX -> registerLinux()
                OperatingSystem.UNSUPPORTED -> Result.Unavailable(UnavailableReason.UNSUPPORTED_PLATFORM)
            }
        } catch (_: CommandUnavailableException) {
            Result.Unavailable(UnavailableReason.COMMAND_UNAVAILABLE)
        } catch (_: AccessDeniedException) {
            Result.Failed(FailureReason.PERMISSION_DENIED)
        } catch (_: SecurityException) {
            Result.Failed(FailureReason.PERMISSION_DENIED)
        } catch (_: IOException) {
            Result.Failed(FailureReason.FILE_WRITE_FAILED)
        } catch (_: IllegalArgumentException) {
            Result.Failed(FailureReason.INVALID_EXECUTABLE)
        } catch (_: Exception) {
            Result.Failed(FailureReason.UNEXPECTED_FAILURE)
        }
    }

    private fun registerWindows(): Result {
        validateExecutable(executable)
        val key = "HKCU\\Software\\Classes\\$SCHEME"
        val commands = listOf(
            listOf("reg", "add", key, "/ve", "/t", "REG_SZ", "/d", "URL:$SCHEME", "/f"),
            listOf("reg", "add", key, "/v", "URL Protocol", "/t", "REG_SZ", "/d", "", "/f"),
            listOf(
                "reg",
                "add",
                "$key\\shell\\open\\command",
                "/ve",
                "/t",
                "REG_SZ",
                "/d",
                "\"${executable.absolutePath}\" \"%1\"",
                "/f",
            ),
        )
        return commands.firstNotNullOfOrNull(::commandFailure)
            ?: Result.Configured(Mechanism.WINDOWS_CURRENT_USER_REGISTRY)
    }

    private fun registerMacOs(): Result {
        return if (resourceLoader(MACOS_PLIST) == null) {
            Result.Failed(FailureReason.RESOURCE_MISSING)
        } else {
            Result.Configured(Mechanism.MACOS_BUNDLE_METADATA)
        }
    }

    private fun registerLinux(): Result {
        validateExecutable(executable, allowQuotes = true)
        val template = resourceLoader(LINUX_DESKTOP_ENTRY)
            ?: return Result.Failed(FailureReason.RESOURCE_MISSING)
        val desktopEntry = template.replace(
            EXECUTABLE_PLACEHOLDER,
            escapeDesktopEntryExecutable(executable.invariantSeparatorsPath),
        )
        linuxApplicationsDirectory.mkdirs()
        val destination = File(linuxApplicationsDirectory, LINUX_DESKTOP_FILE)
        writeFile(destination, desktopEntry)
        val commands = listOf(
            listOf("update-desktop-database", linuxApplicationsDirectory.absolutePath),
            listOf("xdg-mime", "default", LINUX_DESKTOP_FILE, "x-scheme-handler/$SCHEME"),
        )
        return commands.firstNotNullOfOrNull(::commandFailure)
            ?: Result.Configured(Mechanism.LINUX_DESKTOP_ENTRY)
    }

    private fun commandFailure(arguments: List<String>): Result.Failed? {
        val result = commandRunner.run(arguments)
        return result.takeIf { it.exitCode != 0 }?.let { Result.Failed(FailureReason.COMMAND_FAILED) }
    }

    sealed interface Result {
        /** Configuration succeeded; the OS-to-broker action path is intentionally not asserted here. */
        data class Configured(
            val mechanism: Mechanism,
            val endToEndActionVerified: Boolean = false,
        ) : Result

        data class Unavailable(val reason: UnavailableReason) : Result
        data class Failed(val reason: FailureReason) : Result
    }

    enum class Mechanism { WINDOWS_CURRENT_USER_REGISTRY, MACOS_BUNDLE_METADATA, LINUX_DESKTOP_ENTRY }

    enum class UnavailableReason { NON_PACKAGED_RUNTIME, UNSUPPORTED_PLATFORM, COMMAND_UNAVAILABLE }

    enum class FailureReason {
        PERMISSION_DENIED,
        COMMAND_FAILED,
        FILE_WRITE_FAILED,
        INVALID_EXECUTABLE,
        RESOURCE_MISSING,
        UNEXPECTED_FAILURE,
    }

    companion object {
        const val SCHEME = "tachiyomi"
        private const val EXECUTABLE_PLACEHOLDER = "{{EXECUTABLE}}"
        private const val LINUX_DESKTOP_FILE = "mihon-desktop.desktop"
        private const val LINUX_DESKTOP_ENTRY = "platform/linux/$LINUX_DESKTOP_FILE"
        private const val MACOS_PLIST = "platform/macos/tachiyomi-url-types.plist"

        fun currentExecutable(): File = ProcessHandle.current().info().command()
            .map(::File)
            .orElseGet { File("") }

        fun isPackagedExecutable(platform: OperatingSystem, executable: File): Boolean {
            if (executable.path.isBlank()) return false
            val name = executable.name.lowercase()
            if (name == "java" || name == "java.exe" || name == "javaw.exe") return false
            return when (platform) {
                OperatingSystem.WINDOWS -> name.endsWith(".exe")
                OperatingSystem.MACOS -> executable.invariantSeparatorsPath.contains(".app/Contents/MacOS/")
                OperatingSystem.LINUX -> true
                OperatingSystem.UNSUPPORTED -> false
            }
        }

        private fun defaultLinuxApplicationsDirectory(): File = File(
            System.getenv("XDG_DATA_HOME") ?: File(System.getProperty("user.home"), ".local/share").path,
            "applications",
        )

        private fun loadResource(path: String): String? = DesktopUriSchemeRegistration::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }

        private fun validateExecutable(executable: File, allowQuotes: Boolean = false) {
            require(executable.path.isNotBlank())
            require(executable.path.none { it == '\u0000' || it == '\r' || it == '\n' })
            if (!allowQuotes) require('"' !in executable.path)
        }

        private fun escapeDesktopEntryExecutable(path: String): String {
            validateExecutable(File(path), allowQuotes = true)
            return buildString(path.length) {
                path.forEach { character ->
                    when (character) {
                        '\\', '`', '$', '"' -> append('\\').append(character)
                        '%' -> append("%%")
                        else -> append(character)
                    }
                }
            }
        }

        private fun writeAtomically(destination: File, content: String) {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
            try {
                Files.writeString(temporary.toPath(), content)
                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }
    }
}
