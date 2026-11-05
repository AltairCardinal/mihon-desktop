package mihon.desktop.extension

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.Source
import fixture.extension.CompatibilityConstructedSource
import fixture.extension.CompatibilityLoadControl
import fixture.extension.CompatibilityLoadingFactory
import fixture.extension.CompatibilityMetadataSource
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class DesktopExtensionLoadCompatibilityTest {
    @TempDir lateinit var directory: File

    @Test
    fun `empty or helper-only artifact has an ordinary package failure visible in manager`() {
        for (helper in listOf(false, true)) {
            val jar = File(directory, if (helper) "fixture.helper.jar" else "fixture.empty.jar")
            JarOutputStream(jar.outputStream()).use { output ->
                if (helper) {
                    val bytes = org.objectweb.asm.ClassWriter(0).apply {
                        visit(
                            org.objectweb.asm.Opcodes.V1_8,
                            org.objectweb.asm.Opcodes.ACC_PUBLIC,
                            "optional/Helper",
                            null,
                            "ec/absent/OptionalSuperclass",
                            null,
                        )
                        visitEnd()
                    }.toByteArray()
                    output.putNextEntry(JarEntry("optional/Helper.class"))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        DesktopExtensionManager(DesktopExtensionLoader(directory)).use { manager ->
            manager.loadAll()
            assertTrue(manager.getInstalledSources().isEmpty())
            assertEquals(
                setOf("fixture.empty.jar", "fixture.helper.jar"),
                manager.loadFailures.value.map {
                    it.jarName
                }.toSet(),
            )
            assertTrue(manager.loadFailures.value.all { it.metadata == null && it.error == null })
        }
    }

    @Test
    fun `background fatal reaches its local handler even without an initialization awaiter`() =
        kotlinx.coroutines.runBlocking {
            for (expected in listOf<Throwable>(
                object : VirtualMachineError("controlled no-awaiter fatal") {},
                AssertionError("host init"),
            )) {
                val observed = kotlinx.coroutines.CompletableDeferred<Throwable>()
                val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                    observed.complete(error)
                    Unit
                }
                val scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + handler,
                )
                val loader = object : DesktopExtensionLoader(directory) {
                    override fun loadExtensions(): List<LoadedExtension> = throw expected
                }
                try {
                    DesktopExtensionManager(loader, initializationScope = scope).use { manager ->
                        manager.loadAllAsync()
                        assertSame(expected, kotlinx.coroutines.withTimeout(5_000) { observed.await() })
                        org.junit.jupiter.api.Assertions.assertFalse(manager.isInitialized.value)
                    }
                } finally {
                    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                }
            }
        }

    @Test
    fun `failed loader cleanup suppresses ordinary IO but propagates control and host Error`() {
        val original = SourceCompatibilityException(NoSuchMethodError("future constructor"))
        val io = java.io.IOException("close failed")
        closeFailedExtensionLoader(AutoCloseable { throw io }, original)
        assertSame(io, original.suppressed.single())
        val fatal = object : VirtualMachineError("controlled cleanup failure") {}
        for (expected in listOf<Throwable>(
            fatal,
            CancellationException("close cancelled"),
            AssertionError("host close bug"),
        )) {
            val error = assertThrows(Throwable::class.java) {
                closeFailedExtensionLoader(
                    AutoCloseable {
                        throw java.io.IOException("wrapped close", expected)
                    },
                    original,
                )
            }
            assertSame(expected, error)
        }
    }

    @Test
    fun `loader cleanup cannot hide the primary fatal behind a closing cancellation or host Error`() {
        val fatal = object : VirtualMachineError("controlled primary loader fatal") {}
        for (cleanup in listOf<Throwable>(CancellationException("close cancelled"), AssertionError("close host bug"))) {
            val actual = assertThrows(Throwable::class.java) {
                closeFailedExtensionLoader(AutoCloseable { throw cleanup }, fatal)
                throw fatal
            }
            assertSame(fatal, actual)
        }
        val cancellation = CancellationException("primary load cancellation")
        val cleanupFatal = object : VirtualMachineError("controlled closing fatal") {}
        assertSame(
            cleanupFatal,
            assertThrows(Throwable::class.java) {
                closeFailedExtensionLoader(AutoCloseable { throw cleanupFatal }, cancellation)
                throw cancellation
            },
        )
    }

    @Test
    fun `background initialization propagates control and host Error without becoming ready`() {
        val fatal = object : VirtualMachineError("controlled background fatal") {}
        for (expected in listOf<Throwable>(
            CancellationException("cancel background"),
            fatal,
            AssertionError("host bug"),
        )) {
            val loader = object : DesktopExtensionLoader(directory) {
                override fun loadExtensions(): List<LoadedExtension> = throw expected
            }
            val localHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> Unit }
            val scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + localHandler,
            )
            try {
                DesktopExtensionManager(loader, initializationScope = scope).use { manager ->
                    manager.loadAllAsync()
                    val error = assertThrows(Throwable::class.java) {
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeout(5_000) { manager.awaitInitialized() }
                        }
                    }
                    assertEquals(expected.javaClass, error.javaClass)
                    assertTrue(error.inspectSourceFailure().causes.any { it === expected })
                    org.junit.jupiter.api.Assertions.assertFalse(manager.isInitialized.value)
                }
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    }

    @Test
    fun `load failure metadata belongs to the started artifact when sidecar is replaced during construction`() =
        metadataDuringConstruction(failing = true)

    @Test
    fun `loaded source metadata belongs to the started artifact when sidecar is replaced during construction`() =
        metadataDuringConstruction(failing = false)

    private fun metadataDuringConstruction(failing: Boolean) {
        val jar = fixture("strict", "construction")
        val original = checkNotNull(readExtensionMeta(jar))
        CompatibilityLoadControl.site = if (failing) "construction" else "healthy"
        CompatibilityLoadControl.failure = NoSuchMethodError("old artifact constructor")
        CompatibilityLoadControl.onCheck = { site ->
            if (site ==
                "construction"
            ) {
                writeExtensionMeta(jar, original.copy(versionCode = 999, versionName = "1.6.999"))
            }
        }
        try {
            val loader = DesktopExtensionLoader(directory)
            val loaded = loader.loadFromSingleJar(jar)
            try {
                assertEquals(999L, readExtensionMeta(jar)?.versionCode)
                assertEquals(original, if (failing) loader.diagnostics.single().metadata else loaded.single().metadata)
            } finally {
                loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
            }
        } finally {
            CompatibilityLoadControl.site = "healthy"
            CompatibilityLoadControl.onCheck = null
        }
    }

    @Test
    fun `manager keeps failed installed artifact visible while healthy source searches and reload recovers`() =
        kotlinx.coroutines.runBlocking {
            val broken = fixture("strict", "name")
            fixture("service", "construction")
            CompatibilityLoadControl.site = "name"
            CompatibilityLoadControl.failure = NoSuchFieldError("future name field")
            DesktopExtensionManager(DesktopExtensionLoader(directory)).use { manager ->
                try {
                    manager.loadAll()
                    assertEquals(listOf(510L), manager.getInstalledSources().map { it.id })
                    val recorded = manager.loadFailures.value.single()
                    assertEquals(broken.name, recorded.jarName)
                    assertEquals(broken.nameWithoutExtension, recorded.metadata?.pkgName)
                    assertEquals(162L, recorded.metadata?.versionCode)
                    assertEquals(
                        mihon.domain.extension.suggestion.ExtensionPresence.LOAD_FAILED,
                        manager.inventory.value.packages[broken.nameWithoutExtension],
                    )
                    assertEquals(
                        "Healthy search",
                        manager.getInstalledSources().single()
                            .getSearchManga(
                                1,
                                "healthy",
                                eu.kanade.tachiyomi.source.model.FilterList(),
                            ).mangas.single().title,
                    )
                    CompatibilityLoadControl.site = "healthy"
                    manager.reloadAll()
                    assertTrue(manager.loadFailures.value.isEmpty())
                    assertEquals(setOf(510L, 511L), manager.getInstalledSources().map { it.id }.toSet())
                } finally {
                    CompatibilityLoadControl.site = "healthy"
                }
            }
        }

    @Test
    fun `diagnostic classification bounds identity cycles and preserves unknown deep fatal tails`() {
        val cycle = object : RuntimeException("controlled cause cycle") {
            var reads = 0
            override val cause: Throwable get() {
                check(++reads <= 128) { "Unbounded diagnostic cause traversal" }
                return this
            }
        }
        try {
            CompatibilityLoadControl.site = "construction"
            CompatibilityLoadControl.failure = cycle
            val loader = DesktopExtensionLoader(directory)
            assertTrue(loader.loadFromSingleJar(fixture("service", "construction")).isEmpty())
            assertEquals(ExtensionFailureCategory.LOAD_ERROR, loader.diagnostics.single().category)
        } finally {
            CompatibilityLoadControl.site = "healthy"
        }
        val fatal = object : VirtualMachineError("controlled classification fatal") {}
        assertSame(
            fatal,
            assertThrows(VirtualMachineError::class.java) {
                ExtensionFailureCategory.from(java.io.IOException("wrapper", fatal))
            },
        )
        var deep: Throwable = fatal
        repeat(65) { deep = RuntimeException("unknown depth", deep) }
        val original = deep
        assertSame(original, assertThrows(RuntimeException::class.java) { ExtensionFailureCategory.from(original) })
    }

    @Test
    fun `all discovery strategies atomically isolate constructor factory and metadata linkage`() {
        val outcomes = linkedMapOf<String, String>()
        try {
            for (mode in listOf("service", "strict", "manifest", "scan")) {
                for (site in listOf("construction", "factory", "id", "name", "lang")) {
                    if (mode == "service" && site == "factory") continue
                    CompatibilityLoadControl.site = site
                    CompatibilityLoadControl.failure = NoSuchMethodError("future $site")
                    CompatibilityLoadControl.entered.clear()
                    val jar = fixture(mode, site)
                    val loader = DesktopExtensionLoader(directory)
                    val loaded = loader.loadFromSingleJar(jar)
                    outcomes["$mode/$site"] = "${loaded.size}:${loader.diagnostics.singleOrNull()?.errorType}"
                    loader.diagnostics.singleOrNull()?.takeIf {
                        it.error is mihon.domain.error.AppError.ExtensionIncompatible
                    }?.let {
                        assertEquals(jar.nameWithoutExtension, it.metadata?.pkgName)
                        assertEquals(162L, it.metadata?.versionCode)
                        assertEquals("1.6.2", it.metadata?.versionName)
                        assertTrue(
                            checkNotNull(it.cause).inspectSourceFailure().causes.any { cause ->
                                cause === CompatibilityLoadControl.failure
                            },
                        )
                        assertEquals(
                            when (site) {
                                "construction" -> SourceOperationPhase.CONSTRUCTION
                                "factory" -> SourceOperationPhase.FACTORY
                                else -> SourceOperationPhase.METADATA
                            },
                            it.phase,
                        )
                    }
                    loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
                }
            }
            assertEquals(
                outcomes.keys.associateWith { "0:${SourceCompatibilityException::class.java.name}" },
                outcomes,
            )
        } finally {
            CompatibilityLoadControl.site = "healthy"
        }
    }

    @Test
    fun `legacy scan skips optional helper with absent superclass and keeps healthy source search`() =
        kotlinx.coroutines.runBlocking {
            CompatibilityLoadControl.site = "healthy"
            val loader = DesktopExtensionLoader(directory)
            val loaded = loader.loadFromSingleJar(fixture("scan", "healthy"))
            try {
                assertEquals(setOf(510L, 511L), loaded.map { it.source.id }.toSet())
                assertTrue(loader.diagnostics.isEmpty())
                val source = loaded.first { it.source.id == 511L }.source
                assertEquals(
                    "Healthy search",
                    source.getSearchManga(
                        1,
                        "healthy",
                        eu.kanade.tachiyomi.source.model.FilterList(),
                    ).mangas.single().title,
                )
            } finally {
                loaded.map { it.classLoader }.distinct().filterIsInstance<AutoCloseable>().forEach { it.close() }
            }
        }

    @Test
    fun `all provider strategies preserve wrapped cancellation fatal and unrelated Error`() {
        val cancelled = CancellationException("controlled loader cancellation")
        val fatal = object : VirtualMachineError("controlled VM failure") {}
        val ordinary = AssertionError("ordinary source bug")
        try {
            for (mode in listOf("service", "strict", "manifest", "scan")) {
                for (failure in listOf<Throwable>(cancelled, fatal, ordinary)) {
                    val site = if (mode == "service") "construction" else "factory"
                    CompatibilityLoadControl.site = site
                    CompatibilityLoadControl.failure = failure
                    val jar = fixture(mode, site)
                    val error = assertThrows(Throwable::class.java) {
                        DesktopExtensionLoader(directory).loadFromSingleJar(jar)
                    }
                    if (failure === ordinary) {
                        assertTrue(error is Error)
                        assertTrue(error.inspectSourceFailure().causes.any { it === failure })
                    } else {
                        assertSame(failure, error)
                    }
                }
            }
        } finally {
            CompatibilityLoadControl.site = "healthy"
        }
    }

    private fun fixture(mode: String, site: String) = buildCompatibilityLoadingJar(directory, mode, site)
}

internal fun buildCompatibilityLoadingJar(directory: File, mode: String, site: String): File {
    val jar = File(directory, "fixture.$mode.$site.jar")
    val provider = when (site) {
        "construction" -> CompatibilityConstructedSource::class.java
        "factory" -> CompatibilityLoadingFactory::class.java
        else -> CompatibilityMetadataSource::class.java
    }
    JarOutputStream(jar.outputStream()).use { output ->
        listOf(
            CompatibilityConstructedSource::class.java,
            CompatibilityLoadingFactory::class.java,
            CompatibilityMetadataSource::class.java,
        ).forEach { type ->
            val path = type.name.replace('.', '/') + ".class"
            output.putNextEntry(JarEntry(path))
            type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(output) }
            output.closeEntry()
        }
        if (mode == "service") {
            output.putNextEntry(JarEntry("META-INF/services/${Source::class.java.name}"))
            // SourceFactory is a manifest/scan provider, not a Source ServiceLoader provider.
            output.write(
                (
                    if (site ==
                        "factory"
                    ) {
                        CompatibilityConstructedSource::class.java.name
                    } else {
                        provider.name
                    }
                    ).toByteArray(),
            )
            output.closeEntry()
        }
        if (mode == "scan") {
            val optionalHelper = org.objectweb.asm.ClassWriter(0).apply {
                visit(
                    org.objectweb.asm.Opcodes.V1_8,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC,
                    "optional/Helper",
                    null,
                    "ec/absent/OptionalSuperclass",
                    null,
                )
                visitEnd()
            }.toByteArray()
            output.putNextEntry(JarEntry("optional/Helper.class"))
            output.write(optionalHelper)
            output.closeEntry()
        }
    }
    writeExtensionMeta(
        jar,
        ExtensionMeta(
            pkgName = jar.nameWithoutExtension,
            versionCode = 162,
            versionName = "1.6.2",
            name = "Fixed fixture",
            extensionClass = if (mode == "strict" || mode == "manifest") provider.name else null,
            strictProvider = mode == "strict",
        ),
    )
    return jar
}
