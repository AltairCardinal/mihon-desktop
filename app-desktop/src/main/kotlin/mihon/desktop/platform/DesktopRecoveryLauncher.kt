package mihon.desktop.platform

import java.io.File

fun interface DesktopRecoveryProcessPort {
    fun launch(executable: File, arguments: List<String>): Boolean
}

/** A recoverable instance always has a persistent entry using the packaged production executable. */
object DesktopRecoveryLauncher {
    fun writeEntry(
        root: File,
        executable: File = DesktopUriSchemeRegistration.currentExecutable(),
        platform: OperatingSystem = OperatingSystem.detect(),
    ): File {
        val selected = DesktopRecoveryProfile.validateRoot(root)
        require(DesktopUriSchemeRegistration.isPackagedExecutable(platform, executable)) {
            "Recovery requires the packaged production application"
        }
        val recovery = listOf("--recovery-profile=${selected.path}")
        val original = DesktopRecoveryProfile.originalArguments(selected)
        val extension = if (platform == OperatingSystem.WINDOWS) "cmd" else "command"
        val entry = File(selected, "Mihon-recovery-instance.$extension")
        writeOwnedEntry(entry, command(executable, recovery, platform))
        writeOwnedEntry(File(selected, "Mihon-original-instance.$extension"), command(executable, original, platform))
        return entry
    }

    fun launch(
        root: File,
        executable: File = DesktopUriSchemeRegistration.currentExecutable(),
        platform: OperatingSystem = OperatingSystem.detect(),
        process: DesktopRecoveryProcessPort = DesktopRecoveryProcessPort { app, args ->
            DesktopExternalActionPolicy.requireAllowed("Recovery instance launch")
            ProcessBuilder(listOf(app.absolutePath) + args).start()
            true
        },
    ): Boolean {
        writeEntry(root, executable, platform)
        return process.launch(executable, listOf("--recovery-profile=${DesktopRecoveryProfile.validateRoot(root).path}"))
    }

    private fun command(executable: File, arguments: List<String>, platform: OperatingSystem): String {
        val parts = listOf(executable.absolutePath) + arguments
        require(parts.all { value -> value.none { it == '\u0000' || it == '\r' || it == '\n' || it == '"' } })
        return if (platform == OperatingSystem.WINDOWS) {
            "@echo off\r\nrem Mihon recovery entry v1\r\nchcp 65001 >nul\r\n" +
                parts.joinToString(" ") { "\"${it.replace("%", "%%")}\"" } + "\r\n"
        } else {
            "#!/bin/sh\n# Mihon recovery entry v1\nexec " +
                parts.joinToString(" ") { "'${it.replace("'", "'\"'\"'")}'" } + "\n"
        }
    }

    private fun writeOwnedEntry(file: File, content: String) {
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        if (file.exists()) {
            require(file.isFile && file.length() <= 65_536 && file.readText(Charsets.UTF_8) == content) {
                "The existing startup entry was changed; it has been preserved"
            }
        } else {
            java.nio.file.Files.writeString(file.toPath(), content, Charsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        if (file.extension == "command") require(file.setExecutable(true, true))
    }
}
