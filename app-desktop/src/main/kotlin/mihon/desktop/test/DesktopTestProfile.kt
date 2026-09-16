package mihon.desktop.test

import mihon.desktop.platform.DesktopPlatformPaths
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.security.MessageDigest
import java.util.prefs.Preferences

internal data class DesktopTestProfile(
    val paths: DesktopPlatformPaths,
    val preferenceNode: String,
) {
    fun preferences() = DesktopPreferenceStore(Preferences.userRoot().node(preferenceNode))
}

internal fun desktopTestProfile(args: Array<String>): DesktopTestProfile? {
    val flags = args.filter { it.startsWith("--test-profile-dir=") }
    if (flags.isEmpty()) return null
    require(flags.size == 1 && "--test-mode" in args) { "A dedicated profile requires test mode" }
    val raw = flags.single().substringAfter('=')
    require(raw.isNotBlank() && '\n' !in raw && '\r' !in raw && File(raw).isAbsolute) {
        "Test profile must be an absolute single-line directory"
    }
    val directory = File(raw).canonicalFile
    val protected = listOf(
        File(System.getProperty("user.home")).canonicalFile,
        DesktopPlatformPaths.current(createDirectories = false).configDir.canonicalFile,
    )
    require(protected.none { it.toPath().startsWith(directory.toPath()) }) {
        "Test profile must be separate from the existing user profile"
    }
    val identity = MessageDigest.getInstance("SHA-256")
        .digest(directory.path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return DesktopTestProfile(
        paths = DesktopPlatformPaths.resolve(
            osName = System.getProperty("os.name"),
            userHome = directory.path,
            env = mapOf(
                "APPDATA" to directory.resolve("roaming").path,
                "LOCALAPPDATA" to directory.resolve("local").path,
            ),
            createDirectories = false,
        ),
        preferenceNode = "/mihon/test-mode/$identity",
    )
}
