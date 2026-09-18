package mihon.domain.extension

import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.ExtensionSuggestionEngine
import mihon.domain.extension.suggestion.LibrarySourceCount
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionSuggestionContractTest {
    private val largeId = 9007199254740993L
    private val otherId = largeId + 1
    private val engine = ExtensionSuggestionEngine()
    private val library = listOf(LibrarySourceCount(largeId, 3), LibrarySourceCount(otherId, 2))
    private val missing = ExtensionInventory(initialized = true)

    @Test
    fun `ignoring an installed provider never recommends a substitute for the same source`() {
        val installed = artifact("pkg.installed", largeId)
        val substitute = artifact("pkg.substitute", largeId)
        val result = engine.project(
            library,
            emptySet(),
            catalog(installed, substitute),
            ExtensionInventory(true, mapOf("pkg.installed" to ExtensionPresence.LOAD_FAILED)),
            setOf(SuggestionIdentity.of(installed)),
        )
        assertTrue(result.suggestions.isEmpty())
    }

    @Test
    fun `versions within the same repository identity select the update independently of input order`() {
        val old = artifact("pkg.one", largeId)
        val newer = old.copy(versionCode = 2)
        for (entries in listOf(listOf(old, newer), listOf(newer, old))) {
            val result = engine.project(library, emptySet(), catalog(*entries.toTypedArray()), missing)
            assertEquals(2L, result.suggestions.single().artifact.versionCode)
        }
    }

    @Test
    fun `old version source descriptors cannot advertise a source removed by the selected update`() {
        val old = artifact("pkg.one", largeId)
        val newer = artifact("pkg.one", otherId).copy(versionCode = 2)
        val result = engine.project(listOf(library.first()), emptySet(), catalog(old, newer), missing)
        assertTrue(result.suggestions.isEmpty())
        assertEquals(SuggestionProblem.NOT_IN_CATALOG, result.unmatched.single().problem)
    }

    @Test
    fun `partial catalog keeps usable candidates and exposes failures while a complete empty catalog settles`() {
        val artifact = artifact("pkg.one", largeId)
        val failure =
            RepositoryCatalogFailure(
                RepositoryIdentity("https://offline.example", "Offline", "key"),
                AppError.Network(),
            )
        val partial = engine.project(library, emptySet(), catalog(artifact).copy(failures = listOf(failure)), missing)
        assertEquals(listOf("pkg.one"), partial.suggestions.map { it.artifact.packageName })
        assertEquals(listOf(failure), partial.catalogFailures)
        val empty = engine.project(library, emptySet(), catalog(), missing)
        assertTrue(!empty.isLoading && empty.catalogFailures.isEmpty())
        assertEquals(library.map { it.sourceId }, empty.unmatched.map { it.sourceId })
    }

    @Test
    fun `exact long ids and package aggregation exclude local and already available sources`() {
        val result = engine.project(
            library + LibrarySourceCount(0, 50),
            setOf(otherId),
            catalog(artifact("pkg.one", largeId, otherId), artifact("pkg.wrong", largeId - 1)),
            missing,
        )
        assertEquals(listOf("pkg.one"), result.suggestions.map { it.artifact.packageName })
        assertEquals(3L, result.suggestions.single().mangaCount)
        assertEquals(listOf(largeId), result.suggestions.single().sources.map { it.source.id })
        assertEquals(
            5L,
            engine.project(library, emptySet(), catalog(artifact("pkg.one", largeId, otherId)), missing)
                .suggestions.single().mangaCount,
        )
    }

    @Test
    fun `untrusted and failed installed packages never appear as missing in another repository`() {
        for (presence in listOf(
            ExtensionPresence.PRESENT,
            ExtensionPresence.UNTRUSTED,
            ExtensionPresence.LOAD_FAILED,
        )) {
            val result = engine.project(
                library,
                emptySet(),
                catalog(artifact("pkg.one", largeId)),
                ExtensionInventory(true, mapOf("pkg.one" to presence)),
            )
            assertTrue(result.suggestions.isEmpty())
            assertEquals(SuggestionProblem.INSTALLED_UNAVAILABLE, result.unmatched.first().problem)
        }
    }

    @Test
    fun `uninitialized and unknown inventory cannot claim an absent package`() {
        val catalog = catalog(artifact("pkg.one", largeId))
        assertTrue(engine.project(library, emptySet(), catalog, ExtensionInventory()).isLoading)
        assertTrue(engine.project(library, emptySet(), null, missing).isLoading)
        val unknown = engine.project(library, emptySet(), catalog, ExtensionInventory(true, hasUnknownArtifacts = true))
        assertTrue(unknown.suggestions.isEmpty())
        assertEquals(SuggestionProblem.INVENTORY_UNKNOWN, unknown.unmatched.first().problem)
    }

    @Test
    fun `source ambiguity retains candidates without silently selecting a repository`() {
        val first = artifact("pkg.one", largeId, otherId)
        val second = first.copy(repository = RepositoryIdentity("https://other.example", "Other", "bb"))
        val result = engine.project(library, emptySet(), catalog(first, second), missing)
        assertEquals(2, result.suggestions.size)
        assertTrue(result.suggestions.all { it.requiresSelection })
        assertEquals(2, result.choices.getValue(largeId).size)
        assertEquals(2, result.choices.getValue(otherId).size)
    }

    @Test
    fun `ignored identity is normalized and independent of version and language filters`() {
        val original = artifact("pkg.one", largeId)
        val changed = original.copy(
            versionCode = 999,
            language = "ja",
            repository = original.repository.copy(baseUrl = "https://repo.example/", signingKeyFingerprint = "AA:BB"),
        )
        val ignored = setOf(SuggestionIdentity.of(original))
        val result = engine.project(listOf(library.first()), emptySet(), catalog(changed), missing, ignored)
        assertTrue(result.suggestions.isEmpty())
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun `incompatible and restricted sources are explained and empty library stays empty`() {
        val unsafe = artifact("pkg.one", largeId).copy(isNsfw = true)
        val result = engine.project(library, emptySet(), catalog(unsafe), missing, showNsfw = false)
        assertTrue(result.suggestions.isEmpty())
        assertEquals(SuggestionProblem.CONTENT_RESTRICTED, result.unmatched.first().problem)
        val incompatible = catalog(unsafe).copy(
            entries = listOf(
                ExtensionCatalogEntry(
                    unsafe,
                    ExtensionCompatibility.MissingPlatformApi("WebView"),
                ),
            ),
        )
        assertEquals(
            SuggestionProblem.INCOMPATIBLE,
            engine.project(library, emptySet(), incompatible, missing).unmatched.first().problem,
        )
        assertTrue(engine.project(emptyList(), emptySet(), catalog(unsafe), missing).suggestions.isEmpty())
    }

    private fun artifact(packageName: String, vararg ids: Long) = ExtensionArtifact(
        name = "Same display name", packageName = packageName, versionName = "1.6.1", versionCode = 1,
        language = "en", isNsfw = false,
        sources = ids.map { ExtensionSourceDescriptor(it, "en", "Same source name", "https://source.example") },
        repository = RepositoryIdentity("https://repo.example", "Repo", "aabb"),
        downloadUrl = "https://repo.example/$packageName.apk", iconUrl = "", declaredSha256 = null,
    )

    private fun catalog(vararg artifacts: ExtensionArtifact) = ExtensionCatalogResult(
        artifacts.map { ExtensionCatalogEntry(it, ExtensionCompatibility.Compatible) },
        emptyList(),
    )
}
