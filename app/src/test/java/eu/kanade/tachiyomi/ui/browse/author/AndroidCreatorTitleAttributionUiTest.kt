package eu.kanade.tachiyomi.ui.browse.author

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorTitleAttributionEditor
import tachiyomi.domain.creator.service.CreatorTitleAttributionPolicy
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-w400dp-h900dp-mdpi")
class AndroidCreatorTitleAttributionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var clickedMention: tachiyomi.domain.creator.model.CreatorMention? = null

    @Test
    fun `title names appear below original author and open without source opt in`() = fixture { editor, writer, _, _ ->
        compose.onNodeWithTag("creator-mention-0").assertTextContains("Uploader", substring = true)
        compose.onNodeWithTag("title-credit-0").assertTextContains("Artist", substring = true)
        compose.onNodeWithTag("title-credit-1").assertTextContains("Circle", substring = true)
        val original = compose.onNodeWithTag("creator-mention-0").fetchSemanticsNode().boundsInRoot
        val credit = compose.onNodeWithTag("title-credit-0").fetchSemanticsNode().boundsInRoot
        assertTrue(credit.top >= original.bottom)
        compose.onNodeWithTag("title-credit-0").performClick()
        org.junit.Assert.assertEquals("Artist", clickedMention?.displayName)
        assertFalse(editor.policy.isEnabled(10L))
        coVerify(exactly = 0) { writer.indexLibraryMangaBatch(any()) }
    }

    @Test
    fun `title with only an individual shows no circle`() = fixture { _, _, manga, _ ->
        compose.runOnIdle { manga.value = manga.value.copy(title = "[Solo Artist] Another work") }
        compose.onNodeWithTag("title-credit-0").assertTextEquals("Solo Artist")
        compose.onNodeWithTag("title-credit-1").assertDoesNotExist()
        compose.onNodeWithTag("creator-mention-0").assertTextContains("Uploader", substring = true)
    }

    @Test
    fun `native header opens confirmation and only save reaches real title indexing`() = fixture {
            editor,
            writer,
            _,
            _,
        ->
        compose.onNodeWithTag("creator-title-attribution-open").performClick()
        compose.onNodeWithTag("creator-title-attribution-toggle").performClick()
        compose.onNodeWithTag("creator-title-attribution-close").performClick()
        assertFalse(editor.policy.isEnabled(10L))
        coVerify(exactly = 0) { writer.indexLibraryMangaBatch(any()) }
        compose.onNodeWithTag("creator-title-attribution-open").performClick()
        compose.onNodeWithTag("creator-title-attribution-toggle").performClick()
        compose.onNodeWithTag("creator-title-attribution-save").performClick()
        compose.waitForIdle()
        assertTrue(editor.policy.isEnabled(10L))
        compose.onNodeWithTag("creator-mention-0").assertTextContains("Uploader", substring = true)
        compose.onNodeWithTag("title-credit-0").assertTextContains("创作者: Artist", substring = true)
        compose.onNodeWithTag("title-credit-1").assertTextContains("社团: Circle", substring = true)
        coVerify(exactly = 1) {
            writer.indexLibraryMangaBatch(
                match {
                    it.single().mentions.map { mention -> mention.displayName } ==
                        listOf("Artist", "Circle")
                },
            )
        }
    }

    @Test
    fun `failed native save preserves dialog and preview follows changed title`() = fixture {
            editor,
            writer,
            manga,
            _,
        ->
        compose.runOnIdle { manga.value = manga.value.copy(title = "[New Circle (New Artist)] Another work") }
        compose.onNodeWithTag("creator-title-attribution-open").performClick()
        compose.onNodeWithTag("creator-title-attribution-preview-0").assertTextContains("New Artist", substring = true)
        coEvery { writer.indexLibraryMangaBatch(any()) } throws IllegalStateException("磁盘已满")
        compose.onNodeWithTag("creator-title-attribution-toggle").performClick()
        compose.onNodeWithTag("creator-title-attribution-save").performClick()
        compose.onNodeWithTag("creator-title-attribution-error").assertTextContains("磁盘已满")
        compose.onNodeWithTag("creator-title-attribution-save").assertExists()
        assertFalse(editor.policy.isEnabled(10L))
    }

    @Test
    fun `native partial reindex failure retains completed count`() = fixture { _, writer, manga, source ->
        coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } returns
            (1L..250L).map { manga.value.copy(id = it) }
        coEvery { source.getLibraryMangaForCreatorIndex(250L, 250L) } returns listOf(manga.value.copy(id = 251L))
        coEvery { writer.indexLibraryMangaBatch(match { it.size == 1 }) } throws IllegalStateException("磁盘已满")
        compose.onNodeWithTag("creator-title-attribution-open").performClick()
        compose.onNodeWithTag("creator-title-attribution-reindex").performClick()
        compose.onNodeWithTag("creator-title-attribution-error").assertTextContains("磁盘已满")
        compose.onNodeWithTag("creator-title-attribution-feedback").assertTextContains("250", substring = true)
    }

    private fun fixture(
        block: (
            CreatorTitleAttributionEditor,
            CreatorLibraryIndexWriter,
            androidx.compose.runtime.MutableState<Manga>,
            CreatorLibraryMangaSource,
        ) -> Unit,
    ) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val manga =
                mutableStateOf(
                    Manga.create().copy(
                        id = 8L,
                        source = 10L,
                        favorite = true,
                        title = "[Circle (Artist)] Work",
                        author = "Uploader",
                    ),
                )
            val store =
                AndroidPreferenceStore(
                    activity.get(),
                    activity.get().getSharedPreferences("title-native-ui", android.content.Context.MODE_PRIVATE),
                )
            val preferences = CreatorDiscoveryPreferences(store)
            val policy = CreatorTitleAttributionPolicy(preferences)
            val repository = mockk<MangaRepository>()
            coEvery { repository.getMangaById(8L) } answers { manga.value }
            val source = mockk<CreatorLibraryMangaSource>()
            coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } answers { listOf(manga.value) }
            val writer = mockk<CreatorLibraryIndexWriter>(relaxed = true)
            val editor =
                CreatorTitleAttributionEditor(
                    preferences,
                    repository,
                    source,
                    writer,
                    ExtractCreatorsFromManga(policy),
                    policy,
                )
            activity.get().setContent {
                MaterialTheme {
                    eu.kanade.presentation.manga.components.MangaInfoBox(
                        isTabletUi = false, appBarPadding = 0.dp, manga = manga.value,
                        sourceName = "Fixture source", isStubSource = true, onCoverClick = {}, doSearch = { _, _ -> },
                        creatorMentions = ExtractCreatorsFromManga().await(manga.value), onCreatorClick = {
                            clickedMention =
                                it
                        },
                        creatorTitleAttributionControl = {
                            CreatorTitleAttributionControl(manga.value, "Fixture source", editor)
                        },
                    )
                }
            }
            compose.onNodeWithTag("creator-title-attribution-open").assertExists()
            block(editor, writer, manga, source)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
