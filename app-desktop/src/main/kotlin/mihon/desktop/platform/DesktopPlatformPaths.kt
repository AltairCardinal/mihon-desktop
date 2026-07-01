package mihon.desktop.platform

import java.io.File

data class DesktopPlatformPaths(
    val configDir: File,
    val databaseFile: File,
    val networkCacheDir: File,
    val cookiesFile: File,
    val downloadsDir: File,
    val extensionsDir: File,
    val coversDir: File,
    val logsDir: File,
    val backupsDir: File,
    val testScreenshotsDir: File,
) {
    companion object {
        fun current(createDirectories: Boolean = true): DesktopPlatformPaths = resolve(
            osName = System.getProperty("os.name"),
            userHome = System.getProperty("user.home"),
            env = System.getenv(),
            createDirectories = createDirectories,
        )

        fun resolve(
            osName: String,
            userHome: String,
            env: Map<String, String>,
            createDirectories: Boolean = true,
        ): DesktopPlatformPaths {
            val lowerOsName = osName.lowercase()
            val legacyAppDir = File(userHome, ".mihon")

            val configRoot = when {
                lowerOsName.contains("win") -> File(
                    env["APPDATA"] ?: File(userHome, "AppData/Roaming").path,
                    "Mihon",
                )
                else -> legacyAppDir
            }

            val localRoot = when {
                lowerOsName.contains("win") -> File(
                    env["LOCALAPPDATA"] ?: File(userHome, "AppData/Local").path,
                    "Mihon",
                )
                else -> legacyAppDir
            }

            val logsRoot = when {
                lowerOsName.contains("mac") -> File(userHome, "Library/Logs/Mihon")
                lowerOsName.contains("win") -> File(localRoot, "logs")
                else -> File(legacyAppDir, "logs")
            }

            return DesktopPlatformPaths(
                configDir = configRoot,
                databaseFile = File(configRoot, "mihon.db"),
                networkCacheDir = File(localRoot, "cache/network"),
                cookiesFile = File(configRoot, "cookies.json"),
                downloadsDir = File(localRoot, "downloads"),
                extensionsDir = File(localRoot, "extensions"),
                coversDir = File(localRoot, "covers"),
                logsDir = logsRoot,
                backupsDir = File(localRoot, "backups"),
                testScreenshotsDir = File(localRoot, "test-screenshots"),
            ).also { paths ->
                if (createDirectories) {
                    paths.defaultDirectories().forEach { it.mkdirs() }
                }
            }
        }
    }

    fun defaultDirectories(): List<File> = listOf(
        configDir,
        networkCacheDir,
        downloadsDir,
        extensionsDir,
        coversDir,
        logsDir,
        backupsDir,
        testScreenshotsDir,
    )
}
