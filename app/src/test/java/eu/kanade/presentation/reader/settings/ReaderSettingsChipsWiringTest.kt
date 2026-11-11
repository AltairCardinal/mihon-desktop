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
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
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
import org.robolectric.annotation.GraphicsMode
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
    fun `approved default summary shows separate rows instead of a flattened sentence`() = withPage(0L) {
        compose.onNodeWithText("跟随全局阅读偏好").assertIsDisplayed()
        compose.onNodeWithText("默认设置变更后，当前作品同步采用。").assertIsDisplayed()
        val mode = compose.onNodeWithText("横向阅读").fetchSemanticsNode().boundsInRoot
        val layout = compose.onNodeWithText("自动 · 单页").fetchSemanticsNode().boundsInRoot
        assertTrue(mode.bottom <= layout.top)
    }

    @Test
    fun `approved mode cards direction arrows and continuous segments retain their layout`() = withPage(6L) {
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val mode = compose.onNodeWithText("横向阅读").fetchSemanticsNode().boundsInRoot
        assertTrue("Mode card retains the icon text and radio", mode.height >= 82f * density - 1f)
        val single = compose.onNodeWithText("单页").fetchSemanticsNode().boundsInRoot
        val dual = compose.onNodeWithText("双页").fetchSemanticsNode().boundsInRoot
        assertEquals(single.right, dual.left, 1f)
        compose.onNodeWithText("从右到左").performScrollTo()
        val direction = compose.onNodeWithText("从右到左").fetchSemanticsNode().boundsInRoot
        assertTrue(direction.height >= 74f * density - 1f)
        compose.onNodeWithText("在双页显示时，自动调整页面配对").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `pager split stays in original boolean section and retains preference and rotation behavior`() = withPage(
        1L,
    ) { fixture ->
        val label = "拆分双页"
        val hint = "将宽页分切为两个单页显示，适合在竖屏阅读图源为单张跨页的漫画"
        fixture.preferences.dualPageRotateToFit().set(true)
        fixture.preferences.dualPageInvertPaged().set(true)
        val split = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        split.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        compose.onNodeWithText(hint).assertDoesNotExist()
        split.performClick()
        split.assertIsOn()
        assertTrue(fixture.preferences.dualPageSplitPaged().get())
        assertFalse(fixture.preferences.dualPageRotateToFit().get())
        assertTrue(fixture.preferences.dualPageInvertPaged().get())
        compose.onNodeWithText("分割宽页").assertDoesNotExist()
        compose.onNodeWithText(
            MR.strings.pref_page_rotate.getString(RuntimeEnvironment.getApplication()),
        ).performScrollTo()
        val panY = compose.onNodeWithText(MR.strings.pref_navigate_pan.getString(RuntimeEnvironment.getApplication()))
            .fetchSemanticsNode().positionInRoot.y
        val splitY = compose.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y
        val rotateY = compose.onNodeWithText(MR.strings.pref_page_rotate.getString(RuntimeEnvironment.getApplication()))
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(panY < splitY && splitY < rotateY)
        click("双页")
        compose.onNodeWithText(label).performScrollTo().assertIsOn()
        click("自动")
        compose.onNodeWithText(label).performScrollTo().assertIsOn()
        click("纵向阅读")
        compose.onNodeWithText(label).performScrollTo().assertIsOn()
        click("横向阅读")
        click("单页")
        compose.onNodeWithText(label).performScrollTo().assertIsOn()
        assertTrue(ReaderPreferences(fixture.store).dualPageSplitPaged().get())
        click(MR.strings.pref_page_rotate.getString(RuntimeEnvironment.getApplication()))
        assertTrue(fixture.preferences.dualPageRotateToFit().get())
        assertFalse(fixture.preferences.dualPageSplitPaged().get())
        assertTrue(fixture.preferences.dualPageInvertPaged().get())
        click("跟随默认")
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `series settings retain full custom choice while following a changed global default`() = withPage(
        0L,
    ) { fixture ->
        compose.onNodeWithText("跟随默认").assertIsSelected()
        compose.onNodeWithText("单独设置").assertIsNotSelected()
        compose.onNode(
            hasText(matchingLabel) and
                hasAnySibling(hasText(MR.strings.off.getString(RuntimeEnvironment.getApplication()))),
        ).performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("纵向阅读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("逐页翻动").performScrollTo().assertIsDisplayed()
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
        compose.onNode(
            hasText("页面有空隙") and hasAnySibling(hasText(MR.strings.on.getString(RuntimeEnvironment.getApplication()))),
        ).performScrollTo().assertIsDisplayed()
        click("单独设置")
        assertEquals(5L, fixture.mode)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h900dp")
    fun `narrow screen with large text retains two equal width horizontal directions`() = withPage(
        7L,
        fontScale = 1.6f,
    ) { fixture ->
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
        capture(fixture, "narrow-large-text")
        compose.onNodeWithText("在双页显示时，自动调整页面配对").performScrollTo().assertIsDisplayed()
        capture(fixture, "narrow-large-text-scrolled", scrollToTop = false)
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w430dp-h1100dp")
    fun `global grouped settings persist hidden choices and observe legacy preferences`() =
        withPage(0L, global = true, dark = true) { fixture ->
            compose.onNodeWithText("跟随默认").assertDoesNotExist()
            compose.onNodeWithText("横向阅读").assertDoesNotExist()
            capture(fixture, "global-summary", scrollToTop = false)
            click("默认阅读模式设置")
            capture(fixture, "global-dialog", scrollToTop = false)
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
            compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.onNodeWithText("横向阅读 · 单页 · 从左到右", substring = true).assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("横向阅读 · 单页 · 从左到右", substring = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                    it(layouts)
                }
            assertEquals(1, layouts.single().lineCount)
            capture(fixture, "global-summary-updated", scrollToTop = false)
            click("默认阅读模式设置")
            compose.onNodeWithText("单页").assertIsSelected()
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w430dp-h1100dp")
    fun `native renderer paints approved horizontal vertical and inherited controls`() = withPage(
        6L,
        dark = true,
    ) { fixture ->
        capture(fixture, "horizontal-double")
        click("单页")
        click("拆分双页")
        capture(fixture, "pager-boolean-split", scrollToTop = false)
        capture(fixture, "horizontal-single")
        click("自动")
        capture(fixture, "horizontal-auto")
        click("纵向阅读")
        capture(fixture, "vertical-continuous")
        click("逐页翻动")
        capture(fixture, "vertical-paged")
        click("跟随默认")
        capture(fixture, "default-summary")
        fixture.preferences.setDefaultReaderSettings(ReaderSettings(mode = ReaderSettingsMode.VERTICAL))
        capture(fixture, "vertical-default")
    }

    @Test
    fun `mode quick sheet excludes pager extension split options`() = withPage(1L, quick = true) {
        compose.onNodeWithText("分割宽页").assertDoesNotExist()
        compose.onNodeWithText("拆分双页").assertDoesNotExist()
    }

    private fun capture(fixture: Fixture, name: String, scrollToTop: Boolean = true) {
        if (scrollToTop) compose.onNodeWithText("跟随默认").performScrollTo()
        compose.waitForIdle()
        val root = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            ?: fixture.activity.findViewById<android.view.View>(android.R.id.content)
        assertTrue(root.width > 0 && root.height > 0)
        val bitmap = android.graphics.Bitmap.createBitmap(
            root.width,
            root.height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        try {
            fixture.activity.runOnUiThread { root.draw(android.graphics.Canvas(bitmap)) }
            val output = java.io.File("build/reader-settings-parity/$name.png")
            output.parentFile.mkdirs()
            output.outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun click(text: String) {
        val node = if (text == "自动") automaticChoice() else compose.onNodeWithText(text)
        node.performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun automaticChoice() = compose.onNode(
        hasText("自动") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )

    private class Fixture(val store: AndroidPreferenceStore, flags: Long, val activity: ComponentActivity) {
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
        dark: Boolean = false,
        block: (Fixture) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("reader-chips-${System.nanoTime()}", 0)
        val fixture = Fixture(AndroidPreferenceStore(activity.get(), shared), flags, activity.get())
        val previousInjekt = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(fixture.preferences)
        try {
            activity.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    MaterialTheme(
                        colorScheme = if (dark) TachiyomiColorScheme.darkScheme else TachiyomiColorScheme.lightScheme,
                    ) {
                        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
            }
            block(fixture)
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Injekt = previousInjekt
        }
    }
}
