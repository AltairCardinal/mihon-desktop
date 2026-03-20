package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * Loads manga source extensions from JAR files in the extensions directory.
 *
 * Each JAR should contain Source implementations discoverable via ServiceLoader
 * (META-INF/services/eu.kanade.tachiyomi.source.Source).
 */
open class DesktopExtensionLoader(
    val extensionsDirectory: File = File(System.getProperty("user.home"), ".mihon/extensions"),
) {

    /**
     * Loads all Source implementations from JAR files in the extensions directory.
     */
    open fun loadExtensions(): List<LoadedExtension> {
        if (!extensionsDirectory.exists() || !extensionsDirectory.isDirectory) {
            return emptyList()
        }

        val jarFiles = extensionsDirectory.listFiles { file ->
            file.isFile && file.extension.equals("jar", ignoreCase = true)
        } ?: return emptyList()

        return jarFiles.flatMap { jarFile ->
            loadFromJar(jarFile)
        }
    }

    /**
     * Loads Source implementations from a single JAR file.
     */
    private fun loadFromJar(jarFile: File): List<LoadedExtension> {
        return try {
            val classLoader = URLClassLoader(
                arrayOf(jarFile.toURI().toURL()),
                this::class.java.classLoader,
            )

            val sources = ServiceLoader.load(Source::class.java, classLoader).toList()

            sources.map { source ->
                LoadedExtension(
                    source = source,
                    jarFile = jarFile,
                    classLoader = classLoader,
                )
            }
        } catch (e: Exception) {
            // Log and skip broken extensions
            System.err.println("Failed to load extension from ${jarFile.name}: ${e.message}")
            emptyList()
        }
    }
}

/**
 * Represents a loaded extension source with metadata about its origin.
 */
data class LoadedExtension(
    val source: Source,
    val jarFile: File,
    val classLoader: ClassLoader,
)
