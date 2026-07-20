package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class AndroidSourceManagerInitializationTest {

    @Test
    fun `source manager initializes only after the first installed extension snapshot is complete`() = runBlocking {
        val releaseLoader = CompletableDeferred<Unit>()
        val extensionSource = mockk<HttpSource> {
            every { id } returns 7L
            every { lang } returns "en"
            every { name } returns "Example"
        }
        val extensionManager = ExtensionManager(
            context = mockk(relaxed = true),
            preferences = preferences(),
            trustExtension = mockk(relaxed = true),
            installedExtensionsLoader = {
                releaseLoader.await()
                listOf(LoadResult.Success(installed(extensionSource)))
            },
            installReceiverRegistrar = {},
        )
        val sourceRepository = mockk<StubSourceRepository>(relaxed = true) {
            every { subscribeAll() } returns emptyFlow()
        }
        val localSourceFileSystem: LocalSourceFileSystem = mockk(relaxed = true)
        val localCoverManager: LocalCoverManager = mockk(relaxed = true)
        Injekt.addSingleton(localSourceFileSystem)
        Injekt.addSingleton(localCoverManager)
        mockkConstructor(LocalSource::class)
        try {
            val sourceManager = AndroidSourceManager(
                context = mockk(relaxed = true),
                extensionManager = extensionManager,
                sourceRepository = sourceRepository,
            )

            assertNull(withTimeoutOrNull(100) { sourceManager.isInitialized.first { it } })

            releaseLoader.complete(Unit)

            withTimeout(5_000) { sourceManager.isInitialized.first { it } }
            assertEquals(extensionSource, sourceManager.get(7L))
        } finally {
            unmockkConstructor(LocalSource::class)
        }
    }

    private fun preferences() = mockk<SourcePreferences>(relaxed = true) {
        every { enabledLanguages() } returns mockk<Preference<Set<String>>> {
            every { isSet() } returns true
        }
    }

    private fun installed(source: Source) = Extension.Installed(
        name = "Example",
        pkgName = "org.example.extension",
        versionName = "1.0",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        pkgFactory = null,
        sources = listOf(source),
        icon = null,
        isShared = false,
    )
}
