package mihon.desktop.history

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.test.http.ProductionReaderTestModeBridge
import mihon.desktop.ui.history.HistoryRootScreen
import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date
import java.util.Locale

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryAccessibilityIntegrationTest {
    @Test
    fun `shared native actions remain reachable in two languages themes narrow window and double font`(@TempDir folder: File) = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE).forEachIndexed { index, locale ->
                Locale.setDefault(locale)
                val context = initDesktopDIForTest(folder.resolve("case$index"), inMemoryDesktopPreferenceStore())
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val scene = ImageComposeScene(350, 900, coroutineContext = coroutineContext) {}
                try {
                    Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().appLanguage.set(if (index == 0) "en" else "zh-CN")
                    val dependencies = DesktopUiDependencies.fromInjekt()
                    val manga = Injekt.get<SaveSourceMangaForDetails>().awaitListed(
                        eu.kanade.tachiyomi.source.model.SManga.create().apply {
                            url = "/narrow"
                            title = "A very long history title with enough words for two lines 历史长标题"
                        },
                        42,
                    )
                    val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/1", name = "Chapter 1"))).single()
                    Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 1))
                    val item = Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().subscribe("").first().single()
                    scene.setContent {
                        MaterialTheme(colorScheme = if (index == 0) lightColorScheme() else darkColorScheme()) {
                            androidx.compose.material3.Surface {
                                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, LocalDensity provides Density(1f, 2f)) {
                                    Navigator(HistoryRootScreen()) { dependencies.localeAdapter.Provide { CurrentScreen() } }
                                }
                            }
                        }
                    }
                    settle(scene) { nodes(scene).any { tag(it) == "history_item_${item.id}" } }
                    val row = nodes(scene).single { tag(it) == "history_item_${item.id}" }.boundsInRoot
                    val cover = nodes(scene).single { tag(it) == "history_cover_${item.id}" }.boundsInRoot
                    assertTrue(row.height >= 96f)
                    assertEquals(80f * 2f / 3f, cover.width, .5f)
                    assertEquals(80f, cover.height, .5f)
                    listOf("history_cover_${item.id}", "history_favorite_${item.id}", "history_delete_${item.id}", "history_clear_all").forEach { name ->
                        val bounds = nodes(scene).single { tag(it) == name }.boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= 350 && bounds.top >= 0 && bounds.bottom <= 900, "$name remains inside the narrow window")
                    }
                    focusByTag(scene, "history_delete_${item.id}")
                    enter(scene)
                    settle(scene, "keyboard delete opens confirmation") { nodes(scene).any { tag(it) == "history_delete_cancel" } }
                    focusByTag(scene, "history_delete_cancel")
                    scene.sendKeyEvent(key(Key.Escape, KeyEventType.KeyDown))
                    scene.sendKeyEvent(key(Key.Escape, KeyEventType.KeyUp))
                    settle(scene, "Escape dismisses only confirmation") { nodes(scene).none { tag(it) == "history_delete_confirm" } }
                    assertEquals(1, Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().await(manga.id).count { it.readAt != null })
                    scene.render().use { rendered ->
                        File("build/history-parity-native/${if (index == 0) "light-en" else "dark-zh"}-350px-font200.png").apply { parentFile.mkdirs() }
                            .writeBytes(requireNotNull(rendered.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)
                    }
                    scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyDown, shift = true))
                    scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyUp, shift = true))
                    scene.render().close()
                    assertTrue(nodes(scene).any { tag(it) == "history_favorite_${item.id}" && isFocused(it) }, "Shift Tab moves back from delete to favorite")
                    scene.sendKeyEvent(key(Key.Spacebar, KeyEventType.KeyDown))
                    scene.sendKeyEvent(key(Key.Spacebar, KeyEventType.KeyUp))
                    withTimeout(5_000) {
                        while (!Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().getMangaById(manga.id).favorite) {
                            scene.render().close()
                            delay(1)
                        }
                    }
                    settle(scene) { nodes(scene).none { tag(it) == "history_favorite_${item.id}" } }
                    assertTrue(nodes(scene).any { tag(it) == "history_item_${item.id}" }, "Space on favorite keeps History mounted")
                } finally {
                    scene.close()
                    context.closeAndJoin()
                    Dispatchers.resetMain()
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `native detail return retains query scroll and the originating cover focus`(@TempDir folder: File) = runBlocking {
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = ImageComposeScene(700, 650, coroutineContext = coroutineContext) {}
        var navigator: Navigator? = null
        try {
            val dependencies = DesktopUiDependencies.fromInjekt()
            var targetId = 0L
            repeat(16) { index ->
                val manga = Injekt.get<SaveSourceMangaForDetails>().awaitListed(
                    eu.kanade.tachiyomi.source.model.SManga.create().apply {
                        url = "/keyboard-$index"
                        title = "Keyboard book $index"
                    },
                    42,
                )
                val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/1", name = "Chapter 1"))).single()
                Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(System.currentTimeMillis() + index * 1000L), 1))
                if (index == 0) targetId = Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().await(manga.id).single().id
            }
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(HistoryRootScreen()) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            settle(scene) { nodes(scene).any { tag(it) == "history_search_open" } }
            clickTag(scene, "history_search_open")
            settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.SetText) } }
            requireNotNull(nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action)(AnnotatedString("Keyboard"))
            settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.ScrollToIndex) } }
            requireNotNull(nodes(scene).single { it.config.contains(SemanticsActions.ScrollToIndex) }.config[SemanticsActions.ScrollToIndex].action)(16)
            settle(scene) { nodes(scene).any { tag(it) == "history_cover_$targetId" } }
            val before = scrollPosition(scene)
            assertTrue(before > 0f)
            focusByTag(scene, "history_cover_$targetId")
            enter(scene)
            settle(scene) { requireNotNull(navigator).lastItem is mihon.desktop.ui.library.MangaDetailScreen }
            requireNotNull(navigator).pop()
            settle(scene) { requireNotNull(navigator).lastItem is HistoryRootScreen && nodes(scene).any { tag(it) == "history_cover_$targetId" } }
            assertEquals("Keyboard", nodes(scene).single { it.config.contains(SemanticsProperties.EditableText) }.config[SemanticsProperties.EditableText].text)
            assertEquals(before, scrollPosition(scene), .01f)
            assertTrue(nodes(scene).any { tag(it) == "history_cover_$targetId" && isFocused(it) }, "Focus returns to the cover that opened details")
            scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyDown))
            scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyUp))
            scene.render().close()
            assertTrue(nodes(scene).any { tag(it) == "history_favorite_$targetId" && isFocused(it) })
            val target = Injekt.get<tachiyomi.domain.history.interactor.GetHistory>().subscribe("Keyboard").first().single { it.id == targetId }
            Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().update(tachiyomi.domain.manga.model.MangaUpdate(target.mangaId, title = "Keyboard updated title"))
            settle(scene) { nodes(scene).any { labels(it).contains("Keyboard updated title") } }
            assertTrue(nodes(scene).any { tag(it) == "history_favorite_$targetId" && isFocused(it) }, "A background row update must not steal manual keyboard focus")
            val input = nodes(scene).single { tag(it) == "history_search_input" }
            assertTrue(requireNotNull(input.config[SemanticsActions.RequestFocus].action)())
            Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>().update(tachiyomi.domain.manga.model.MangaUpdate(target.mangaId, title = "Keyboard changed while editing"))
            settle(scene) { nodes(scene).any { labels(it).contains("Keyboard changed while editing") } }
            assertTrue(nodes(scene).any { tag(it) == "history_search_input" && isFocused(it) }, "A background update must retain the active search editor")

            clickTag(scene, "history_clear_all")
            settle(scene) { nodes(scene).any { tag(it) == "history_clear_cancel" } }
            clickTag(scene, "history_clear_cancel")
            settle(scene) { nodes(scene).none { tag(it) == "history_clear_cancel" } }
            assertTrue(nodes(scene).any { tag(it) == "history_clear_all" && isFocused(it) }, "Cancelling clear returns focus to its toolbar trigger")
            focusByTag(scene, "history_cover_$targetId")
            enter(scene)
            settle(scene) { requireNotNull(navigator).lastItem is mihon.desktop.ui.library.MangaDetailScreen }
            Injekt.get<tachiyomi.domain.history.interactor.RemoveHistory>().await(target)
            requireNotNull(navigator).pop()
            settle(scene) { requireNotNull(navigator).lastItem is HistoryRootScreen && nodes(scene).any { tag(it) == "history_search_close" } && nodes(scene).none { tag(it) == "history_cover_$targetId" } }
            assertTrue(nodes(scene).any { tag(it) == "history_search_close" && isFocused(it) }, "Removing the originating row restores a valid toolbar focus")
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun tag(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun clickTag(scene: ImageComposeScene, name: String) = assertTrue(requireNotNull(nodes(scene).single { tag(it) == name }.config[SemanticsActions.OnClick].action)())
    private suspend fun focusByTag(scene: ImageComposeScene, name: String) {
        repeat(50) {
            if (nodes(scene).any { tag(it) == name && isFocused(it) }) return
            scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyDown))
            scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyUp))
            scene.render().close()
            delay(1)
        }
        fail<Unit>("Tab must reach $name")
    }

    private suspend fun focusByTab(scene: ImageComposeScene, label: String) {
        repeat(40) {
            if (nodes(scene).any { isFocused(it) && it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == androidx.compose.ui.semantics.Role.Button && flatten(it).any { child -> labels(child).contains(label) } }) return
            scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyDown))
            scene.sendKeyEvent(key(Key.Tab, KeyEventType.KeyUp))
            scene.render().close()
            delay(10)
        }
        fail<Unit>("Tab must reach $label")
    }
    private fun scrollPosition(scene: ImageComposeScene) = nodes(scene).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun isFocused(node: SemanticsNode) = node.config.contains(SemanticsProperties.Focused) && node.config[SemanticsProperties.Focused]
    private fun enter(scene: ImageComposeScene) {
        scene.sendKeyEvent(key(Key.Enter, KeyEventType.KeyDown))
        scene.sendKeyEvent(key(Key.Enter, KeyEventType.KeyUp))
    }
    private suspend fun settle(scene: ImageComposeScene, description: String = "page settles", predicate: () -> Boolean) = try {
        withTimeout(10_000) {
            while (!predicate()) {
                scene.render().close()
                delay(10)
            }
            scene.render().close()
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("$description; tags=${nodes(scene).mapNotNull(::tag)}; focused=${nodes(scene).filter(::isFocused).mapNotNull(::tag)}", error)
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> labels(child).contains(label) } }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode) = (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()) + (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList())
    private fun key(key: Key, type: KeyEventType, shift: Boolean = false): androidx.compose.ui.input.key.KeyEvent {
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType").getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp").invoke(null)
        val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, shift, null))
    }
}
