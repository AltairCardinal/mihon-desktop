package eu.kanade.presentation.reader.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderModeFlags
import mihon.domain.reader.ReaderScrollMode
import mihon.domain.reader.ReaderSettings
import mihon.domain.reader.ReaderSettingsCodec
import mihon.domain.reader.ReaderSettingsLayout
import mihon.domain.reader.ReaderSettingsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN", application = android.app.Application::class)
class ReaderSettingsChipsWiringTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val matchingLabel get() = MR.strings.desktop_ui_auto_spread_matching.getString(
        RuntimeEnvironment.getApplication(),
    )

    @Test
    fun `series settings retain full custom choice while following a changed global default`() = withPage(
        0L,
    ) { fixture ->
        compose.onNodeWithText("跟随默认").assertIsSelected()
        compose.onNodeWithText("单独设置").assertIsNotSelected()
        compose.onNodeWithText(
            matchingLabel + ": " + MR.strings.off.getString(RuntimeEnvironment.getApplication()),
            substring = true,
        )
            .assertIsDisplayed()
        click("单独设置")
        assertEquals(7L, fixture.mode)
        click("双页")
        compose.onNodeWithText("双页").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithText("单页").assertIsNotSelected()
        automaticChoice().assertIsNotSelected()
        click("从左到右")
        assertEquals(8L, fixture.mode)
        compose.onNodeWithText(matchingLabel).performScrollTo().assertIsDisplayed()
        val left = compose.onNodeWithText("从左到右").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithText("从右到左").fetchSemanticsNode().boundsInRoot
        assertEquals(left.width, right.width, 1f)
        val matching = compose.onNodeWithText(matchingLabel).fetchSemanticsNode().boundsInRoot
        assertTrue(matching.top > left.bottom)
        click("跟随默认")
        assertEquals(0L, fixture.mode)
        fixture.preferences.setDefaultReaderSettings(
            ReaderSettings(mode = ReaderSettingsMode.VERTICAL, scroll = ReaderScrollMode.PAGED),
        )
        compose.onNodeWithText("纵向阅读 · 逐页翻动", substring = true).assertIsDisplayed()
        compose.onNodeWithText("页面有空隙", substring = true).assertDoesNotExist()
        compose.onNodeWithText(matchingLabel, substring = true).assertDoesNotExist()
        click("单独设置")
        assertEquals(8L, fixture.mode)
        compose.onNodeWithText("双页").assertIsSelected()
        compose.onNodeWithText("从左到右").assertIsSelected()
        click("单页")
        compose.onNodeWithText(matchingLabel).assertDoesNotExist()
        click("自动")
        assertEquals(9L, fixture.mode)
        compose.onNodeWithText("根据阅读区域宽高比自动切换单/双页").assertIsDisplayed()
        val globalMatchingBefore = fixture.preferences.automaticSpreadMatching().get()
        val customMatchingBefore = ReaderSettingsCodec.custom(fixture.manga.viewerFlags).match
        click(matchingLabel)
        assertEquals(
            !customMatchingBefore,
            ReaderSettingsCodec.effective(fixture.manga.viewerFlags, fixture.preferences.defaultReaderSettings()).match,
        )
        assertEquals(globalMatchingBefore, fixture.preferences.automaticSpreadMatching().get())
        assertEquals(0x28L, fixture.manga.viewerFlags and 0x38L)
    }

    @Test
    fun `legacy vertical pager exposes real paged mode and retains hidden gap across mode changes`() = withPage(
        3L,
    ) { fixture ->
        compose.onNodeWithText("逐页翻动").performScrollTo().assertIsSelected()
        compose.onNodeWithText("页面有空隙").assertDoesNotExist()
        compose.onNodeWithText("阅读方向").assertDoesNotExist()
        click("连续滚动")
        assertEquals(4L, fixture.mode)
        click("页面有空隙")
        assertEquals(5L, fixture.mode)
        compose.onNodeWithText("是").assertDoesNotExist()
        compose.onNodeWithText("否").assertDoesNotExist()
        click("逐页翻动")
        assertEquals(3L, fixture.mode)
        compose.onNodeWithText("页面有空隙").assertDoesNotExist()
        click("横向阅读")
        click("纵向阅读")
        compose.onNodeWithText("逐页翻动").assertIsSelected()
        click("连续滚动")
        assertEquals(5L, fixture.mode)
        assertTrue(ReaderSettingsCodec.custom(fixture.manga.viewerFlags).gap)
        click("跟随默认")
        fixture.preferences.setDefaultReaderSettings(ReaderSettings(mode = ReaderSettingsMode.VERTICAL, gap = true))
        val gapSummary = "页面有空隙: " + MR.strings.on.getString(RuntimeEnvironment.getApplication())
        compose.onNodeWithText(gapSummary, substring = true).assertIsDisplayed()
        click("单独设置")
        assertEquals(5L, fixture.mode)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h900dp")
    fun `narrow screen with large text retains two equal width horizontal directions`() = withPage(
        7L,
        fontScale = 1.6f,
    ) {
        click("从左到右")
        val left = compose.onNodeWithText("从左到右").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithText("从右到左").fetchSemanticsNode().boundsInRoot
        assertEquals(left.width, right.width, 1f)
        assertTrue(
            left.left >= 0f &&
                right.right <= 320f * RuntimeEnvironment.getApplication().resources.displayMetrics.density,
        )
        compose.onNodeWithText("从上到下").assertDoesNotExist()
        compose.onNodeWithText("从左到右").assertIsSelected()
        compose.onNodeWithText("从右到左").assertIsNotSelected()
    }

    @Test
    fun `toolbar quick sheet uses the same grouped controls and production writer`() = withPage(
        3L,
        quick = true,
    ) { fixture ->
        compose.onNodeWithText("逐页翻动").performScrollTo().assertIsSelected()
        click("连续滚动")
        click("页面有空隙")
        click("跟随默认")
        click("单独设置")
        assertEquals(5L, fixture.mode)
    }

    @Test
    fun `global grouped settings persist hidden choices and observe legacy preferences`() =
        withPage(0L, global = true) { fixture ->
            compose.onNodeWithText("跟随默认").assertDoesNotExist()
            click("纵向阅读")
            click("页面有空隙")
            click("逐页翻动")
            compose.onNodeWithText("页面有空隙").assertDoesNotExist()
            click("连续滚动")
            assertEquals(5, fixture.preferences.defaultReadingMode().get())
            val restored = ReaderPreferences(fixture.store)
            assertTrue(restored.defaultReaderSettings().gap)
            restored.defaultReadingMode().set(1)
            restored.automaticSpreadMatching().set(true)
            assertEquals(ReaderDirection.LTR, restored.defaultReaderSettings().direction)
            assertEquals(ReaderSettingsLayout.SINGLE, restored.defaultReaderSettings().layout)
            assertTrue(restored.defaultReaderSettings().match)
            assertTrue(restored.defaultReaderSettings().gap)
            compose.onNodeWithText("单页").assertIsSelected()
            compose.onNodeWithText("从左到右").assertIsSelected()
        }

    private fun click(text: String) {
        val node = if (text == "自动") automaticChoice() else compose.onNodeWithText(text)
        node.performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun automaticChoice() = compose.onNode(
        hasText("自动") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    private class Fixture(val store: AndroidPreferenceStore, flags: Long) {
        val preferences = ReaderPreferences(store)
        var manga = Manga.create().copy(id = 1L, viewerFlags = flags or 0x28L)
        val mode get() = ReaderModeFlags.read(manga.viewerFlags)
        val state = MutableStateFlow(ReaderViewModel.State(manga = manga))
        private val repository = mockk<MangaRepository>()
        private val writer = SetMangaViewerFlags(repository)
        val model: ReaderSettingsScreenModel

        init {
            coEvery { repository.getMangaById(1L) } answers { manga }
            coEvery { repository.update(any()) } answers {
                manga = manga.copy(viewerFlags = requireNotNull(firstArg<MangaUpdate>().viewerFlags))
                true
            }
            model = ReaderSettingsScreenModel(
                state,
                { error("Grouped settings must dispatch the full snapshot, not just a mode enum") },
                {},
                preferences,
                onChangeReaderSettings = { settings, follow ->
                    runBlocking {
                        writer.awaitSetReaderSettings(1L, settings, follow, preferences.defaultReaderSettings())
                    }
                    state.value = state.value.copy(manga = manga)
                },
            )
        }
    }

    private fun withPage(
        flags: Long,
        quick: Boolean = false,
        global: Boolean = false,
        fontScale: Float = 1f,
        block: (Fixture) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("reader-chips-${System.nanoTime()}", 0)
        val fixture = Fixture(AndroidPreferenceStore(activity.get(), shared), flags)
        val previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(fixture.preferences)
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    MaterialTheme {
                        if (quick) {
                            ReadingModeSelectDialog({}, fixture.model)
                        } else {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (global) {
                                    val entry = SettingsReaderScreen.getPreferences().first()
                                    require(entry is Preference.PreferenceItem.CustomPreference)
                                    entry.content()
                                } else {
                                    ReadingModePage(fixture.model)
                                }
                            }
                        }
                    }
                }
            }
            block(fixture)
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Injekt = previousInjekt
        }
    }
}
