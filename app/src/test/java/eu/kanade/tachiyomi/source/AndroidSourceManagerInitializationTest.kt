package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `source manager initializes only after the first installed extension snapshot is complete`() = runTest {
        val releaseLoader = CompletableDeferred<Unit>()
        val installedExtensionCollectionStarted = CompletableDeferred<Unit>()
        val extensionSource = mockk<HttpSource> {
            every { id } answers {
                installedExtensionCollectionStarted.complete(Unit)
                7L
            }
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
            scope = backgroundScope,
        )
        val sourceRepository = mockk<StubSourceRepository>(relaxed = true) {
            every { subscribeAll() } returns flowOf(emptyList())
            coEvery { getStubSource(any()) } returns null
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
                scope = backgroundScope,
            )

            runCurrent()
            assertFalse(sourceManager.isInitialized.value)
            assertFalse(installedExtensionCollectionStarted.isCompleted)

            releaseLoader.complete(Unit)
            runCurrent()

            assertTrue(installedExtensionCollectionStarted.isCompleted)
            assertTrue(sourceManager.isInitialized.value)
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
