package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.service.ExtensionUpdatePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class ExtensionManagerUpdatePolicyWiringTest {

    @Test
    fun `Android production manager status refresh delegates to shared version policy`() = runBlocking {
        val installed = installedExtension()
        val available = availableExtension()
        val evaluatedVersions = mutableListOf<List<Number>>()
        val context = mockk<Context>(relaxed = true)
        val enabledLanguages = mockk<Preference<Set<String>>>()
        val extensionUpdatesCount = mockk<Preference<Int>>(relaxed = true)
        val preferences = mockk<SourcePreferences> {
            every { enabledLanguages() } returns enabledLanguages
            every { extensionUpdatesCount() } returns extensionUpdatesCount
        }
        every { enabledLanguages.isSet() } returns true
        val manager = ExtensionManager(
            context = context,
            preferences = preferences,
            trustExtension = mockk<TrustExtension>(relaxed = true),
            updatePolicy = ExtensionUpdatePolicy { availableCode, availableLib, installedCode, installedLib ->
                evaluatedVersions += listOf(availableCode, availableLib, installedCode, installedLib)
                true
            },
            installedExtensionsLoader = { listOf(LoadResult.Success(installed)) },
            availableExtensionsProvider = { listOf(available) },
            installReceiverRegistrar = {},
        )
        manager.isInitialized.first { it }

        manager.findAvailableExtensions()

        assertTrue(manager.installedExtensionsFlow.first { it.isNotEmpty() }.single().hasUpdate)
        assertEquals(listOf(listOf(10L, 1.4, 10L, 1.4)), evaluatedVersions)
    }

    private fun installedExtension() = Extension.Installed(
        name = "Example",
        pkgName = "example.extension",
        versionName = "1.4.1",
        versionCode = 10,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = false,
    )

    private fun availableExtension() = Extension.Available(
        name = "Example",
        pkgName = "example.extension",
        versionName = "1.4.1",
        versionCode = 10,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkName = "example.apk",
        iconUrl = "https://repo.example/icon.png",
        repoUrl = "https://repo.example",
    )
}
