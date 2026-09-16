package eu.kanade.tachiyomi.extension

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
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
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.error.AppError
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExtensionManagerTest {

    @Test
    fun `revocation during shared scan cannot erase previously loaded sources`() = runTest {
        var permission = eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus.GRANTED
        val controller = eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionController({ permission })
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val existing = installed().copy(isShared = true)
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences(),
            trustExtension = mockk(relaxed = true),
            installedAppsPermissionController = controller,
            installedExtensionsLoader = {
                calls++
                when (calls) {
                    1 -> listOf(LoadResult.Success(existing))
                    3 -> {
                        gate.await()
                        emptyList()
                    }
                    else -> emptyList()
                }
            },
            installReceiverRegistrar = {},
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals(listOf(existing), manager.installedExtensionsFlow.value)
        permission = eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus.DENIED
        controller.refresh()
        permission = eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus.GRANTED
        val rescan = async { controller.refresh() }
        runCurrent()
        permission = eu.kanade.tachiyomi.extension.permission.InstalledAppsPermissionStatus.DENIED
        val revocation = async { controller.refresh() }
        runCurrent()
        gate.complete(Unit)
        rescan.await()
        revocation.await()
        assertEquals(listOf(existing), manager.installedExtensionsFlow.value)
        assertEquals(permission, controller.state.value.status)
    }

    @Test
    fun `refresh uses successful owner only and clears removed source metadata`() = runTest {
        withNotificationEnvironment {
            val owner = RepositoryIdentity("https://example.org", "Owner", "key")
            val other = RepositoryIdentity("https://other.example", "Other", "other-key")
            val candidate = available()
            var discovery = eu.kanade.tachiyomi.extension.api.ExtensionDiscoveryResult(
                listOf(candidate),
                emptyList(),
                listOf(owner),
            )
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { listOf(LoadResult.Success(installed().copy(repoUrl = owner.baseUrl))) },
                installReceiverRegistrar = {},
                scope = backgroundScope,
                discoveryProvider = { discovery },
            )
            runCurrent()
            manager.findAvailableExtensions()
            assertEquals(7L, manager.getSourceData(7)?.id)
            val failure = RepositoryCatalogFailure(owner, AppError.Network())
            discovery = discovery.copy(failures = listOf(failure), repositories = listOf(owner, other))
            manager.findAvailableExtensions()
            assertEquals(listOf(failure), manager.repositoryFailures.value)
            assertFalse(manager.installedExtensionsFlow.value.single().isObsolete)

            discovery = discovery.copy(
                extensions = listOf(candidate.copy(repoUrl = other.baseUrl)),
                failures = emptyList(),
            )
            manager.findAvailableExtensions()
            assertTrue(manager.installedExtensionsFlow.value.single().isObsolete)
            assertEquals(owner.baseUrl, manager.installedExtensionsFlow.value.single().repoUrl)

            discovery = discovery.copy(extensions = emptyList())
            manager.findAvailableExtensions()
            assertEquals(null, manager.getSourceData(7))
            discovery = discovery.copy(extensions = listOf(candidate))
            manager.findAvailableExtensions()
            assertFalse(manager.installedExtensionsFlow.value.single().isObsolete)
        }
    }

    @Test
    fun `refresh cancellation propagates without changing published state`() = runTest {
        val cancellation = java.util.concurrent.CancellationException("cancel refresh")
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences(),
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = { emptyList() },
            installReceiverRegistrar = {},
            scope = backgroundScope,
            availableExtensionsProvider = { throw cancellation },
        )
        val thrown = runCatching { manager.findAvailableExtensions() }.exceptionOrNull()
        assertTrue(thrown === cancellation)
        assertTrue(manager.availableExtensionsFlow.value.isEmpty())
    }

    @Test
    fun `unsupported catalog candidate stays visible but cannot install or update`() = runTest {
        withNotificationEnvironment {
            val installer = mockk<ExtensionInstaller>(relaxed = true)
            every { installer.downloadAndInstall(any(), any()) } returns flowOf(InstallStep.Installed)
            val unsupported = available().copy(libVersion = ExtensionLoader.LIB_VERSION_MAX + 0.1, versionCode = 999)
            val manager = manager(listOf(LoadResult.Success(installed())), listOf(unsupported), installer = installer)
            manager.isInitialized.await { it }
            manager.findAvailableExtensions()

            assertEquals(listOf(unsupported), manager.availableExtensionsFlow.value)
            assertEquals(listOf(InstallStep.Error), manager.installExtension(unsupported).toList())
            assertEquals(listOf(InstallStep.Error), manager.updateExtension(installed()).toList())
            assertFalse(manager.installedExtensionsFlow.value.single().hasUpdate)
            verify(exactly = 0) { installer.downloadAndInstall(any(), any()) }
        }
    }

    @Test
    fun `runtime reload before initial publication is replayed over the loader snapshot`() = runTest {
        val snapshotFixed = CompletableDeferred<Unit>()
        val allowSnapshotPublication = CompletableDeferred<Unit>()
        val runtimeReloader = CompletableDeferred<suspend (String) -> Unit>()
        val pkgName = "$PACKAGE.runtime-reload"
        val initialUntrusted = Extension.Untrusted(
            name = "Initial untrusted",
            pkgName = pkgName,
            versionName = "1.0",
            versionCode = 1,
            libVersion = 1.4,
            signatureHash = "old-signature",
        )
        val reloaded = installed().copy(
            pkgName = pkgName,
            versionName = "2.0",
            versionCode = 2,
            hasUpdate = true,
        )
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences(),
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = {
                snapshotFixed.complete(Unit)
                allowSnapshotPublication.await()
                listOf(LoadResult.Untrusted(initialUntrusted))
            },
            extensionLoader = { _, requestedPackage ->
                assertEquals(pkgName, requestedPackage)
                LoadResult.Success(reloaded)
            },
            installerFactory = { reload ->
                runtimeReloader.complete(reload)
                mockk(relaxed = true)
            },
            installReceiverRegistrar = {},
            scope = backgroundScope,
        )

        manager.cancelInstallUpdateExtension(reloaded)
        runCurrent()
        assertTrue(snapshotFixed.isCompleted)

        runtimeReloader.await()(pkgName)
        assertFalse(manager.isInitialized.value)

        allowSnapshotPublication.complete(Unit)
        runCurrent()

        assertTrue(manager.isInitialized.value)
        assertEquals(listOf(reloaded), manager.installedExtensionsFlow.value)
        assertTrue(manager.untrustedExtensionsFlow.value.isEmpty())
    }

    @Test
    fun `receiver events after loader snapshot are replayed before initialization is published`() = runTest {
        val snapshotFixed = CompletableDeferred<Unit>()
        val allowSnapshotPublication = CompletableDeferred<Unit>()
        val receiverRegistered = CompletableDeferred<ExtensionInstallReceiver.Listener>()
        val initial = installed().copy(pkgName = "$PACKAGE.initial", versionCode = 1)
        val sentinel = installed().copy(pkgName = "$PACKAGE.sentinel", hasUpdate = true)
        val installedAfterSnapshot = installed().copy(pkgName = "$PACKAGE.installed-after-snapshot")
        val updatedInitial = initial.copy(versionName = "2.0", versionCode = 2)
        val untrustedAfterSnapshot = Extension.Untrusted(
            name = installedAfterSnapshot.name,
            pkgName = installedAfterSnapshot.pkgName,
            versionName = installedAfterSnapshot.versionName,
            versionCode = installedAfterSnapshot.versionCode,
            libVersion = installedAfterSnapshot.libVersion,
            signatureHash = "signature",
        )
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences(),
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = {
                snapshotFixed.complete(Unit)
                allowSnapshotPublication.await()
                listOf(LoadResult.Success(initial), LoadResult.Success(sentinel))
            },
            installerFactory = { mockk(relaxed = true) },
            installReceiverRegistrar = { receiverRegistered.complete(it) },
            scope = backgroundScope,
        )

        assertTrue(receiverRegistered.isCompleted, "package receiver must be registered during construction")
        runCurrent()
        assertTrue(snapshotFixed.isCompleted)
        val receiver = receiverRegistered.await()
        mockkObject(ExtensionLoader)
        try {
            every { ExtensionLoader.uninstallPrivateExtension(any(), initial.pkgName) } returns Unit
            receiver.onExtensionInstalled(installedAfterSnapshot)
            receiver.onExtensionUpdated(updatedInitial)
            receiver.onExtensionUntrusted(untrustedAfterSnapshot)
            receiver.onPackageUninstalled(initial.pkgName)
            assertFalse(manager.isInitialized.value)

            allowSnapshotPublication.complete(Unit)
            runCurrent()

            assertTrue(manager.isInitialized.value)
            assertEquals(listOf(sentinel), manager.installedExtensionsFlow.value)
            assertEquals(
                listOf(untrustedAfterSnapshot),
                manager.untrustedExtensionsFlow.value,
            )
            verify(exactly = 1) { ExtensionLoader.uninstallPrivateExtension(any(), initial.pkgName) }
        } finally {
            unmockkObject(ExtensionLoader)
        }
    }

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
        val sentinel = installed().copy(pkgName = "$PACKAGE.sentinel", hasUpdate = true)
        val manager = manager(
            initial = listOf(LoadResult.Success(sentinel)),
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

        runConcurrently(
            workerCount,
            installed.map { extension ->
                { receiver.onExtensionInstalled(extension) }
            },
        )

        assertEquals(
            (installed + sentinel).map { it.pkgName }.toSet(),
            manager.installedExtensionsFlow.value.map { it.pkgName }.toSet(),
        )

        val updated = installed.filterIndexed { index, _ -> index % 3 == 0 }
            .map { it.copy(versionName = "2.0", versionCode = 2) }
        val untrusted = installed.filterIndexed { index, _ -> index % 3 == 1 }.map { extension ->
            Extension.Untrusted(
                name = extension.name,
                pkgName = extension.pkgName,
                versionName = extension.versionName,
                versionCode = extension.versionCode,
                libVersion = extension.libVersion,
                signatureHash = "signature",
            )
        }
        val uninstalled = installed.filterIndexed { index, _ -> index % 3 == 2 }
        val updatedByPackage = updated.associateBy { it.pkgName }
        val untrustedByPackage = untrusted.associateBy { it.pkgName }
        mockkObject(ExtensionLoader)
        try {
            every { ExtensionLoader.uninstallPrivateExtension(any(), any()) } returns Unit
            runConcurrently(
                workerCount,
                installed.map { extension ->
                    {
                        when (extension.pkgName) {
                            in updatedByPackage -> receiver.onExtensionUpdated(
                                updatedByPackage.getValue(extension.pkgName),
                            )
                            in untrustedByPackage -> receiver.onExtensionUntrusted(
                                untrustedByPackage.getValue(extension.pkgName),
                            )
                            else -> receiver.onPackageUninstalled(extension.pkgName)
                        }
                    }
                },
            )
        } finally {
            unmockkObject(ExtensionLoader)
        }

        val remainingByPackage = manager.installedExtensionsFlow.value.associateBy { it.pkgName }
        assertEquals(updatedByPackage.keys + sentinel.pkgName, remainingByPackage.keys)
        updatedByPackage.forEach { (pkgName, extension) ->
            assertEquals(extension.versionCode, remainingByPackage.getValue(pkgName).versionCode)
        }
        assertEquals(
            untrusted.map { it.pkgName }.toSet(),
            manager.untrustedExtensionsFlow.value.map { it.pkgName }.toSet(),
        )
        assertTrue(uninstalled.none { it.pkgName in remainingByPackage })
        assertTrue(
            uninstalled.none { extension ->
                manager.untrustedExtensionsFlow.value.any { it.pkgName == extension.pkgName }
            },
        )
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

    @Test
    fun `partial catalog failure preserves the failed repository extension and exposes feedback`() = runTest {
        val failedRepoUrl = "https://failed.example"
        val failed = installed().copy(
            pkgName = "failed.extension",
            repoUrl = failedRepoUrl,
            isObsolete = false,
        )
        val healthy = installed().copy(repoUrl = "https://example.org")
        val failure = RepositoryCatalogFailure(
            repository = RepositoryIdentity(failedRepoUrl, "Failed", "failed-key"),
            error = AppError.Network(),
        )
        val manager = manager(
            initial = listOf(LoadResult.Success(failed), LoadResult.Success(healthy)),
            available = listOf(available()),
            failures = listOf(failure),
        )
        manager.isInitialized.await { it }

        manager.findAvailableExtensions()

        assertEquals(listOf(failure), manager.repositoryFailures.value)
        assertFalse(manager.installedExtensionsFlow.value.single { it.pkgName == failed.pkgName }.isObsolete)
        assertFalse(manager.installedExtensionsFlow.value.single { it.pkgName == healthy.pkgName }.isObsolete)
    }

    @Test
    fun `successful rediscovery clears obsolete status for a recovered extension`() = runTest {
        val stale = installed().copy(repoUrl = "https://example.org", isObsolete = true)
        val manager = manager(
            initial = listOf(LoadResult.Success(stale)),
            available = listOf(available()),
        )
        manager.isInitialized.await { it }

        manager.findAvailableExtensions()

        assertFalse(manager.installedExtensionsFlow.value.single { it.pkgName == stale.pkgName }.isObsolete)
    }

    private fun manager(
        initial: List<LoadResult>,
        available: List<Extension.Available> = emptyList(),
        failures: List<RepositoryCatalogFailure> = emptyList(),
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
        catalogFailuresProvider = { failures },
        installerFactory = { installer },
        installReceiverRegistrar = receiver,
    )

    private fun preferences() = mockk<SourcePreferences>(relaxed = true) {
        every { enabledLanguages() } returns mockk<Preference<Set<String>>> {
            every { isSet() } returns true
        }
    }

    private suspend fun withNotificationEnvironment(block: suspend () -> Unit) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(SecurityPreferences(InMemoryPreferenceStore()))
        val notifications = mockk<NotificationManagerCompat>(relaxed = true)
        mockkStatic(NotificationManagerCompat::class)
        try {
            every { NotificationManagerCompat.from(any()) } returns notifications
            block()
            verify(atLeast = 1) {
                notifications.cancel(eu.kanade.tachiyomi.data.notification.Notifications.ID_UPDATES_TO_EXTS)
            }
        } finally {
            unmockkStatic(NotificationManagerCompat::class)
            Injekt = previous
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
