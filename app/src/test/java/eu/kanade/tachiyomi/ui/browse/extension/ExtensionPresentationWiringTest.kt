package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.interactor.androidExtensionPresentationStore
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class ExtensionPresentationWiringTest {

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

    private fun <T> preference(value: T) = mockk<Preference<T>> {
        every { get() } returns value
        every { changes() } returns flowOf(value)
    }
}
