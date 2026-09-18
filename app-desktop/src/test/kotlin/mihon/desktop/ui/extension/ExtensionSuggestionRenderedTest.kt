package mihon.desktop.ui.extension

import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionInstallInvalidated
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionInstallArbiter
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.extension.model.*
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import java.util.Locale
import androidx.compose.runtime.CompositionLocalProvider
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import java.io.File
import java.util.prefs.Preferences
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.LocalExtensionScreenModel
import mihon.desktop.extension.DesktopExtensionPresentationService
import mihon.desktop.platform.DesktopUrlOpener
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.browse.BrowseSourceListScreen
import mihon.desktop.ui.migration.MigrationMangaScreen
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelState
import org.junit.jupiter.api.Assertions
import tachiyomi.core.common.preference.DesktopPreferenceStore
@OptIn(ExperimentalComposeUiApi::class)
class ExtensionSuggestionRenderedTest {
    @Test
    fun `batch shows each result and confirms only failed items for retry`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val artifacts = (1L..2L).map { id -> ExtensionArtifact(
            "Reader $id", "pkg.retry$id", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(id, "ja", "Source $id", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/$id.jar", "", null,
        ) }
        val catalog = ExtensionCatalogResult(artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) }, emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        coEvery { api.refreshCatalog() } returns catalog
        val attempted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val service = mockk<DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory.toFile()
            every { installExtensionStates(any(), any()) } answers {
                val artifact = firstArg<ExtensionArtifact>()
                val commit = secondArg<(() -> Unit)?>()
                kotlinx.coroutines.flow.flow {
                    attempted += artifact.packageName
                    if (artifact == artifacts.first() && attempted.count { it == artifact.packageName } == 1) {
                        emit(ExtensionInstallState.Failed(AppError.Network()))
                    } else {
                        commit?.invoke()
                        emit(ExtensionInstallState.Installed(artifact))
                    }
                }
            }
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(artifacts.map {
                Source(it.sources.single().id, "ja", it.name, false, true) to 1L
            })
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, service, inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())),
        )
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 2 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Install all suggestions")
            click(scene, "Install all suggestions")
            renderUntil(scene, "Confirm installations")
            click(scene, "Install selected (2)")
            withTimeout(5_000) { model.suggestionBatch.state.first { !it.running && it.completed == 2 } }
            renderUntil(scene, "Retry failed")
            assertTrue(texts(scene).any { it.contains("Failed") })
            assertTrue(texts(scene).any { it.contains("Installed") })
            val batchId = model.suggestionBatch.state.value.id
            click(scene, "Retry failed")
            renderUntil(scene, "Confirm installations")
            click(scene, "Install selected (1)")
            withTimeout(5_000) { model.suggestionBatch.state.first {
                !it.running && it.items.all { item -> item.result == mihon.domain.extension.suggestion.SuggestionBatchResult.Installed }
            } }
            Assertions.assertEquals(batchId, model.suggestionBatch.state.value.id)
            Assertions.assertEquals(listOf("pkg.retry1", "pkg.retry2", "pkg.retry1"), attempted.toList())
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `paused batch confirms its remaining snapshot and keeps its identity`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val artifacts = (1L..2L).map { id -> ExtensionArtifact(
            "Reader $id", "pkg.retry$id", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(id, "ja", "Source $id", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/$id.jar", "", null,
        ) }
        val catalog = ExtensionCatalogResult(artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) }, emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        var latestCatalog = catalog
        coEvery { api.refreshCatalog() } answers { latestCatalog }
        val attempted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val service = mockk<DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory.toFile()
            every { installExtensionStates(any(), any()) } answers {
                val artifact = firstArg<ExtensionArtifact>()
                val commit = secondArg<(() -> Unit)?>()
                kotlinx.coroutines.flow.flow {
                    attempted += artifact.packageName
                    if (artifact == artifacts.first() && attempted.count { it == artifact.packageName } == 1) {
                        emit(ExtensionInstallState.Failed(AppError.Unknown(ExtensionInstallInvalidated(mihon.domain.extension.service.ExtensionInstallInvalidation.CATALOG_CHANGED))))
                    } else {
                        commit?.invoke()
                        emit(ExtensionInstallState.Installed(artifact))
                    }
                }
            }
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(artifacts.map {
                Source(it.sources.single().id, "ja", it.name, false, true) to 1L
            })
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, service, inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())),
        )
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 2 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Install all suggestions")
            click(scene, "Install all suggestions")
            renderUntil(scene, "Confirm installations")
            click(scene, "Install selected (2)")
            withTimeout(5_000) { model.suggestionBatch.state.first { !it.running && it.remaining.size == 2 } }
            renderUntil(scene, "Review remaining")
            assertTrue(texts(scene).any { it.contains("0 of 2") })
            assertFalse(model.state.value.installErrors.values.any { it.cause is ExtensionInstallInvalidated }, "Do not expose internal invalidation enums as install errors")
            assertFalse(model.state.value.installSteps.values.any { it == mihon.domain.extension.presentation.ExtensionPresentationInstallStep.Error })
            val batchId = model.suggestionBatch.state.value.id
            latestCatalog = catalog.copy(entries = catalog.entries.map { it.copy(artifact = it.artifact.copy(versionCode = 2, versionName = "1.6.2")) })
            model.refresh().join()
            click(scene, "Review remaining")
            renderUntil(scene, "Confirm installations")
            renderUntil(scene, "Reader 1 · 1.6.2")
            click(scene, "Install selected (2)")
            withTimeout(5_000) { model.suggestionBatch.state.first {
                !it.running && it.items.all { item -> item.result == mihon.domain.extension.suggestion.SuggestionBatchResult.Installed }
            } }
            Assertions.assertEquals(batchId, model.suggestionBatch.state.value.id)
            assertTrue(model.suggestionBatch.state.value.items.all { it.artifact.versionCode == 2L })
            Assertions.assertEquals(listOf("pkg.retry1", "pkg.retry1", "pkg.retry2"), attempted.toList())
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `resume confirmation explains competing full artifacts and keeps the paused batch`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val artifacts = (1L..2L).map { id -> ExtensionArtifact(
            "Reader $id", "pkg.retry$id", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(id, "ja", "Source $id", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/$id.jar", "", null,
        ) }
        val catalog = ExtensionCatalogResult(artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) }, emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        var latestCatalog = catalog
        coEvery { api.refreshCatalog() } answers { latestCatalog }
        val attempted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val service = mockk<DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory.toFile()
            every { installExtensionStates(any(), any()) } answers {
                val artifact = firstArg<ExtensionArtifact>()
                val commit = secondArg<(() -> Unit)?>()
                kotlinx.coroutines.flow.flow {
                    attempted += artifact.packageName
                    if (artifact == artifacts.first() && attempted.count { it == artifact.packageName } == 1) {
                        emit(ExtensionInstallState.Failed(AppError.Unknown(ExtensionInstallInvalidated(mihon.domain.extension.service.ExtensionInstallInvalidation.CATALOG_CHANGED))))
                    } else {
                        commit?.invoke()
                        emit(ExtensionInstallState.Installed(artifact))
                    }
                }
            }
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(artifacts.map {
                Source(it.sources.single().id, "ja", it.name, false, true) to 1L
            })
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, service, inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())),
        )
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 2 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Install all suggestions")
            click(scene, "Install all suggestions")
            renderUntil(scene, "Confirm installations")
            click(scene, "Install selected (2)")
            withTimeout(5_000) { model.suggestionBatch.state.first { !it.running && it.remaining.size == 2 } }
            renderUntil(scene, "Review remaining")
            assertTrue(texts(scene).any { it.contains("0 of 2") })
            val batchId = model.suggestionBatch.state.value.id
            latestCatalog = catalog.copy(entries = catalog.entries.map { it.copy(artifact = it.artifact.copy(versionCode = 2, versionName = "1.6.2", sources = artifacts.first().sources)) })
            model.refresh().join()
            click(scene, "Review remaining")
            renderUntil(scene, "Confirm installations")
            renderUntil(scene, "Reader 1 · 1.6.2")
            renderUntil(scene, "provide the same source")
            val install = nodes(scene).first { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Install selected (2)" }
                    && node.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(install.config.contains(SemanticsProperties.Disabled))
            Assertions.assertEquals(batchId, model.suggestionBatch.state.value.id)
            Assertions.assertEquals(listOf("pkg.retry1"), attempted.toList())
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `removed repository requires explicit replacement in resume confirmation`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val artifacts = (1L..2L).map { id -> ExtensionArtifact(
            "Reader $id", "pkg.retry$id", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(id, "ja", "Source $id", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/$id.jar", "", null,
        ) }
        val catalog = ExtensionCatalogResult(artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) }, emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        var latestCatalog = catalog
        coEvery { api.refreshCatalog() } answers { latestCatalog }
        val attempted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val service = mockk<DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory.toFile()
            every { installExtensionStates(any(), any()) } answers {
                val artifact = firstArg<ExtensionArtifact>()
                val commit = secondArg<(() -> Unit)?>()
                kotlinx.coroutines.flow.flow {
                    attempted += artifact.packageName
                    if (artifact == artifacts.first() && attempted.count { it == artifact.packageName } == 1) {
                        emit(ExtensionInstallState.Failed(AppError.Unknown(ExtensionInstallInvalidated(mihon.domain.extension.service.ExtensionInstallInvalidation.CATALOG_CHANGED))))
                    } else {
                        commit?.invoke()
                        emit(ExtensionInstallState.Installed(artifact))
                    }
                }
            }
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(artifacts.map {
                Source(it.sources.single().id, "ja", it.name, false, true) to 1L
            })
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, service, inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())),
        )
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 2 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Install all suggestions")
            click(scene, "Install all suggestions")
            renderUntil(scene, "Confirm installations")
            click(scene, "Install selected (2)")
            withTimeout(5_000) { model.suggestionBatch.state.first { !it.running && it.remaining.size == 2 } }
            renderUntil(scene, "Review remaining")
            assertTrue(texts(scene).any { it.contains("0 of 2") })
            val batchId = model.suggestionBatch.state.value.id
            latestCatalog = catalog.copy(entries = catalog.entries.map { it.copy(artifact = it.artifact.copy(versionCode = 2, versionName = "1.6.2", repository = it.artifact.repository.copy(baseUrl = "https://replacement.example", signingKeyFingerprint = "replacement-key"))) })
            model.refresh().join()
            click(scene, "Review remaining")
            renderUntil(scene, "Confirm installations")
            renderUntil(scene, "Use this repository")
            click(scene, "Use this repository")
            withTimeout(5_000) { while (texts(scene).count { it == "Use this repository" } != 1) { scene.render(); yield() } }
            click(scene, "Use this repository")
            withTimeout(5_000) { while (texts(scene).any { it == "Use this repository" }) { scene.render(); yield() } }
            renderUntil(scene, "Reader 1 · 1.6.2")
            renderUntil(scene, "Reader 2 · 1.6.2")
            click(scene, "Install selected (2)")
            val completed = kotlinx.coroutines.withTimeoutOrNull(5_000) { model.suggestionBatch.state.first {
                !it.running && it.items.all { item -> item.result == mihon.domain.extension.suggestion.SuggestionBatchResult.Installed }
            } }
            assertTrue(completed != null, "Batch did not install explicit replacements: ${model.suggestionBatch.state.value}; attempted=$attempted")
            Assertions.assertEquals(batchId, model.suggestionBatch.state.value.id)
            assertTrue(model.suggestionBatch.state.value.items.all { it.artifact.versionCode == 2L && it.artifact.repository.signingKeyFingerprint == "replacement-key" })
            Assertions.assertEquals(listOf("pkg.retry1", "pkg.retry1", "pkg.retry2"), attempted.toList())
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `search confirmation starts only its snapshot and collapsed progress survives page recreation`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val artifacts = (1L..2L).map { id -> ExtensionArtifact("Reader $id", "pkg.reader$id", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(id, "ja", "Source $id", "https://source.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"), "https://repo.example/$id.jar", "", null) }
        val catalog = ExtensionCatalogResult(artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) }, emptyList())
        val api = io.mockk.spyk(DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json,
            mihon.desktop.domain.fakes.FakeExtensionRepoRepository()))
        coEvery { api.refreshCatalog() } returns catalog
        val entered = kotlinx.coroutines.CompletableDeferred<ExtensionArtifact>()
        val service = mockk<DesktopExtensionPresentationService> {
            every { installedExtensions } returns MutableStateFlow(emptyList())
            every { extensionsDirectory } returns directory.toFile()
            every { installExtensionStates(any(), any()) } answers {
                val artifact = firstArg<ExtensionArtifact>()
                kotlinx.coroutines.flow.flow {
                    entered.complete(artifact)
                    emit(ExtensionInstallState.Preparing)
                    kotlinx.coroutines.awaitCancellation()
                }
            }
        }
        val sources = mockk<SourceRepository> { every { getSourcesWithFavoriteCount() } returns flowOf(
            artifacts.map { Source(it.sources.single().id, "ja", it.name, false, true) to 1L }) }
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, service, inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())))
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        var scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 2 } }
            model.search("Reader 1")
            withTimeout(5_000) { model.state.first { it.suggestionPanel.rows.size == 1 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Install matching")
            click(scene, "Install matching")
            renderUntil(scene, "Confirm installations")
            assertTrue(texts(scene).any { it.contains("https://repo.example") })
            click(scene, "Install selected (1)")
            Assertions.assertEquals(artifacts.first(), withTimeout(5_000) { entered.await() })
            click(scene, "Suggested installations")
            renderUntil(scene, "Stop remaining")
            scene.close()
            scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Stop remaining")
            assertTrue(model.suggestionBatch.state.value.running)
            click(scene, "Stop remaining")
            withTimeout(5_000) { model.suggestionBatch.state.first { !it.running } }
            Assertions.assertEquals(listOf(artifacts.first()), model.suggestionBatch.state.value.items.map { it.artifact })
            Assertions.assertEquals(mihon.domain.extension.suggestion.SuggestionBatchResult.Stopped,
                model.suggestionBatch.state.value.items.single().result)
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `installed page renders keyboard actions collapse ignore and undo at narrow width`() = runBlocking {
        val artifact = ExtensionArtifact("Suggested Reader with a long extension name", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(ExtensionSourceDescriptor(71, "ja", "My source", "https://source.example"),
                ExtensionSourceDescriptor(72, "en", "Another long source name", "https://another.example")),
            RepositoryIdentity("https://repo.example", "Repository", "key"), "https://repo.example/a.apk", "", null)
        val api = mockk<DesktopExtensionApi> {
            io.mockk.every { installArbiter } returns ExtensionInstallArbiter()
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList())
            every { availableExtensions(any()) } returns emptyList()
        }
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(
                Source(71, "ja", "My source", false, true) to 3L,
                Source(72, "en", "Another long source name", false, true) to 3L))
        }
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, mockk(), MutableStateFlow(emptyList()),
                inventory = flowOf(ExtensionInventory(true))),
            this, ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, FakeDesktopSourceManager(emptyList())),
        )
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            model.refresh().join()
            withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 1 } }
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            renderUntil(scene, "Suggested Reader")
            assertTrue(texts(scene).any { it.contains("6") })
            System.getenv("EIS_PREVIEW_PATH")?.let { path ->
                val image = scene.render()
                File(path).writeBytes(requireNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)
            }
            click(scene, "Ignore")
            renderUntil(scene, "Undo")
            assertFalse(texts(scene).any { it.contains("Suggested Reader") })
            click(scene, "Undo")
            renderUntil(scene, "Suggested Reader")
            click(scene, "Suggested installations")
            withTimeout(5_000) {
                while (model.state.value.suggestionPanel.expanded) { scene.render(); yield() }
            }
            withTimeout(5_000) {
                while (texts(scene).any { it.contains("Suggested Reader") }) { scene.render(); yield() }
            }
            assertFalse(texts(scene).any { it.contains("Suggested Reader") })
            assertFalse(model.state.value.suggestionPanel.expanded)
        } finally {
            scene.close()
            model.closeAndJoin()
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `candidate dialog distinguishes identical repository names and signing identities`() = runBlocking {
        val source = ExtensionSourceDescriptor(71, "ja", "Source", "https://source.example")
        fun candidate(key: String): ExtensionSuggestion {
            val artifact = ExtensionArtifact("Reader", "pkg.reader", "1.6.1", 1, "ja", false,
                listOf(source), RepositoryIdentity("https://repo.example", "Same repository", key),
                "https://repo.example/a.apk", "", null)
            return ExtensionSuggestion(
                SuggestionIdentity.of(artifact), artifact,
                listOf(SuggestedSource(source, 1)), true)
        }
        val candidates = listOf(candidate("first-signing-key"), candidate("second-signing-key"))
        val state = SuggestionPanelState(loading = false,
            total = 2, choices = mapOf(71L to candidates))
        val scene = ImageComposeScene(360, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { MaterialTheme {
                ExtensionSuggestionSection(state, null, {}, {}, {}, {}, {}, {})
            } }
            renderUntil(scene, "Choose")
            click(scene, "Choose")
            renderUntil(scene, "Same repository")
            assertTrue(texts(scene).any { it.contains("https://repo.example") })
            assertTrue(texts(scene).any { it.contains("first-signing-key") })
            assertTrue(texts(scene).any { it.contains("second-signing-key") })
        } finally { scene.close() }
    }

    @Test
    fun `actual Browse extension entry opens website repositories and migration and keeps diagnosis visible`() = runBlocking {
        val testModeBefore = mihon.desktop.test.state.applicationState.testMode
        mihon.desktop.test.state.applicationState.testMode = true
        val source = ExtensionSourceDescriptor(71, "ja", "My source", "https://source.example")
        val artifact = ExtensionArtifact("Suggested Reader", "pkg.reader", "1.6.1", 1, "ja", false,
            listOf(source), RepositoryIdentity("https://repo.example", "Repository", "key"),
            "https://repo.example/a.apk", "", null)
        val api = mockk<DesktopExtensionApi> {
            io.mockk.every { installArbiter } returns ExtensionInstallArbiter()
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(
                listOf(ExtensionCatalogEntry(artifact, ExtensionCompatibility.Compatible)), emptyList())
            every { availableExtensions(any()) } returns emptyList()
        }
        val sourceManager = FakeDesktopSourceManager(emptyList())
        val sources = mockk<SourceRepository> {
            every { getSourcesWithFavoriteCount() } returns flowOf(listOf(
                Source(71, "ja", "My source", false, true) to 3L,
                Source(72, "en", "Unmatched source", false, true) to 2L))
        }
        val inventory = MutableStateFlow(ExtensionInventory(true))
        val service = mockk<DesktopExtensionPresentationService>(relaxed = true)
        val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, service,
            MutableStateFlow(emptyList()), inventory = inventory), this,
            ExtensionPresentationOptions(true, setOf("en")),
            suggestionObserver = ObserveExtensionSuggestions(sources, sourceManager))
        val preferenceRoot = Preferences.userRoot().node("/mihon/eis02-browse/${System.nanoTime()}")
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.sourceManager } returns sourceManager
            every { appPreferences } returns DesktopAppPreferences(
                DesktopPreferenceStore(preferenceRoot))
        }
        val screen = BrowseSourceListScreen()
        lateinit var navigator: Navigator
        val scene = ImageComposeScene(600, 1000, coroutineContext = coroutineContext) {}
        io.mockk.mockkObject(DesktopUrlOpener)
        every { DesktopUrlOpener.open(any(), any()) } returns Result.success(Unit)
        try {
            model.refresh().join()
            val testController = mihon.desktop.test.http.SourceExtensionTestModeController(model)
            assertTrue(testController.execute("extension_suggestion_show").success)
            val beforeMount = kotlinx.serialization.json.Json.encodeToJsonElement(
                mihon.desktop.test.http.SourceExtensionTestSnapshot.serializer(), testController.snapshot(),
            ) as kotlinx.serialization.json.JsonObject
            Assertions.assertEquals(kotlinx.serialization.json.JsonNull, beforeMount.getValue("displayedRequestId"))
            scene.setContent { MaterialTheme {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalExtensionScreenModel provides { model },
                ) {
                    Navigator(screen) { nav ->
                        navigator = nav
                        screen.Content()
                    }
                }
            } }
            renderUntil(scene, "Suggested Reader")
            val displayed = kotlinx.serialization.json.Json.encodeToJsonElement(
                mihon.desktop.test.http.SourceExtensionTestSnapshot.serializer(), testController.snapshot(),
            ) as kotlinx.serialization.json.JsonObject
            assertTrue(displayed.getValue("displayedRequestId") != kotlinx.serialization.json.JsonNull)
            click(scene, "Open website")
            io.mockk.verify(exactly = 1) {
                DesktopUrlOpener.open("https://source.example", any())
            }
            click(scene, "Extension repos")
            assertTrue(navigator.lastItem is ExtensionRepoScreen)
            navigator.pop()
            scene.render()
            nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
                .config[SemanticsActions.ScrollBy].action!!.invoke(0f, 300f)
            renderUntil(scene, "Migrate")
            click(scene, "Migrate")
            val migration = navigator.lastItem
            if (navigator.items.size > 1) navigator.pop()
            inventory.value = ExtensionInventory(initialized = true, hasUnknownArtifacts = true)
            renderUntil(scene, "Review installed")
            click(scene, "Review installed")
            renderUntil(scene, "Installed extensions checked.")
            Assertions.assertAll(
                { assertTrue(migration is MigrationMangaScreen) },
                { assertFalse(migration is Tab) },
                { assertTrue(model.state.value.suggestionPanel.expanded) },
                { assertTrue(texts(scene).any { it.contains("could not be verified") }) },
            )
            io.mockk.coVerify(exactly = 1) { service.reloadAll() }
        } finally {
            scene.close()
            model.closeAndJoin()
            io.mockk.unmockkObject(DesktopUrlOpener)
            preferenceRoot.removeNode()
            mihon.desktop.test.state.applicationState.testMode = testModeBefore
            mihon.desktop.test.navigation.TestNavigationController.reset()
        }
    }

    @Test
    fun `actual Browse batch downloads authenticated fixture and reloads its source`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/extensions/real/aex00-external-v15-suspend-only.jar")).use { it.readBytes() }
        val expectedDigest = "ffcaad5974329a319e20b3cabd8565117f23668315bddac5ab72eae819e74fcf"
        val signer = "9be8a18439915033e8362f25426323e8b7b94f223eadca4962ce5f91a23d6021"
        val packageName = "aex00.external.v15"
        Assertions.assertEquals(expectedDigest, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        mockwebserver3.MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().removeSuffix("/")
            server.enqueue(mockwebserver3.MockResponse(body = """{
                "name":"Controlled", "badgeLabel":"C", "signingKey":"$signer",
                "contact":{"website":"https://fixture.example","discord":null},
                "extensionList":{"extensions":[{
                  "name":"Controlled signed reader","packageName":"$packageName",
                  "resources":{"apkUrl":"$base/fixture.jar","jarUrl":"$base/fixture.jar","iconUrl":""},
                  "extensionLib":"1.5","versionCode":150,"versionName":"1.5.0",
                  "contentWarning":"CONTENT_WARNING_SAFE",
                  "sources":[{"id":11403285,"name":"Controlled source","language":"en","homeUrl":"https://fixture.example"}]
                }]}
            }"""))
            server.enqueue(mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(bytes)).build())
            val repositories = mihon.desktop.domain.fakes.FakeExtensionRepoRepository().apply {
                insertRepo(base, "Controlled", null, "https://fixture.example", signer)
            }
            val api = DesktopExtensionApi(okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, repositories)
            val manager = mihon.desktop.extension.DesktopExtensionManager(
                loader = mihon.desktop.extension.DesktopExtensionLoader(directory.toFile()),
                artifactProvider = api::downloadArtifact,
            )
            manager.loadAll()
            val sourceManager = FakeDesktopSourceManager(emptyList())
            val sources = mockk<SourceRepository> {
                every { getSourcesWithFavoriteCount() } returns flowOf(listOf(Source(0xAE0015L, "en", "Controlled source", false, true) to 1L))
            }
            val model = ExtensionsScreenModel(DesktopExtensionPresentationPort(api, manager, inventory = manager.inventory),
                this, ExtensionPresentationOptions(true, setOf("en")),
                suggestionObserver = ObserveExtensionSuggestions(sources, sourceManager))
            val root = Preferences.userRoot().node("/mihon/eis03-pipeline/${System.nanoTime()}")
            val dependencies = mockk<DesktopUiDependencies> {
                every { this@mockk.sourceManager } returns sourceManager
                every { extensionApi } returns api
                every { appPreferences } returns DesktopAppPreferences(DesktopPreferenceStore(root))
            }
            val locale = Locale.getDefault()
            Locale.setDefault(Locale.US)
            val scene = ImageComposeScene(600, 1000, coroutineContext = coroutineContext) {}
            try {
                model.refresh().join()
                withTimeout(5_000) { model.state.first { it.suggestionPanel.total == 1 } }
                val browse = BrowseSourceListScreen()
                scene.setContent { MaterialTheme {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, LocalExtensionScreenModel provides { model }) {
                        Navigator(browse) { browse.Content() }
                    }
                } }
                renderUntil(scene, "Extensions")
                click(scene, "Extensions")
                renderUntil(scene, "Install all suggestions")
                click(scene, "Install all suggestions")
                renderUntil(scene, "Confirm installations")
                click(scene, "Install selected (1)")
                withTimeout(10_000) { model.suggestionBatch.state.first { !it.running && it.completed == 1 } }
                Assertions.assertEquals(mihon.domain.extension.suggestion.SuggestionBatchResult.Installed, model.suggestionBatch.state.value.items.single().result)
                renderUntil(scene, "1 of 1 installations completed")
                withTimeout(5_000) { model.state.first { it.suggestionPanel.rows.isEmpty() } }
                Assertions.assertEquals("AEX-00 v1.5 suspend-only fixture", manager.getSource(0xAE0015L)?.name)
                val installed = directory.resolve("$packageName.jar").toFile()
                Assertions.assertArrayEquals(bytes, installed.readBytes())
                val metadata = requireNotNull(mihon.desktop.extension.readExtensionMeta(installed))
                Assertions.assertEquals(signer, metadata.repoFingerprint)
                Assertions.assertEquals(base, metadata.repoUrl)
                Assertions.assertEquals(expectedDigest, metadata.artifactSha256)
                Assertions.assertEquals(2, server.requestCount)
                assertFalse(api.installArbiter.isBusy(packageName))
            } finally {
                scene.close()
                model.closeAndJoin()
                manager.close()
                root.removeNode()
                Locale.setDefault(locale)
            }
        }
    }

    private suspend fun renderUntil(scene: ImageComposeScene, text: String) {
        val found = kotlinx.coroutines.withTimeoutOrNull(5_000) {
            while (texts(scene).none { it.contains(text) }) { scene.render(); yield() }
            true
        }
        assertTrue(found == true, "Missing rendered text '$text'; actual: ${texts(scene)}")
    }
    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains(text) }
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap {
        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
