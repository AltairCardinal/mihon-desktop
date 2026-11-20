package eu.kanade.presentation.manga

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import eu.kanade.presentation.manga.components.MangaDeletionOptions
import eu.kanade.presentation.manga.components.MangaDeletionSettingsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.MangaDownloadDeletion
import tachiyomi.domain.download.service.MangaDownloadDeletionSettings
import tachiyomi.domain.manga.model.MangaDetailAction
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.manga.DetailActionItem
import tachiyomi.presentation.manga.DetailActionStrip
import tachiyomi.presentation.manga.detailActionLabels

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-sw720dp-w840dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaDeletionSettingsDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `deletion changes apply immediately and back preserves them`() {
        verifyDialog(wide = true)
    }

    @Test
    @Config(qualifiers = "zh-rCN-sw320dp-w320dp-h640dp-mdpi")
    fun `narrow deletion tiles wrap and immediate settings remain reachable`() {
        verifyDialog(wide = false)
    }

    private fun verifyDialog(wide: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("deletion-dialog-${System.nanoTime()}", 0)
        val store = AndroidPreferenceStore(activity.get(), shared)
        val preferences = DownloadPreferences(store)
        try {
            activity.get().setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    DetailActionStrip(
                        mangaId = 7,
                        preferenceStore = store,
                        actions = listOf(DetailActionItem(MangaDetailAction.LIBRARY, "收藏", { Text("L") }, {})),
                        globalDeletionEnabled = false,
                        deleteIcon = { Text("D") },
                        labels = detailActionLabels { stringResource(it) },
                        deletionOptions = { settings, onChange -> MangaDeletionOptions(settings, onChange) },
                        deletionDialog = { title, onDismiss, content ->
                            MangaDeletionSettingsSheet(title, onDismiss, content)
                        },
                    )
                }
            }
            lateinit var outerDialog: androidx.activity.ComponentDialog
            fun open() {
                compose.onNodeWithTag("manga-action-DELETE_AFTER_READ")
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.runOnIdle {
                    outerDialog =
                        org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog
                }
            }
            fun edit() {
                compose.onNodeWithTag("manga-delete-choice-ENABLED").performClick()
                compose.runOnIdle {
                    assertEquals(MangaDownloadDeletion.ENABLED, preferences.mangaDeletionSettings(7).get().mode)
                }
                val enabled = compose.onNodeWithTag("manga-delete-choice-ENABLED").fetchSemanticsNode().boundsInRoot
                val retention = compose.onNodeWithTag("manga-delete-retention").fetchSemanticsNode().boundsInRoot
                val bookmark = compose.onNodeWithTag("manga-delete-bookmarked").fetchSemanticsNode().boundsInRoot
                val disabled = compose.onNodeWithTag("manga-delete-choice-DISABLED").fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue(enabled.bottom <= retention.top)
                org.junit.Assert.assertTrue(retention.bottom <= bookmark.top)
                val inherit = compose.onNodeWithTag("manga-delete-choice-INHERIT").fetchSemanticsNode().boundsInRoot
                val sheet = compose.onNodeWithTag(
                    "manga-delete-reader-sheet",
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertEquals("Bottom breathing room", 32f, sheet.bottom - bookmark.bottom, 0.5f)
                if (wide) {
                    org.junit.Assert.assertEquals(420f, sheet.center.x, 1f)
                    org.junit.Assert.assertEquals(300f, sheet.center.y, 1f)
                } else {
                    org.junit.Assert.assertTrue(sheet.bottom >= 600f)
                    org.junit.Assert.assertTrue(sheet.width <= 320f)
                }
                compose.runOnIdle {
                    val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                    val image = android.graphics.Bitmap.createBitmap(
                        window.width,
                        window.height,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    window.draw(android.graphics.Canvas(image))
                    val screenshot = java.io.File(
                        "../.test-tmp/detail-actions-visual/deletion-tiles-${if (wide) 840 else 320}-dark.png",
                    )
                    screenshot.parentFile.mkdirs()
                    screenshot.outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
                if (wide) {
                    org.junit.Assert.assertEquals(inherit.top, enabled.top, 0.1f)
                } else {
                    org.junit.Assert.assertTrue(inherit.bottom <= disabled.top)
                }
                org.junit.Assert.assertTrue(disabled.bottom <= retention.top)
                compose.onNodeWithTag("manga-delete-retention").performClick()
                compose.onNodeWithText(activity.get().getString(MR.strings.third_to_last.resourceId)).performClick()
                compose.runOnIdle { assertEquals(2, preferences.mangaDeletionSettings(7).get().slots) }
                compose.onNodeWithTag("manga-delete-bookmarked").performScrollTo().performClick()
                compose.runOnIdle {
                    org.junit.Assert.assertTrue(preferences.mangaDeletionSettings(7).get().removeBookmarked)
                }
                for (mode in listOf("DISABLED", "INHERIT")) {
                    compose.onNodeWithTag(
                        "manga-delete-choice-$mode",
                    ).performScrollTo().performClick().assertIsSelected()
                    compose.onNodeWithTag("manga-delete-retention").assertDoesNotExist()
                }
                compose.onNodeWithTag("manga-delete-choice-ENABLED").performClick().assertIsSelected()
                compose.onNodeWithTag("manga-delete-retention").performScrollTo().performClick()
                compose.onNodeWithTag("manga-delete-retention-2").assertIsSelected().performClick()
                compose.onNodeWithTag("manga-delete-retention-picker").assertExists()
                compose.onNodeWithTag("manga-delete-retention-cancel").performClick()
            }
            open()
            compose.onNodeWithTag("manga-delete-retention").assertDoesNotExist()
            compose.onNodeWithText(
                "读完章节后自动删除此作品的本机下载内容。此设置仅保存在本机，优先于全局设置，不会立即删除已有下载。",
            ).assertDoesNotExist()
            edit()
            compose.onNodeWithText("保存").assertDoesNotExist()
            compose.onNodeWithText("取消").assertDoesNotExist()
            compose.runOnUiThread {
                outerDialog.onBackPressedDispatcher.onBackPressed()
            }
            compose.onNodeWithTag("manga-delete-setting").assertDoesNotExist()
            open()
            compose.onNodeWithTag("manga-delete-choice-ENABLED").assertIsSelected()
            val reopened = DownloadPreferences(AndroidPreferenceStore(activity.get(), shared))
            assertEquals(
                MangaDownloadDeletionSettings(MangaDownloadDeletion.ENABLED, 2, true),
                reopened.mangaDeletionSettings(7).get(),
            )
            assertEquals(MangaDownloadDeletionSettings(), reopened.mangaDeletionSettings(8).get())
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
        }
    }
}
