package mihon.domain.extension.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionPresentationStoreTest {

    private val store = ExtensionPresentationStore(FixtureAdapter)

    @Test
    fun `fixed main action lifecycle refreshes and isolates install state by package`() {
        var state = store.reduce(
            ExtensionPresentationActionState(),
            ExtensionPresentationAction.InstallStepChanged(
                "pkg.alpha",
                ExtensionPresentationInstallStep.Downloading,
            ),
        )
        state = store.reduce(state, ExtensionPresentationAction.RefreshStarted)
        assertTrue(state.isRefreshing)
        assertEquals(ExtensionPresentationInstallStep.Downloading, state.installSteps["pkg.alpha"])
        state = store.reduce(
            state,
            ExtensionPresentationAction.InstallStepChanged("pkg.beta", ExtensionPresentationInstallStep.Installing),
        )
        state = store.reduce(
            state,
            ExtensionPresentationAction.InstallStepChanged("pkg.alpha", ExtensionPresentationInstallStep.Installing),
        )
        assertTrue(state.isRefreshing)
        assertEquals(
            mapOf(
                "pkg.alpha" to ExtensionPresentationInstallStep.Installing,
                "pkg.beta" to ExtensionPresentationInstallStep.Installing,
            ),
            state.installSteps,
        )
        assertEquals(
            listOf(ExtensionPresentationInstallStep.Installed),
            ExtensionPresentationInstallStep.entries.filterNot(store::shouldContinue),
        )

        state = store.reduce(state, ExtensionPresentationAction.InstallFinished("pkg.alpha"))
        assertTrue(state.isRefreshing)
        state = store.reduce(state, ExtensionPresentationAction.RefreshFinished)
        assertFalse(state.isRefreshing)
        assertEquals(setOf("pkg.beta"), state.installSteps.keys)
    }

    @Test
    fun `fixed main source ordering keeps enabled first then display name`() {
        val sources = listOf(
            SourceFixture(1, "Aardvark", enabled = false),
            SourceFixture(2, "alpha", enabled = true),
            SourceFixture(3, "Zulu", enabled = true),
        )

        val result = store.enabledFirst(sources, SourceFixture::enabled, SourceFixture::name)

        assertEquals(listOf(3L, 2L, 1L), result.map(SourceFixture::id))
    }

    @Test
    fun `fixed main classification filters sorts partitions and projects available sources`() {
        val installed = listOf(
            item("Hidden", "installed.hidden", nsfw = true),
            item("alpha", "installed.alpha", update = true),
            item("Zulu", "installed.zulu", update = true, obsolete = true),
            item("alpha", "installed.beta"),
            item("Zulu", "installed.outside", lang = "fr"),
        )
        val untrusted = listOf(
            item("Zulu", "untrusted.zeta", lang = "fr", nsfw = true),
            item("alpha", "untrusted.alpha", lang = "jp"),
        )
        val available = listOf(
            item("Duplicate installed", "installed.beta"),
            item("Duplicate untrusted", "untrusted.alpha"),
            item("Adult", "available.adult", nsfw = true),
            item("Zulu", "available.fallback", lang = "en"),
            item("Disabled fallback", "available.fr", lang = "fr"),
            item(
                "Bundle",
                "available.bundle",
                sources = listOf(source(7, "en", "alpha"), source(8, "fr", "French")),
            ),
        )

        val result = store.classify(
            installed,
            untrusted,
            available,
            ExtensionPresentationOptions(showNsfw = false, enabledLanguages = setOf("en")),
        )

        assertEquals(listOf("Zulu", "alpha"), result.updates.map(Fixture::name))
        assertEquals(listOf("alpha", "Zulu"), result.installed.map(Fixture::name))
        assertEquals(listOf("alpha", "Zulu"), result.untrusted.map(Fixture::name))
        assertEquals(listOf("alpha", "Zulu"), result.available.map(Fixture::name))
        assertEquals("available.bundle-7", result.available.first().pkgName)
        assertEquals(listOf(7L), result.available.first().sources.map(ExtensionPresentationSource::id))

        val nsfwVisible = store.classify(
            installed,
            untrusted,
            available,
            ExtensionPresentationOptions(showNsfw = true, enabledLanguages = setOf("en")),
        )
        assertTrue(nsfwVisible.installed.any { it.name == "Hidden" })
        assertTrue(nsfwVisible.available.any { it.name == "Adult" })
    }

    @Test
    fun `fixed main comma search is OR across extension and source fields with package opt in`() {
        val extension = item(
            "Reader Plus",
            "org.example.reader",
            sources = listOf(source(42, "en", "Manga Hub", "https://reader.example")),
        )

        assertTrue(store.matches(extension, " missing, reader plus "))
        assertTrue(store.matches(extension, "manga hub"))
        assertTrue(store.matches(extension, "READER.EXAMPLE"))
        assertTrue(store.matches(extension, "42"))
        assertTrue(store.matches(extension, " , "))
        assertFalse(store.matches(extension, "org.example"))
        assertTrue(store.matches(extension, "org.example", includePackageName = true))
    }

    private fun item(
        name: String,
        pkgName: String,
        lang: String? = "en",
        nsfw: Boolean = false,
        update: Boolean = false,
        obsolete: Boolean = false,
        sources: List<ExtensionPresentationSource> = emptyList(),
    ) = Fixture(name, pkgName, lang, nsfw, update, obsolete, sources)

    private fun source(id: Long, lang: String, name: String, baseUrl: String? = null) =
        ExtensionPresentationSource(id, lang, name, baseUrl)

    private data class Fixture(
        val name: String,
        val pkgName: String,
        val lang: String?,
        val nsfw: Boolean,
        val update: Boolean,
        val obsolete: Boolean,
        val sources: List<ExtensionPresentationSource>,
    )

    private data class SourceFixture(val id: Long, val name: String, val enabled: Boolean)

    private object FixtureAdapter : ExtensionPresentationAdapter<Fixture> {
        override fun describe(extension: Fixture) = ExtensionPresentationItem(
            name = extension.name,
            packageName = extension.pkgName,
            language = extension.lang,
            isNsfw = extension.nsfw,
            hasUpdate = extension.update,
            isObsolete = extension.obsolete,
            sources = extension.sources,
        )

        override fun projectAvailableSource(extension: Fixture, source: ExtensionPresentationSource) = extension.copy(
            name = source.name,
            pkgName = "${extension.pkgName}-${source.id}",
            lang = source.language,
            sources = listOf(source),
        )
    }
}
