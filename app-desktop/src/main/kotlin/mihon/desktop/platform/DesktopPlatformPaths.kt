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
    val instanceStateFile: File = File(configDir, "desktop-instance.json"),
    val recoveryProfileRoot: File? = null,
) {
    companion object {
        fun current(createDirectories: Boolean = true): DesktopPlatformPaths = resolve(
            osName = System.getProperty("os.name"),
            userHome = System.getProperty("user.home"),
            env = DesktopRecoveryProfile.environment(if (DesktopTestProfile.root == null) System.getenv() else emptyMap()),
            createDirectories = createDirectories,
        )

        /** Path descriptions only; startup must validate the marker before opening any business data. */
        internal fun preservedRecoveryPaths(root: File): DesktopPlatformPaths = DesktopPlatformPaths(
            configDir = File(root, "config"), databaseFile = File(root, "config/mihon.db"),
            networkCacheDir = File(root, "cache/network"), cookiesFile = File(root, "config/cookies.json"),
            downloadsDir = File(root, "storage/downloads"), extensionsDir = File(root, "extensions"),
            coversDir = File(root, "covers"), logsDir = File(root, "logs"), backupsDir = File(root, "storage/backups"),
            recoveryProfileRoot = root,
        )

        fun resolve(
            osName: String,
            userHome: String,
            env: Map<String, String>,
            createDirectories: Boolean = true,
        ): DesktopPlatformPaths {
            env[DesktopRecoveryProfile.ENVIRONMENT_KEY]?.let { requested ->
                val root = DesktopRecoveryProfile.validateRoot(File(requested))
                return preservedRecoveryPaths(root).also {
                    if (createDirectories) it.defaultDirectories().forEach(File::mkdirs)
                }
            }
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
    )
}
