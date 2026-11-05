package mihon.desktop.extension

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import mihon.domain.error.AppError
import tachiyomi.domain.source.service.toSourceAppError
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
    internal fun loadFromSingleJar(jarFile: File): List<LoadedExtension> = loadFromJar(jarFile)

    private fun loadFromJar(jarFile: File): List<LoadedExtension> {
        var classLoader: ExtensionClassLoader? = null
        mutableDiagnostics.removeAll { it.jarName == jarFile.name }
        val metadata = readExtensionMeta(jarFile)
        return try {
            classLoader = ExtensionClassLoader(
                jarFile.toURI().toURL(),
                this::class.java.classLoader,
            )
            networkContext.register(classLoader, jarFile.nameWithoutExtension)

            // 1. Try ServiceLoader first (fast path for JVM-compiled extensions).
            val meta = metadata
            val strict = meta?.strictProvider == true
            var sources = if (strict) {
                loadStrictManifestSources(jarFile, checkNotNull(meta.extensionClass), classLoader)
            } else {
                guardSourceOperation(SourceOperationPhase.CONSTRUCTION) {
                    ServiceLoader.load(Source::class.java, classLoader).toList()
                }
            }

            // 2. Manifest class name (fast path for dex2jar-converted APK extensions).
            //    The exact class was extracted from AndroidManifest.xml at install time
            //    and stored in the .meta.json sidecar — no need to scan all classes.
            if (sources.isEmpty() && !strict) {
                if (meta?.extensionClass != null) {
                    sources = loadByClassName(meta.extensionClass, classLoader)
                }
            }

            // 3. Fallback: scan all class entries for concrete Source implementations.
            //    Needed for dex2jar-converted APKs without a meta sidecar (e.g. manually placed).
            if (sources.isEmpty() && !strict) {
                sources = scanJarForSources(jarFile, classLoader, metadata)
            }

            if (sources.isEmpty()) {
                classLoader.close()
                if (mutableDiagnostics.none { it.jarName == jarFile.name }) {
                    recordDiagnostic(jarFile, IllegalStateException("No loadable Source provider was found"), metadata)
                }
                return emptyList()
            }

            // A JAR scan can discover both a SourceFactory and one of its concrete products.
            // Source IDs are the runtime identity, so publish only the first discovered
            // provider from this installed artifact.
            val metadataValidated = guardSourceOperation(SourceOperationPhase.METADATA) {
                sources.forEach { source ->
                    source.id
                    source.name
                    source.lang
                }
                sources.distinctBy(Source::id)
            }
            metadataValidated.map { source ->
                LoadedExtension(
                    source = source,
                    jarFile = jarFile,
                    classLoader = classLoader,
                    metadata = metadata,
                )
            }
        } catch (e: Throwable) {
            closeFailedExtensionLoader(classLoader, e)
            rethrowLoaderControlFailure(e)
            // ServiceConfigurationError (extends Error) is thrown when a ServiceLoader provider
            // cannot be instantiated — must catch Throwable, not just Exception.
            recordDiagnostic(jarFile, e, metadata)
            emptyList()
        }
    }

    private fun loadStrictManifestSources(jarFile: File, classes: String, loader: ClassLoader): List<Source> =
        JarFile(jarFile).use { jar ->
            classes.split(':').flatMap { name ->
                require(jar.getJarEntry(name.replace('.', '/') + ".class") != null) {
                    "Missing manifest provider: $name"
                }
                val type = guardSourceOperation(SourceOperationPhase.CONSTRUCTION) { loader.loadClass(name) }
                require(type.classLoader === loader) { "Manifest provider must belong to the extension" }
                val provider = guardSourceOperation(SourceOperationPhase.CONSTRUCTION) {
                    type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                }
                val sources = when (provider) {
                    is Source -> listOf(provider)
                    is SourceFactory -> guardSourceOperation(SourceOperationPhase.FACTORY) {
                        provider.createSources().toList()
                    }
                    else -> error("Manifest entry is not a Source or SourceFactory")
                }
                require(sources.isNotEmpty()) { "Manifest provider has no sources" }
                require(sources.all { it.javaClass.classLoader === loader }) { "Source must belong to the extension" }
                sources
            }
        }

    private fun recordDiagnostic(jarFile: File, error: Throwable, metadata: ExtensionMeta?) {
        mutableDiagnostics += ExtensionLoadDiagnostic(
            jarName = jarFile.name,
            category = ExtensionFailureCategory.from(error),
            errorType = error.javaClass.name,
            message = error.message ?: error.javaClass.simpleName,
            cause = error,
            error = if (error is SourceCompatibilityException) error.toSourceAppError() else null,
            metadata = metadata,
            phase = (error as? SourceCompatibilityException)?.phase,
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
                val cls = guardSourceOperation(SourceOperationPhase.CONSTRUCTION) { classLoader.loadClass(trimmed) }
                if (!sourceInterface.isAssignableFrom(cls) && !sourceFactoryInterface.isAssignableFrom(cls)) {
                    return@flatMap emptyList()
                }
                val instance = guardSourceOperation(SourceOperationPhase.CONSTRUCTION) {
                    val ctor = cls.getDeclaredConstructors().firstOrNull { it.parameterCount == 0 }
                        ?: return@flatMap emptyList()
                    ctor.isAccessible = true
                    ctor.newInstance()
                }
                when (instance) {
                    is Source -> listOf(instance)
                    is SourceFactory -> guardSourceOperation(SourceOperationPhase.FACTORY) {
                        instance.createSources().toList()
                    }
                    else -> emptyList()
                }
            } catch (error: Throwable) {
                error.rethrowSourceControlFailure()
                if (error is SourceCompatibilityException) throw error
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
    internal fun scanJarForSources(
        jarFile: File,
        classLoader: ClassLoader,
        metadata: ExtensionMeta? = readExtensionMeta(jarFile),
    ): List<Source> {
        val sourceInterface = Source::class.java
        val sourceFactoryInterface = SourceFactory::class.java
        val result = mutableListOf<Source>()
        try {
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                    .forEach { entry ->
                        val className = entry.name.removeSuffix(".class").replace('/', '.')
                        var provider = false
                        try {
                            val cls = classLoader.loadClass(className)
                            if (
                                (
                                    sourceInterface.isAssignableFrom(cls) ||
                                        sourceFactoryInterface.isAssignableFrom(cls)
                                    ) &&
                                !cls.isInterface &&
                                !Modifier.isAbstract(cls.modifiers)
                            ) {
                                provider = true
                                val instance = guardSourceOperation(SourceOperationPhase.CONSTRUCTION) {
                                    val ctor = cls.getDeclaredConstructors().firstOrNull { it.parameterCount == 0 }
                                        ?: return@forEach
                                    ctor.isAccessible = true
                                    ctor.newInstance()
                                }
                                when (instance) {
                                    is Source -> result.add(instance)
                                    is SourceFactory -> result.addAll(
                                        guardSourceOperation(SourceOperationPhase.FACTORY) {
                                            instance.createSources().toList()
                                        },
                                    )
                                }
                            }
                        } catch (error: Throwable) {
                            if (provider) {
                                error.rethrowSourceControlFailure()
                                if (error is SourceCompatibilityException) throw error
                            } else {
                                val inspection = error.inspectSourceFailure()
                                inspection.fatal?.let { throw it }
                                if (!inspection.complete) throw error
                                inspection.cancellation?.let { throw it }
                                if (error is Error && error !is LinkageError) throw error
                            }
                            // Unknown optional helpers may depend on platform classes absent on Desktop.
                        }
                    }
            }
        } catch (e: Throwable) {
            e.rethrowSourceControlFailure()
            if (e is SourceCompatibilityException) throw e
            recordDiagnostic(jarFile, e, metadata)
            return emptyList()
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
    val metadata: ExtensionMeta? = null,
)

data class ExtensionLoadDiagnostic(
    val jarName: String,
    val category: ExtensionFailureCategory,
    val errorType: String,
    val message: String,
    val cause: Throwable? = null,
    val error: AppError? = null,
    val metadata: ExtensionMeta? = null,
    val phase: SourceOperationPhase? = null,
)

/** ServiceLoader's own invalid-provider configuration is a diagnostic, not an unrelated host Error. */
private fun rethrowLoaderControlFailure(error: Throwable) {
    if (error !is java.util.ServiceConfigurationError) {
        error.rethrowSourceControlFailure()
        return
    }
    val inspection = error.inspectSourceFailure()
    inspection.fatal?.let { throw it }
    if (!inspection.complete) throw error
    inspection.cancellation?.let { throw it }
    inspection.causes.drop(1).firstOrNull {
        it is Error && it !is java.util.ServiceConfigurationError
    }?.let { throw it }
}

internal fun closeFailedExtensionLoader(loader: AutoCloseable?, error: Throwable) {
    try {
        loader?.close()
    } catch (cleanup: Throwable) {
        val primary = error.inspectSourceFailure()
        val closing = cleanup.inspectSourceFailure()
        primary.fatal?.let { throw it }
        closing.fatal?.let { throw it }
        if (!primary.complete) throw error
        if (!closing.complete) throw cleanup
        primary.cancellation?.let { throw it }
        closing.cancellation?.let { throw it }
        rethrowLoaderControlFailure(error)
        cleanup.rethrowSourceControlFailure()
        if (cleanup !is java.io.IOException) throw cleanup
        if (cleanup !== error) error.addSuppressed(cleanup)
    }
}

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
        // Error mapping must see the host exception identity even when a fat JAR bundles the API.
        if (name == "eu.kanade.tachiyomi.network.HttpException") return true

        // Source interface hierarchy — must match the app's class objects for
        // ServiceLoader and extension manager type checks.
        if (name == "eu.kanade.tachiyomi.source.Source" ||
            name == "eu.kanade.tachiyomi.source.CatalogueSource" ||
            name == "eu.kanade.tachiyomi.source.SourceFactory" ||
            name == "eu.kanade.tachiyomi.source.UnmeteredSource" ||
            name.startsWith("eu.kanade.tachiyomi.source.model.")
        ) {
            return true
        }

        // Android compat stubs — must match the types used by parent-loaded source.model classes.
        // Page.<init> accepts android.net.Uri; if that came from child the constructor lookup fails.
        if (name.startsWith("android.") ||
            name.startsWith("androidx.")
        ) {
            return true
        }

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
            name.startsWith("rx.") || // RxJava 1.x (bundled in fat JARs via desktop-api)
            name.startsWith("io.reactivex.") ||
            name.startsWith("org.jsoup.") ||
            name.startsWith("logcat.") ||
            name.startsWith("tachiyomi.") ||
            name.startsWith("mihon.") ||
            name.startsWith("dev.mihon.")
        ) {
            return true
        }

        return false
    }
}
