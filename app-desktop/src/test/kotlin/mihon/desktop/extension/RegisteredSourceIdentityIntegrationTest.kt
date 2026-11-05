package mihon.desktop.extension

import ec.registration.PoisonableRegisteredSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import mihon.desktop.source.DesktopSourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class RegisteredSourceIdentityIntegrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `real loaded registration survives failed id getter and replacement never rebinds old source`() {
        val jar = File(directory, "registered.jar")
        JarOutputStream(jar.outputStream()).use { output ->
            val type = PoisonableRegisteredSource::class.java
            val path = type.name.replace('.', '/') + ".class"
            output.putNextEntry(JarEntry(path))
            type.classLoader.getResourceAsStream(path)!!.use { it.copyTo(output) }
            output.closeEntry()
            output.putNextEntry(JarEntry("META-INF/services/eu.kanade.tachiyomi.source.Source"))
            output.write(type.name.toByteArray())
            output.closeEntry()
        }
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory))
        val sources = DesktopSourceManager(manager, builtinSources = emptyList())
        try {
            manager.loadAll()
            val first = manager.getInstalledSources().single()
            assertTrue(first.javaClass.classLoader is ExtensionClassLoader)
            val before = reads(first)
            poison(first)
            val observations = mutableListOf<String>()
            observations += "registered:${sources.getRegisteredSourceId(first)}"
            observations += observe("get") { sources.get(7013L) === first }
            observations += observe("manager-get") { manager.getSource(7013L) === first }
            observations += observe("query") { sources.getQuerySources().single() === first }
            observations += "extra:${reads(first) - before}"
            manager.loadAll()
            val second = manager.getInstalledSources().single()
            observations += "old:${sources.getRegisteredSourceId(first)}"
            observations += "new:${sources.getRegisteredSourceId(second)}"
            observations += observe("published") { sources.getQuerySources().single() === second }
            poison(second)
            observations += observe("remove") { manager.removeExtension(manager.getInstalledExtensions().single()) }
            observations += "removed:${sources.getRegisteredSourceId(second)}"
            assertEquals(
                listOf(
                    "registered:7013", "get:true", "manager-get:true", "query:true", "extra:0",
                    "old:null", "new:7013", "published:true", "remove:true", "removed:null",
                ),
                observations,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `builtin and legacy additional registration keeps original lookup without repeated getter probes`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory))
        val builtin = HostSource(1L)
        var additional = HostSource(2L)
        val sources = DesktopSourceManager(
            manager,
            builtinSources = listOf(builtin),
            additionalCatalogueSources = { listOf(additional) },
        )
        try {
            assertSame(additional, sources.get(2L))
            sources.getQuerySources()
            val firstAdditional = additional
            val builtinReads = builtin.reads
            val additionalReads = additional.reads
            builtin.broken = true
            additional.broken = true
            val observations = mutableListOf<String>()
            observations += "builtin:${sources.getRegisteredSourceId(builtin)}"
            observations += "additional:${sources.getRegisteredSourceId(additional)}"
            observations += observe("get-builtin") { sources.get(1L) === builtin }
            observations += observe("get-additional") { sources.get(2L) === additional }
            observations += observe("query") { sources.getQuerySources().size == 2 }
            observations += "extra:${builtin.reads - builtinReads}:${additional.reads - additionalReads}"
            additional = HostSource(3L)
            observations += observe("replacement-query") { sources.getQuerySources().any { it === additional } }
            observations += "old:${sources.getRegisteredSourceId(firstAdditional)}"
            observations += "new:${sources.getRegisteredSourceId(additional)}"
            val unknown = HostSource(99L)
            observations += "unknown:${sources.getRegisteredSourceId(unknown)}:${unknown.reads}"
            assertEquals(
                listOf(
                    "builtin:1", "additional:2", "get-builtin:true", "get-additional:true", "query:true",
                    "extra:0:0", "replacement-query:true", "old:null", "new:3", "unknown:null:0",
                ),
                observations,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `typed host registration sees replacement and clear without executing source id`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory))
        val first = HostSource(41L).apply { broken = true }
        val second = HostSource(41L).apply { broken = true }
        var registrations = listOf(tachiyomi.domain.source.service.SourceRegistration(first, 41L))
        val sources = DesktopSourceManager(
            manager,
            builtinSources = emptyList(),
            additionalSourceRegistrations = { registrations },
        )
        try {
            assertEquals(41L, sources.getRegisteredSourceId(first))
            assertSame(first, sources.get(41L))
            assertSame(first, sources.getQuerySources().single())
            registrations = listOf(tachiyomi.domain.source.service.SourceRegistration(second, 41L))
            assertEquals(null, sources.getRegisteredSourceId(first))
            assertEquals(41L, sources.getRegisteredSourceId(second))
            assertSame(second, sources.get(41L))
            registrations = emptyList()
            assertEquals(null, sources.getRegisteredSourceId(second))
            assertEquals(null, sources.get(41L))
            assertTrue(sources.getQuerySources().isEmpty())
            assertEquals(0, first.reads + second.reads)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `raw registration preserves builtin and first match short circuit while host failures stay host failures`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory))
        val builtin = HostSource(1L)
        val first = HostSource(2L)
        val bad = HostSource(3L).apply { broken = true }
        var providerCalls = 0
        var rawSources = listOf(first, bad)
        val sources = DesktopSourceManager(
            manager,
            builtinSources = listOf(builtin),
            additionalCatalogueSources = {
                providerCalls++
                rawSources
            },
        )
        try {
            assertSame(builtin, sources.get(1L))
            assertEquals(0, providerCalls)
            assertSame(first, sources.get(2L))
            assertEquals(0, bad.reads)
            assertEquals(null, sources.getRegisteredSourceId(bad))
            assertEquals(0, bad.reads)
            val failure = runCatching { sources.get(99L) }.exceptionOrNull()
            assertTrue(failure is NoSuchMethodError)
            rawSources = listOf(bad)
            assertTrue(runCatching { sources.get(99L) }.exceptionOrNull() is NoSuchMethodError)
            assertEquals(
                null,
                sources.getRegisteredSourceId(first),
                "Removed raw source cannot survive a failed refresh",
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `raw aliases reuse established builtin registration without probing its now broken getter`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory))
        val builtin = HostSource(1L)
        val additional = HostSource(2L)
        val sources = DesktopSourceManager(
            manager,
            builtinSources = listOf(builtin),
            additionalCatalogueSources = { listOf(builtin, additional, additional) },
        )
        try {
            val before = builtin.reads
            builtin.broken = true
            val listed = runCatching { sources.getQuerySources() }
            assertEquals(null, listed.exceptionOrNull())
            assertEquals(2, listed.getOrThrow().size)
            assertEquals(0, builtin.reads - before)
            assertEquals(1, additional.reads)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `raw registration query completes from published snapshot while runtime publication lock is held`() {
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory))
        val source = HostSource(9L)
        val entered = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val result = java.util.concurrent.atomic.AtomicReference<List<Source>?>()
        val sources = DesktopSourceManager(
            manager,
            builtinSources = emptyList(),
            additionalCatalogueSources = {
                entered.countDown()
                listOf(source)
            },
        )
        val lock = DesktopExtensionManager::class.java.getDeclaredField("runtimeLock")
            .apply { isAccessible = true }.get(manager)
        val worker = Thread({
            try {
                result.set(sources.getQuerySources())
            } catch (
                error: Throwable,
            ) {
                failure.set(error)
            } finally {
                completed.countDown()
            }
        }, "ec-registration-runtime-lock")
        try {
            synchronized(lock) {
                worker.start()
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(
                    completed.await(2, java.util.concurrent.TimeUnit.SECONDS),
                    "Published-snapshot query must not wait for runtime lock; worker=${worker.state}",
                )
            }
            assertEquals(null, failure.get())
            assertSame(source, result.get()!!.single())
        } finally {
            worker.join(5_000)
            assertTrue(!worker.isAlive, "Owned registration worker must finish after runtime lock release")
            manager.close()
        }
    }

    private fun observe(label: String, action: () -> Boolean): String = try {
        "$label:${action()}"
    } catch (failure: Throwable) {
        "$label:escaped:${failure.javaClass.simpleName}"
    }
    private fun reads(source: Source) = source.javaClass.getField("reads").getInt(source)
    private fun poison(source: Source) {
        source.javaClass.getField("broken").setBoolean(source, true)
    }

    private class HostSource(private val knownId: Long) : CatalogueSource {
        var reads = 0
        var broken = false
        override val id: Long get() {
            reads++
            if (broken) throw NoSuchMethodError("host registered id")
            return knownId
        }
        override val name = "Host fixture"
        override val lang = "en"
        override val supportsLatest = false
        override fun getFilterList() = FilterList()
    }
}
