package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtensionManagerTest {

    @Test
    fun `concurrent receiver mutations preserve every extension snapshot`() = runBlocking {
        val workerCount = 32
        val extensionCount = 128
        val gate = CyclicBarrier(workerCount)
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { isInstallTransactionActive(any()) } answers {
                gate.await(5, TimeUnit.SECONDS)
                false
            }
        }
        lateinit var receiver: ExtensionInstallReceiver.Listener
        val manager = manager(
            initial = emptyList(),
            installer = installer,
            receiver = { receiver = it },
        )
        manager.isInitialized.await { it }
        val installed = (1..extensionCount).map { index ->
            installed().copy(
                name = "Example $index",
                pkgName = "$PACKAGE.$index",
                hasUpdate = true,
            )
        }

        runConcurrently(workerCount, installed.map { extension ->
            { receiver.onExtensionInstalled(extension) }
        })

        assertEquals(installed.map { it.pkgName }.toSet(), manager.installedExtensionsFlow.value.map { it.pkgName }.toSet())

        val untrusted = installed.map { extension ->
            Extension.Untrusted(
                name = extension.name,
                pkgName = extension.pkgName,
                versionName = extension.versionName,
                versionCode = extension.versionCode,
                libVersion = extension.libVersion,
                signatureHash = "signature",
            )
        }
        runConcurrently(workerCount, untrusted.map { extension ->
            { receiver.onExtensionUntrusted(extension) }
        })

        assertTrue(manager.installedExtensionsFlow.value.isEmpty())
        assertEquals(untrusted.map { it.pkgName }.toSet(), manager.untrustedExtensionsFlow.value.map { it.pkgName }.toSet())
    }

    @Test
    fun `fixed main routes package actions and waits for uninstall receiver`() = runBlocking {
        val installed = installed()
        val available = available()
        val installer = mockk<ExtensionInstaller> {
            every { downloadAndInstall(any(), available) } returns flowOf(InstallStep.Pending)
            every { cancelInstall(any()) } returns Unit
            every { uninstallApk(any()) } returns Unit
            every { isInstallTransactionActive(any()) } returns false
        }
        lateinit var receiver: ExtensionInstallReceiver.Listener
        val manager = manager(
            initial = listOf(
                LoadResult.Success(installed),
                LoadResult.Success(installed.copy(pkgName = "pending.extension", hasUpdate = true)),
                LoadResult.Untrusted(Extension.Untrusted("Untrusted", PACKAGE, "1.0", 1, 1.4, "signature")),
            ),
            available = listOf(available),
            installer = installer,
            receiver = { receiver = it },
        )
        manager.isInitialized.await { it }
        manager.installedExtensionsFlow.await { it.isNotEmpty() }
        manager.untrustedExtensionsFlow.await { it.isNotEmpty() }

        manager.findAvailableExtensions()
        assertTrue(
            manager.installedExtensionsFlow
                .await { items -> items.any { it.pkgName == PACKAGE && it.hasUpdate } }
                .isNotEmpty(),
        )
        assertEquals(7L, manager.getSourceData(7)?.id)
        assertEquals(InstallStep.Pending, manager.installExtension(available).first())
        assertEquals(InstallStep.Pending, manager.updateExtension(installed).first())
        assertTrue(manager.updateExtension(installed.copy(pkgName = "missing")).toList().isEmpty())
        manager.cancelInstallUpdateExtension(installed)
        manager.uninstallExtension(installed)
        assertTrue(manager.installedExtensionsFlow.value.any { it.pkgName == installed.pkgName })
        verify(exactly = 2) { installer.downloadAndInstall(any(), available) }
        verify { installer.cancelInstall(installed.pkgName) }
        verify { installer.uninstallApk(installed.pkgName) }

        mockkObject(ExtensionLoader)
        try {
            every { ExtensionLoader.uninstallPrivateExtension(any(), installed.pkgName) } returns Unit
            receiver.onPackageUninstalled(installed.pkgName)
            verify { ExtensionLoader.uninstallPrivateExtension(any(), installed.pkgName) }
            val remaining = manager.installedExtensionsFlow.await {
                it.none { extension -> extension.pkgName == installed.pkgName }
            }
            assertTrue(remaining.any { it.pkgName == "pending.extension" })
            manager.untrustedExtensionsFlow.await { it.none { extension -> extension.pkgName == installed.pkgName } }
        } finally {
            unmockkObject(ExtensionLoader)
        }
        Unit
    }

    @Test
    fun `trust persists before reloading through the manager adapter`() = runBlocking {
        val calls = mutableListOf<String>()
        val untrusted = Extension.Untrusted("Example", PACKAGE, "1.0", 1, 1.4, "signature")
        lateinit var manager: ExtensionManager
        val trust = mockk<TrustExtension> {
            every { trust(PACKAGE, 1, "signature") } answers {
                calls += "trust"
            }
        }
        manager = manager(
            initial = listOf(LoadResult.Untrusted(untrusted)),
            trust = trust,
            loader = { _, _ ->
                manager.untrustedExtensionsFlow.await { it.none { extension -> extension.pkgName == PACKAGE } }
                calls += "reload"
                LoadResult.Success(installed())
            },
        )
        manager.isInitialized.await { it }
        manager.untrustedExtensionsFlow.await { it.isNotEmpty() }
        mockkObject(ExtensionLoader)
        try {
            coEvery { ExtensionLoader.loadExtensionFromPkgName(any(), PACKAGE) } returns LoadResult.Error
            manager.trust(untrusted)
        } finally {
            unmockkObject(ExtensionLoader)
        }

        assertEquals(listOf("trust", "reload"), calls)
        assertTrue(manager.installedExtensionsFlow.await { it.isNotEmpty() }.any { it.pkgName == PACKAGE })
        assertTrue(manager.untrustedExtensionsFlow.await { it.isEmpty() }.isEmpty())
    }

    @Test
    fun `failed trust persistence keeps the untrusted extension`() = runBlocking {
        val untrusted = Extension.Untrusted("Example", PACKAGE, "1.0", 1, 1.4, "signature")
        val manager = manager(
            initial = listOf(LoadResult.Untrusted(untrusted)),
            trust = mockk {
                every { trust(PACKAGE, 1, "signature") } throws IllegalStateException("persistence failed")
            },
            loader = { _, _ -> error("loader must not run") },
        )
        manager.isInitialized.await { it }
        manager.untrustedExtensionsFlow.await { it.isNotEmpty() }
        val disappearance = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            manager.untrustedExtensionsFlow.first { it.none { extension -> extension.pkgName == PACKAGE } }
        }
        assertTrue(runCatching { manager.trust(untrusted) }.exceptionOrNull() is IllegalStateException)
        assertTrue(withTimeoutOrNull(100) { disappearance.await() } == null)
        disappearance.cancel()
    }

    @Test
    fun `failed private cleanup keeps installed and untrusted extensions`() = runBlocking {
        val installed = installed()
        lateinit var receiver: ExtensionInstallReceiver.Listener
        val manager = manager(
            initial = listOf(
                LoadResult.Success(installed),
                LoadResult.Untrusted(Extension.Untrusted("Untrusted", PACKAGE, "1.0", 1, 1.4, "signature")),
            ),
            receiver = { receiver = it },
        )
        manager.isInitialized.await { it }
        manager.installedExtensionsFlow.await { it.isNotEmpty() }
        manager.untrustedExtensionsFlow.await { it.isNotEmpty() }
        val disappearance = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            combine(manager.installedExtensionsFlow, manager.untrustedExtensionsFlow) { installed, untrusted ->
                installed.none { it.pkgName == PACKAGE } || untrusted.none { it.pkgName == PACKAGE }
            }.first { it }
        }
        mockkObject(ExtensionLoader)
        try {
            every { ExtensionLoader.uninstallPrivateExtension(any(), PACKAGE) } throws
                IllegalStateException("cleanup failed")
            val failure = runCatching { receiver.onPackageUninstalled(PACKAGE) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(withTimeoutOrNull(100) { disappearance.await() } == null)
            disappearance.cancel()
        } finally {
            unmockkObject(ExtensionLoader)
        }
        Unit
    }

    private fun manager(
        initial: List<LoadResult>,
        available: List<Extension.Available> = emptyList(),
        installer: ExtensionInstaller = mockk(relaxed = true),
        trust: TrustExtension = mockk(relaxed = true),
        loader: suspend (Context, String) -> LoadResult = { _, _ -> LoadResult.Error },
        receiver: (ExtensionInstallReceiver.Listener) -> Unit = {},
    ) = ExtensionManager(
        context = mockk(relaxed = true),
        preferences = preferences(),
        trustExtension = trust,
        installedExtensionsLoader = { initial },
        extensionLoader = loader,
        availableExtensionsProvider = { available },
        installerFactory = { installer },
        installReceiverRegistrar = receiver,
    )

    private fun preferences() = mockk<SourcePreferences>(relaxed = true) {
        every { enabledLanguages() } returns mockk<Preference<Set<String>>> {
            every { isSet() } returns true
        }
    }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    private fun runConcurrently(workerCount: Int, actions: List<() -> Unit>) {
        val executor = Executors.newFixedThreadPool(workerCount)
        val start = CountDownLatch(1)
        try {
            val futures = actions.map { action ->
                executor.submit {
                    start.await()
                    action()
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun installed() = Extension.Installed(
        "Example", PACKAGE, "1.0", 1, 1.4, "en", false, null, emptyList(), null, isShared = false,
    )

    private fun available() = Extension.Available(
        "Example", PACKAGE, "2.0", 2, 1.4, "en", false,
        listOf(Extension.Available.Source(7, "en", "Source", "https://example.org")),
        "example.apk", "https://example.org/icon.png", "https://example.org",
    )

    private companion object {
        const val PACKAGE = "org.example.extension"
    }
}
