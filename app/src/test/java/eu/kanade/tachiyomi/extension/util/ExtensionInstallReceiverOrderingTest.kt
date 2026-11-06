package eu.kanade.tachiyomi.extension.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uy.kohesive.injekt.api.addSingleton
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionInstallReceiverOrderingTest {
    @Test
    fun `late broadcast failure cannot overwrite a newer successful manual retry`() = overlappingManualLoad(
        trust = false,
    )

    @Test
    fun `late broadcast failure cannot overwrite a newer explicitly trusted load`() = overlappingManualLoad(
        trust = true,
    )

    @Test
    fun `newer trusted load wins even when the older broadcast finishes first`() =
        overlappingManualLoad(trust = true, broadcastFinishesFirst = true)

    @Test
    fun `trust intent survives an older untrusted broadcast during persistence`() =
        overlappingManualLoad(trust = true, blockTrustPersistence = true)

    @Test
    fun `new package start during trust persistence supersedes the older trust intent`() =
        overlappingManualLoad(trust = true, blockTrustPersistence = true, newPackageDuringSave = true)

    private fun overlappingManualLoad(
        trust: Boolean,
        broadcastFinishesFirst: Boolean = false,
        blockTrustPersistence: Boolean = false,
        newPackageDuringSave: Boolean = false,
    ) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = eu.kanade.domain.source.service.SourcePreferences(
            tachiyomi.core.common.preference.AndroidPreferenceStore(context),
        )
        val previous = uy.kohesive.injekt.Injekt
        uy.kohesive.injekt.Injekt =
            uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
        uy.kohesive.injekt.Injekt.addSingleton(
            eu.kanade.tachiyomi.core.security.SecurityPreferences(
                tachiyomi.core.common.preference.AndroidPreferenceStore(context),
            ),
        )
        val failure = eu.kanade.tachiyomi.extension.model.ExtensionLoadFailure(
            "Broken",
            "fixture.same.version",
            "1.6.2",
            2,
            true,
            eu.kanade.tachiyomi.network.SourceOperationPhase.FACTORY,
            mihon.domain.error.AppError.ExtensionIncompatible(),
        )
        val recovered = Extension.Installed(
            failure.name, failure.pkgName, failure.versionName, failure.versionCode, 1.6,
            "en", false, null, emptyList(), null, isShared = true,
        )
        val untrusted = Extension.Untrusted(
            failure.name,
            failure.pkgName,
            failure.versionName,
            failure.versionCode,
            1.6,
            "signature",
        )
        val managerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        var receiver: ExtensionInstallReceiver? = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val manualStarted = CompletableDeferred<Unit>()
        val manualRelease = CompletableDeferred<Unit>()
        val saveStarted = CompletableDeferred<Unit>()
        val saveRelease = CountDownLatch(1)
        val trustAdapter = mockk<eu.kanade.domain.extension.interactor.TrustExtension>(relaxed = true) {
            every { trust(any(), any(), any()) } answers {
                if (blockTrustPersistence) {
                    saveStarted.complete(Unit)
                    assertTrue("Controlled trust persistence must be released", saveRelease.await(5, TimeUnit.SECONDS))
                }
            }
        }
        mockkObject(ExtensionLoader)
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, failure.pkgName) } coAnswers {
            started.complete(Unit)
            release.await()
            if (blockTrustPersistence) {
                LoadResult.Untrusted(
                    untrusted,
                )
            } else {
                LoadResult.Failure(failure.copy(name = "stale broadcast"))
            }
        }
        try {
            val manager = eu.kanade.tachiyomi.extension.ExtensionManager(
                context = context, preferences = preferences, trustExtension = trustAdapter,
                installedExtensionsLoader = {
                    listOf(if (trust) LoadResult.Untrusted(untrusted) else LoadResult.Failure(failure))
                },
                extensionLoader = { _, _ ->
                    manualStarted.complete(Unit)
                    if (broadcastFinishesFirst) manualRelease.await()
                    LoadResult.Success(recovered)
                },
                installerFactory = { mockk(relaxed = true) },
                installReceiverRegistrar = { receiver = ExtensionInstallReceiver(it) }, scope = managerScope,
                inventoryProvider = { mihon.domain.extension.suggestion.ExtensionInventory(initialized = true) },
            )
            withTimeout(5_000) { manager.isInitialized.first { it } }
            val activeReceiver = checkNotNull(receiver)
            activeReceiver.onReceive(
                context,
                Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:${failure.pkgName}")),
            )
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) {
                if (blockTrustPersistence) {
                    // Trust writes synchronously; give it a separate worker so the receiver can finish.
                    val manual = async(Dispatchers.IO) { manager.trust(untrusted) }
                    saveStarted.await()
                    assertFalse("No plugin call before trust is durable", manualStarted.isCompleted)
                    if (newPackageDuringSave) {
                        activeReceiver.onReceive(
                            context,
                            Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:${failure.pkgName}")),
                        )
                    }
                    release.complete(Unit)
                    activeReceiver.scope.coroutineContext.job.children.toList().forEach { it.join() }
                    assertFalse("Saving still blocks the plugin call", manualStarted.isCompleted)
                    saveRelease.countDown()
                    manual.await()
                    if (!newPackageDuringSave) {
                        assertTrue("Durable trust must invoke its owned loader", manualStarted.isCompleted)
                    }
                } else if (broadcastFinishesFirst) {
                    val manual = async { manager.trust(untrusted) }
                    manualStarted.await()
                    release.complete(Unit)
                    activeReceiver.scope.coroutineContext.job.children.toList().forEach { it.join() }
                    manualRelease.complete(Unit)
                    manual.await()
                } else if (trust) {
                    manager.trust(untrusted)
                } else {
                    manager.retryFailedExtension(failure).join()
                }
            }
            if (newPackageDuringSave) {
                assertFalse("The new package owns this result", manualStarted.isCompleted)
                assertEquals(untrusted, manager.untrustedExtensionsFlow.value.single())
                assertTrue(manager.installedExtensionsFlow.value.isEmpty())
            } else {
                assertEquals(recovered, manager.installedExtensionsFlow.value.single())
            }
            release.complete(Unit)
            manualRelease.complete(Unit)
            withTimeout(5_000) { activeReceiver.scope.coroutineContext.job.children.toList().forEach { it.join() } }
            if (!newPackageDuringSave) assertEquals(recovered, manager.installedExtensionsFlow.value.singleOrNull())
            assertEquals(
                emptyMap<String, eu.kanade.tachiyomi.extension.model.ExtensionLoadFailure>(),
                manager.loadFailures.value,
            )
        } finally {
            release.complete(Unit)
            saveRelease.countDown()
            manualRelease.complete(Unit)
            receiver?.scope?.coroutineContext?.job?.cancelAndJoin()
            managerScope.coroutineContext.job.cancelAndJoin()
            unmockkObject(ExtensionLoader)
            uy.kohesive.injekt.Injekt = previous
        }
    }

    @Test
    fun `old load cleanup cannot remove a newer same package load`() = overlappingLoads(samePackage = true)

    @Test
    fun `loading another package does not suppress a current load`() = overlappingLoads(samePackage = false)

    private fun overlappingLoads(samePackage: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val packages = listOf("owned.first", if (samePackage) "owned.first" else "owned.second")
        val started = List(2) { CompletableDeferred<Unit>() }
        val release = List(2) { CompletableDeferred<Unit>() }
        val calls = AtomicInteger()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val installed = mockk<Extension.Installed>()
        val receiver = ExtensionInstallReceiver(object : ExtensionInstallReceiver.Listener {
            override fun onPackageChanged(pkgName: String) {
                events += "changed:$pkgName"
            }
            override fun onExtensionInstalled(extension: Extension.Installed) {
                events += "installed"
            }
            override fun onExtensionUpdated(extension: Extension.Installed) {
                events += "updated"
            }
            override fun onExtensionUntrusted(extension: Extension.Untrusted) {
                events += "untrusted"
            }
            override fun onPackageUninstalled(pkgName: String) {
                events += "removed:$pkgName"
            }
        })
        mockkObject(ExtensionLoader)
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, any()) } coAnswers {
            val index = calls.getAndIncrement()
            started[index].complete(Unit)
            release[index].await()
            LoadResult.Success(installed)
        }
        try {
            receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:${packages[0]}")))
            withTimeout(5_000) { started[0].await() }
            val oldJob = receiver.scope.coroutineContext.job.children.single()
            receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:${packages[1]}")))
            withTimeout(5_000) { started[1].await() }
            release[0].complete(Unit)
            withTimeout(5_000) { oldJob.join() }
            release[1].complete(Unit)
            withTimeout(5_000) { receiver.scope.coroutineContext.job.children.toList().forEach { it.join() } }
            val expected = if (samePackage) {
                listOf("updated", "changed:owned.first")
            } else {
                listOf("installed", "changed:owned.first", "updated", "changed:owned.second")
            }
            assertEquals(expected, events.toList())
        } finally {
            release.forEach { it.complete(Unit) }
            receiver.scope.coroutineContext.job.cancelAndJoin()
            unmockkObject(ExtensionLoader)
        }
    }

    @Test
    fun `removed package rejects an earlier added load result`() = staleLoadAfterRemoval(Intent.ACTION_PACKAGE_ADDED)

    @Test
    fun `removed package rejects an earlier replaced load result`() = staleLoadAfterRemoval(
        Intent.ACTION_PACKAGE_REPLACED,
    )

    private fun staleLoadAfterRemoval(action: String) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val packageName = "owned.extension"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val installed = mockk<Extension.Installed>()
        val receiver = ExtensionInstallReceiver(object : ExtensionInstallReceiver.Listener {
            override fun onPackageChanged(pkgName: String) {
                events += "changed:$pkgName"
            }
            override fun onExtensionInstalled(extension: Extension.Installed) {
                events += "installed"
            }
            override fun onExtensionUpdated(extension: Extension.Installed) {
                events += "updated"
            }
            override fun onExtensionUntrusted(extension: Extension.Untrusted) {
                events += "untrusted"
            }
            override fun onPackageUninstalled(pkgName: String) {
                events += "removed:$pkgName"
            }
        })
        mockkObject(ExtensionLoader)
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, packageName) } coAnswers {
            started.complete(Unit)
            release.await()
            LoadResult.Success(installed)
        }
        fun intent(value: String) = Intent(value, Uri.parse("package:$packageName"))
        try {
            receiver.onReceive(context, intent(action))
            withTimeout(5_000) { started.await() }
            receiver.onReceive(context, intent(Intent.ACTION_PACKAGE_REMOVED))
            assertEquals(listOf("removed:$packageName"), events.toList())
            release.complete(Unit)
            withTimeout(5_000) { receiver.scope.coroutineContext.job.children.toList().forEach { it.join() } }
            assertEquals(
                "A completed stale loader must not resurrect an uninstalled package",
                listOf("removed:$packageName"),
                events.toList(),
            )
        } finally {
            release.complete(Unit)
            receiver.scope.coroutineContext.job.cancelAndJoin()
            unmockkObject(ExtensionLoader)
        }
    }
}
