package mihon.desktop.platform

import mihon.desktop.test.TestArguments
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.prefs.Preferences

/** Explicit test-only process configuration. Never migrate or copy the ordinary user's data. */
object DesktopTestProfile {
    var root: File? = null
        private set

    private const val MARKER = ".mihon-test-profile"
    private const val FORMAT = "mihon-desktop-test-profile-v1\n"

    @Synchronized
    fun configure(args: Array<String>) {
        val requested = TestArguments.parse(args).testProfile ?: return
        check(root == null) { "A test profile can only be selected once, at process startup" }
        val path = File(requested).toPath().toAbsolutePath().normalize()
        require(path.parent != null && path != File(System.getProperty("user.home")).canonicalFile.toPath()) {
            "A filesystem root or the ordinary home directory is not a test profile"
        }
        generateSequence(path) { it.parent }.forEach {
            require(!Files.isSymbolicLink(it)) { "Test profile must not traverse symbolic links" }
        }
        val directory = path.toFile().canonicalFile
        val marker = File(directory, MARKER)
        if (directory.exists()) {
            require(directory.isDirectory) { "Test profile must be a directory" }
            if (marker.exists()) {
                require(!Files.isSymbolicLink(marker.toPath()) && marker.readText(Charsets.UTF_8) == FORMAT) {
                    "Unrecognized test profile marker"
                }
            } else {
                require(directory.list()?.isEmpty() == true) { "Refusing to use a nonempty unmarked directory" }
            }
            Files.walk(path).use { entries ->
                require(entries.noneMatch(Files::isSymbolicLink)) { "Test profile contains symbolic links" }
            }
        }
        Files.createDirectories(path)
        if (!marker.exists()) marker.writeText(FORMAT, Charsets.UTF_8)
        val home = File(directory, "home")
        Files.createDirectories(home.toPath())
        root = directory
        System.setProperty("user.home", home.path)
        System.setProperty("java.util.prefs.PreferencesFactory", IsolatedDesktopPreferencesFactory::class.java.name)
        // Preferences caches its factory. A late bootstrap must abort, not silently use the normal backend.
        check(Preferences.userRoot() is IsolatedDesktopPreferences) {
            "Preferences initialized before test profile selection; refusing unsafe test startup"
        }
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
    }

    fun credentialService(service: String): String {
        val directory = root ?: return service
        val id = MessageDigest.getInstance("SHA-256").digest(directory.path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$service.test-$id"
    }
}
