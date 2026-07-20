package mihon.desktop.ui.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.desktop.source.FakeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.coroutines.CoroutineContext

internal fun sourceBrowseHistoryPreferences() = mockk<DesktopAppPreferences> {
    every { incognitoMode } returns sourceBrowseIncognitoPreference()
    every { incognitoExtensions } returns sourceBrowseExtensionIncognitoPreference()
    every { lastUsedSource } returns sourceBrowseLastUsedPreference()
}
internal fun sourceBrowseIncognitoPreference() = mockk<tachiyomi.core.common.preference.Preference<Boolean>>(relaxed = true) {
    every { get() } returns false
}

internal fun sourceBrowseExtensionIncognitoPreference() =
    mockk<tachiyomi.core.common.preference.Preference<Set<String>>>(relaxed = true) { every { get() } returns emptySet() }
internal fun sourceBrowseLastUsedPreference() = mockk<tachiyomi.core.common.preference.Preference<Long>>(relaxed = true)
internal fun sourceBrowseExtensionManager() = mockk<DesktopExtensionManager>(relaxed = true)

@OptIn(ExperimentalComposeUiApi::class)
class SourceLastUsedWiringTest {
    @Test
    fun `global incognito short circuits before extension package lookup`() {
        val root = Preferences.userRoot().node("/mihon/source-last-used-short-circuit/${System.nanoTime()}")
        val store = DesktopPreferenceStore(root)
        val preferences = DesktopAppPreferences(store).apply { incognitoMode.set(true) }
        val extensionManager = mockk<DesktopExtensionManager>(relaxed = true)

        try {
            DesktopSourceLastUsedRecorder(preferences, extensionManager).record(101L)

            verify(exactly = 0) { extensionManager.getExtensionPackage(any()) }
            assertEquals(-1L, preferences.lastUsedSource.get())
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `real navigation records last used outside incognito and the same mounted list reorders reactively`() = runBlocking {
        val root = Preferences.userRoot().node("/mihon/source-last-used/${System.nanoTime()}")
        val store = DesktopPreferenceStore(root)
        val preferences = DesktopAppPreferences(store).apply { enabledLanguages.set(setOf("en")) }
        val lastUsed = store.getLong(Preference.appStateKey("last_catalogue_source"), -1L)
        val alpha = FakeSource(201, "en", "Alpha projection source")
        val zeta = FakeSource(202, "en", "Zeta projection source")
        val mounted = mountedScene(preferences, listOf(alpha, zeta), coroutineContext)
        val scene = mounted.scene

        try {
            assertEquals(-1L, lastUsed.get())
            awaitRows(scene, listOf(alpha.name, zeta.name))
            assertTrue(
                texts(scene).contains(
                    DesktopSourceGroupLabeler.displayName(DesktopSourceGroupKey.Language("en"), Locale.getDefault()),
                ),
            )

            clickSource(scene, zeta.name)
            awaitBrowseDetails(mounted, zeta.name)
            awaitRecorderOutcome(mounted) { mounted.extensionLookups.get() > 0 && lastUsed.get() == zeta.id }
            assertEquals(zeta.id, lastUsed.get())
            val back = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("Back") }
            assertTrue(requireNotNull(back.config[SemanticsActions.OnClick].action).invoke())
            awaitRows(scene, listOf(zeta.name, alpha.name, zeta.name))
            assertTrue(rendered(scene).contains(MR.strings.last_used_source.localized()))

            lastUsed.set(alpha.id)
            awaitRows(scene, listOf(alpha.name, alpha.name, zeta.name))
        } finally {
            scene.close()
            root.removeNode()
        }
    }

    @Test
    fun `real navigation records last used except for matching global or extension incognito`() = runBlocking {
        listOf(
            LastUsedScenario(true, emptySet(), null, shouldRecord = false),
            LastUsedScenario(false, setOf("incognito.extension"), "incognito.extension", shouldRecord = false),
            LastUsedScenario(false, setOf("other.extension"), "current.extension", shouldRecord = true),
            LastUsedScenario(false, setOf("other.extension"), null, shouldRecord = true),
        ).forEachIndexed { index, scenario ->
            val root = Preferences.userRoot().node("/mihon/source-last-used-incognito/$index-${System.nanoTime()}")
            val store = DesktopPreferenceStore(root)
            val preferences = DesktopAppPreferences(store).apply {
                enabledLanguages.set(setOf("en"))
                incognitoMode.set(scenario.globalIncognito)
                incognitoExtensions.set(scenario.incognitoExtensions)
            }
            val lastUsed = store.getLong(Preference.appStateKey("last_catalogue_source"), -1L).apply { set(301L) }
            val source = FakeSource(302 + index.toLong(), "en", "Incognito projection source $index")
            val mounted = mountedScene(preferences, listOf(source), coroutineContext, scenario.extensionPackage)
            try {
                clickSource(mounted.scene, source.name)
                val expected = if (scenario.shouldRecord) source.id else 301L
                awaitBrowseDetails(mounted, source.name)
                if (!scenario.globalIncognito) {
                    awaitRecorderOutcome(mounted) {
                        mounted.extensionLookups.get() > 0 && (!scenario.shouldRecord || lastUsed.get() == expected)
                    }
                }
                if (scenario.globalIncognito) {
                    assertEquals(0, mounted.extensionLookups.get())
                } else {
                    assertTrue(mounted.extensionLookups.get() > 0)
                }
                assertEquals(expected, lastUsed.get())
            } finally {
                mounted.scene.close()
                root.removeNode()
            }
        }
    }

    private fun mountedScene(
        preferences: DesktopAppPreferences,
        sources: List<FakeSource>,
        coroutineContext: CoroutineContext,
        extensionPackage: String? = null,
    ): MountedBrowseScene {
        val sourceManager = FakeDesktopSourceManager(sources)
        val extensionLookups = AtomicInteger()
        val extensionManager = mockk<DesktopExtensionManager> {
            every { getExtensionPackage(any()) } answers {
                extensionLookups.incrementAndGet()
                extensionPackage
            }
        }
        val saver = mockk<SaveSourceMangaForDetails> {
            coEvery { awaitSearchResults(any(), any()) } returns emptyList()
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { this@mockk.sourceManager } returns sourceManager
            every { appPreferences } returns preferences
            every { sourceMangaSearchService } returns SourceMangaSearchService()
            every { saveSourceMangaForDetails } returns saver
            every { getManga } returns mockk<GetManga> {
                every { subscribe(any<String>(), any<Long>()) } returns flowOf(null)
            }
            every { this@mockk.extensionManager } returns extensionManager
            every { sourceLoginSessionFactory } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(900, 1_200, coroutineContext = coroutineContext) {}.also { scene ->
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(BrowseSourceListScreen()) { CurrentScreen() }
                }
            }
            scene.render()
        }
        return MountedBrowseScene(scene, extensionLookups)
    }

    private suspend fun awaitBrowseDetails(
        mounted: MountedBrowseScene,
        sourceName: String,
    ) = withTimeout(2_000) {
        while (!texts(mounted.scene).contains(sourceName) || !hasBackAction(mounted.scene)) {
            mounted.scene.render()
            delay(10)
        }
    }

    private suspend fun awaitRecorderOutcome(mounted: MountedBrowseScene, expected: () -> Boolean) =
        withTimeout(2_000) {
            while (!expected()) {
                mounted.scene.render()
                delay(10)
            }
        }

    private suspend fun awaitRows(scene: ImageComposeScene, expected: List<String>) = withTimeout(5_000) {
        while (sourceRows(scene, expected.toSet()) != expected) {
            scene.render()
            delay(10)
        }
    }

    private fun clickSource(scene: ImageComposeScene, name: String) {
        val node = nodes(scene).first {
            it.config.contains(SemanticsActions.OnLongClick) && it.config.toString().contains(name)
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun sourceRows(scene: ImageComposeScene, names: Set<String>): List<String> = nodes(scene)
        .filter { it.config.contains(SemanticsActions.OnLongClick) }
        .mapNotNull { node -> names.firstOrNull { node.config.toString().contains(it) } }

    private fun rendered(scene: ImageComposeScene): String = nodes(scene).joinToString { it.config.toString() }

    private fun hasBackAction(scene: ImageComposeScene): Boolean = nodes(scene).any {
        it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains("Back")
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        flatten(owner.rootSemanticsNode)
    }

    private data class MountedBrowseScene(
        val scene: ImageComposeScene,
        val extensionLookups: AtomicInteger,
    )

    private data class LastUsedScenario(
        val globalIncognito: Boolean,
        val incognitoExtensions: Set<String>,
        val extensionPackage: String?,
        val shouldRecord: Boolean,
    )
}
