package eu.kanade.tachiyomi.data.backup.create

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourcePreferenceBackupCompatibilityTest {
    private lateinit var previousInjekt: InjektScope

    @Before
    fun setUp() {
        previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton<Application>(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        Injekt = previousInjekt
    }

    @Test
    fun `Source-only settings are backed up without exporting private or runtime state by default`() {
        val source = object : ConfigurableSource {
            override val id = 1605L
            override val name = "Source-only settings"
            override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit
        }
        val manager = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources = MutableStateFlow(emptyList<eu.kanade.tachiyomi.source.CatalogueSource>())
            override val querySources = MutableStateFlow(listOf(source))
            override fun get(sourceKey: Long) = source.takeIf { it.id == sourceKey }
            override fun getOrStub(sourceKey: Long) = requireNotNull(get(sourceKey))
            override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
            override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()
            override fun getQuerySources() = listOf(source)
            override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
        }
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("source_1605", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("language", "zh")
            .putString(Preference.privateKey("credential"), "test-only-secret")
            .putString(Preference.appStateKey("session"), "runtime-only")
            .commit()
        val creator = PreferenceBackupCreator(manager, mockk())

        val ordinary = creator.createSource(false).single()
        assertEquals("source_1605", ordinary.sourceKey)
        assertEquals(listOf("language"), ordinary.prefs.map { it.key })
        assertEquals(StringPreferenceValue("zh"), ordinary.prefs.single().value)

        val privateBackup = creator.createSource(true).single()
        assertEquals(setOf("language", Preference.privateKey("credential")), privateBackup.prefs.map { it.key }.toSet())

        prefs.edit().clear().commit()
        runBlocking {
            PreferenceRestorer(RuntimeEnvironment.getApplication(), mockk(), mockk())
                .restoreSource(listOf(ordinary))
        }
        assertEquals(mapOf("language" to "zh"), prefs.all)
    }
}
