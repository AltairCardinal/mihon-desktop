package mihon.domain.extension

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.ExtensionSuggestionPanel
import mihon.domain.extension.suggestion.ExtensionSuggestionPreferences
import mihon.domain.extension.suggestion.ExtensionSuggestions
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import eu.kanade.tachiyomi.source.Source as QuerySource
class ExtensionSuggestionPanelTest {
    @Test
    fun `a busy complete artifact blocks changing a conflicting source provider`() = runTest {
        val original = suggestion()
        val source = original.sources.single()
        val extra = source.copy(source = source.source.copy(id = source.source.id + 1))
        val a = original.copy(
            artifact = original.artifact.copy(sources = listOf(source.source, extra.source)),
            sources = listOf(source, extra),
        )
        val b = suggestion("pkg.b")
        val steps = MutableStateFlow(emptyMap<String, ExtensionPresentationInstallStep>())
        val panel = ExtensionSuggestionPanel(
            backgroundScope,
            MutableStateFlow(
                ExtensionSuggestions(
                    false,
                    listOf(a, b),
                    mapOf(source.source.id to listOf(a.identity, b.identity)),
                ),
            ),
            steps,
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        )
        runCurrent()
        panel.choose(source.source.id, a.identity)
        runCurrent()
        steps.value = mapOf(a.artifact.packageName to ExtensionPresentationInstallStep.Installing)
        runCurrent()
        panel.choose(source.source.id, b.identity)
        runCurrent()
        assertEquals(a.identity, panel.state.value.selected[source.source.id])
        assertNull(panel.installable(b.identity))
        assertFalse(panel.state.value.rows.single().canIgnore)
    }

    @Test
    fun `overlapping multi source choices allocate each source once and only expose associated websites`() = runTest {
        val first = suggestion()
        val s1 = first.sources.single()
        val s2 = s1.copy(source = s1.source.copy(id = s1.source.id + 1, name = "Second"), count = 5)
        val a = first.copy(
            artifact = first.artifact.copy(sources = listOf(s1.source, s2.source)),
            sources = listOf(s1, s2),
        )
        val b = suggestion("pkg.b")
        val cBase = suggestion("pkg.c")
        val c = cBase.copy(artifact = cBase.artifact.copy(sources = listOf(s2.source)), sources = listOf(s2))
        val panel = ExtensionSuggestionPanel(
            backgroundScope,
            MutableStateFlow(
                ExtensionSuggestions(
                    false,
                    listOf(a, b, c),
                    mapOf(
                        s1.source.id to listOf(a.identity, b.identity),
                        s2.source.id to listOf(a.identity, c.identity),
                    ),
                ),
            ),
            MutableStateFlow(emptyMap()),
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        )
        runCurrent()
        panel.choose(s1.source.id, b.identity)
        panel.choose(s2.source.id, c.identity)
        runCurrent()
        assertEquals(setOf("pkg.b", "pkg.c"), panel.state.value.rows.map { it.suggestion.artifact.packageName }.toSet())
        val selectedSources = panel.state.value.rows.filter { it.canInstall }.flatMap { it.suggestion.sources }
        assertEquals(2, selectedSources.size)
        assertEquals(2, selectedSources.map { it.source.id }.distinct().size)
        panel.choose(s1.source.id, a.identity)
        runCurrent()
        assertFalse(panel.state.value.selected.containsKey(s2.source.id))
        assertNull(panel.installable(a.identity))
        panel.choose(s2.source.id, a.identity)
        runCurrent()
        assertEquals(1, panel.state.value.rows.size)
        assertEquals(8L, panel.state.value.rows.single().suggestion.mangaCount)
        val physicalSources = panel.state.value.rows.filter { it.canInstall }.flatMap { it.suggestion.artifact.sources }
        assertEquals(physicalSources.size, physicalSources.map { it.id }.distinct().size)
    }

    @Test
    fun `encoded ignore survives reconstructed preferences version updates and session dismissal`() = runTest {
        val stored = InMemoryPreferenceStore.InMemoryPreference(
            Preference.appStateKey("extension_suggestions_ignored"),
            null,
            "",
        )
        val expanded = InMemoryPreferenceStore.InMemoryPreference(
            Preference.appStateKey("extension_suggestions_expanded"),
            null,
            true,
        )
        val store = io.mockk.mockk<PreferenceStore> {
            io.mockk.every { getString(any(), any()) } returns stored
            io.mockk.every { getBoolean(any(), any()) } returns expanded
        }
        val original = suggestion(repo = "https://repo.example/路径?a=b.c")
        val preferences = ExtensionSuggestionPreferences(store)
        val input = MutableStateFlow(ExtensionSuggestions(false, listOf(original)))
        val panel = ExtensionSuggestionPanel(backgroundScope, input, MutableStateFlow(emptyMap()), preferences)
        runCurrent()
        panel.ignore(original.identity)
        runCurrent()
        assertEquals(setOf(original.identity), preferences.ignoredIdentities().first())
        panel.dismissUndo()
        runCurrent()
        assertFalse(panel.state.value.canUndo)
        val newer = original.copy(artifact = original.artifact.copy(versionCode = 2, versionName = "1.6.2"))
        input.value = ExtensionSuggestions(false, listOf(newer))
        val restoredPreferences = ExtensionSuggestionPreferences(store)
        val restored = ExtensionSuggestionPanel(
            backgroundScope,
            input,
            MutableStateFlow(emptyMap()),
            restoredPreferences,
        )
        runCurrent()
        assertEquals(setOf(original.identity), restoredPreferences.ignoredIdentities().first())
        assertTrue(restored.state.value.rows.isEmpty())
        assertFalse(restored.state.value.canUndo)
        val manager = object : SourceManager by io.mockk.mockk(relaxed = true) {
            override val isInitialized = MutableStateFlow(true)
            override val querySources = MutableStateFlow(emptyList<QuerySource>())
        }
        val repository = io.mockk.mockk<SourceRepository> {
            io.mockk.every {
                getSourcesWithFavoriteCount()
            } returns MutableStateFlow(
                listOf(Source(original.sources.single().source.id, "ja", "Source", false, true) to 3L),
            )
        }
        val catalog = ExtensionCatalogResult(
            listOf(
                ExtensionCatalogEntry(
                    newer.artifact,
                    ExtensionCompatibility.Compatible,
                ),
            ),
            emptyList(),
        )
        val projected = ObserveExtensionSuggestions(repository, manager).subscribe(
            MutableStateFlow(catalog),
            MutableStateFlow(ExtensionInventory(true)),
            MutableStateFlow(true),
            restoredPreferences.ignoredIdentities(),
        ).first()
        assertTrue(projected.suggestions.isEmpty())
        assertTrue(projected.unmatched.isEmpty())
    }

    private fun suggestion(pkg: String = "pkg.a", repo: String = "https://repo.example"): ExtensionSuggestion {
        val artifact = ExtensionArtifact(
            "Reader", pkg, "1.6.1", 1, "en", false,
            listOf(ExtensionSourceDescriptor(9007199254740993L, "ja", "Source", "https://source.example")),
            RepositoryIdentity(repo, "Repository", "key"), "$repo/a.apk", "", null,
        )
        return ExtensionSuggestion(
            SuggestionIdentity.of(artifact),
            artifact,
            artifact.sources.map { SuggestedSource(it, 3) },
            false,
        )
    }

    @Test
    fun `panel searches names sources and packages ignores with undo and keeps active progress`() = runTest {
        val row = suggestion()
        val input = MutableStateFlow(ExtensionSuggestions(false, listOf(row)))
        val steps = MutableStateFlow(emptyMap<String, ExtensionPresentationInstallStep>())
        val preferences = ExtensionSuggestionPreferences(InMemoryPreferenceStore())
        val panel = ExtensionSuggestionPanel(backgroundScope, input, steps, preferences)
        runCurrent()
        assertEquals(1, panel.state.value.total)
        assertEquals(3L, panel.state.value.rows.single().suggestion.mangaCount)
        assertTrue(panel.state.value.expanded)
        panel.search("pkg.a")
        runCurrent()
        assertEquals(1, panel.state.value.rows.size)
        panel.search("missing")
        runCurrent()
        assertEquals(0, panel.state.value.rows.size)
        assertEquals(1, panel.state.value.total)
        panel.search("Source")
        runCurrent()
        panel.ignore(row.identity)
        runCurrent()
        assertTrue(panel.state.value.rows.isEmpty())
        assertTrue(panel.state.value.canUndo)
        panel.undo()
        runCurrent()
        assertEquals(1, panel.state.value.rows.size)
        steps.value = mapOf("pkg.a" to ExtensionPresentationInstallStep.Downloading)
        runCurrent()
        panel.ignore(row.identity)
        runCurrent()
        assertFalse(panel.state.value.rows.single().canIgnore)
        assertNull(panel.installable(row.identity))
        input.value = ExtensionSuggestions()
        runCurrent()
        assertEquals(ExtensionPresentationInstallStep.Downloading, panel.state.value.rows.single().step)
        panel.toggle()
        runCurrent()
        assertFalse(preferences.expanded.get())
        assertEquals(1, panel.state.value.activeCount)
        assertTrue(Preference.isAppState(preferences.expanded.key()))
        assertTrue(Preference.isAppState(preferences.ignored.key()))
    }

    @Test
    fun `source choices never select a repository implicitly and invalid websites are excluded`() = runTest {
        val first = suggestion()
        val other = suggestion("pkg.b", "https://other.example")
        val sourceId = first.sources.single().source.id
        val input = MutableStateFlow(
            ExtensionSuggestions(
                false,
                listOf(first, other),
                mapOf(sourceId to listOf(first.identity, other.identity)),
            ),
        )
        val panel = ExtensionSuggestionPanel(
            backgroundScope,
            input,
            MutableStateFlow(emptyMap()),
            ExtensionSuggestionPreferences(InMemoryPreferenceStore()),
        )
        runCurrent()
        assertNull(panel.installable(first.identity))
        panel.choose(sourceId, other.identity)
        runCurrent()
        assertEquals(other.artifact, panel.installable(other.identity))
        assertNull(panel.installable(first.identity))
        input.value = input.value.copy(
            suggestions = listOf(
                other.copy(
                    sources = listOf(
                        SuggestedSource(other.sources.single().source.copy(baseUrl = "javascript:alert(1)"), 3),
                    ),
                ),
            ),
        )
        runCurrent()
        assertTrue(panel.state.value.rows.single().websites.isEmpty())
    }
}
