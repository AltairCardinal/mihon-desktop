package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.error.AppError
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionPresence
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.atomic.AtomicReference

class AndroidExtensionMetadataProjectionTest {
    @Test
    fun `source preference emissions reuse prepared details metadata and keep the original Source`() = runTest {
        environment {
            val disabled = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
            val preferences = mockk<SourcePreferences> {
                every { disabledSources() } returns mockk {
                    every { changes() } returns disabled
                }
            }
            val source = UrlSource(71L, failing = false)
            val rows = mutableListOf<List<eu.kanade.domain.extension.interactor.ExtensionSourceItem>>()
            val subscription = backgroundScope.launch {
                eu.kanade.domain.extension.interactor.GetExtensionSources(preferences)
                    .subscribe(extension("pkg.details", source)).collect { rows += it }
            }
            try {
                runCurrent()
                val reads = source.nameReads
                assertEquals(1, reads)
                source.failName = true
                disabled.value = setOf("71")
                runCurrent()
                assertEquals(2, rows.size)
                assertSame(source, rows.last().single().source)
                assertEquals("Projection fixture", rows.last().single().presentation.name)
                assertEquals(false, rows.last().single().enabled)
                assertEquals(reads, source.nameReads)
                assertEquals(0, source.reads, "Opening source rows must not probe an unused baseUrl getter")
            } finally {
                subscription.cancelAndJoin()
            }
        }
    }

    @Test
    fun `real details source metadata failure reaches installed owner feedback instead of a silent empty list`() =
        runTest {
            kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
            val host = eu.kanade.tachiyomi.test.ScreenModelTestHost()
            try {
                environment {
                    val preferences = SourcePreferences(InMemoryPreferenceStore())
                    val source = UrlSource(61L, failing = false)
                    val owner = extension(
                        "pkg.details",
                        source,
                    ).copy(sources = listOf(source, UrlSource(62L, failing = false)))
                    val manager = ExtensionManager(
                        context = mockk<Context>(relaxed = true),
                        preferences = preferences,
                        trustExtension = mockk(relaxed = true),
                        installedExtensionsLoader = { listOf(LoadResult.Success(owner)) },
                        installerFactory = {
                            mockk(relaxed = true)
                        },
                        installReceiverRegistrar = {},
                        scope = backgroundScope,
                        inventoryProvider = {
                            ExtensionInventory(
                                initialized = true,
                                packages = mapOf(owner.pkgName to ExtensionPresence.PRESENT),
                            )
                        },
                    )
                    runCurrent()
                    source.failName = true
                    val before = source.nameReads
                    host.create {
                        eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreenModel(
                            owner.pkgName,
                            mockk(relaxed = true),
                            mockk(relaxed = true),
                            manager,
                            eu.kanade.domain.extension.interactor.GetExtensionSources(preferences),
                            mockk(relaxed = true),
                            mockk(relaxed = true),
                            preferences,
                        )
                    }
                    runCurrent()
                    val failure = checkNotNull(manager.loadFailures.value[owner.pkgName])
                    assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.error)
                    assertEquals(owner.versionCode, failure.versionCode)
                    assertEquals(ExtensionPresence.LOAD_FAILED, manager.inventory.value.packages[owner.pkgName])
                    assertTrue(manager.installedExtensionsFlow.value.isEmpty())
                    runCurrent()
                    assertEquals(before + 1, source.nameReads)
                }
            } finally {
                host.close()
                kotlinx.coroutines.Dispatchers.resetMain()
            }
        }

    @Test
    fun `real projection turns baseUrl linkage into one visible owned failure and keeps healthy source`() = runTest {
        environment {
            val preferences = SourcePreferences(InMemoryPreferenceStore())
            preferences.enabledLanguages().set(setOf("en"))
            val broken = UrlSource(31L, failing = true)
            val healthy = UrlSource(32L, failing = false)
            val installed = listOf(extension("pkg.broken", broken), extension("pkg.healthy", healthy))
            val manager = ExtensionManager(
                context = mockk<Context>(relaxed = true),
                preferences = preferences,
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { installed.map { LoadResult.Success(it) } },
                installerFactory = { mockk(relaxed = true) },
                installReceiverRegistrar = {},
                scope = backgroundScope,
                inventoryProvider = {
                    ExtensionInventory(
                        initialized = true,
                        packages = installed.associate { it.pkgName to ExtensionPresence.PRESENT },
                    )
                },
            )
            runCurrent()
            assertEquals(0, broken.reads)
            val outputs = mutableListOf<eu.kanade.domain.extension.model.Extensions>()
            val escaped = AtomicReference<Throwable?>()
            val subscription = backgroundScope.launch {
                try {
                    GetExtensionsByType(preferences, manager).subscribe().collect { outputs += it }
                } catch (
                    error: Throwable,
                ) {
                    escaped.set(error)
                }
            }
            try {
                runCurrent()
                assertNull(escaped.get())
                val failure = checkNotNull(manager.loadFailures.value["pkg.broken"])
                assertInstanceOf(AppError.ExtensionIncompatible::class.java, failure.error)
                assertEquals(2L, failure.versionCode)
                assertEquals("1.6.2", failure.versionName)
                assertEquals(listOf("pkg.healthy"), outputs.last().installed.map { it.pkgName })
                assertSame(healthy, outputs.last().installed.single().sources.single())
                runCurrent()
                assertEquals(1, broken.reads, "The failed package must not re-enter its own getter")
                assertEquals(ExtensionPresence.LOAD_FAILED, manager.inventory.value.packages["pkg.broken"])
            } finally {
                subscription.cancelAndJoin()
            }
        }
    }

    @Test
    fun `late baseUrl failure from old same version instance cannot poison replacement`() = runTest {
        environment {
            val preferences = SourcePreferences(InMemoryPreferenceStore())
            preferences.enabledLanguages().set(setOf("en"))
            val broken = UrlSource(41L, failing = true)
            val replacement = UrlSource(41L, failing = false)
            val oldOwner = extension("pkg.replaced", broken)
            val nextOwner = extension("pkg.replaced", replacement)
            lateinit var receiver: ExtensionInstallReceiver.Listener
            val manager = ExtensionManager(
                context = mockk<Context>(relaxed = true),
                preferences = preferences,
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { listOf(LoadResult.Success(oldOwner)) },
                installerFactory = {
                    mockk(relaxed = true) { every { isInstallTransactionActive(any()) } returns false }
                },
                installReceiverRegistrar = { receiver = it },
                scope = backgroundScope,
                inventoryProvider = {
                    ExtensionInventory(
                        initialized = true,
                        packages = mapOf("pkg.replaced" to ExtensionPresence.PRESENT),
                    )
                },
            )
            runCurrent()
            broken.onRead = {
                receiver.onExtensionLoadStarted("pkg.replaced")
                receiver.onExtensionUpdated(nextOwner)
            }
            val outputs = mutableListOf<eu.kanade.domain.extension.model.Extensions>()
            val escaped = AtomicReference<Throwable?>()
            val subscription = backgroundScope.launch {
                try {
                    GetExtensionsByType(preferences, manager).subscribe().collect { outputs += it }
                } catch (
                    error: Throwable,
                ) {
                    escaped.set(error)
                }
            }
            try {
                runCurrent()
                assertNull(escaped.get())
                assertTrue(manager.loadFailures.value.isEmpty())
                assertSame(replacement, outputs.last().installed.single().sources.single())
                assertSame(nextOwner, manager.installedExtensionsFlow.value.single())
                assertEquals(1, broken.reads)
            } finally {
                subscription.cancelAndJoin()
            }
        }
    }

    private class UrlSource(override val id: Long, private val failing: Boolean) : HttpSource() {
        var failName = false
        var nameReads = 0
        override val name: String get() {
            nameReads++
            if (failName) throw NoSuchMethodError("future details name dependency")
            return "Projection fixture"
        }
        override val lang = "en"
        override val supportsLatest = false
        var reads = 0
        var onRead: (() -> Unit)? = null
        override val baseUrl: String get() {
            check(++reads <= 32) { "Unbounded projection re-entry" }
            onRead?.also { onRead = null }?.invoke()
            if (failing) throw NoSuchMethodError("future baseUrl dependency")
            return "https://fixture.invalid"
        }
        override fun chapterPageParse(response: Response) = SChapter.create()
    }

    private fun extension(pkg: String, source: HttpSource) = Extension.Installed(
        "Known installed name", pkg, "1.6.2", 2, 1.6, "en", false, null, listOf(source), null, isShared = true,
    )

    private suspend fun environment(block: suspend () -> Unit) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(SecurityPreferences(InMemoryPreferenceStore()))
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns mockk(relaxed = true)
        try {
            block()
        } finally {
            unmockkStatic(NotificationManagerCompat::class)
            Injekt = previous
        }
    }
}
