package mihon.desktop.ui.authors

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorTitleAttributionEditor
import tachiyomi.domain.creator.service.CreatorTitleAttributionPolicy
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.UUID
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
class CreatorTitleAttributionUiTest {
    private var clickedMention: tachiyomi.domain.creator.model.CreatorMention? = null

    @Test
    fun `title credits appear below original author without enabling source or opening settings`() = fixture { scene, editor, writer, _, _ ->
        assertFalse(nodes(scene).any { tag(it) == "creator-title-attribution-open" })
        val caption = nodes(scene).single { text(it) == "Title-recognized authors" }
        val firstCredit = nodes(scene).single { tag(it) == "title-credit-0" }
        assertTrue(caption.boundsInRoot.right <= firstCredit.boundsInRoot.left)
        assertTrue(caption.boundsInRoot.top < firstCredit.boundsInRoot.bottom)
        assertTrue(firstCredit.boundsInRoot.top < caption.boundsInRoot.bottom)
        val original = nodes(scene).first { text(it) == "Uploader" }
        val artist = nodes(scene).single { tag(it) == "title-credit-0" }
        assertTrue(text(artist).contains("Artist"))
        assertTrue(artist.boundsInRoot.top >= original.boundsInRoot.bottom)
        assertTrue(nodes(scene).any { tag(it) == "title-credit-1" && text(it).contains("Circle") })
        click(scene, "title-credit-0")
        assertEquals("Artist", clickedMention?.displayName)
        assertFalse(editor.policy.isEnabled(10L))
        coVerify(exactly = 0) { writer.indexLibraryMangaBatch(any()) }
    }

    @Test
    fun `individual-only title credit has no circle row and follows title updates`() = fixture { scene, _, _, manga, _ ->
        manga.value = manga.value.copy(title = "[Solo Artist] Another work")
        settle(scene) { nodes(scene).any { tag(it) == "title-credit-0" && text(it).contains("Solo Artist") } }
        assertEquals("Solo Artist", text(nodes(scene).single { tag(it) == "title-credit-0" }))
        assertFalse(nodes(scene).any { tag(it) == "title-credit-1" })
        assertTrue(nodes(scene).any { text(it) == "Uploader" })
    }

    @Test
    fun `standalone control invokes source confirmation and real indexing only after save`() = fixture(showStandaloneControl = true) { scene, editor, writer, _, _ ->
        click(scene, "creator-title-attribution-open")
        click(scene, "creator-title-attribution-toggle")
        click(scene, "creator-title-attribution-close")
        assertFalse(editor.policy.isEnabled(10L))
        coVerify(exactly = 0) { writer.indexLibraryMangaBatch(any()) }
        click(scene, "creator-title-attribution-open")
        click(scene, "creator-title-attribution-toggle")
        click(scene, "creator-title-attribution-save")
        settle(scene) { editor.policy.isEnabled(10L) && nodes(scene).none { tag(it) == "creator-title-attribution-save" } }
        coVerify(exactly = 1) { writer.indexLibraryMangaBatch(match { it.single().mentions.map { mention -> mention.displayName } == listOf("Artist", "Circle") }) }
        assertTrue(nodes(scene).any { text(it).contains(creatorTitleCreditText(editor.extract(mangaForLabels).first())) })
        assertTrue(nodes(scene).any { text(it).contains(creatorTitleCreditText(editor.extract(mangaForLabels).last())) })
        click(scene, "creator-title-attribution-open")
        click(scene, "creator-title-attribution-reindex")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-feedback" } }
        coVerify(exactly = 2) { writer.indexLibraryMangaBatch(any()) }
    }

    @Test
    fun `failed indexing retains the confirmation and does not enable the source`() = fixture(showStandaloneControl = true) { scene, editor, writer, _, _ ->
        coEvery { writer.indexLibraryMangaBatch(any()) } throws IllegalStateException("磁盘已满")
        click(scene, "creator-title-attribution-open")
        click(scene, "creator-title-attribution-toggle")
        click(scene, "creator-title-attribution-save")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-error" } }
        assertFalse(editor.policy.isEnabled(10L))
        assertTrue(nodes(scene).any { tag(it) == "creator-title-attribution-save" })
    }

    @Test
    fun `reopening previews the updated title rather than the old uploader`() = fixture(showStandaloneControl = true) { scene, _, _, manga, _ ->
        manga.value = manga.value.copy(title = "(C123) [New Circle (New Artist)] Another work")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-open" } }
        click(scene, "creator-title-attribution-open")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-preview-0" } }
        val text = nodes(scene).single { tag(it) == "creator-title-attribution-preview-0" }.config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(text.contains("New Artist"), text)
    }

    @Test
    fun `partial reindex failure keeps completed count visible`() = fixture(showStandaloneControl = true) { scene, _, writer, manga, source ->
        coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } returns (1L..250L).map { manga.value.copy(id = it) }
        coEvery { source.getLibraryMangaForCreatorIndex(250L, 250L) } returns listOf(manga.value.copy(id = 251L))
        coEvery { writer.indexLibraryMangaBatch(match { it.size == 1 }) } throws IllegalStateException("磁盘已满")
        click(scene, "creator-title-attribution-open")
        click(scene, "creator-title-attribution-reindex")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-error" } }
        assertTrue(nodes(scene).any { tag(it) == "creator-title-attribution-feedback" && text(it).contains("250") })
    }

    private val mangaForLabels = Manga.create().copy(source = 10L, title = "[Circle (Artist)] Work")
    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString { it.text } else ""

    private fun fixture(showStandaloneControl: Boolean = false, block: suspend (ImageComposeScene, CreatorTitleAttributionEditor, CreatorLibraryIndexWriter, androidx.compose.runtime.MutableState<Manga>, CreatorLibraryMangaSource) -> Unit) = runBlocking {
        val manga = mutableStateOf(Manga.create().copy(id = 8L, source = 10L, favorite = true, title = "[Circle (Artist)] Work", author = "Uploader"))
        val node = Preferences.userRoot().node("/mihon-tests/title-native-ui-${UUID.randomUUID()}")
        val preferences = CreatorDiscoveryPreferences(DesktopPreferenceStore(node))
        val policy = CreatorTitleAttributionPolicy(preferences)
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaById(8L) } answers { manga.value }
        val source = mockk<CreatorLibraryMangaSource>()
        coEvery { source.getLibraryMangaForCreatorIndex(Long.MIN_VALUE, 250L) } answers { listOf(manga.value) }
        val writer = mockk<CreatorLibraryIndexWriter>(relaxed = true)
        val editor = CreatorTitleAttributionEditor(preferences, repository, source, writer, ExtractCreatorsFromManga(policy), policy)
        val scene = ImageComposeScene(640, 1000, coroutineContext = coroutineContext) {
            MaterialTheme {
                mihon.desktop.ui.library.MangaHeader(
                    manga = manga.value, coverModel = null, coverLastModified = 0L, coverFeedback = null,
                    coverFailed = false, sourceName = "Fixture source", sourceLanguage = "en",
                    onSourceSearch = {}, onTagSearch = {}, onTagGlobalSearch = {}, onTagCopy = {},
                    creatorMentions = ExtractCreatorsFromManga().await(manga.value), creatorIdentityLoading = false,
                    onCreatorClick = { clickedMention = it }, onCreatorSearch = {}, onTitleSearch = {}, onNotes = {},
                    onViewCover = {}, coverFocus = FocusRequester(),
                )
                if (showStandaloneControl) {
                    CreatorTitleAttributionControl(manga.value, "Fixture source", editor)
                }
            }
        }
        try {
            scene.render().close()
            block(scene, editor, writer, manga, source)
        } finally { scene.close(); node.removeNode() }
    }

    private suspend fun click(scene: ImageComposeScene, value: String) {
        settle(scene) { nodes(scene).any { tag(it) == value } }
        assertTrue(requireNotNull(nodes(scene).single { tag(it) == value }.config[SemanticsActions.OnClick].action)())
        scene.render().close()
    }
    private suspend fun settle(scene: ImageComposeScene, ready: () -> Boolean) {
        withTimeout(5_000) {
            do { scene.render().close(); yield() } while (!ready())
        }
    }
    private fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
