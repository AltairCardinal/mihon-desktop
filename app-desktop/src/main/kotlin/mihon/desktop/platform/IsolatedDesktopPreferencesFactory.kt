package mihon.desktop.platform

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** Portable test-only backend: no registry, native JDK preferences library, or ordinary preference roots. */
class IsolatedDesktopPreferencesFactory : PreferencesFactory {
    private val directory = File(checkNotNull(DesktopTestProfile.root), "preferences")
    private val user = IsolatedDesktopPreferences(null, "", File(directory, "user"))
    private val system = IsolatedDesktopPreferences(null, "", File(directory, "system"))

    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system
}

internal class IsolatedDesktopPreferences(
    parent: AbstractPreferences?,
    name: String,
    private val directory: File,
) : AbstractPreferences(parent, name) {
    private val valuesFile = File(directory, "values.properties")
    private val nameFile = File(directory, "node-name")

    init {
        newNode = !directory.exists()
        Files.createDirectories(directory.toPath())
        if (name.isNotEmpty()) {
            if (nameFile.exists()) {
                check(nameFile.readText(Charsets.UTF_8) == name) { "Preference node identity mismatch" }
            } else {
                nameFile.writeText(name, Charsets.UTF_8)
            }
        }
    }

    // AbstractPreferences serializes SPI calls per node. Writes are durable before returning so
    // direct legacy consumers that do not call flush() still survive a test-process restart.
    override fun putSpi(key: String, value: String) = update { setProperty(key, value) }
    override fun getSpi(key: String): String? = read().getProperty(key)
    override fun removeSpi(key: String) = update { remove(key) }
    override fun keysSpi(): Array<String> = read().stringPropertyNames().toTypedArray()

    override fun childrenNamesSpi(): Array<String> = Files.list(directory.toPath()).use { paths ->
        paths.filter { Files.isDirectory(it) }
            .map { File(it.toFile(), "node-name").readText(Charsets.UTF_8) }
            .toList().toTypedArray()
    }

    override fun childSpi(name: String): AbstractPreferences = IsolatedDesktopPreferences(
        this,
        name,
        // Lowercase hex avoids case-folding collisions. Hashing also keeps an 80-character Unicode
        // Preferences name below filesystem component length limits; node-name preserves enumeration.
        File(
            directory,
            MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        ),
    )

    override fun removeNodeSpi() {
        Files.deleteIfExists(valuesFile.toPath())
        Files.deleteIfExists(nameFile.toPath())
        Files.deleteIfExists(directory.toPath())
    }

    override fun syncSpi() = Unit // Each operation reads the backing file; no cached values to synchronize.
    override fun flushSpi() = Unit // Each write is persisted atomically.

    private fun read(): Properties = Properties().apply {
        if (valuesFile.exists()) valuesFile.reader(Charsets.UTF_8).use(::load)
    }

    private fun update(change: Properties.() -> Unit) {
        val values = read().apply(change)
        val temporary = Files.createTempFile(directory.toPath(), "values-", ".tmp")
        try {
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { values.store(it, null) }
            try {
                Files.move(
                    temporary,
                    valuesFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, valuesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
