package mihon.desktop.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.ExtensionOrigin
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.ui.extension.DesktopExtensionPresentationPort
import mihon.desktop.ui.extension.ExtensionDetailsScreen
import mihon.desktop.ui.extension.ExtensionsScreenModel
import mihon.desktop.ui.extension.extensionVersionCopy
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
class DesktopExtensionDetailsMetadataCopyTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `extension details metadata and source copy follows locale`() = runBlocking {
        val jar = directory.resolve("example.jar").also { it.writeText("desktop-extension") }
        val source = mockk<HttpSource>(relaxed = true, moreInterfaces = arrayOf(ConfigurableSource::class)) {
            every { id } returns 42L
            every { name } returns "Example Source"
            every { lang } returns "en"
            every { baseUrl } returns "https://source.example"
        }
        val extension = InstalledExtension(
            jar, listOf(source), displayName = "Example Extension", versionName = "1.2.3-raw", artifactSha256 = "sha-example",
            repoName = "Example Repository", repoFingerprint = "fingerprint-example", origin = ExtensionOrigin.COMPILED_JAR,
        )
        val api = mockk<DesktopExtensionApi> {
            coEvery { refreshCatalog() } returns ExtensionCatalogResult(emptyList(), emptyList())
            every { availableExtensions(any()) } returns emptyList()
            coEvery { loadExtensionIcon(any()) } returns null
        }
        val manager = mockk<DesktopExtensionManager>(relaxed = true)
        val model = ExtensionsScreenModel(
            DesktopExtensionPresentationPort(api, manager, MutableStateFlow(listOf(extension))), this,
            ExtensionPresentationOptions(false, setOf("en")),
        )
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionApi } returns api
            every { extensionManager } returns manager
            every { appPreferences } returns DesktopAppPreferences(
                DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
            )
            every { sourceManager } returns mockk<SourceManager>()
        }
        val scene = ImageComposeScene(900, 1600, coroutineContext = coroutineContext) {}
        val previousInjekt = Injekt
        val previousLocale = Locale.getDefault()
        try {
            patchInjekt()
            Injekt.addSingleton(model)
            withTimeout(5_000) { model.refresh().join() }
            withTimeout(5_000) { model.state.first { it.projection?.installed?.singleOrNull()?.installed === extension } }
            listOf(Locale.forLanguageTag("zh-CN"), Locale.US).forEach { locale ->
                Locale.setDefault(locale)
                scene.setContent {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(ExtensionDetailsScreen(jar.absolutePath)) { ExtensionDetailsScreen(jar.absolutePath).Content() }
                    }
                }
                scene.render()
                renderUntil(scene, extension.name)
                val rendered = texts(scene)
                listOf(
                    "${MR.strings.ext_info_version.localized(locale)}: ${extension.versionName}", source.lang,
                    MR.strings.desktop_extension_origin_native.localized(locale), MR.strings.desktop_extension_metadata_title.localized(locale),
                    MR.strings.desktop_extension_metadata_file.localized(locale, jar.absolutePath), MR.strings.desktop_extension_metadata_size.localized(locale, jar.length()),
                    MR.strings.desktop_extension_metadata_sha256.localized(locale, extension.artifactSha256), MR.strings.desktop_extension_metadata_repository.localized(locale, extension.repoName),
                    MR.strings.desktop_extension_metadata_fingerprint.localized(locale, extension.repoFingerprint), MR.strings.label_sources.localized(locale),
                    MR.strings.browse.localized(locale), MR.strings.pref_incognito_mode.localized(locale),
                    MR.strings.pref_incognito_mode_extension_summary.localized(locale),
                ).forEach { assertTrue(it in rendered, "Missing '$it': $rendered") }
                assertTrue(extensionVersionCopy("", locale) == "${MR.strings.ext_info_version.localized(locale)}: ${MR.strings.unknown.localized(locale)}")
                val descriptions = descriptions(scene)
                listOf(
                    MR.strings.action_bar_up_description.localized(locale), MR.strings.desktop_extension_open_source_website.localized(locale, source.name),
                    MR.strings.desktop_extension_source_settings.localized(locale, source.name),
                    MR.strings.desktop_extension_incognito_for.localized(locale, extension.pkgName),
                ).forEach { assertTrue(it in descriptions, "Missing '$it': $descriptions") }
                if (locale.language == "zh") {
                    assertNotEquals(MR.strings.label_sources.localized(Locale.US), MR.strings.label_sources.localized(locale))
                    assertTrue(rendered.any { it.contains(jar.absolutePath) } && rendered.any { it.contains(source.name) })
                }
            }
        } finally {
            scene.close()
            model.closeAndJoin()
            Injekt = previousInjekt
            Locale.setDefault(previousLocale)
        }
    }

    private suspend fun renderUntil(scene: ImageComposeScene, text: String) = withTimeout(5_000) {
        while (texts(scene).none { it.contains(text) }) { scene.render(); yield() }
    }

    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { value -> value.text } else emptyList()
    }

    private fun descriptions(scene: ImageComposeScene) = nodes(scene).flatMap {
        if (it.config.contains(SemanticsProperties.ContentDescription)) it.config[SemanticsProperties.ContentDescription] else emptyList()
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
