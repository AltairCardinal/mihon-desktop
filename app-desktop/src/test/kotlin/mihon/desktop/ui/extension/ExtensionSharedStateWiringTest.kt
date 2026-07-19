package mihon.desktop.ui.extension

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.service.ExtensionInstallState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ExtensionSharedStateWiringTest {
    @Test
    fun `real port refresh reducer and authoritative flow preserve partial failure identity`() = runTest {
        val installedFlow = MutableStateFlow<List<InstalledExtension>>(emptyList())
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val port = DesktopExtensionPresentationPort(api, manager, installedFlow)
        val failure = RepositoryCatalogFailure(
            RepositoryIdentity("https://failed", "Failed", "key"),
            AppError.Network(),
        )
        val catalog = ExtensionCatalogResult(emptyList(), listOf(failure))
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        coEvery { api.refreshCatalog() } coAnswers {
            refreshEntered.complete(Unit)
            releaseRefresh.await()
            catalog
        }
        every { api.availableExtensions(catalog) } returns emptyList()
        val initial = ExtensionPresentationOptions(false, setOf("en"))
        val changed = ExtensionPresentationOptions(true, setOf("en"))
        val model = ExtensionsScreenModel(port, backgroundScope, initial)

        model.setOptions(changed)
        assertEquals(changed, model.state.value.options)
        val refresh = model.refresh()
        refreshEntered.await()
        assertTrue(model.state.value.actions.isRefreshing)
        releaseRefresh.complete(Unit)
        refresh.join()
        assertFalse(model.state.value.actions.isRefreshing)
        assertSame(failure, model.state.value.projection?.failures?.single())
        val installed = installed("pkg.authoritative", "https://failed")
        installedFlow.value = listOf(installed)
        testScheduler.runCurrent()
        val projected = model.state.value.projection?.installed?.single()
        assertSame(installed, projected?.installed)
        assertEquals("pkg.authoritative", model.state.value.presentation?.installed?.single()?.operationPackageName)
        assertSame(failure, model.state.value.projection?.failures?.single())
        val refreshError = IllegalStateException("refresh failed")
        coEvery { api.refreshCatalog() } throws refreshError
        model.refresh().join()
        assertSame(refreshError, model.state.value.refreshError)
        assertFalse(model.state.value.actions.isRefreshing)
    }

    @Test
    fun `real shared update projection selects raw catalog candidate and typed uninstall exact instance`() = runTest {
        val installed = installed("pkg.update", "https://repo")
        val installedFlow = MutableStateFlow(listOf(installed))
        val firstCandidate = available("pkg.update").copy(name = "First candidate", repoUrl = "https://first", repoFingerprint = "first")
        val candidate = available("pkg.update").copy(name = "Last candidate", repoUrl = "https://last", repoFingerprint = "last")
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        coEvery { api.refreshCatalog() } returns catalog
        every { api.availableExtensions(catalog) } returns listOf(firstCandidate, candidate, available("pkg.not-update"))
        every { manager.removeExtensionWithMeta(any()) } returns true
        val port = DesktopExtensionPresentationPort(api, manager, installedFlow)
        val model = ExtensionsScreenModel(
            port,
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
        )

        model.refresh().join()
        val projected = model.state.value.projection?.installed?.single()
        assertTrue(projected?.presentation?.hasUpdate == true)
        assertSame(installed, projected?.installed)
        assertEquals("pkg.update", model.state.value.presentation?.updates?.single()?.operationPackageName)
        val projectedCandidates = model.state.value.projection?.available.orEmpty().filter { it.operationPackageName == "pkg.update" }
        assertEquals(1, projectedCandidates.size)
        assertSame(candidate, projectedCandidates.single().available)
        assertEquals(candidate.name, projectedCandidates.single().presentation.name)
        assertEquals("https://last", projectedCandidates.single().available?.repoUrl)
        assertEquals("last", projectedCandidates.single().available?.repoFingerprint)
        assertSame(candidate, model.updateAllCandidates().single())
        coEvery { api.beginInstall(candidate, manager) } returns DesktopExtensionInstallStart.Started(
            flowOf(ExtensionInstallState.Installed(mockk())),
        )
        model.update(checkNotNull(projected))?.join()
        model.retry(checkNotNull(projected))?.join()
        coVerify(exactly = 2) { api.beginInstall(candidate, manager) }
        coVerify(exactly = 0) { api.beginInstall(firstCandidate, manager) }
        assertTrue(model.uninstall(checkNotNull(projected)))
        verify(exactly = 1) { manager.removeExtensionWithMeta(match { it === installed }) }
    }

    @Test
    fun `typed install flow stops after installed while exact error remains and idle cleans`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val model = model(api, manager, backgroundScope)
        val success = available("pkg.success")
        val failure = available("pkg.failure")
        val cancelled = available("pkg.cancelled")
        val error = AppError.Network()
        val rawFailure = ExtensionInstallState.Failed(error)
        coEvery { api.beginInstall(success, manager) } returns DesktopExtensionInstallStart.Started(
            flowOf(ExtensionInstallState.Preparing, ExtensionInstallState.Installed(mockk()), ExtensionInstallState.Failed(error)),
        )
        coEvery { api.beginInstall(failure, manager) } returnsMany listOf(
            DesktopExtensionInstallStart.Started(flowOf(rawFailure)),
            DesktopExtensionInstallStart.Started(flowOf(ExtensionInstallState.Installed(mockk()))),
        )
        coEvery { api.beginInstall(cancelled, manager) } returns DesktopExtensionInstallStart.Started(
            flowOf(ExtensionInstallState.Failed(AppError.Cancelled)),
        )

        model.install(success.item()).join()
        model.retry(failure.item())?.join()
        assertFalse(success.pkgName in model.state.value.actions.installSteps)
        assertEquals(ExtensionPresentationInstallStep.Error, model.state.value.actions.installSteps[failure.pkgName])
        assertSame(error, model.state.value.installErrors[failure.pkgName])
        assertSame(rawFailure, model.state.value.rawInstallStates[failure.pkgName])
        model.install(failure.item()).join()
        model.install(cancelled.item()).join()
        assertFalse(failure.pkgName in model.state.value.installErrors)
        assertFalse(cancelled.pkgName in model.state.value.actions.installSteps)
        assertFalse(success.pkgName in model.state.value.installErrors)
        assertFalse(success.pkgName in model.state.value.rawInstallStates)
        assertFalse(failure.pkgName in model.state.value.rawInstallStates)
        assertFalse(cancelled.pkgName in model.state.value.rawInstallStates)
        assertFalse(cancelled.pkgName in model.state.value.installErrors)
    }

    @Test
    fun `same package replacement joins cleanup and cancel leaves sibling independent`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val model = model(api, manager, backgroundScope)
        val replace = available("pkg.replace")
        val siblingExtension = available("pkg.sibling")
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        var attempts = 0
        coEvery { api.beginInstall(replace, manager) } answers {
            DesktopExtensionInstallStart.Started(flow {
                if (attempts++ == 0) firstStarted.complete(Unit) else secondStarted.complete(Unit)
                try { emit(ExtensionInstallState.Preparing); awaitCancellation() } finally {
                    if (attempts == 1) withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() }
                    else secondStopped.complete(Unit)
                }
            })
        }
        coEvery { api.beginInstall(siblingExtension, manager) } returns DesktopExtensionInstallStart.Started(
            flow { emit(ExtensionInstallState.Preparing); awaitCancellation() },
        )

        model.install(replace.item())
        firstStarted.await()
        val current = model.install(replace.item())
        cleanupEntered.await()
        assertFalse(secondStarted.isCompleted)
        releaseCleanup.complete(Unit)
        secondStarted.await()
        val sibling = model.install(siblingExtension.item())
        model.cancel(replace.pkgName).join()
        assertFalse(current.isActive)
        assertTrue(secondStopped.isCompleted)
        assertTrue(sibling.isActive)
    }

    @Test
    fun `trust ids are consumed once and close drains owned work without cancelling parent sibling`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val model = model(api, manager, backgroundScope)
        val one = available("pkg.one")
        val two = available("pkg.two")
        val late = available("pkg.late")
        val active = available("pkg.active")
        coEvery { api.beginInstall(one, manager) } returns trust("one")
        coEvery { api.beginInstall(two, manager) } returns trust("two")
        every { api.discardTrust(any()) } returns true
        val error = AppError.Storage()
        every { api.confirmTrust("two", manager) } returns flowOf(ExtensionInstallState.Failed(error))
        val lateEntered = CompletableDeferred<Unit>()
        val releaseLate = CompletableDeferred<Unit>()
        val closeFinally = CompletableDeferred<Unit>()
        val releaseFinally = CompletableDeferred<Unit>()
        coEvery { api.beginInstall(late, manager) } coAnswers {
            suspendCoroutine { continuation ->
                lateEntered.complete(Unit)
                releaseLate.invokeOnCompletion { continuation.resume(trust("late")) }
            }
        }
        coEvery { api.beginInstall(active, manager) } returns DesktopExtensionInstallStart.Started(flow {
            try { emit(ExtensionInstallState.Preparing); awaitCancellation() } finally {
                withContext(NonCancellable) { closeFinally.complete(Unit); releaseFinally.await() }
            }
        })

        model.install(one.item()).join()
        assertEquals("one", model.state.value.pendingTrust?.request?.requestId)
        model.install(two.item()).join()
        verify(exactly = 1) { api.discardTrust("one") }
        model.confirmTrust()?.join()
        assertSame(error, model.state.value.installErrors[two.pkgName])
        model.install(one.item()).join()
        assertTrue(model.dismissTrust())
        model.install(two.item()).join()
        model.install(late.item())
        lateEntered.await()
        model.install(active.item())
        val parentSibling = backgroundScope.launch { awaitCancellation() }
        val closing = async { model.closeAndJoin() }
        closeFinally.await()
        assertFalse(closing.isCompleted)
        releaseLate.complete(Unit)
        releaseFinally.complete(Unit)
        closing.await()
        assertEquals(null, model.state.value.pendingTrust)
        assertTrue(parentSibling.isActive)
        verify(exactly = 2) { api.discardTrust("one") }
        verify(exactly = 1) { api.discardTrust("two") }
        verify(exactly = 1) { api.discardTrust("late") }
        assertThrows(IllegalStateException::class.java) { model.install(late.item()) }
    }

    private fun installed(pkg: String, repo: String) = InstalledExtension(
        File("$pkg.jar"),
        emptyList(),
        versionCode = 1,
        versionName = "1.4.1",
        repoUrl = repo,
        displayName = pkg,
        language = "en",
    )

    private fun available(pkg: String) = DesktopAvailableExtension(
        pkg,
        pkg,
        "1.4.2",
        2,
        1.4,
        "en",
        false,
        "https://repo/$pkg.jar",
        "",
        "https://repo",
    )

    private fun model(api: DesktopExtensionApi, manager: DesktopExtensionManager, scope: CoroutineScope) = ExtensionsScreenModel(
        DesktopExtensionPresentationPort(api, manager, MutableStateFlow(emptyList())),
        scope,
        ExtensionPresentationOptions(false, setOf("en")),
    )

    private fun trust(id: String) = DesktopExtensionInstallStart.TrustRequired(id, "old", "new", emptySet(), mockk())
}
