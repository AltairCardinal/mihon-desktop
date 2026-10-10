package mihon.desktop.platform

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.prefs.Preferences
import tachiyomi.core.common.preference.DesktopPreferenceStore

/** Explicit production recovery instance. Originals and path-bound credentials never move. */
object DesktopRecoveryProfile {
    const val ENVIRONMENT_KEY = "MIHON_RECOVERY_PROFILE"
    private const val MARKER = ".mihon-recovery-profile"
    private const val FORMAT = "mihon-desktop-recovery-profile-v1\n"
    private const val ORIGIN = "original-instance.txt"
    private const val ORIGIN_PROFILE = "original-recovery-profile.txt"
    private var selectedRoot: File? = null
    private var originalSelected = false

    fun configure(args: Array<String>, env: Map<String, String> = System.getenv()) {
        val selected = args.filter { it.startsWith("--recovery-profile=") }
        require(selected.size <= 1) { "Only one recovery instance can be selected" }
        if ("--original-profile" in args) {
            require(selected.isEmpty() && args.none { it.startsWith("--test-profile=") })
            originalSelected = true
            return
        }
        if (selected.isEmpty()) {
            if (args.none { it.startsWith("--test-profile=") }) env[ENVIRONMENT_KEY]?.let {
                selectedRoot = validateRoot(File(it))
            }
            return
        }
        require(args.none { it.startsWith("--test-profile=") })
        check(selectedRoot == null) { "Select the instance before application startup" }
        selectedRoot = validateRoot(File(selected.single().substringAfter('=')))
    }

    fun environment(env: Map<String, String>): Map<String, String> = if (originalSelected) {
        env - ENVIRONMENT_KEY
    } else selectedRoot?.let {
        env + (ENVIRONMENT_KEY to it.path)
    } ?: env

    fun validateRoot(directory: File): File {
        val path = directory.toPath().toAbsolutePath().normalize()
        require(path.parent != null && directory.path.isNotBlank()) { "Invalid recovery directory" }
        generateSequence(path) { it.parent }.forEach {
            require(!Files.isSymbolicLink(it)) { "Recovery directories must not traverse symbolic links" }
        }
        val root = path.toFile().canonicalFile
        require(root.isDirectory && File(root, MARKER).isFile) { "Unrecognized recovery instance" }
        require(!Files.isSymbolicLink(File(root, MARKER).toPath()))
        require(File(root, MARKER).length() == FORMAT.toByteArray(Charsets.UTF_8).size.toLong())
        require(File(root, MARKER).readText(Charsets.UTF_8) == FORMAT) { "Unrecognized recovery instance" }
        require(path == root.toPath()) { "Recovery directory must not redirect to another path" }
        return root
    }

    /** Called only after an explicit user confirmation; a nonempty unmarked destination is rejected. */
    fun create(directory: File, original: DesktopPlatformPaths): File {
        val path = directory.toPath().toAbsolutePath().normalize()
        require(path.parent != null && directory.path.isNotBlank())
        generateSequence(path) { it.parent }.forEach {
            require(!Files.isSymbolicLink(it)) { "Recovery directories must not traverse symbolic links" }
        }
        val root = path.toFile().canonicalFile
        require(original.recoveryProfileRoot?.let { root.toPath().startsWith(it.canonicalFile.toPath()) } != true)
        require(original.defaultDirectories().none { root.toPath().startsWith(it.canonicalFile.toPath()) }) {
            "The new instance must be separate from the original instance"
        }
        require(!root.exists() || (root.isDirectory && root.list()?.isEmpty() == true)) {
            "Choose a new empty recovery directory"
        }
        Files.createDirectories(root.toPath())
        File(root, ORIGIN).writeText(original.configDir.canonicalPath, Charsets.UTF_8)
        File(root, ORIGIN_PROFILE).writeText(original.recoveryProfileRoot?.canonicalPath.orEmpty(), Charsets.UTF_8)
        File(root, MARKER).writeText(FORMAT, Charsets.UTF_8)
        return validateRoot(root)
    }

    fun originalLabel(root: File): String? {
        val original = File(validateRoot(root), ORIGIN)
        if (!original.isFile || Files.isSymbolicLink(original.toPath()) || original.length() > 16_384) return null
        return original.readText(Charsets.UTF_8).takeIf { it.isNotBlank() && '\u0000' !in it }
    }

    fun preferences(root: File): DesktopPreferenceStore {
        val id = MessageDigest.getInstance("SHA-256").digest(validateRoot(root).path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return DesktopPreferenceStore(Preferences.userRoot().node("mihon/recovery/$id"))
    }

    /** Account mapping hook shared by tracking, app lock and synchronization. */
    fun credentialBackend(root: File?, delegate: CredentialBackend): CredentialBackend {
        if (root == null) return delegate
        val id = MessageDigest.getInstance("SHA-256").digest(validateRoot(root).path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val prefix = "recovery-$id:"
        return object : CredentialBackend {
            override fun save(account: String, secret: CharArray) = delegate.save(prefix + account, secret)
            override fun load(account: String): CharArray? = delegate.load(prefix + account)
            override fun delete(account: String) = delegate.delete(prefix + account)
        }
    }

    fun originalArguments(root: File): List<String> {
        val pointer = File(validateRoot(root), ORIGIN_PROFILE)
        require(pointer.isFile && !Files.isSymbolicLink(pointer.toPath()) && pointer.length() <= 16_384)
        val parent = pointer.readText(Charsets.UTF_8)
        return if (parent.isBlank()) listOf("--original-profile") else {
            // The startup boundary validates this explicit return target again. A damaged marker opens safety UI.
            listOf("--recovery-profile=${File(parent).absolutePath}")
        }
    }
}
