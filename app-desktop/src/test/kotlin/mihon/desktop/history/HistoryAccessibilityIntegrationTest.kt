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
    fun `native loading cancel survives language theme font and width changes without another source call or late navigation`(@TempDir folder: File) = runBlocking {
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val context = initDesktopDIForTest(folder, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val feeds = java.util.concurrent.atomic.AtomicInteger()
        val server = mockwebserver3.MockWebServer().apply {
            dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest) = if (request.url.encodedPath.endsWith("/feed")) {
                    feeds.incrementAndGet()
                    mockwebserver3.MockResponse.Builder().code(500).body("{}").headersDelay(2, java.util.concurrent.TimeUnit.SECONDS).build()
                } else {
                    mockwebserver3.MockResponse(body = HistoryCatalogHttpIntegrationTest.DETAILS)
                }
            }
            start()
        }
        val source = mihon.desktop.test.http.HistoryCatalogTestSource(Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client, server.url("/").toString().trimEnd('/'))
        mihon.desktop.test.http.HistoryCatalogTestSourceBridge.install(source)
        val scene = ImageComposeScene(800, 900, coroutineContext = coroutineContext) {}
        val compact = androidx.compose.runtime.mutableStateOf(false)
        var destination: Screen? = null
        try {
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().appLanguage.set("en")
            val dependencies = DesktopUiDependencies.fromInjekt()
            val manga = Injekt.get<SaveSourceMangaForDetails>().awaitListed(
                eu.kanade.tachiyomi.source.model.SManga.create().apply {
                    url = mihon.desktop.test.http.HistoryCatalogTestSource.MANGA_URL
                    title = "Loading history book"
                },
                source.id,
            )
            val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter/2", name = "Ch.2"))).single()
            Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 1))
            scene.setContent {
                MaterialTheme(colorScheme = if (compact.value) darkColorScheme() else lightColorScheme()) {
                    androidx.compose.material3.Surface {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, LocalDensity provides Density(1f, if (compact.value) 1.8f else 1f)) {
                            androidx.compose.foundation.layout.Box(Modifier.width(if (compact.value) 420.dp else 800.dp)) {
                                Navigator(HistoryRootScreen()) { nav ->
                                    destination = nav.lastItem
                                    dependencies.localeAdapter.Provide { CurrentScreen() }
                                }
                            }
                        }
                    }
                }
            }
            settle(scene) { nodes(scene).any { labels(it).contains(manga.title) } }
            click(scene, manga.title)
            settle(scene) { nodes(scene).any { labels(it).contains(MR.strings.history_loading_chapters.localized()) } && feeds.get() == 1 }
            compact.value = true
            settle(scene) { nodes(scene).any { labels(it).contains(MR.strings.history_loading_chapters.localized()) } }
            focusByTab(scene, MR.strings.action_cancel.localized())
            scene.sendKeyEvent(key(Key.Escape, KeyEventType.KeyDown))
            settle(scene) { nodes(scene).none { labels(it).contains(MR.strings.history_loading_chapters.localized()) } }
            repeat(250) {
                scene.render().close()
                delay(10)
            }
            dependencies.localeAdapter.select("zh-CN")
            repeat(5) {
                scene.render().close()
                delay(10)
            }
            assertTrue(nodes(scene).any { labels(it).contains("今天") })
            assertEquals(1, feeds.get())
            assertTrue(destination is HistoryRootScreen)
            assertTrue(nodes(scene).none { labels(it).contains(MR.strings.history_retry_chapters.localized()) })
            assertEquals(chapter, Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single())
        } finally {
            scene.close()
            mihon.desktop.test.http.HistoryCatalogTestSourceBridge.clear(source)
            server.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `history failure retry existing cancel and return focus use real events in both languages themes and large narrow window`(@TempDir folder: File) = runBlocking {
        val originalLocale = Locale.getDefault()
        try {
            listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE).forEachIndexed { index, locale ->
                Locale.setDefault(locale)
                val context = initDesktopDIForTest(folder.resolve("case$index"), inMemoryDesktopPreferenceStore())
                Dispatchers.setMain(UnconfinedTestDispatcher())
                val scene = ImageComposeScene(420, 900, coroutineContext = coroutineContext) {}
                var destination: Screen? = null
                try {
                    Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().appLanguage.set(if (index == 0) "en" else "zh-CN")
                    val dependencies = DesktopUiDependencies.fromInjekt()
                    val failureLabel = if (index == 0) "Source unavailable" else "源不可用"
                    val retryLabel = if (index == 0) "Retry loading chapters" else "重试加载章节"
                    val existingLabel = if (index == 0) "Read using existing chapters" else "使用已有章节阅读"
                    val manga = Injekt.get<SaveSourceMangaForDetails>().awaitListed(
                        eu.kanade.tachiyomi.source.model.SManga.create().apply {
                            url = "/history-accessibility"
                            title = "History keyboard book"
                        },
                        456789,
                    )
                    val chapter = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/2", name = "Chapter 2"))).single()
                    Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 12))
                    repeat(12) { position ->
                        val other = Injekt.get<SaveSourceMangaForDetails>().awaitListed(
                            eu.kanade.tachiyomi.source.model.SManga.create().apply {
                                url = "/keyboard-$position"
                                title = "Keyboard book $position"
                            },
                            456789,
                        )
                        val row = Injekt.get<ChapterRepository>().addAll(listOf(Chapter.create().copy(mangaId = other.id, url = "/1", name = "Chapter 1"))).single()
                        Injekt.get<UpsertHistory>().await(HistoryUpdate(row.id, Date(System.currentTimeMillis() + position * 1000L + 1000L), 1))
                    }
                    scene.setContent {
                        MaterialTheme(colorScheme = if (index == 0) lightColorScheme() else darkColorScheme()) {
                            androidx.compose.material3.Surface {
                                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies, LocalDensity provides Density(1f, 1.8f)) {
                                    Navigator(HistoryRootScreen()) { nav ->
                                        destination = nav.lastItem
                                        dependencies.localeAdapter.Provide { CurrentScreen() }
                                    }
                                }
                            }
                        }
                    }
                    settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.SetText) } }
                    val field = nodes(scene).first { it.config.contains(SemanticsActions.SetText) }
                    assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString("keyboard")))
                    settle(scene) { nodes(scene).any { it.config.contains(SemanticsActions.ScrollToIndex) } }
                    assertTrue(requireNotNull(nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }.config[SemanticsActions.ScrollToIndex].action).invoke(13))
                    settle(scene) { nodes(scene).any { labels(it).contains(manga.title) } }
                    val scrollBefore = scrollPosition(scene)
                    assertTrue(scrollBefore > 0f)
                    click(scene, manga.title)
                    settle(scene) { nodes(scene).any { labels(it).contains(failureLabel) } }
                    repeat(3) {
                        scene.render().close()
                        delay(10)
                    }
                    assertTrue(requireNotNull(nodes(scene).first { it.config.contains(SemanticsActions.ScrollToIndex) }.config[SemanticsActions.ScrollToIndex].action).invoke(13))
                    repeat(3) {
                        scene.render().close()
                        delay(10)
                    }
                    scene.render().use { rendered ->
                        File("build/history-reader-candidates/${if (index == 0) "light-en" else "dark-zh"}-failure.png").apply { parentFile.mkdirs() }
                            .writeBytes(requireNotNull(rendered.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).bytes)
                    }
                    assertTrue(destination is HistoryRootScreen)
                    focusByTab(scene, retryLabel)
                    enter(scene)
                    settle(scene) { nodes(scene).any { labels(it).contains(failureLabel) } }
                    // Escape cancels only the transient feedback and keeps the query.
                    scene.sendKeyEvent(key(Key.Escape, KeyEventType.KeyDown))
                    settle(scene) { nodes(scene).none { labels(it).contains(failureLabel) } }
                    assertEquals("keyboard", nodes(scene).first { it.config.contains(SemanticsProperties.EditableText) }.config[SemanticsProperties.EditableText].text)
                    click(scene, manga.title)
                    settle(scene) { nodes(scene).any { labels(it).contains(existingLabel) } }
                    focusByTab(scene, existingLabel)
                    enter(scene)
                    settle(scene) { destination is DesktopReaderScreen && ProductionReaderTestModeBridge.binding != null }
                    assertEquals(chapter.id, (destination as DesktopReaderScreen).chapterId)
                    ProductionReaderTestModeBridge.binding!!.close()
                    settle(scene) { destination is HistoryRootScreen && nodes(scene).any { isFocused(it) && flatten(it).any { child -> labels(child).contains(MR.strings.action_resume.localized()) } } }
                    assertEquals("keyboard", nodes(scene).first { it.config.contains(SemanticsProperties.EditableText) }.config[SemanticsProperties.EditableText].text)
                    assertTrue(nodes(scene).none { labels(it).contains(failureLabel) })
                    assertEquals(scrollBefore, scrollPosition(scene), 0.01f, "History must retain a nonzero scroll position on reader return")
                } finally {
                    scene.close()
                    ProductionReaderTestModeBridge.reset()
                    context.closeAndJoin()
                    Dispatchers.resetMain()
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
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
    private suspend fun settle(scene: ImageComposeScene, predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) {
            scene.render().close()
            delay(10)
        }
        scene.render().close()
    }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && flatten(it).any { child -> labels(child).contains(label) } }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
    private fun labels(node: SemanticsNode) = (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()) + (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList())
    private fun key(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType").getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp").invoke(null)
        val factory = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
    }
}
