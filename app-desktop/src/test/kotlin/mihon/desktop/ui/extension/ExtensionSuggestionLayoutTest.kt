package mihon.desktop.ui.extension

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.suggestion.ExtensionSuggestion
import mihon.domain.extension.suggestion.SuggestedSource
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.SuggestionPanelRow
import mihon.domain.extension.suggestion.SuggestionPanelState
import mihon.domain.extension.suggestion.SuggestionProblem
import mihon.domain.extension.suggestion.UnmatchedLibrarySource

@OptIn(ExperimentalComposeUiApi::class)
class ExtensionSuggestionLayoutTest {
    @Test fun `wide suggestion and unmatched actions use right side of full rows`() = runBlocking {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(900, 1000, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { MaterialTheme { ExtensionSuggestionSection(panel(), null, {}, {}, {}, {}, {}, {}) } }
            scene.render()
            val title = text(scene, "Reader 1").boundsInRoot
            val action = text(scene, "Install").boundsInRoot
            val missing = text(scene, "Missing source", true).boundsInRoot
            val migrate = text(scene, "Migrate").boundsInRoot
            assertEquals(16f, title.left, 1f)
            assertEquals(title.left, missing.left, 1f)
            assertTrue(action.left > title.right && action.left > 450, "$title / $action")
            assertTrue(migrate.left > missing.right && migrate.top < missing.bottom + 80, "$missing / $migrate")
        } finally { scene.close(); Locale.setDefault(locale) }
    }

    @Test fun `narrow actions wrap and retain the exact install identity`() = runBlocking {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(360, 1000, coroutineContext = coroutineContext) {}
        var clicked: SuggestionIdentity? = null
        val state = panel()
        try {
            scene.setContent { MaterialTheme { ExtensionSuggestionSection(state, null, { clicked = it }, {}, {}, {}, {}, {}) } }
            scene.render()
            val title = text(scene, "Reader 1").boundsInRoot
            val action = text(scene, "Install")
            assertEquals(16f, title.left, 1f)
            assertTrue(action.boundsInRoot.right <= 344f)
            assertTrue(action.boundsInRoot.top > title.bottom)
            assertTrue(text(scene, "Ignore").boundsInRoot.right > 315, "Wrapped actions remain right aligned")
            requireNotNull(action.config[SemanticsActions.OnClick].action).invoke()
            assertEquals(state.rows.single().suggestion.identity, clicked)
        } finally { scene.close(); Locale.setDefault(locale) }
    }

    @Test fun `blank missing source name displays its stable source ID`() = runBlocking {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val scene = ImageComposeScene(900, 1000, coroutineContext = coroutineContext) {}
        val state = panel().let { it.copy(unmatched = it.unmatched.map { missing -> missing.copy(name = " ") }) }
        try {
            scene.setContent { MaterialTheme { ExtensionSuggestionSection(state, null, {}, {}, {}, {}, {}, {}) } }
            scene.render()
            assertTrue(nodes(scene).any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.contains("999") }
            })
        } finally { scene.close(); Locale.setDefault(locale) }
    }

    @Test fun `real installed page gives suggestions the page viewport and keeps rows lazy`() = runBlocking {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val model = mockk<ExtensionsScreenModel>(relaxed = true)
        every { model.state } returns MutableStateFlow(DesktopExtensionsState(options = mihon.domain.extension.presentation.ExtensionPresentationOptions(true, setOf("en")), suggestionPanel = panel(40)))
        every { model.suggestionBatch } returns mockk {
            every { state } returns MutableStateFlow(mihon.domain.extension.suggestion.SuggestionBatchState())
        }
        val scene = ImageComposeScene(900, 1000, coroutineContext = coroutineContext) {}
        try {
            scene.setContent { MaterialTheme { ExtensionListContent(model, showBackButton = false) } }
            scene.render()
            val scrolls = nodes(scene).filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            assertEquals(1, scrolls.size)
            assertTrue(scrolls.single().boundsInRoot.height > 500, "Suggestion scroll must be the page viewport")
            assertFalse(nodes(scene).any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Reader 40" } })
        } finally { scene.close(); Locale.setDefault(locale) }
    }

    private fun text(scene: ImageComposeScene, value: String, partial: Boolean = false) = nodes(scene).first { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { if (partial) it.text.contains(value) else it.text == value }
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun panel(count: Int = 1) = SuggestionPanelState(
        loading = false, total = count,
        rows = (1..count).map { index ->
            val source = ExtensionSourceDescriptor(index.toLong(), "en", "Source $index", "https://source.example")
            val artifact = ExtensionArtifact(
                "Reader $index", "pkg.$index", "1.6.1", 1, "en", false, listOf(source),
                RepositoryIdentity("https://repo.example", "Repository", "key"),
                "https://repo.example/$index.apk", "", null,
            )
            SuggestionPanelRow(
                ExtensionSuggestion(SuggestionIdentity.of(artifact), artifact, listOf(SuggestedSource(source, 3)), false),
                canInstall = true, canIgnore = true, websites = listOf(source),
            )
        },
        unmatched = listOf(UnmatchedLibrarySource(999, 2, SuggestionProblem.NOT_IN_CATALOG, "Missing source")),
    )
}
