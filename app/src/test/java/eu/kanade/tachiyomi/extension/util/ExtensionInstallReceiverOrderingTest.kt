package eu.kanade.tachiyomi.extension.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionInstallReceiverOrderingTest {
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
