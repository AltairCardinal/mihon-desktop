package eu.kanade.tachiyomi.ui.category

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CategoryRecoveryScreenWiringTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun `actual category Screen blocks unrecovered consumers then retries through existing error action`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("category-recovery-screen", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val actual = AndroidPreferenceStore(activity.get(), shared)
        var reject = true
        val guarded = object : PreferenceStore by actual {
            override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                val preference = actual.getStringSet(key, defaultValue)
                return object : Preference<Set<String>> by preference {
                    override fun set(value: Set<String>) {
                        if (reject && key == "library_update_categories_exclude") error("Rejected recovery")
                        preference.set(value)
                    }
                }
            }
        }
        val preferences = LibraryPreferences(guarded)
        val downloads = DownloadPreferences(guarded)
        preferences.categoryDeletionPending().set(setOf("1"))
        preferences.defaultCategory().set(1)
        actual.getStringSet("library_update_categories_exclude", emptySet()).set(setOf("1", "2"))
        val remaining = Category(2, "Remaining category", 0, 0)
        val repository = mockk<CategoryRepository>()
        coEvery { repository.get(any()) } returns null
        coEvery { repository.getAll() } returns listOf(remaining)
        coEvery { repository.updatePartial(any<List<tachiyomi.domain.category.model.CategoryUpdate>>()) } returns Unit
        every { repository.getAllAsFlow() } returns flowOf(listOf(remaining))
        try {
            Injekt.addSingleton(GetCategories(repository))
            Injekt.addSingleton(CreateCategoryWithName(repository, preferences))
            Injekt.addSingleton(DeleteCategory(repository, preferences, downloads))
            Injekt.addSingleton(ReorderCategory(repository))
            Injekt.addSingleton(RenameCategory(repository))
            val screen = CategoryScreen()
            assertTrue(screen is Screen)
            val parent = object : Screen {
                override val key = "category-recovery-parent"

                @Composable
                override fun Content() {
                    Text("Recovery parent")
                }
            }
            lateinit var navigator: Navigator
            activity.get().setContent {
                MaterialTheme {
                    Navigator(listOf(parent, screen)) {
                        navigator = it
                        CurrentScreen()
                    }
                }
            }
            compose.onNodeWithText(activity.get().stringResource(MR.strings.internal_error)).assertIsDisplayed()
            assertEquals(setOf("1"), preferences.categoryDeletionPending().get())
            compose.onNodeWithText(activity.get().stringResource(MR.strings.action_bar_up_description)).performClick()
            compose.onNodeWithText("Recovery parent").assertIsDisplayed()
            assertTrue(navigator.lastItem === parent)
            compose.runOnIdle { navigator.push(CategoryScreen()) }
            compose.onNodeWithText(activity.get().stringResource(MR.strings.internal_error)).assertIsDisplayed()
            reject = false
            compose.onNodeWithText(activity.get().stringResource(MR.strings.action_retry)).performClick()
            compose.onNodeWithText(remaining.name).assertIsDisplayed()
            assertEquals(setOf("2"), preferences.updateCategoriesExclude().get())
            assertEquals(emptySet<String>(), preferences.categoryDeletionPending().get())
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }
}
