package eu.kanade.tachiyomi.author

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.Constants
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorTitleAttributionEditor
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** One offline synthetic work through the installed Debug app, production DI, SQL and native screens. */
class TitleAttributionPreviewInstrumentationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun nativeTitleCreditsOpenAndFollowTheRealAuthorWithoutChangingSourceMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev")) { "Only the isolated development identity is permitted" }
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Dedicated emulator required" }
        Injekt.get<BasePreferences>().shownOnboardingFlow().set(true)
        val mangas = Injekt.get<MangaRepository>()
        val creators = Injekt.get<CreatorRepository>()
        val editor = Injekt.get<CreatorTitleAttributionEditor>()
        val fixture = runBlocking {
            mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 990123456L,
                        url = "/title-attribution-preview-fixture",
                        title = "(C123) [星舟社 (青岚)] 预研示例",
                        author = "上传者甲",
                        artist = "上传者甲",
                        favorite = true,
                        initialized = true,
                    ),
                ),
            ).single()
        }
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Constants.SHORTCUT_MANGA)
            .putExtra(Constants.MANGA_EXTRA, fixture.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(15_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("creator-title-attribution-open"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("creator-title-attribution-open").performClick()
            compose.onNodeWithTag("creator-title-attribution-preview-0").assertExists()
            compose.onNodeWithTag("creator-title-attribution-toggle").performClick()
            compose.onNodeWithTag("creator-title-attribution-save").performClick()
            compose.waitUntil(10_000) { editor.policy.isEnabled(fixture.source) }
            compose.onNodeWithTag("creator-mention-0").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(
                    androidx.compose.ui.test.hasText(context.getString(MR.strings.desktop_ui_follow.resourceId)),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                compose.onAllNodes(androidx.compose.ui.test.hasText("青岚"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithText(context.getString(MR.strings.desktop_ui_follow.resourceId)).performClick()
            val artist = runBlocking {
                creators.getCreatorsAsFlow().first().single { creator ->
                    creator.displayName ==
                        "青岚"
                }
            }
            compose.waitUntil(10_000) {
                runBlocking {
                    creators.getFollowedCreators().any { watch ->
                        watch.creatorId ==
                            artist.id
                    }
                }
            }
            val original = runBlocking { mangas.getMangaById(fixture.id) }
            assertEquals(fixture.title, original.title)
            assertEquals("上传者甲", original.author)
            assertEquals("上传者甲", original.artist)
            assertTrue(runBlocking { creators.getMangaCreatorsForCreator(artist.id).any { it.mangaId == fixture.id } })
        }
    }
}
