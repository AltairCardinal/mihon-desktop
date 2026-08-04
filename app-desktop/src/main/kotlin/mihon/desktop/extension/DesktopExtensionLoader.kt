package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile

/**
 * Reads the version code from the meta sidecar for a given JAR, or 0 if missing/unreadable.
 * Exposed for testing.
 */
fun DesktopExtensionLoader.readMetaVersionCode(jarFile: java.io.File): Long =
    readExtensionMeta(jarFile)?.versionCode ?: 0L

/**
 * Loads manga source extensions from JAR files in the extensions directory.
 *
 * Each JAR should contain Source implementations discoverable via ServiceLoader
 * (META-INF/services/eu.kanade.tachiyomi.source.Source).
 */
open class DesktopExtensionLoader(
    val extensionsDirectory: File = File(System.getProperty("user.home"), ".mihon/extensions"),
    private val networkContext: DesktopExtensionNetworkContext = DesktopExtensionNetworkContext(),
) {
    private val mutableDiagnostics = mutableListOf<ExtensionLoadDiagnostic>()
    val diagnostics: List<ExtensionLoadDiagnostic> get() = mutableDiagnostics.toList()

    /**
     * Loads all Source implementations from JAR files in the extensions directory.
     */
    open fun loadExtensions(): List<LoadedExtension> {
        mutableDiagnostics.clear()
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

    /** Loads one installed package without disturbing the manager's current runtime snapshot. */
    open fun loadPackage(packageName: String): List<LoadedExtension> {
        val jarFile = extensionArtifactFile(extensionsDirectory, packageName, "jar")
        return if (jarFile.isFile) loadFromJar(jarFile) else emptyList()
    }

    /**
     * Loads Source implementations from a single JAR file.
     *
     * Discovery strategy:
     * 1. ServiceLoader via META-INF/services (JVM-compiled desktop extensions).
     * 2. JAR class scan fallback (APK→JAR converted extensions that have no
     *    META-INF/services because Android uses PackageManager, not ServiceLoader).
     */
    /** Loads extensions from a single JAR; exposed internally for testing. */
    internal fun loadFromSingleJar(jarFile: File): List<LoadedExtension> = loadFromJar(jarFile)

    private fun loadFromJar(jarFile: File): List<LoadedExtension> {
        var classLoader: ExtensionClassLoader? = null
        return try {
            classLoader = ExtensionClassLoader(
                jarFile.toURI().toURL(),
                this::class.java.classLoader,
            )
            networkContext.register(classLoader, jarFile.nameWithoutExtension)

            // 1. Try ServiceLoader first (fast path for JVM-compiled extensions).
            var sources = ServiceLoader.load(Source::class.java, classLoader).toList()

            // 2. Manifest class name (fast path for dex2jar-converted APK extensions).
            //    The exact class was extracted from AndroidManifest.xml at install time
            //    and stored in the .meta.json sidecar — no need to scan all classes.
            if (sources.isEmpty()) {
                val meta = readExtensionMeta(jarFile)
                if (meta?.extensionClass != null) {
                    sources = loadByClassName(meta.extensionClass, classLoader)
                }
            }

            // 3. Fallback: scan all class entries for concrete Source implementations.
            //    Needed for dex2jar-converted APKs without a meta sidecar (e.g. manually placed).
            if (sources.isEmpty()) {
                sources = scanJarForSources(jarFile, classLoader)
            }

            if (sources.isEmpty()) {
                classLoader.close()
                return emptyList()
            }

            // A JAR scan can discover both a SourceFactory and one of its concrete products.
            // Source IDs are the runtime identity, so publish only the first discovered
            // provider from this installed artifact.
            sources.distinctBy(Source::id).map { source ->
                LoadedExtension(
                    source = source,
                    jarFile = jarFile,
                    classLoader = classLoader,
                )
            }
        } catch (e: Throwable) {
            classLoader?.close()
            // ServiceConfigurationError (extends Error) is thrown when a ServiceLoader provider
            // cannot be instantiated — must catch Throwable, not just Exception.
            recordDiagnostic(jarFile, e)
            emptyList()
        }
    }

    private fun recordDiagnostic(jarFile: File, error: Throwable) {
        mutableDiagnostics += ExtensionLoadDiagnostic(
            jarName = jarFile.name,
            category = ExtensionFailureCategory.from(error),
            errorType = error.javaClass.name,
            message = error.message ?: error.javaClass.simpleName,
        )
        System.err.println("Failed to load extension from ${jarFile.name}: ${error.message}")
    }

    /**
     * Loads Source instances by a colon-separated list of fully-qualified class names,
     * as stored in [ExtensionMeta.extensionClass] from the APK manifest.
     *
     * Each name in [classNames] (split on ':') is loaded and instantiated.
     * Classes that implement [Source] are instantiated directly. Classes that implement
     * [SourceFactory] contribute their complete [SourceFactory.createSources] result.
     * Invalid entries are ignored without preventing later manifest classes from loading.
     */
    internal fun loadByClassName(classNames: String, classLoader: ClassLoader): List<Source> {
        val sourceInterface = Source::class.java
        val sourceFactoryInterface = SourceFactory::class.java
        return classNames.split(":").flatMap { name ->
            val trimmed = name.trim().ifEmpty { return@flatMap emptyList() }
            try {
                val cls = classLoader.loadClass(trimmed)
                if (!sourceInterface.isAssignableFrom(cls) && !sourceFactoryInterface.isAssignableFrom(cls)) {
                    return@flatMap emptyList()
                }
                val ctor = cls.getDeclaredConstructors().firstOrNull { it.parameterCount == 0 }
                    ?: return@flatMap emptyList()
                ctor.isAccessible = true
                when (val instance = ctor.newInstance()) {
                    is Source -> listOf(instance)
                    is SourceFactory -> instance.createSources()
                    else -> emptyList()
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /**
     * Scans all top-level class entries in [jarFile] and returns instances from
     * concrete [Source] and [SourceFactory] implementations.
     *
     * Inner/anonymous classes (names containing '$') are skipped to avoid
     * spurious instantiation attempts on compiler-generated artefacts.
     */
    internal fun scanJarForSources(jarFile: File, classLoader: ClassLoader): List<Source> {
        val sourceInterface = Source::class.java
        val sourceFactoryInterface = SourceFactory::class.java
        val result = mutableListOf<Source>()
        try {
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                    .forEach { entry ->
                        val className = entry.name.removeSuffix(".class").replace('/', '.')
                        try {
                            val cls = classLoader.loadClass(className)
                            if (
                                (sourceInterface.isAssignableFrom(cls) ||
                                    sourceFactoryInterface.isAssignableFrom(cls)) &&
                                !cls.isInterface &&
                                !Modifier.isAbstract(cls.modifiers)
                            ) {
                                val ctor = cls.getDeclaredConstructors()
                                    .firstOrNull { it.parameterCount == 0 }
                                    ?: return@forEach
                                ctor.isAccessible = true
                                when (val instance = ctor.newInstance()) {
                                    is Source -> result.add(instance)
                                    is SourceFactory -> result.addAll(instance.createSources())
                                }
                            }
                        } catch (_: Throwable) {
                            // Skip classes that cannot be loaded or instantiated
                        }
                    }
            }
        } catch (e: Throwable) {
            recordDiagnostic(jarFile, e)
        }
        return result
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

data class ExtensionLoadDiagnostic(
    val jarName: String,
    val category: ExtensionFailureCategory,
    val errorType: String,
    val message: String,
)

/**
 * Child-first classloader for extensions.
 *
 * Problem: The app's classpath contains two competing HttpSource implementations:
 *   - source-api's HttpSource: compiled with the real Kohesive Injekt (injectLazy uses InjektScope)
 *   - desktop-api's HttpSource: compiled with our custom Injekt stub (has NetworkHelper factory)
 *
 * With standard parent-first delegation, source-api's version wins and injectLazy fails
 * because NetworkHelper is not registered in the real Kohesive InjektScope.
 *
 * Solution: use child-first loading for implementation classes so the extension JAR's bundled
 * desktop-api (HttpSource, Injekt, NetworkHelper) takes precedence over source-api.
 *
 * Classes that MUST come from the parent (type safety for ServiceLoader and data exchange):
 *   - eu.kanade.tachiyomi.source.Source and sub-interfaces
 *   - eu.kanade.tachiyomi.source.model.* (SManga, SChapter, Page, FilterList, ...)
 *   - Standard JVM libraries
 */
internal class ExtensionClassLoader(
    jarUrl: URL,
    parent: ClassLoader,
) : URLClassLoader(arrayOf(jarUrl), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }

            // These must always load from parent to preserve type compatibility
            // with the app's Source/SManga/SChapter instances.
            if (mustLoadFromParent(name)) {
                return super.loadClass(name, resolve)
            }

            // For everything else try the extension JAR first (child-first),
            // falling back to parent if not found in JAR.
            val c = try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                return super.loadClass(name, resolve)
            }
            if (resolve) resolveClass(c)
            return c
        }
    }

    private fun mustLoadFromParent(name: String): Boolean {
        // Source interface hierarchy — must match the app's class objects for
        // ServiceLoader and extension manager type checks.
        if (name == "eu.kanade.tachiyomi.source.Source" ||
            name == "eu.kanade.tachiyomi.source.CatalogueSource" ||
            name == "eu.kanade.tachiyomi.source.SourceFactory" ||
            name == "eu.kanade.tachiyomi.source.UnmeteredSource" ||
            name.startsWith("eu.kanade.tachiyomi.source.model.")
        ) return true

        // Android compat stubs — must match the types used by parent-loaded source.model classes.
        // Page.<init> accepts android.net.Uri; if that came from child the constructor lookup fails.
        if (name.startsWith("android.") ||
            name.startsWith("androidx.")
        ) return true

        // Standard JVM / third-party shared libraries
        if (name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("kotlin.") ||
            // kotlinx.coroutines must be parent-loaded: suspend functions compiled to
            // kotlin.coroutines.Continuation (always parent), and coroutine context
            // dispatch must be shared with the app.
            // kotlinx.serialization is intentionally NOT here — extensions bundle their
            // own copy and the generated @Serializable companion ($serializer) must share
            // the same KSerializer interface as the serialization runtime it calls into.
            name.startsWith("kotlinx.coroutines.") ||
            name.startsWith("com.squareup.okhttp") ||
            name.startsWith("okhttp3.") ||
            name.startsWith("rx.") ||             // RxJava 1.x (bundled in fat JARs via desktop-api)
            name.startsWith("io.reactivex.") ||
            name.startsWith("org.jsoup.") ||
            name.startsWith("logcat.") ||
            name.startsWith("tachiyomi.") ||
            name.startsWith("mihon.") ||
            name.startsWith("dev.mihon.")
        ) return true

        return false
    }
}
