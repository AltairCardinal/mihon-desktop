package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.ExtensionSourceItem
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.interactor.androidExtensionPresentationStore
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.toArtifact
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsEvent
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreenModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationStore
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionSuggestionPreferences
import mihon.domain.extension.suggestion.ExtensionSuggestions
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.api.addSingleton
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExtensionPresentationWiringTest {
    private val modelHost = ScreenModelTestHost()

    @Test
    fun `batch replacement respects disabled nsfw and requires explicit new repository choice`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val first = available(
            "Reader",
            "pkg.replaced",
            listOf(Extension.Available.Source(73, "en", "Reader source", "https://source.example")),
        )
            .copy(versionName = "1.6.1", libVersion = 1.6).toArtifact()
        fun catalogOf(vararg artifacts: mihon.domain.extension.model.ExtensionArtifact) =
            mihon.domain.extension.model.ExtensionCatalogResult(
                artifacts.map {
                    ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible)
                },
                emptyList(),
                artifacts.map { it.repository }.distinct(),
            )
        fun suggestionOf(artifact: mihon.domain.extension.model.ExtensionArtifact) =
            mihon.domain.extension.suggestion.ExtensionSuggestion(
                mihon.domain.extension.suggestion.SuggestionIdentity.of(artifact),
                artifact,
                artifact.sources.map { SuggestedSource(it, 1) },
                false,
            )
        val catalog = MutableStateFlow<mihon.domain.extension.model.ExtensionCatalogResult?>(catalogOf(first))
        val suggestions = MutableStateFlow(ExtensionSuggestions(false, listOf(suggestionOf(first))))
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { scope } returns backgroundScope
            every { installArbiter } returns ExtensionInstallArbiter()
            every { suggestionCatalog } returns catalog
            every { inventory } returns MutableStateFlow(ExtensionInventory(true))
            every { installErrors } returns MutableStateFlow(emptyMap())
            every { originConfirmations } returns MutableStateFlow(emptyList())
            every { pendingSystemPauses } returns MutableStateFlow(emptyMap())
        }
        io.mockk.coEvery { manager.installReservedObserved(any(), any(), any()) } returns
            SuggestionBatchResult.Installed
        val batch = eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch(
            manager,
            suggestions,
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        ) {
            BasePreferences.ExtensionInstaller.PRIVATE
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
            androidExtensionPresentationStore,
            suggestions,
            batch,
            showNsfw = false,
        )
        try {
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 1 } }
            }
            model.requestSuggestionBatch()
            val hidden = first.copy(versionName = "1.6.2", versionCode = first.versionCode + 1, isNsfw = true)
            val replacement = first.copy(
                repository = first.repository.copy(signingKeyFingerprint = "explicit-new-signer"),
            )
            catalog.value = catalogOf(hidden, replacement)
            assertFalse(model.confirmSuggestionBatch(listOf(first)))
            val review = checkNotNull(model.state.value.batchConfirmation)
            assertEquals(listOf(first), review.artifacts)
            assertEquals(setOf(first.packageName), review.unavailablePackages)
            assertEquals(listOf(replacement), review.replacements[first.packageName])
            model.selectBatchReplacement(hidden)
            assertEquals(review, model.state.value.batchConfirmation)
            model.selectBatchReplacement(replacement)
            assertEquals(listOf(replacement), model.state.value.batchConfirmation?.artifacts)
            suggestions.value = suggestions.value.copy(suggestions = listOf(suggestionOf(replacement)))
            runCurrent()
            assertTrue(model.confirmSuggestionBatch(listOf(replacement)))
            runCurrent()
            assertEquals(listOf(replacement), batch.state.value.items.map { it.artifact })
            assertEquals(SuggestionBatchResult.Installed, batch.state.value.items.single().result)
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `model publishes nonterminal system interruption and clears its resolved state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val pauses = MutableStateFlow<Map<String, SuggestionBatchPause>>(emptyMap())
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installErrors } returns MutableStateFlow(emptyMap())
            every { originConfirmations } returns MutableStateFlow(emptyList())
            every { pendingSystemPauses } returns pauses
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
            androidExtensionPresentationStore,
        )
        try {
            pauses.value = mapOf("pkg.pending" to mihon.domain.extension.suggestion.SuggestionBatchPause.SERVICE)
            runCurrent()
            assertEquals(pauses.value, model.state.value.pendingSystemPauses)
            assertTrue(model.state.value.installErrors.isEmpty())
            pauses.value = emptyMap()
            runCurrent()
            assertTrue(model.state.value.pendingSystemPauses.isEmpty())
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `batch review freezes search results and requires fresh version confirmation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val first = available(
            "First Reader",
            "pkg.first",
            listOf(Extension.Available.Source(71, "en", "First Source", "https://source.example")),
        )
            .copy(versionName = "1.6.1", libVersion = 1.6).toArtifact()
        val second = first.copy(
            name = "Second Reader",
            packageName = "pkg.second",
            sources = listOf(first.sources.single().copy(id = 72, name = "Second Source")),
        )
        val catalog = MutableStateFlow<mihon.domain.extension.model.ExtensionCatalogResult?>(
            mihon.domain.extension.model.ExtensionCatalogResult(
                listOf(first, second).map {
                    ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible)
                },
                emptyList(),
                listOf(first.repository),
            ),
        )
        val suggestions = MutableStateFlow(
            ExtensionSuggestions(
                false,
                listOf(first, second).map {
                    mihon.domain.extension.suggestion.ExtensionSuggestion(
                        mihon.domain.extension.suggestion.SuggestionIdentity.of(it),
                        it,
                        it.sources.map { source -> SuggestedSource(source, 1) },
                        false,
                    )
                },
            ),
        )
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { scope } returns backgroundScope
            every { installArbiter } returns ExtensionInstallArbiter()
            every { suggestionCatalog } returns catalog
            every { inventory } returns MutableStateFlow(ExtensionInventory(true))
            every { installErrors } returns MutableStateFlow(emptyMap())
            every { originConfirmations } returns MutableStateFlow(emptyList())
        }
        io.mockk.coEvery { manager.installReservedObserved(any(), any(), any()) } returns
            SuggestionBatchResult.Installed
        val batch = eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch(
            manager,
            suggestions,
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        ) {
            BasePreferences.ExtensionInstaller.PRIVATE
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
            androidExtensionPresentationStore,
            suggestions,
            batch,
        )
        try {
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 2 } }
            }
            model.search("First")
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { model.state.first { it.suggestionPanel.rows.size == 1 } }
            }
            model.requestSuggestionBatch()
            assertEquals(listOf(first), model.state.value.batchConfirmation?.artifacts)
            val updated = first.copy(versionCode = first.versionCode + 1, versionName = "1.6.2")
            catalog.value = catalog.value!!.copy(
                entries = listOf(updated, second).map {
                    ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible)
                },
            )
            assertFalse(model.confirmSuggestionBatch(listOf(first)))
            assertEquals(listOf(updated), model.state.value.batchConfirmation?.artifacts)
            assertTrue(model.confirmSuggestionBatch(listOf(updated)))
            runCurrent()
            assertEquals(listOf(updated), batch.state.value.items.map { it.artifact })
            io.mockk.coVerify(exactly = 0) {
                manager.installReservedObserved(
                    match { it.artifact.packageName == second.packageName },
                    any(),
                    any(),
                )
            }
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `synchronous install failure is visible and releases screen ownership for retry`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val extension = available("Reader", "pkg.sync.failure", emptyList())
        val failure = IllegalStateException("Installer could not start")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installExtension(extension) } answers {
                if (calls.incrementAndGet() == 1) throw failure
                flow {
                    emit(eu.kanade.tachiyomi.extension.model.InstallStep.Downloading)
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        }
        val model =
            screenModel(
                manager,
                Extensions(emptyList(), emptyList(), listOf(extension), emptyList()),
                androidExtensionPresentationStore,
            )
        try {
            model.installExtension(extension)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    model.state.first {
                        it.installErrors[extension.pkgName]?.cause ===
                            failure
                    }
                }
            }
            model.installExtension(extension)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    model.state.first {
                        it.items.values.flatten().singleOrNull()?.installStep ==
                            eu.kanade.tachiyomi.extension.model.InstallStep.Downloading
                    }
                }
            }
            assertEquals(2, calls.get())
            assertFalse(model.state.value.installErrors.containsKey(extension.pkgName))
        } finally {
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `another manager entry remains downloading when the real screen rejects its duplicate`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val previousInjekt = uy.kohesive.injekt.Injekt
        uy.kohesive.injekt.Injekt =
            uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
        uy.kohesive.injekt.Injekt.addSingleton(
            eu.kanade.tachiyomi.core.security.SecurityPreferences(
                InMemoryPreferenceStore(),
            ),
        )
        io.mockk.mockkStatic(androidx.core.app.NotificationManagerCompat::class)
        every { androidx.core.app.NotificationManagerCompat.from(any()) } returns mockk(relaxed = true)
        val extension = available("Reader", "pkg.held", emptyList())
        val steps = MutableStateFlow(eu.kanade.tachiyomi.extension.model.InstallStep.Downloading)
        val installer = mockk<eu.kanade.tachiyomi.extension.util.ExtensionInstaller>(relaxed = true) {
            every { installErrors } returns MutableStateFlow(emptyMap())
            every { originConfirmations } returns MutableStateFlow(emptyList())
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                val finished = lastArg<() -> Unit>()
                steps.onCompletion { finished() }
            }
        }
        val manager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = mockk(relaxed = true) {
                every { enabledLanguages() } returns
                    mockk { every { isSet() } returns true }
            },
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = { emptyList() },
            availableExtensionsProvider = { listOf(extension) },
            installerFactory = { installer },
            installReceiverRegistrar = {},
            inventoryProvider = { ExtensionInventory(true) },
            scope = backgroundScope,
        )
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.installExtension(extension).collect {}
        }
        val model =
            screenModel(
                manager,
                Extensions(emptyList(), emptyList(), listOf(extension), emptyList()),
                androidExtensionPresentationStore,
            )
        try {
            model.installExtension(extension)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { model.state.first { it.installRequestBusy } }
            }
            verify(exactly = 1) { installer.downloadAndInstall(any(), any(), any(), any()) }
            assertTrue(manager.installArbiter.isBusy(extension.pkgName))
            assertTrue(model.state.value.installErrors.isEmpty())
            assertFalse(first.isCancelled)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { model.state.first { it.items.isNotEmpty() } }
            }
            assertEquals(
                eu.kanade.tachiyomi.extension.model.InstallStep.Downloading,
                model.state.value.items.values.flatten().single().installStep,
            )
            model.installExtension(extension)
            kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.delay(50) }
            verify(exactly = 1) { installer.downloadAndInstall(any(), any(), any(), any()) }
        } finally {
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            first.cancelAndJoin()
            io.mockk.unmockkStatic(androidx.core.app.NotificationManagerCompat::class)
            uy.kohesive.injekt.Injekt = previousInjekt
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `suggestion and ordinary install share active state and cannot submit twice`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val extension = available(
            "Suggested Reader",
            "pkg.suggested",
            listOf(Extension.Available.Source(71, "ja", "Source", "https://source.example")),
        )
            .copy(versionName = "1.6.1", libVersion = 1.6)
        val artifact = extension.toArtifact()
        val identity = mihon.domain.extension.suggestion.SuggestionIdentity.of(artifact)
        val suggestions = ExtensionSuggestions(
            false,
            listOf(
                mihon.domain.extension.suggestion.ExtensionSuggestion(
                    identity,
                    artifact,
                    artifact.sources.map { SuggestedSource(it, 2) },
                    false,
                ),
            ),
        )
        val gate = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { inventory } returns MutableStateFlow(ExtensionInventory(true))
            every {
                suggestionCatalog
            } returns MutableStateFlow(
                mihon.domain.extension.model.ExtensionCatalogResult(
                    listOf(
                        ExtensionCatalogEntry(
                            artifact,
                            ExtensionCompatibility.Compatible,
                        ),
                    ),
                    emptyList(),
                ),
            )
            every { installExtension(extension) } answers {
                calls.incrementAndGet()
                flow {
                    emit(eu.kanade.tachiyomi.extension.model.InstallStep.Downloading)
                    gate.await()
                    emit(eu.kanade.tachiyomi.extension.model.InstallStep.Error)
                }
            }
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), listOf(extension), emptyList()),
            androidExtensionPresentationStore,
            flowOf(suggestions),
        )
        try {
            kotlinx.coroutines.withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 1 } }
            model.installSuggestion(identity)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    model.state.first {
                        it.suggestionPanel.rows.single().step ==
                            ExtensionPresentationInstallStep.Downloading
                    }
                }
            }
            model.installSuggestion(identity)
            model.installExtension(extension)
            kotlinx.coroutines.withContext(Dispatchers.IO) { Thread.sleep(100) }
            assertEquals(1, calls.get())
            model.suggestionPanel.ignore(identity)
            assertFalse(model.state.value.suggestionPanel.rows.single().canIgnore)
            gate.complete(Unit)
        } finally {
            gate.complete(Unit)
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `suggestion diagnosis calls inventory recheck without catalog install or runtime reload`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { recheckInstalledInventory() } returns kotlinx.coroutines.Job().apply { complete() }
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
            androidExtensionPresentationStore,
        )
        try {
            model.recheckInstalledInventory().join()
            verify(exactly = 1) { manager.recheckInstalledInventory() }
            verify(exactly = 0) { manager.installExtension(any()) }
        } finally {
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `origin confirmation is observed and both dialog answers reach manager with exact request id`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val artifact = mihon.domain.extension.model.ExtensionArtifact(
            name = "Legacy", packageName = "pkg.legacy", versionName = "1.6.0", versionCode = 2,
            language = "en", isNsfw = false, sources = emptyList(),
            repository = mihon.domain.extension.model.RepositoryIdentity("https://repo.example", "Repo", "key"),
            downloadUrl = "https://repo.example/extension.apk", iconUrl = "", declaredSha256 = null,
        )
        val request = eu.kanade.tachiyomi.extension.util.ExtensionOriginConfirmation("transaction", artifact)
        val requests = MutableStateFlow(listOf(request))
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { originConfirmations } returns requests
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
            androidExtensionPresentationStore,
        )
        try {
            assertEquals(listOf(request), model.state.value.originConfirmations)
            model.answerOriginConfirmation(request.id, false)
            verify(exactly = 1) { manager.answerOriginConfirmation("transaction", false) }
            model.answerOriginConfirmation(request.id, true)
            verify(exactly = 1) { manager.answerOriginConfirmation("transaction", true) }
            requests.value = emptyList()
            assertTrue(model.state.value.originConfirmations.isEmpty())
        } finally {
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late error from an old screen collection cannot replace an active retry`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val extension = available("Reader", "pkg.reader", emptyList())
        val oldGate = CompletableDeferred<Unit>()
        val oldStarted = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val oldDone = CountDownLatch(1)
        val actionStore = spyk(androidExtensionPresentationStore)
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installExtension(extension) } returnsMany listOf(
                flow {
                    try {
                        oldStarted.complete(Unit)
                        emit(eu.kanade.tachiyomi.extension.model.InstallStep.Pending)
                        oldGate.await()
                        emit(eu.kanade.tachiyomi.extension.model.InstallStep.Error)
                    } finally {
                        oldDone.countDown()
                    }
                },
                flow {
                    emit(eu.kanade.tachiyomi.extension.model.InstallStep.Downloading)
                    newGate.await()
                    emit(eu.kanade.tachiyomi.extension.model.InstallStep.Installed)
                },
            )
        }
        val model =
            screenModel(manager, Extensions(emptyList(), emptyList(), listOf(extension), emptyList()), actionStore)
        try {
            model.installExtension(extension)
            verify(timeout = 5_000) {
                actionStore.reduce(
                    any(),
                    ExtensionPresentationAction.InstallStepChanged(
                        extension.pkgName,
                        ExtensionPresentationInstallStep.Pending,
                    ),
                )
            }
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { oldStarted.await() }
            }
            model.cancelInstallUpdateExtension(extension)
            model.installExtension(extension)
            verify(timeout = 5_000) {
                actionStore.reduce(
                    any(),
                    ExtensionPresentationAction.InstallStepChanged(
                        extension.pkgName,
                        ExtensionPresentationInstallStep.Downloading,
                    ),
                )
            }
            oldGate.complete(Unit)
            assertTrue(oldDone.await(5, TimeUnit.SECONDS))
            verify(exactly = 0) {
                actionStore.reduce(
                    any(),
                    ExtensionPresentationAction.InstallStepChanged(
                        extension.pkgName,
                        ExtensionPresentationInstallStep.Error,
                    ),
                )
            }
            verify(exactly = 1) {
                actionStore.reduce(any(), ExtensionPresentationAction.InstallFinished(extension.pkgName))
            }
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    model.state.first { state ->
                        state.items.values.flatten().any {
                            it.extension.pkgName == extension.pkgName &&
                                it.installStep == eu.kanade.tachiyomi.extension.model.InstallStep.Downloading
                        }
                    }
                }
            }
        } finally {
            oldGate.complete(Unit)
            newGate.complete(Unit)
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `android screen observes typed install failure and recovery from production manager contract`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val failure = AppError.Authentication()
        val errors = MutableStateFlow(mapOf("pkg.reader" to failure as AppError))
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installErrors } returns errors
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
            androidExtensionPresentationStore,
        )
        try {
            assertEquals(failure, model.state.value.installErrors["pkg.reader"])
            errors.value = emptyMap()
            assertTrue(model.state.value.installErrors.isEmpty())
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `android list subscribes to repository failure and recovery feedback`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val failure = mihon.domain.extension.model.RepositoryCatalogFailure(
            mihon.domain.extension.model.RepositoryIdentity("https://repo.example", "Reader repository", "key"),
            AppError.Network(),
        )
        val failures = MutableStateFlow(listOf(failure))
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { repositoryFailures } returns failures
        }
        val model =
            screenModel(
                manager,
                Extensions(emptyList(), emptyList(), emptyList(), emptyList()),
                androidExtensionPresentationStore,
            )
        try {
            assertEquals(listOf(failure), model.state.value.repositoryFailures)
            failures.value = emptyList()
            assertTrue(model.state.value.repositoryFailures.isEmpty())
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `android install collection stops at installed and cleans package state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val extension = available("Reader", "pkg.reader", emptyList())
        val collectedPastInstalled = AtomicBoolean(false)
        val actionStore = spyk(androidExtensionPresentationStore)
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installExtension(extension) } returns flow {
                emit(eu.kanade.tachiyomi.extension.model.InstallStep.Installing)
                emit(eu.kanade.tachiyomi.extension.model.InstallStep.Installed)
                collectedPastInstalled.set(true)
                emit(eu.kanade.tachiyomi.extension.model.InstallStep.Error)
            }
        }
        val screenModel = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), listOf(extension), emptyList()),
            actionStore,
        )
        try {
            screenModel.installExtension(extension)
            verify(timeout = 5_000) {
                actionStore.reduce(
                    match { it.installSteps[extension.pkgName] == ExtensionPresentationInstallStep.Installed },
                    ExtensionPresentationAction.InstallFinished(extension.pkgName),
                )
            }
            verify(timeout = 5_000) {
                actionStore.reduce(any(), ExtensionPresentationAction.RefreshStarted)
                actionStore.reduce(match { it.isRefreshing }, ExtensionPresentationAction.RefreshFinished)
            }
            verify { actionStore.shouldContinue(ExtensionPresentationInstallStep.Installed) }
            verify {
                actionStore.reduce(any(), match { it is ExtensionPresentationAction.InstallStepChanged })
            }
            assertFalse(collectedPastInstalled.get())
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `android details keep fixed main source actions and exit only after installed flow removal`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val zulu = source(3, "Zulu")
        val alpha = source(2, "alpha")
        val disabled = source(1, "Aardvark")
        val extension = installed("Reader", "pkg.reader", sources = listOf(disabled, alpha, zulu))
        val installedFlow = MutableStateFlow(listOf(extension))
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installedExtensionsFlow } returns installedFlow
        }
        val toggleSource = mockk<ToggleSource>(relaxed = true)
        val toggleIncognito = mockk<ToggleIncognito>(relaxed = true)
        val preferences = mockk<SourcePreferences> {
            every { incognitoExtensions() } returns preference(setOf(extension.pkgName))
        }
        val actionStore = spyk(androidExtensionPresentationStore)
        val details = modelHost.create {
            ExtensionDetailsScreenModel(
                extension.pkgName,
                mockk(relaxed = true),
                mockk<NetworkHelper>(relaxed = true),
                manager,
                mockk {
                    every { subscribe(extension) } returns flowOf(
                        listOf(
                            ExtensionSourceItem(disabled, false, true),
                            ExtensionSourceItem(alpha, true, true),
                            ExtensionSourceItem(zulu, true, true),
                        ),
                    )
                },
                toggleSource,
                toggleIncognito,
                preferences,
                actionStore,
            )
        }
        try {
            assertEquals(listOf(3L, 2L, 1L), details.state.value.sources.map { it.source.id })
            verify { actionStore.enabledFirst<ExtensionSourceItem>(any(), any(), any()) }
            details.toggleSource(2)
            details.toggleSources(false)
            details.toggleIncognito(true)
            val event = async(start = CoroutineStart.UNDISPATCHED) { details.events.first() }
            details.uninstallExtension()
            assertFalse(event.isCompleted)
            verify { toggleSource.await(2) }
            verify { toggleSource.await(listOf(1L, 2L, 3L), false) }
            verify { toggleIncognito.await(extension.pkgName, true) }
            verify { manager.uninstallExtension(extension) }
            assertTrue(installedFlow.value.isNotEmpty())
            installedFlow.value = emptyList()
            assertEquals(ExtensionDetailsEvent.Uninstalled, event.await())
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `android get extensions consumes shared classification and source projection`() = runTest {
        val update = installed("Update", "pkg.update", update = true)
        val obsolete = installed("Zulu obsolete", "pkg.obsolete", obsolete = true)
        val normal = installed("alpha normal", "pkg.installed")
        val nsfw = installed("Adult installed", "pkg.adult", nsfw = true)
        val untrustedZulu = untrusted("Zulu", "pkg.untrusted.zulu")
        val untrustedAlpha = untrusted("alpha", "pkg.untrusted.alpha")
        val bundle = available(
            "Bundle",
            "pkg.bundle",
            listOf(
                Extension.Available.Source(7, "en", "English source", "https://en.example"),
                Extension.Available.Source(8, "fr", "French source", "https://fr.example"),
            ),
        )
        val duplicateInstalled = available("Duplicate installed", normal.pkgName, emptyList())
        val duplicateUntrusted = available("Duplicate untrusted", untrustedAlpha.pkgName, emptyList())
        val preferences = mockk<SourcePreferences> {
            every { showNsfwSource() } returns preference(false)
            every { enabledLanguages() } returns preference(setOf("en"))
        }
        val manager = mockk<ExtensionManager> {
            every { installedExtensionsFlow } returns MutableStateFlow(listOf(normal, nsfw, update, obsolete))
            every { untrustedExtensionsFlow } returns MutableStateFlow(listOf(untrustedZulu, untrustedAlpha))
            every { availableExtensionsFlow } returns MutableStateFlow(
                listOf(bundle, duplicateInstalled, duplicateUntrusted),
            )
        }
        val classifier = spyk(androidExtensionPresentationStore)

        val result = GetExtensionsByType(preferences, manager, classifier).subscribe().first()

        assertEquals(listOf(update), result.updates)
        assertEquals(listOf(obsolete, normal), result.installed)
        assertFalse(nsfw in result.updates || nsfw in result.installed)
        assertEquals(listOf(untrustedAlpha, untrustedZulu), result.untrusted)
        assertFalse(result.available.any { it.pkgName == duplicateInstalled.pkgName })
        assertFalse(result.available.any { it.pkgName == duplicateUntrusted.pkgName })
        val synthetic = result.available.single()
        assertEquals("pkg.bundle", synthetic.pkgName)
        assertEquals("en", synthetic.lang)
        val source = synthetic.sources.single()
        assertEquals(
            listOf(7L, "English source", "en", "https://en.example"),
            listOf(source.id, source.name, source.lang, source.baseUrl),
        )
        assertEquals(
            listOf("1.0", 1L, 1.4, "pkg.bundle.apk", "https://repo.example", "Extension repo"),
            listOf(
                synthetic.versionName,
                synthetic.versionCode,
                synthetic.libVersion,
                synthetic.apkName,
                synthetic.repoUrl,
                synthetic.repoName,
            ),
        )
        verify(exactly = 1) { classifier.classify(any(), any(), any(), any()) }
    }

    @Test
    fun `projected language rows send the real package identity to install and cancel`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val bundle = available(
            "Bundle",
            "pkg.bundle",
            listOf(
                Extension.Available.Source(7, "en", "Reader", "https://en.example"),
                Extension.Available.Source(8, "fr", "Reader", "https://fr.example"),
            ),
        )
        val projected = androidExtensionPresentationStore.classify(
            emptyList(),
            emptyList(),
            listOf(bundle),
            mihon.domain.extension.presentation.ExtensionPresentationOptions(false, setOf("en", "fr")),
        ).available.filterIsInstance<Extension.Available>()
        val gate = CompletableDeferred<Unit>()
        val manager = mockk<ExtensionManager>(relaxed = true) {
            every { installArbiter } returns ExtensionInstallArbiter()
            every { installExtension(any()) } returns flow {
                emit(eu.kanade.tachiyomi.extension.model.InstallStep.Downloading)
                gate.await()
            }
        }
        val model = screenModel(
            manager,
            Extensions(emptyList(), emptyList(), projected, emptyList()),
            androidExtensionPresentationStore,
        )
        try {
            assertEquals(2, projected.size)
            assertEquals(2, projected.map { it.hashCode() }.distinct().size)
            for (row in projected) {
                model.installExtension(row)
                verify(timeout = 5_000) {
                    manager.installExtension(
                        match {
                            it.pkgName == bundle.pkgName && it.lang == row.lang &&
                                it.downloadUrl == bundle.downloadUrl && it.versionCode == bundle.versionCode
                        },
                    )
                }
                model.cancelInstallUpdateExtension(row)
                verify { manager.cancelInstallUpdateExtension(match { it.pkgName == bundle.pkgName }) }
            }
        } finally {
            gate.complete(Unit)
            val owner = model.screenModelScope.coroutineContext[kotlinx.coroutines.Job]
            modelHost.close()
            kotlinx.coroutines.withContext(Dispatchers.Default) { owner?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `android screen search consumes shared matcher and package search is opt in`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val httpSource = mockk<HttpSource> {
            every { id } returns 42L
            every { name } returns "Manga Hub"
            every { lang } returns "en"
            every { baseUrl } returns "https://reader.example"
        }
        val installed = installed("Installed Reader", "org.example.installed", sources = listOf(httpSource))
        val available = available(
            "Reader Plus",
            "org.example.reader",
            listOf(Extension.Available.Source(42, "en", "Manga Hub", "https://reader.example")),
        )
        val classifier = spyk(androidExtensionPresentationStore)
        val preferences = mockk<SourcePreferences> {
            every { extensionUpdatesCount() } returns preference(0)
        }
        val basePreferences = mockk<BasePreferences> {
            every { extensionInstaller() } returns mockk {
                every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
            }
        }
        val getExtensions = mockk<GetExtensionsByType> {
            every { subscribe() } returns flowOf(Extensions(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        val screenModel = modelHost.create {
            ExtensionsScreenModel(
                preferences,
                basePreferences,
                mockk(relaxed = true),
                getExtensions,
                classifier,
                mockk<Application>(relaxed = true),
            )
        }

        try {
            assertTrue(screenModel.searchQueryPredicate("installed reader")(installed))
            assertTrue(screenModel.searchQueryPredicate("manga hub")(installed))
            assertTrue(screenModel.searchQueryPredicate("reader.example")(installed))
            assertTrue(screenModel.searchQueryPredicate("42")(installed))
            assertFalse(screenModel.searchQueryPredicate("org.example")(available))
            assertTrue(screenModel.searchQueryPredicate("org.example", includePackageName = true)(available))
            verify { classifier.searchPredicate("org.example", false) }
            verify { classifier.searchPredicate("org.example", true) }
        } finally {
            modelHost.close()
            Dispatchers.resetMain()
        }
    }

    private fun installed(
        name: String,
        pkg: String,
        update: Boolean = false,
        obsolete: Boolean = false,
        nsfw: Boolean = false,
        sources: List<Source> = emptyList(),
    ) = Extension.Installed(
        name = name,
        pkgName = pkg,
        versionName = "1.0",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = nsfw,
        pkgFactory = null,
        sources = sources,
        icon = null,
        hasUpdate = update,
        isObsolete = obsolete,
        isShared = false,
    )

    private fun available(name: String, pkg: String, sources: List<Extension.Available.Source>) = Extension.Available(
        name = name,
        pkgName = pkg,
        versionName = "1.0",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        sources = sources,
        apkName = "$pkg.apk",
        iconUrl = "https://repo.example/icon/$pkg.png",
        repoUrl = "https://repo.example",
        repoName = "Extension repo",
    )

    private fun untrusted(name: String, pkg: String) =
        Extension.Untrusted(name, pkg, "1.0", 1, 1.4, "signature")

    private fun source(sourceId: Long, sourceName: String) = mockk<Source> {
        every { id } returns sourceId
        every { name } returns sourceName
        every { lang } returns "en"
    }

    private fun screenModel(
        manager: ExtensionManager,
        extensions: Extensions,
        actionStore: ExtensionPresentationStore<Extension>,
        suggestions: Flow<ExtensionSuggestions>? = null,
        batch: eu.kanade.tachiyomi.extension.AndroidExtensionSuggestionBatch? = null,
        showNsfw: Boolean = true,
    ): ExtensionsScreenModel {
        val preferences = mockk<SourcePreferences> {
            every { extensionUpdatesCount() } returns preference(0)
            every { showNsfwSource() } returns preference(showNsfw)
        }
        val basePreferences = mockk<BasePreferences> {
            every { extensionInstaller() } returns mockk {
                every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
            }
        }
        return modelHost.create {
            ExtensionsScreenModel(
                preferences,
                basePreferences,
                manager,
                mockk { every { subscribe() } returns flowOf(extensions) },
                androidExtensionPresentationStore,
                mockk(relaxed = true),
                actionStore,
                suggestions,
                suggestionBatch = batch,
            )
        }
    }

    private fun <T> preference(value: T) = mockk<Preference<T>> {
        every { get() } returns value
        every { changes() } returns flowOf(value)
    }
}
