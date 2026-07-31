package mihon.desktop.ui.extension

import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extensionrepo.model.ExtensionRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ExtensionSharedStateWiringTest {
    @Test
    fun `fresh catalog snapshot is reused until its bounded freshness window expires`() = runTest {
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        var nowMillis = 1_000L
        var refreshCalls = 0
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers {
                refreshCalls++
                catalog
            }
            every { availableExtensions(catalog) } returns emptyList()
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList())),
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
            nowMillis = { nowMillis },
            catalogFreshnessMillis = 300_000L,
        )

        checkNotNull(model.refreshIfStale()).join()
        assertTrue(model.state.value.hasLoadedCatalog)
        assertEquals(null, model.refreshIfStale())
        assertEquals(1, refreshCalls)

        nowMillis += 300_001L
        checkNotNull(model.refreshIfStale()).join()
        assertEquals(2, refreshCalls)
    }

    @Test
    fun `repository flow prefetches asynchronously and invalidates the catalog when configuration changes`() = runTest {
        val firstRepository = ExtensionRepo("https://first.example", "First", null, "https://first.example", "first")
        val secondRepository = ExtensionRepo("https://second.example", "Second", null, "https://second.example", "second")
        val firstCatalog = ExtensionCatalogResult(emptyList(), emptyList(), listOf(firstRepository.toIdentity()))
        val secondCatalog = ExtensionCatalogResult(emptyList(), emptyList(), listOf(secondRepository.toIdentity()))
        val repositories = MutableStateFlow(
            listOf(firstRepository),
        )
        var refreshCalls = 0
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers {
                refreshCalls++
                if (refreshCalls == 1) firstCatalog else secondCatalog
            }
            every { availableExtensions(firstCatalog) } returns emptyList()
            every { availableExtensions(secondCatalog) } returns emptyList()
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(
                api,
                mockk(),
                MutableStateFlow(emptyList()),
                configuredRepositories = repositories,
            ),
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
        )

        testScheduler.runCurrent()
        assertEquals(1, model.state.value.configuredRepositoryCount)
        assertTrue(model.state.value.hasLoadedCatalog)
        assertEquals(1, refreshCalls)

        repositories.value = listOf(
            secondRepository,
        )
        testScheduler.runCurrent()

        assertEquals(2, refreshCalls)
        assertTrue(model.state.value.hasLoadedCatalog)
        assertEquals(1, model.state.value.configuredRepositoryCount)
    }

    @Test
    fun `refresh is single flight and a failed request can be retried successfully`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstFailure = IllegalStateException("catalog offline")
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        var refreshCalls = 0
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } coAnswers {
                refreshCalls++
                if (refreshCalls == 1) {
                    entered.complete(Unit)
                    release.await()
                    throw firstFailure
                }
                catalog
            }
            every { availableExtensions(catalog) } returns emptyList()
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList())),
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
        )

        val first = model.refresh()
        entered.await()
        val duplicate = model.refresh()
        testScheduler.runCurrent()
        val callsWhileFirstIsActive = refreshCalls
        release.complete(Unit)
        first.join()
        duplicate.join()

        assertSame(first, duplicate)
        assertEquals(1, callsWhileFirstIsActive)
        assertSame(firstFailure, model.state.value.refreshError)
        assertFalse(model.state.value.actions.isRefreshing)

        val retry = model.refresh()
        assertNotSame(first, retry)
        retry.join()

        assertEquals(2, refreshCalls)
        assertEquals(emptyList<DesktopExtensionItem>(), model.state.value.projection?.available)
        assertEquals(null, model.state.value.refreshError)
        assertFalse(model.state.value.actions.isRefreshing)
    }

    @Test
    fun `installed snapshot remains available when the first catalog refresh fails`() = runTest {
        val installed = installed("pkg.local", "https://repo")
        val installedFlow = MutableStateFlow(listOf(installed))
        val refreshError = IllegalStateException("catalog offline")
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } throws refreshError
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, mockk(), installedFlow),
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
        )

        testScheduler.runCurrent()
        assertSame(installed, model.state.value.projection?.installed?.single()?.installed)

        model.refresh().join()

        assertSame(installed, model.state.value.projection?.installed?.single()?.installed)
        assertSame(refreshError, model.state.value.refreshError)
        assertFalse(model.state.value.actions.isRefreshing)
    }

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
    fun `update all queues concurrent trust requests until every package is resolved`() = runTest {
        val first = available("pkg.update.first")
        val second = available("pkg.update.second")
        val catalog = ExtensionCatalogResult(emptyList(), emptyList())
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        coEvery { api.refreshCatalog() } returns catalog
        every { api.availableExtensions(catalog) } returns listOf(first, second)
        coEvery { api.beginInstall(first, manager) } returns trust("trust-first")
        coEvery { api.beginInstall(second, manager) } returns trust("trust-second")
        val confirmationStarted = CompletableDeferred<Unit>()
        val releaseConfirmation = CompletableDeferred<Unit>()
        every { api.confirmTrust("trust-first", manager) } returns flow {
            confirmationStarted.complete(Unit)
            releaseConfirmation.await()
            emit(ExtensionInstallState.Installed(mockk()))
        }
        every { api.discardTrust("trust-first") } returns true
        every { api.discardTrust("trust-second") } returns true
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(
                api,
                manager,
                MutableStateFlow(
                    listOf(
                        installed(first.pkgName, first.repoUrl),
                        installed(second.pkgName, second.repoUrl),
                    ),
                ),
            ),
            backgroundScope,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        model.refresh().join()
        model.updateAll().forEach { it.join() }
        assertEquals("trust-first", model.state.value.pendingTrust?.request?.requestId)
        val confirmation = checkNotNull(model.confirmTrust())
        confirmationStarted.await()
        assertTrue(model.state.value.pendingTrust?.request?.requestId != "trust-second")
        releaseConfirmation.complete(Unit)
        confirmation.join()
        assertEquals("trust-second", model.state.value.pendingTrust?.request?.requestId)
        assertTrue(model.dismissTrust())
        assertEquals(null, model.state.value.pendingTrust)
        assertTrue(
            listOf(first.pkgName, second.pkgName).none { packageName ->
                model.state.value.actions.installSteps[packageName] in setOf(
                    ExtensionPresentationInstallStep.Pending,
                    ExtensionPresentationInstallStep.Installing,
                )
            },
        )
        verify(exactly = 1) { api.confirmTrust("trust-first", manager) }
        verify(exactly = 1) { api.discardTrust("trust-second") }
        verify(exactly = 0) { api.discardTrust("trust-first") }
    }

    @Test
    fun `port and flow failures discard active trust before advancing the queue`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val one = available("pkg.failure.one")
        val two = available("pkg.failure.two")
        val three = available("pkg.failure.three")
        coEvery { api.beginInstall(one, manager) } returns trust("failure-one")
        coEvery { api.beginInstall(two, manager) } returns trust("failure-two")
        coEvery { api.beginInstall(three, manager) } returns trust("failure-three")
        every { api.confirmTrust("failure-one", manager) } throws IllegalStateException("port failure")
        every { api.confirmTrust("failure-two", manager) } returns flow { throw IllegalStateException("flow failure") }
        every { api.discardTrust(any()) } returns true
        val uncaught = mutableListOf<Throwable>()
        val parentScope = exceptionCapturingScope(uncaught)
        val model = model(api, manager, parentScope)
        try {
            listOf(one, two, three).forEach { model.install(it.item()).join() }
            checkNotNull(model.confirmTrust()).join()
            checkNotNull(model.confirmTrust()).join()
            verify(exactly = 1) { api.discardTrust("failure-one") }
            verify(exactly = 1) { api.discardTrust("failure-two") }
            assertEquals(emptyList<Throwable>(), uncaught)
            assertEquals("failure-three", model.state.value.pendingTrust?.request?.requestId)
            assertTrue(
                listOf(one.pkgName, two.pkgName).none { packageName ->
                    model.state.value.actions.installSteps[packageName] in setOf(
                        ExtensionPresentationInstallStep.Pending,
                        ExtensionPresentationInstallStep.Installing,
                    )
                },
            )
        } finally {
            model.closeAndJoin()
            parentScope.cancel()
        }
    }

    @Test
    fun `cancelling confirmation discards active trust before exposing the next request`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val one = available("pkg.cancel.one")
        val two = available("pkg.cancel.two")
        val confirmationStarted = CompletableDeferred<Unit>()
        coEvery { api.beginInstall(one, manager) } returns trust("cancel-one")
        coEvery { api.beginInstall(two, manager) } returns trust("cancel-two")
        every { api.confirmTrust("cancel-one", manager) } returns flow {
            confirmationStarted.complete(Unit)
            awaitCancellation()
        }
        every { api.discardTrust(any()) } returns true
        val model = model(api, manager, backgroundScope)
        model.install(one.item()).join()
        model.install(two.item()).join()
        val confirmation = checkNotNull(model.confirmTrust())
        confirmationStarted.await()
        confirmation.cancel()
        confirmation.join()
        verify(exactly = 1) { api.discardTrust("cancel-one") }
        assertEquals("cancel-two", model.state.value.pendingTrust?.request?.requestId)
        assertEquals(null, model.state.value.actions.installSteps[one.pkgName])
    }

    @Test
    fun `closing during confirmation discards active and queued trust without pending actions`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val one = available("pkg.close.one")
        val two = available("pkg.close.two")
        val confirmationStarted = CompletableDeferred<Unit>()
        coEvery { api.beginInstall(one, manager) } returns trust("close-one")
        coEvery { api.beginInstall(two, manager) } returns trust("close-two")
        every { api.confirmTrust("close-one", manager) } returns flow {
            confirmationStarted.complete(Unit)
            awaitCancellation()
        }
        every { api.discardTrust(any()) } returns true
        val model = model(api, manager, backgroundScope)
        model.install(one.item()).join()
        model.install(two.item()).join()
        checkNotNull(model.confirmTrust())
        confirmationStarted.await()
        model.closeAndJoin()
        verify(exactly = 1) { api.discardTrust("close-one") }
        verify(exactly = 1) { api.discardTrust("close-two") }
        assertEquals(null, model.state.value.pendingTrust)
        assertTrue(
            listOf(one.pkgName, two.pkgName).none { packageName ->
                model.state.value.actions.installSteps[packageName] in setOf(
                    ExtensionPresentationInstallStep.Pending,
                    ExtensionPresentationInstallStep.Installing,
                )
            },
        )
    }

    @Test
    fun `pending cleanup preserves a concurrent package error update`() = runTest {
        val api = mockk<DesktopExtensionApi>()
        val manager = mockk<DesktopExtensionManager>()
        val pending = available("pkg.interleave.pending")
        val rejected = available("pkg.interleave.rejected")
        val rejection = AppError.Storage()
        val releaseFailure = CompletableDeferred<Unit>()
        coEvery { api.beginInstall(pending, manager) } returns trust("interleave-pending")
        coEvery { api.beginInstall(rejected, manager) } returns DesktopExtensionInstallStart.Started(flow {
            releaseFailure.await()
            emit(ExtensionInstallState.Failed(rejection))
            awaitCancellation()
        })
        every { api.discardTrust("interleave-pending") } returns true
        val parentScope = exceptionCapturingScope(mutableListOf())
        val model = model(api, manager, parentScope)
        val (stateRead, releaseStateWrite) = List(2) { CountDownLatch(1) }
        try {
            model.install(pending.item()).join()
            val rejectedInstall = model.install(rejected.item())
            val armed = AtomicBoolean(false)
            interceptStateFlow(model) { method, delegate, arguments ->
                val value = method.invoke(delegate, *arguments)
                if (method.name == "getValue" && armed.compareAndSet(true, false)) {
                    stateRead.countDown()
                    assertTrue(releaseStateWrite.await(5, TimeUnit.SECONDS))
                }
                value
            }
            armed.set(true)
            val dismissing = async(Dispatchers.Default) { model.dismissTrust() }
            assertTrue(stateRead.await(5, TimeUnit.SECONDS))
            releaseFailure.complete(Unit)
            assertSame(rejection, model.state.value.installErrors[rejected.pkgName])
            releaseStateWrite.countDown()
            assertTrue(dismissing.await())
            assertSame(rejection, model.state.value.installErrors[rejected.pkgName])
            assertEquals(ExtensionPresentationInstallStep.Error, model.state.value.actions.installSteps[rejected.pkgName])
            rejectedInstall.cancel()
            rejectedInstall.join()
        } finally {
            releaseFailure.complete(Unit)
            releaseStateWrite.countDown()
            model.closeAndJoin()
            parentScope.cancel()
        }
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
        every { api.confirmTrust("one", manager) } returns flowOf(ExtensionInstallState.Failed(error))
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
        assertEquals("one", model.state.value.pendingTrust?.request?.requestId)
        model.confirmTrust()?.join()
        assertSame(error, model.state.value.installErrors[one.pkgName])
        assertEquals("two", model.state.value.pendingTrust?.request?.requestId)
        model.install(one.item()).join()
        assertTrue(model.dismissTrust())
        assertEquals("one", model.state.value.pendingTrust?.request?.requestId)
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
        verify(exactly = 1) { api.discardTrust("one") }
        verify(exactly = 2) { api.discardTrust("two") }
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

    private fun exceptionCapturingScope(errors: MutableList<Throwable>) = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> errors += error },
    )

    @Suppress("UNCHECKED_CAST")
    private fun interceptStateFlow(
        model: ExtensionsScreenModel,
        invoke: (java.lang.reflect.Method, MutableStateFlow<DesktopExtensionsState>, Array<out Any?>) -> Any?,
    ) {
        val field = ExtensionsScreenModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
        val delegate = field.get(model) as MutableStateFlow<DesktopExtensionsState>
        field.set(model, Proxy.newProxyInstance(
            MutableStateFlow::class.java.classLoader,
            arrayOf(MutableStateFlow::class.java),
        ) { _, method, arguments -> invoke(method, delegate, arguments.orEmpty()) })
    }

    private fun trust(id: String) = DesktopExtensionInstallStart.TrustRequired(id, "old", "new", emptySet(), mockk())
}
