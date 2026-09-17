package eu.kanade.tachiyomi.extension

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.app.NotificationManagerCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.model.toArtifact
import eu.kanade.tachiyomi.extension.util.AndroidInstallPaused
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mihon.domain.error.AppError
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallInvalidated
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extensionrepo.model.ExtensionRepo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `source icon falls back when an uninstalled package still has a source row`() = missingSourceIcon(null)

    @Test
    fun `source icon falls back when package metadata has no application info`() =
        missingSourceIcon(PackageInfo().apply { applicationInfo = null })

    private fun missingSourceIcon(packageInfo: PackageInfo?) = runTest {
        val source = mockk<eu.kanade.tachiyomi.source.Source> { every { id } returns 77L }
        val extension = installed().copy(sources = listOf(source))
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences(),
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = { listOf(LoadResult.Success(extension)) },
            installerFactory = { mockk(relaxed = true) },
            installReceiverRegistrar = {},
            inventoryProvider = { ExtensionInventory(initialized = true) },
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals(PACKAGE, manager.getExtensionPackage(77L))
        mockkObject(ExtensionLoader)
        try {
            every { ExtensionLoader.getExtensionPackageInfoFromPkgName(any(), PACKAGE) } returns packageInfo
            assertNull(manager.getAppIconForSource(77L))
        } finally {
            unmockkObject(ExtensionLoader)
        }
    }

    @Test
    fun `restored installation window retains package until actual result inventory is published`() = runTest {
        withNotificationEnvironment(expectNotification = false) {
            val refreshAllowed = CompletableDeferred<Unit>()
            var scans = 0
            val installer =
                mockk<ExtensionInstaller>(relaxed = true) { every { pendingSystemPackage(any()) } returns null }
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { emptyList() },
                installerFactory = { installer },
                installReceiverRegistrar = {},

                scope = backgroundScope,
                inventoryProvider = {
                    if (++scans > 1) refreshAllowed.await()
                    ExtensionInventory(initialized = true)
                },
            )
            runCurrent()
            assertTrue(manager.restoreInstallWindow("original-window", PACKAGE))
            assertTrue(manager.restoreInstallWindow("original-window", PACKAGE))
            assertFalse(manager.restoreInstallWindow("original-window", "wrong.package"))
            assertFalse(manager.restoreInstallWindow("second-window", PACKAGE))
            assertTrue(manager.installArbiter.isBusy(PACKAGE))
            manager.completeInstallWindow("foreign-result", InstallStep.Installed)
            assertTrue(manager.installArbiter.isBusy(PACKAGE))
            manager.completeInstallWindow("original-window", InstallStep.Installed)
            runCurrent()
            assertFalse(manager.restoreInstallWindow("original-window", PACKAGE))
            assertTrue(manager.installArbiter.isBusy(PACKAGE))
            refreshAllowed.complete(Unit)
            runCurrent()
            assertFalse(manager.installArbiter.isBusy(PACKAGE))
            val next = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
            manager.completeInstallWindow("original-window", InstallStep.Idle)
            assertTrue(manager.installArbiter.owns(next, next.artifact))
            verify(exactly = 0) { installer.updateInstallStep("original-window", any()) }
            manager.installArbiter.release(next)
        }
    }

    @Test
    fun `same process restored installation reuses its live platform transaction`() = runBlocking {
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { pendingSystemPackage("live-window") } returns PACKAGE
        }
        val manager = manager(initial = emptyList(), installer = installer)
        val lease = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
        assertTrue(manager.restoreInstallWindow("live-window", PACKAGE))
        assertFalse(manager.restoreInstallWindow("live-window", "wrong.package"))
        manager.completeInstallWindow("live-window", InstallStep.Installed)
        verify(exactly = 1) { installer.updateInstallStep("live-window", InstallStep.Installed) }
        assertTrue(manager.installArbiter.owns(lease, lease.artifact))
        assertFalse(manager.restoreInstallWindow("live-window", PACKAGE))
        manager.installArbiter.release(lease)
        Unit
    }

    @Test
    fun `rollback removal restoration and result retain original installation owner`() = runBlocking {
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { hasSystemRemoval("rollback-owned") } returns true
            every { restoreSystemRemoval("rollback-owned", PACKAGE) } returns true
            every { restoreSystemRemoval("rollback-owned", "wrong.package") } returns false
            every { completeSystemRemoval("rollback-owned", android.app.Activity.RESULT_OK) } returns true
        }
        val manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val lease = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
        assertTrue(manager.restoreUninstall("rollback-owned", PACKAGE))
        assertFalse(manager.restoreUninstall("rollback-owned", "wrong.package"))
        manager.completeUninstall("rollback-owned", android.app.Activity.RESULT_OK)
        verify(exactly = 1) { installer.completeSystemRemoval("rollback-owned", android.app.Activity.RESULT_OK) }
        assertTrue(manager.installArbiter.isBusy(PACKAGE))
        assertFalse(manager.restoreUninstall("rollback-owned", PACKAGE))
        manager.installArbiter.release(lease)
        Unit
    }

    @Test
    fun `reserved observed entry preserves system interruption before successful result and cleanup`() = runBlocking {
        val notices = mutableListOf<mihon.domain.extension.suggestion.SuggestionBatchPause>()
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                val finished = arg<() -> Unit>(3)
                kotlinx.coroutines.flow.flow {
                    try {
                        emit(InstallStep.Installed)
                    } finally {
                        finished()
                    }
                }
            }
            every { downloadAndInstallObserved(any(), any(), any(), any(), any()) } answers {
                val finished = arg<() -> Unit>(3)
                val interrupted = arg<(mihon.domain.extension.suggestion.SuggestionBatchPause) -> Unit>(4)
                kotlinx.coroutines.flow.flow {
                    interrupted(mihon.domain.extension.suggestion.SuggestionBatchPause.SERVICE)
                    try {
                        emit(InstallStep.Installed)
                    } finally {
                        finished()
                    }
                }
            }
        }
        val manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val lease = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
        val result = manager.installReservedObserved(lease, { _, _ -> }) {
            assertTrue(manager.installArbiter.isBusy(PACKAGE))
            notices += it
        }
        assertEquals(mihon.domain.extension.suggestion.SuggestionBatchResult.Installed, result)
        assertEquals(listOf(mihon.domain.extension.suggestion.SuggestionBatchPause.SERVICE), notices)
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
    }

    @Test
    fun `reserved guard invalidation remains reconfirmation instead of ordinary install failure`() = runBlocking {
        val reason = mihon.domain.extension.service.ExtensionInstallInvalidation.CATALOG_CHANGED
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { installErrors } returns kotlinx.coroutines.flow.MutableStateFlow(
                mapOf(PACKAGE to AppError.Unknown(ExtensionInstallInvalidated(reason))),
            )
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                val finished = arg<() -> Unit>(3)
                kotlinx.coroutines.flow.flow {
                    try {
                        emit(InstallStep.Error)
                    } finally {
                        finished()
                    }
                }
            }
        }
        val manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val lease = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
        assertEquals(
            mihon.domain.extension.suggestion.SuggestionBatchResult.Invalidated(reason),
            manager.installReserved(lease) { _, _ -> },
        )
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
    }

    @Test
    fun `reserved v2 dual artifact downloads Android apk and retains frozen catalog identity`() = runBlocking {
        val repository = ExtensionRepo("https://repo.example", "Store", null, "", "trusted-fingerprint")
        val json = """
            {"name":"Store","badgeLabel":"Store","signingKey":"trusted-fingerprint",
            "contact":{"website":"https://repo.example"},"extensionList":{"extensions":[{
            "name":"Example","packageName":"$PACKAGE",
            "resources":{"apkUrl":"https://repo.example/example.apk",
            "jarUrl":"https://repo.example/example.jar","iconUrl":"https://repo.example/icon.png"},
            "extensionLib":"1.6","versionCode":160,"versionName":"1.6.1",
            "contentWarning":"CONTENT_WARNING_UNSPECIFIED",
            "sources":[{"id":7,"name":"Source","language":"en","homeUrl":"https://source.example"}]}]}}
        """.trimIndent()
        val artifact = mihon.domain.extensionrepo.service.ExtensionStoreCatalogDecoder.decode(
            json.toByteArray(),
            "https://repo.example/repo.json",
            repository,
        ).entries.single().artifact
        assertEquals("https://repo.example/example.jar", artifact.downloadUrl)
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                val finished = arg<() -> Unit>(3)
                kotlinx.coroutines.flow.flow {
                    try {
                        emit(InstallStep.Installed)
                    } finally {
                        finished()
                    }
                }
            }
        }
        val manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val lease = checkNotNull(manager.installArbiter.reserve(artifact))
        assertEquals(
            mihon.domain.extension.suggestion.SuggestionBatchResult.Installed,
            manager.installReserved(lease) { _, _ -> },
        )
        assertEquals(artifact, lease.artifact)
        verify(exactly = 1) {
            installer.downloadAndInstall(
                "https://repo.example/example.apk",
                match {
                    it.pkgName == artifact.packageName && it.versionCode == artifact.versionCode &&
                        it.repoFingerprint == artifact.repository.signingKeyFingerprint && it.libVersion == 1.6
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `reserved platform permission failure pauses the batch after cleanup`() = runBlocking {
        val reason = mihon.domain.extension.suggestion.SuggestionBatchPause.PERMISSION
        lateinit var manager: ExtensionManager
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { installErrors } returns kotlinx.coroutines.flow.MutableStateFlow(
                mapOf(PACKAGE to AppError.Unknown(AndroidInstallPaused(reason))),
            )
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                val finished = arg<() -> Unit>(3)
                kotlinx.coroutines.flow.flow {
                    try {
                        emit(InstallStep.Error)
                    } finally {
                        finished()
                        assertTrue(
                            runCatching { manager.installExtension(available()) }.exceptionOrNull() is
                                mihon.domain.extension.service.ExtensionInstallBusy,
                            "Batch result must be captured before a new owner can clear its error",
                        )
                    }
                }
            }
        }
        manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val lease = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
        assertEquals(
            mihon.domain.extension.suggestion.SuggestionBatchResult.Paused(reason),
            manager.installReserved(lease) { _, _ -> },
        )
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
    }

    @Test
    fun `reserved completion waits for its inventory publication before advancing batch`() = runTest {
        withNotificationEnvironment(expectNotification = false) {
            val refreshStarted = CompletableDeferred<Unit>()
            val refreshAllowed = CompletableDeferred<Unit>()
            var scans = 0
            val installer = mockk<ExtensionInstaller>(relaxed = true) {
                every { downloadAndInstall(any(), any(), any(), any()) } answers {
                    val finished = arg<() -> Unit>(3)
                    kotlinx.coroutines.flow.flow {
                        try {
                            emit(InstallStep.Installed)
                        } finally {
                            finished()
                        }
                    }
                }
            }
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { emptyList() },
                installerFactory = { installer },
                installReceiverRegistrar = {},
                scope = backgroundScope,
                inventoryProvider = {
                    if (++scans > 1) {
                        refreshStarted.complete(Unit)
                        refreshAllowed.await()
                    }
                    ExtensionInventory(initialized = true)
                },
            )
            runCurrent()
            assertTrue(manager.inventory.value.initialized)
            val lease = checkNotNull(manager.installArbiter.reserve(available().toArtifact()))
            val result = async { manager.installReserved(lease) { _, _ -> } }
            try {
                runCurrent()
                assertTrue(refreshStarted.isCompleted)
                assertFalse(manager.inventory.value.initialized)
                assertFalse(result.isCompleted, "A following batch item must not see our own unfinished inventory scan")
                assertTrue(manager.installArbiter.isBusy(PACKAGE))
            } finally {
                refreshAllowed.complete(Unit)
                runCurrent()
            }
            assertEquals(mihon.domain.extension.suggestion.SuggestionBatchResult.Installed, result.await())
            assertTrue(manager.inventory.value.initialized)
            assertFalse(manager.installArbiter.isBusy(PACKAGE))
        }
    }

    @Test
    fun `reserved batch uses exact artifact and awaits cleanup while ordinary entry remains busy`() = runBlocking {
        val cleanup = CompletableDeferred<Unit>()
        val emitted = CompletableDeferred<Unit>()
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                val guard = arg<(() -> Unit)?>(2)
                val finished = arg<() -> Unit>(3)
                kotlinx.coroutines.flow.flow {
                    guard?.invoke()
                    emitted.complete(Unit)
                    try {
                        emit(InstallStep.Installed)
                    } finally {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            cleanup.await()
                            finished()
                        }
                    }
                }
            }
        }
        val manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val artifact = available().copy(downloadUrl = "https://example.org/frozen.apk", apkName = "frozen.apk")
        val frozen = artifact.toArtifact()
        val lease = checkNotNull(manager.installArbiter.reserve(frozen))
        val progress = mutableListOf<Long>()
        val install = async { manager.installReserved(lease) { id, _ -> progress += id } }
        withTimeout(5_000) {
            kotlinx.coroutines.selects.select<Unit> {
                emitted.onAwait { }
                install.onAwait { error("Reserved install returned before platform completion: $it") }
            }
        }
        assertFalse(install.isCompleted)
        assertTrue(manager.installArbiter.isBusy(PACKAGE))
        assertTrue(
            runCatching { manager.installExtension(artifact) }.exceptionOrNull() is
                mihon.domain.extension.service.ExtensionInstallBusy,
        )
        cleanup.complete(Unit)
        assertEquals(mihon.domain.extension.suggestion.SuggestionBatchResult.Installed, install.await())
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it == lease.transactionId })
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
        verify(exactly = 1) { installer.downloadAndInstall(frozen.downloadUrl, artifact, any(), any()) }
    }

    @Test
    fun `reserved install rejects a foreign owner without consuming a valid owner`() = runBlocking {
        val installer = mockk<ExtensionInstaller>(relaxed = true)
        val manager = manager(initial = emptyList(), installer = installer)
        manager.isInitialized.await { it }
        val artifact = available().toArtifact()
        val valid = checkNotNull(manager.installArbiter.reserve(artifact))
        val foreign = checkNotNull(mihon.domain.extension.service.ExtensionInstallArbiter().reserve(artifact))
        assertEquals(
            mihon.domain.extension.suggestion.SuggestionBatchResult.Busy,
            manager.installReserved(foreign) { _, _ -> },
        )
        assertTrue(manager.installArbiter.owns(valid, artifact))
        verify(exactly = 0) { installer.downloadAndInstall(any(), any(), any(), any()) }
        manager.installArbiter.release(valid)
        Unit
    }

    @Test
    fun `restored uninstall bridge claims original package without dispatching again`() = runBlocking {
        val installer = mockk<ExtensionInstaller>(relaxed = true)
        val manager = manager(initial = listOf(LoadResult.Success(installed())), installer = installer)
        manager.isInitialized.await { it }
        assertTrue(manager.restoreUninstall("restored-system-request", PACKAGE))
        assertTrue(manager.installArbiter.isBusy(PACKAGE))
        assertTrue(manager.restoreUninstall("restored-system-request", PACKAGE))
        assertFalse(manager.restoreUninstall("restored-system-request", "different.package"))
        assertFalse(manager.restoreUninstall("other-request", PACKAGE))
        verify(exactly = 0) { installer.uninstallApk(any(), any()) }
        manager.completeUninstall("restored-system-request")
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
        assertFalse(manager.restoreUninstall("restored-system-request", PACKAGE))
        manager.completeUninstall("completion-before-restore")
        assertFalse(manager.restoreUninstall("completion-before-restore", PACKAGE))
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
    }

    @Test
    fun `system uninstall retains package ownership until matching activity result`() = runBlocking {
        val request = io.mockk.slot<String>()
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { uninstallApk(any(), capture(request)) } returns true
        }
        val manager = manager(initial = listOf(LoadResult.Success(installed())), installer = installer)
        manager.isInitialized.await { it }
        manager.uninstallExtension(installed())
        assertTrue(manager.installArbiter.isBusy(PACKAGE))
        val original = request.captured
        manager.completeUninstall("unrelated")
        assertTrue(manager.installArbiter.isBusy(PACKAGE))
        manager.completeUninstall(original)
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
        manager.uninstallExtension(installed())
        val next = request.captured
        assertTrue(next != original)
        manager.completeUninstall(original)
        assertTrue(manager.installArbiter.isBusy(PACKAGE))
        manager.completeUninstall(next)
        assertFalse(manager.installArbiter.isBusy(PACKAGE))
    }

    @Test
    fun `normal install and update entry cannot replace an already reserved package`() = runBlocking {
        val available = available()
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { downloadAndInstall(any(), any(), any(), any()) } returns flowOf(InstallStep.Pending)
        }
        val manager =
            manager(
                initial = listOf(LoadResult.Success(installed())),
                available = listOf(available),
                installer = installer,
            )
        manager.isInitialized.await { it }
        manager.findAvailableExtensions()
        assertEquals(InstallStep.Pending, manager.installExtension(available).first())
        val second = runCatching { manager.updateExtension(installed()).first() }
        verify(exactly = 1) { installer.downloadAndInstall(any(), any(), any(), any()) }
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("already"))
    }

    @Test
    fun `repository updates suppress a late obsolete catalog and refresh through the existing manager`() = runTest {
        withNotificationEnvironment {
            val old = ExtensionRepo("https://old.example", "Old", null, "", "old")
            val next = old.copy(baseUrl = "https://new.example", signingKeyFingerprint = "new")
            val repositories = MutableSharedFlow<List<ExtensionRepo>>(extraBufferCapacity = 1)
            val response = CompletableDeferred<eu.kanade.tachiyomi.extension.api.ExtensionDiscoveryResult>()
            var calls = 0
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { emptyList() },
                installReceiverRegistrar = {},
                scope = backgroundScope,
                repositoryUpdates = repositories,
                discoveryProvider = {
                    if (++calls == 1) {
                        response.await()
                    } else {
                        eu.kanade.tachiyomi.extension.api.ExtensionDiscoveryResult(
                            listOf(available().copy(repoUrl = next.baseUrl)),
                            emptyList(),
                            listOf(RepositoryIdentity(next.baseUrl, next.name, next.signingKeyFingerprint)),
                        )
                    }
                },
            )
            val seen = mutableListOf<String>()
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                manager.suggestionCatalog.collect { catalog ->
                    seen += catalog?.repositories.orEmpty().map { it.baseUrl }
                }
            }
            runCurrent()
            val refresh = async { manager.findAvailableExtensions() }
            runCurrent()
            repositories.tryEmit(listOf(next))
            runCurrent()
            response.complete(
                eu.kanade.tachiyomi.extension.api.ExtensionDiscoveryResult(
                    listOf(available().copy(repoUrl = old.baseUrl)),
                    emptyList(),
                    listOf(RepositoryIdentity(old.baseUrl, old.name, old.signingKeyFingerprint)),
                ),
            )
            runCurrent()
            refresh.await()
            assertFalse(old.baseUrl in seen)
            assertEquals(next.baseUrl, manager.suggestionCatalog.value!!.repositories.single().baseUrl)
        }
    }

    @Test
    fun `suggestion catalog preserves mutually exclusive repositories before ordinary list deduplication`() = runTest {
        withNotificationEnvironment {
            val first = available()
            val second = first.copy(repoUrl = "https://other.example", repoFingerprint = "other")
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { emptyList() },
                installReceiverRegistrar = {},
                scope = backgroundScope,
                availableExtensionsProvider = { listOf(first, second) },
            )
            manager.findAvailableExtensions()
            assertEquals(1, manager.availableExtensionsFlow.value.size)
            assertEquals(
                setOf(first.repoUrl, second.repoUrl),
                manager.suggestionCatalog.value!!.entries.map { it.artifact.repository.baseUrl }.toSet(),
            )
        }
    }

    @Test
    fun `inventory retries a stale initial scan after uninstall and retains failed load presence`() = runTest {
        withNotificationEnvironment {
            val oldScan = CompletableDeferred<ExtensionInventory>()
            var scans = 0
            lateinit var listener: ExtensionInstallReceiver.Listener
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { emptyList() },
                installerFactory = { mockk(relaxed = true) },
                installReceiverRegistrar = { listener = it },
                scope = backgroundScope,
                inventoryProvider = {
                    scans++
                    if (scans == 1) {
                        oldScan.await()
                    } else {
                        ExtensionInventory(
                            true,
                            mapOf("pkg.failed" to mihon.domain.extension.suggestion.ExtensionPresence.PRESENT),
                        )
                    }
                },
            )
            runCurrent()
            assertFalse(manager.inventory.value.initialized)
            mockkObject(ExtensionLoader)
            try {
                every { ExtensionLoader.uninstallPrivateExtension(any(), any()) } returns Unit
                listener.onPackageUninstalled("pkg.removed")
                oldScan.complete(
                    ExtensionInventory(
                        true,
                        mapOf("pkg.removed" to mihon.domain.extension.suggestion.ExtensionPresence.PRESENT),
                    ),
                )
                runCurrent()
                assertTrue(manager.inventory.value.initialized)
                assertEquals(
                    mapOf("pkg.failed" to mihon.domain.extension.suggestion.ExtensionPresence.LOAD_FAILED),
                    manager.inventory.value.packages,
                )
            } finally {
                unmockkObject(ExtensionLoader)
            }
        }
    }

    @Test
    fun `explicit inventory recheck performs a fresh read without loading or installing packages`() = runTest {
        withNotificationEnvironment(expectNotification = false) {
            var scans = 0
            var loads = 0
            val manager = ExtensionManager(
                context = mockk(relaxed = true),
                preferences = preferences(),
                trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = {
                    loads++
                    emptyList()
                },
                installReceiverRegistrar = {},
                scope = backgroundScope,
                inventoryProvider = {
                    scans++
                    ExtensionInventory(true, hasUnknownArtifacts = scans == 1)
                },
            )
            runCurrent()
            assertTrue(manager.inventory.value.hasUnknownArtifacts)
            manager.recheckInstalledInventory().join()
            runCurrent()
            assertEquals(2, scans)
            assertEquals(1, loads)
            assertFalse(manager.inventory.value.hasUnknownArtifacts)
        }
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
            every { installer.downloadAndInstall(any(), any(), any(), any()) } returns flowOf(InstallStep.Installed)
            val unsupported = available().copy(libVersion = ExtensionLoader.LIB_VERSION_MAX + 0.1, versionCode = 999)
            val manager = manager(listOf(LoadResult.Success(installed())), listOf(unsupported), installer = installer)
            manager.isInitialized.await { it }
            manager.findAvailableExtensions()

            assertEquals(listOf(unsupported), manager.availableExtensionsFlow.value)
            assertEquals(listOf(InstallStep.Error), manager.installExtension(unsupported).toList())
            assertEquals(listOf(InstallStep.Error), manager.updateExtension(installed()).toList())
            assertFalse(manager.installedExtensionsFlow.value.single().hasUpdate)
            verify(exactly = 0) { installer.downloadAndInstall(any(), any(), any(), any()) }
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
            every { downloadAndInstall(any(), available, any(), any()) } answers {
                val finished = lastArg<() -> Unit>()
                kotlinx.coroutines.flow.flow {
                    try {
                        emit(InstallStep.Pending)
                    } finally {
                        finished()
                    }
                }
            }
            every { cancelInstall(any()) } returns Unit
            every { uninstallApk(any(), any()) } returns false
            every { isInstallTransactionActive(any()) } returns false
            every { hasSystemRemoval(any()) } returns false
            every { completeSystemRemoval(any(), any()) } returns false
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
        verify(exactly = 2) { installer.downloadAndInstall(any(), available, any(), any()) }
        verify { installer.cancelInstall(installed.pkgName) }
        verify { installer.uninstallApk(installed.pkgName, any()) }

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

    private suspend fun withNotificationEnvironment(expectNotification: Boolean = true, block: suspend () -> Unit) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(SecurityPreferences(InMemoryPreferenceStore()))
        val notifications = mockk<NotificationManagerCompat>(relaxed = true)
        mockkStatic(NotificationManagerCompat::class)
        try {
            every { NotificationManagerCompat.from(any()) } returns notifications
            block()
            if (expectNotification) {
                verify(atLeast = 1) {
                    notifications.cancel(Notifications.ID_UPDATES_TO_EXTS)
                }
            } else {
                verify(exactly = 0) { notifications.cancel(any<Int>()) }
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
