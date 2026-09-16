package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionDiscoveryResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExtensionSuggestionAppModuleTest {
    @Test
    fun `production AppModule manager subscribes to configuration updates without an open screen`() = runBlocking {
        val previous = Injekt
        val context = RuntimeEnvironment.getApplication()
        val repositories = MutableStateFlow(listOf(ExtensionRepo("https://repo.example", "Repo", null, "", "key")))
        val repository = mockk<ExtensionRepoRepository> {
            every { subscribeAll() } returns repositories
            coEvery { getAll() } answers { repositories.value }
        }
        var manager: ExtensionManager? = null
        mockkConstructor(ExtensionApi::class)
        Injekt = InjektScope(DefaultRegistrar())
        try {
            coEvery { anyConstructed<ExtensionApi>().findExtensionsWithFailures() } coAnswers {
                ExtensionDiscoveryResult(emptyList(), emptyList(), repositories.value.map { it.toIdentity() })
            }
            Injekt.importModule(AppModule(context))
            Injekt.importModule(PreferenceModule(context))
            val preferences = SourcePreferences(AndroidPreferenceStore(context))
            Injekt.addSingleton(preferences)
            Injekt.addSingleton(TrustExtension(repository, preferences))
            Injekt.addSingleton(GetExtensionRepo(repository))
            val resolved = Injekt.get<ExtensionManager>()
            manager = resolved
            withTimeout(5_000) { resolved.suggestionCatalog.first { it?.repositories?.size == 1 } }
            repositories.value = emptyList()
            val removed = withTimeout(5_000) {
                resolved.suggestionCatalog.first { it?.repositories?.isEmpty() == true }
            }
            assertEquals(emptyList<Any>(), removed!!.entries)
        } finally {
            manager?.scope?.cancel()
            Injekt = previous
            unmockkConstructor(ExtensionApi::class)
        }
    }
}
