package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
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
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsEvent
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreenModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.util.concurrent.atomic.AtomicBoolean

class ExtensionPresentationWiringTest {

    @Test
    fun `android install collection stops at installed and cleans package state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val extension = available("Reader", "pkg.reader", emptyList())
        val collectedPastInstalled = AtomicBoolean(false)
        val actionStore = spyk(androidExtensionPresentationStore)
        val manager = mockk<ExtensionManager>(relaxed = true) {
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
            screenModel.onDispose()
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
            every { installedExtensionsFlow } returns installedFlow
        }
        val toggleSource = mockk<ToggleSource>(relaxed = true)
        val toggleIncognito = mockk<ToggleIncognito>(relaxed = true)
        val preferences = mockk<SourcePreferences> {
            every { incognitoExtensions() } returns preference(setOf(extension.pkgName))
        }
        val actionStore = spyk(androidExtensionPresentationStore)
        val details = ExtensionDetailsScreenModel(
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
            details.onDispose()
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
        assertEquals("pkg.bundle-7", synthetic.pkgName)
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
        val screenModel = ExtensionsScreenModel(
            preferences,
            basePreferences,
            mockk(relaxed = true),
            getExtensions,
            classifier,
            mockk<Application>(relaxed = true),
        )

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
            screenModel.onDispose()
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
    ): ExtensionsScreenModel {
        val preferences = mockk<SourcePreferences> { every { extensionUpdatesCount() } returns preference(0) }
        val basePreferences = mockk<BasePreferences> {
            every { extensionInstaller() } returns mockk {
                every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
            }
        }
        return ExtensionsScreenModel(
            preferences,
            basePreferences,
            manager,
            mockk { every { subscribe() } returns flowOf(extensions) },
            androidExtensionPresentationStore,
            mockk(relaxed = true),
            actionStore,
        )
    }

    private fun <T> preference(value: T) = mockk<Preference<T>> {
        every { get() } returns value
        every { changes() } returns flowOf(value)
    }
}
