package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ReaderResumeRecoveryUiIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `real invalid resume event opens a native repair action which exposes actual reader controls`() = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val store = InMemoryPreferenceStore()
        Injekt.addSingleton(ReaderPreferences(store))
        Injekt.addSingleton(UiPreferences(store))
        val application = RuntimeEnvironment.getApplication()
        Injekt.addSingleton(BasePreferences(application, store))
        Injekt.addSingleton(SecurityPreferences(store))
        Injekt.addSingleton(application)
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = ReaderSyncResumeWiringTest.Fixture(pageIndex = 8)
        fixture.repository.openedContext = fixture.repository.responses.getValue(1).copy(
            pageIndex = 8,
            snapshot = fixture.original.snapshot,
            resumedWithinChapter = true,
        )
        val reader = Robolectric.buildActivity(ReaderActivity::class.java).get()
        reader.binding = mockk<ReaderActivityBinding>(relaxed = true)
        ReflectionHelpers.setField(reader, "viewModel\$delegate", lazyOf(fixture.model))
        val events = reader.subscribeReaderEvents()
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            host.get().setContent { MaterialTheme { reader.SyncResumeRecoveryNotice() } }
            assertTrue(fixture.model.init(1, 1).getOrThrow())
            compose.waitForIdle()
            compose.onNodeWithTag("sync-reader-choose-position").performClick()
            compose.runOnIdle { assertTrue(fixture.model.state.value.menuVisible) }
            assertTrue("opening controls must not create a reading fact", fixture.repository.records.isEmpty)
        } finally {
            events.cancel()
            fixture.close()
            host.pause().stop().destroy()
            Dispatchers.resetMain()
            Injekt = previous
        }
    }
}
