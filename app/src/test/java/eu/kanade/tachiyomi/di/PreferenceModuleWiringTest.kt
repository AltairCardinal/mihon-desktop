package eu.kanade.tachiyomi.di

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PreferenceModuleWiringTest {

    @Test
    fun `PreferenceModule resolves Android PreferenceStore`() {
        withIsolatedInjekt {
            val application = RuntimeEnvironment.getApplication()
            Injekt.importModule(PreferenceModule(application))

            assertTrue(Injekt.get<PreferenceStore>() is AndroidPreferenceStore)
        }
    }

    @Test
    fun `detail action and reader deletion preferences share the production store`() {
        withIsolatedInjekt {
            Injekt.importModule(PreferenceModule(RuntimeEnvironment.getApplication()))
            val store = Injekt.get<PreferenceStore>()
            val downloads = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>()
            val preference = downloads.mangaDeletion(7)
            try {
                preference.set(tachiyomi.domain.download.service.MangaDownloadDeletion.ENABLED)
                org.junit.Assert.assertEquals(
                    tachiyomi.domain.download.service.MangaDownloadDeletion.ENABLED,
                    tachiyomi.domain.download.service.DownloadPreferences(store).mangaDeletion(7).get(),
                )
                assertTrue(tachiyomi.core.common.preference.Preference.isAppState(preference.key()))
            } finally {
                preference.delete()
            }
        }
    }

    private inline fun <T> withIsolatedInjekt(block: () -> T): T {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        return try {
            block()
        } finally {
            Injekt = previous
        }
    }
}
