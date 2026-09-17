package eu.kanade.tachiyomi.ui.browse.author

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.components.TabbedScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorDiscoverySchedule
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceCapability
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.CreatorSourceFailure
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.EnabledCreatorSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidCreatorSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `actual author list saves one draft shows failure and returns focus to real AppBar gear`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-settings-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        var fail = false
        val store = AndroidPreferenceStore(activity.get(), shared)
        val guarded = object : PreferenceStore by store {
            override fun getString(key: String, defaultValue: String): Preference<String> {
                val delegate = store.getString(key, defaultValue)
                return object : Preference<String> by delegate {
                    override fun set(value: String) {
                        if (fail) error("disk full") else delegate.set(value)
                    }
                }
            }
        }
        val preferences = CreatorDiscoveryPreferences(guarded)
        try {
            val repository = mockk<CreatorRepository> {
                every { getCreatorsAsFlow() } returns flowOf(emptyList())
                every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            }
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(preferences)
            Injekt.addSingleton(
                mockk<CreatorArchiveRepository> {
                    coEvery { getDueWatchSources(any(), any()) } returns
                        emptyList()
                },
            )
            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }
            compose.onNodeWithTag("creator-settings-open").assertIsDisplayed().performClick()
            compose.onNodeWithTag("creator-frequency-monthly").performClick()
            assertEquals(CreatorCheckFrequency.DAILY, preferences.current())
            compose.onNodeWithTag("creator-settings-cancel").performClick()
            compose.onNodeWithTag("creator-settings-open").assertIsFocused().performClick()
            compose.onNodeWithTag("creator-frequency-daily").assertIsSelected()
            compose.onNodeWithTag("creator-frequency-weekly").performClick()
            fail = true
            compose.onNodeWithTag("creator-settings-save").performClick()
            compose.onNodeWithText("disk full").assertIsDisplayed()
            compose.onNodeWithTag("creator-frequency-weekly").assertIsSelected()
            assertEquals(CreatorCheckFrequency.DAILY, preferences.current())
            fail = false
            compose.onNodeWithTag("creator-settings-save").performClick()
            compose.onNodeWithTag("creator-settings-open").assertIsFocused()
            compose.onNodeWithTag("creator-settings-save").assertDoesNotExist()
            assertEquals(CreatorCheckFrequency.WEEKLY, CreatorDiscoveryPreferences(store).current())
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }
}

private class SettingsAuthorsScreen : Screen {
    @Composable override fun Content() {
        TabbedScreen(MR.strings.desktop_ui_authors, persistentListOf(authorsTab()))
    }
}
