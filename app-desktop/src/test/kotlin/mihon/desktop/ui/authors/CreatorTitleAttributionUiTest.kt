package mihon.desktop.ui.authors

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    @Test
    fun `detail header invokes source confirmation and real indexing only after save`() = fixture { scene, editor, writer, _, _ ->
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
    fun `failed indexing retains the confirmation and does not enable the source`() = fixture { scene, editor, writer, _, _ ->
        coEvery { writer.indexLibraryMangaBatch(any()) } throws IllegalStateException("磁盘已满")
        click(scene, "creator-title-attribution-open")
        click(scene, "creator-title-attribution-toggle")
        click(scene, "creator-title-attribution-save")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-error" } }
        assertFalse(editor.policy.isEnabled(10L))
        assertTrue(nodes(scene).any { tag(it) == "creator-title-attribution-save" })
    }

    @Test
    fun `reopening previews the updated title rather than the old uploader`() = fixture { scene, _, _, manga, _ ->
        manga.value = manga.value.copy(title = "(C123) [New Circle (New Artist)] Another work")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-open" } }
        click(scene, "creator-title-attribution-open")
        settle(scene) { nodes(scene).any { tag(it) == "creator-title-attribution-preview-0" } }
        val text = nodes(scene).single { tag(it) == "creator-title-attribution-preview-0" }.config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(text.contains("New Artist"), text)
    }

    @Test
    fun `partial reindex failure keeps completed count visible`() = fixture { scene, _, writer, manga, source ->
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

    private fun fixture(block: suspend (ImageComposeScene, CreatorTitleAttributionEditor, CreatorLibraryIndexWriter, androidx.compose.runtime.MutableState<Manga>, CreatorLibraryMangaSource) -> Unit) = runBlocking {
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
            val sources by editor.enabledSourcesFlow.collectAsState(emptySet())
            sources.size // subscribe to source changes exactly as the detail screen
            MaterialTheme {
                mihon.desktop.ui.library.MangaHeader(
                    manga = manga.value, coverModel = null, coverLastModified = 0L, coverFeedback = null,
                    coverFailed = false, sourceName = "Fixture source", sourceLanguage = "en",
                    onSourceSearch = {}, onTagSearch = {}, onTagGlobalSearch = {}, onTagCopy = {},
                    creatorMentions = editor.extract(manga.value), creatorIdentityLoading = false,
                    onCreatorClick = {}, onCreatorSearch = {}, onTitleSearch = {}, onNotes = {},
                    onViewCover = {}, coverFocus = FocusRequester(),
                    creatorTitleAttributionControl = { CreatorTitleAttributionControl(manga.value, "Fixture source", editor) },
                )
            }
        }
        try {
            scene.render().close()
            assertTrue(nodes(scene).any { tag(it) == "creator-title-attribution-open" }, "The production detail header must expose title-attribution controls")
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
