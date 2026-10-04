package mihon.desktop.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.inMemoryDesktopPreferenceStore
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.ui.history.HistoryRootScreen
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date

@Isolated
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class HistoryFavoriteComposeIntegrationTest {
    @Test
    fun `duplicate add anyway favorites the clicked work`(
        @TempDir directory: File,
    ) = scenario(directory, "duplicate_anyway")

    @Test
    fun `duplicate open navigates to existing exact detail and leaves target uncollected`(
        @TempDir directory: File,
    ) = scenario(directory, "duplicate_open")

    @Test
    fun `duplicate migration cancel preserves current and target memberships`(
        @TempDir directory: File,
    ) = scenario(directory, "duplicate_migrate_cancel")

    @Test
    fun `duplicate migration confirms existing work into clicked target through real parser`(
        @TempDir directory: File,
    ) = scenario(directory, "duplicate_migrate_confirm")

    @Test
    fun `category manager return retains history query and current selection`(
        @TempDir directory: File,
    ) = scenario(directory, "manage")

    @Test
    fun `favorite without categories adds through the history button`(
        @TempDir directory: File,
    ) = scenario(directory, "none")

    @Test
    fun `favorite uses the selected default category atomically`(
        @TempDir directory: File,
    ) = scenario(directory, "default")

    @Test
    fun `asking for category allows cancel and confirms selected membership`(
        @TempDir directory: File,
    ) = scenario(directory, "ask")

    @Test
    fun `duplicate dialog cancels without changing either membership`(
        @TempDir directory: File,
    ) = scenario(directory, "duplicate")

    private fun scenario(directory: File, branch: String) = runBlocking {
        val context = initDesktopDIForTest(directory, inMemoryDesktopPreferenceStore())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = ImageComposeScene(700, 600, coroutineContext = coroutineContext) {}
        var navigator: Navigator? = null
        var server: MockWebServer? = null
        var testSource: mihon.desktop.test.http.HistoryCatalogTestSource? = null
        try {
            val sourceId = if (branch == "duplicate_migrate_confirm") {
                server = MockWebServer().also {
                    it.start()
                    it.dispatcher = object : Dispatcher() {
                        override fun dispatch(
                            request: RecordedRequest,
                        ) = MockResponse(body = mihon.desktop.test.http.historyCatalogFeed())
                    }
                }
                testSource =
                    mihon.desktop.test.http.HistoryCatalogTestSource(
                        okhttp3.OkHttpClient(),
                        requireNotNull(server).url("/").toString(),
                    ).also {
                        mihon.desktop.test.http.HistoryCatalogTestSourceBridge.install(it)
                    }
                mihon.desktop.test.http.HistoryCatalogTestSource.SOURCE_ID
            } else {
                42L
            }
            val saver = Injekt.get<SaveSourceMangaForDetails>()
            suspend fun save(url: String): Manga = saver.await(
                SManga.create().apply {
                    this.url = url
                    title = "Favorite from history"
                },
                sourceId,
                listOf(
                    SChapter.create().apply {
                        this.url = "$url/chapter"
                        name = "Chapter 1"
                    },
                ),
            )
            val manga = save("/favorite")
            val category = if (branch in setOf("default", "ask", "manage")) {
                Injekt.get<CategoryRepository>().insert(Category(0, "Selected", 0, 0))
                Injekt.get<GetCategories>().await().first { !it.isSystemCategory }
            } else {
                null
            }
            if (branch ==
                "default"
            ) {
                Injekt.get<LibraryPreferences>().defaultCategory().set(requireNotNull(category).id.toInt())
            }
            val duplicate = if (branch.startsWith("duplicate")) {
                save("/duplicate").also {
                    Injekt.get<UpdateLibraryMembership>().await(it, true)
                }
            } else {
                null
            }
            if (branch == "duplicate_migrate_confirm") {
                val current = Injekt.get<ChapterRepository>().getChapterByMangaId(requireNotNull(duplicate).id).single()
                Injekt.get<ChapterRepository>().update(
                    tachiyomi.domain.chapter.model.ChapterUpdate(current.id, read = true, bookmark = true),
                )
            }
            val chapter = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id).single()
            Injekt.get<UpsertHistory>().await(HistoryUpdate(chapter.id, Date(), 1))
            val item = Injekt.get<GetHistory>().subscribe("").first().single()
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                    Navigator(HistoryRootScreen()) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            settle(scene) { nodes(scene).any { tag(it) == "history_favorite_${item.id}" } }
            if (branch == "manage") {
                click(scene, "history_search_open")
                settle(scene) { nodes(scene).any { tag(it) == "history_search_input" } }
                requireNotNull(
                    nodes(scene).single {
                        tag(it) == "history_search_input"
                    }.config[SemanticsActions.SetText].action,
                )(androidx.compose.ui.text.AnnotatedString("Favorite"))
                settle(scene) {
                    nodes(scene).single {
                        tag(it) == "history_search_input"
                    }.config[SemanticsProperties.EditableText].text ==
                        "Favorite"
                }
            }
            pointerClick(scene, "history_favorite_${item.id}")
            when (branch) {
                "none", "default" -> {
                    withTimeout(5_000) { Injekt.get<GetManga>().subscribe(manga.id).first { it?.favorite == true } }
                    assertEquals(
                        listOfNotNull(category?.id),
                        Injekt.get<GetCategories>().await(manga.id).filterNot {
                            it.isSystemCategory
                        }.map { it.id },
                    )
                }
                "ask" -> {
                    settle(scene) { nodes(scene).any { tag(it) == "history_category_confirm" } }
                    fun assertCategoryName() {
                        val checkbox = nodes(scene).single {
                            tag(it) ==
                                "history_category_${requireNotNull(category).id}"
                        }.config
                        assertEquals(
                            listOf(requireNotNull(category).name),
                            checkbox.getOrElse(SemanticsProperties.ContentDescription) { emptyList() },
                        )
                        assertEquals(Role.Checkbox, checkbox[SemanticsProperties.Role])
                        assertEquals(ToggleableState.Off, checkbox[SemanticsProperties.ToggleableState])
                        assertTrue(checkbox[SemanticsActions.OnClick].action != null)
                    }
                    assertCategoryName()
                    click(scene, "history_category_cancel")
                    assertFalse(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                    settle(scene) { nodes(scene).none { tag(it) == "history_category_confirm" } }
                    pointerClick(scene, "history_favorite_${item.id}")
                    settle(scene) { nodes(scene).any { tag(it) == "history_category_${requireNotNull(category).id}" } }
                    assertCategoryName()
                    click(scene, "history_category_${requireNotNull(category).id}")
                    settle(scene) {
                        nodes(scene).single {
                            tag(it) == "history_category_${category.id}"
                        }.config[SemanticsProperties.ToggleableState] ==
                            androidx.compose.ui.state.ToggleableState.On
                    }
                    click(scene, "history_category_confirm")
                    withTimeout(5_000) { Injekt.get<GetManga>().subscribe(manga.id).first { it?.favorite == true } }
                    assertEquals(listOf(category.id), Injekt.get<GetCategories>().await(manga.id).map { it.id })
                }
                "duplicate" -> {
                    settle(scene) { nodes(scene).any { tag(it) == "history_duplicate_cancel" } }
                    click(scene, "history_duplicate_cancel")
                    assertFalse(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                    assertTrue(requireNotNull(Injekt.get<GetManga>().await(requireNotNull(duplicate).id)).favorite)
                }
                "duplicate_anyway" -> {
                    settle(scene) { nodes(scene).any { tag(it) == "history_duplicate_confirm" } }
                    click(scene, "history_duplicate_confirm")
                    withTimeout(5_000) { Injekt.get<GetManga>().subscribe(manga.id).first { it?.favorite == true } }
                    assertTrue(requireNotNull(Injekt.get<GetManga>().await(requireNotNull(duplicate).id)).favorite)
                }
                "duplicate_open" -> {
                    settle(scene) {
                        nodes(scene).any {
                            tag(it) ==
                                "history_duplicate_open_${requireNotNull(duplicate).id}"
                        }
                    }
                    click(scene, "history_duplicate_open_${requireNotNull(duplicate).id}")
                    settle(scene) { navigator?.lastItem is mihon.desktop.ui.library.MangaDetailScreen }
                    assertEquals(mihon.desktop.ui.library.MangaDetailScreen(duplicate.id), navigator?.lastItem)
                    assertFalse(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                }
                "duplicate_migrate_cancel", "duplicate_migrate_confirm" -> {
                    settle(scene) {
                        nodes(scene).any {
                            tag(it) ==
                                "history_duplicate_migrate_${requireNotNull(duplicate).id}"
                        }
                    }
                    click(scene, "history_duplicate_migrate_${requireNotNull(duplicate).id}")
                    settle(scene) { nodes(scene).any { tag(it) == "history_migrate_confirm" } }
                    if (branch == "duplicate_migrate_cancel") {
                        click(scene, "history_migrate_cancel")
                        assertFalse(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                        assertTrue(requireNotNull(Injekt.get<GetManga>().await(duplicate.id)).favorite)
                    } else {
                        click(scene, "history_migrate_confirm")
                        withTimeout(5_000) { Injekt.get<GetManga>().subscribe(manga.id).first { it?.favorite == true } }
                        assertFalse(requireNotNull(Injekt.get<GetManga>().await(duplicate.id)).favorite)
                        val targetChapters = Injekt.get<ChapterRepository>().getChapterByMangaId(manga.id)
                        assertTrue(targetChapters.any { it.id == chapter.id && it.read && it.bookmark })
                        assertTrue(requireNotNull(server).requestCount > 0)
                    }
                }
                "manage" -> {
                    settle(scene) { nodes(scene).any { tag(it) == "history_category_${requireNotNull(category).id}" } }
                    click(scene, "history_category_${requireNotNull(category).id}")
                    settle(scene) {
                        nodes(scene).single {
                            tag(it) == "history_category_${category.id}"
                        }.config[SemanticsProperties.ToggleableState] ==
                            androidx.compose.ui.state.ToggleableState.On
                    }
                    click(scene, "history_category_edit")
                    settle(scene) { nodes(scene).any { tag(it) == "category_new_name" } }
                    requireNotNull(
                        nodes(scene).single {
                            tag(it) == "category_new_name"
                        }.config[SemanticsActions.SetText].action,
                    )(androidx.compose.ui.text.AnnotatedString("New from manager"))
                    settle(scene) {
                        nodes(scene).single {
                            tag(it) == "category_new_name"
                        }.config[SemanticsProperties.EditableText].text ==
                            "New from manager"
                    }
                    click(scene, "category_create")
                    val created =
                        withTimeout(5_000) {
                            Injekt.get<GetCategories>().subscribe().first {
                                it.any { category ->
                                    category.name ==
                                        "New from manager"
                                }
                            }.single { it.name == "New from manager" }
                        }
                    click(scene, "category_manage_done")
                    settle(scene) { nodes(scene).any { tag(it) == "history_category_${created.id}" } }
                    assertEquals(
                        androidx.compose.ui.state.ToggleableState.On,
                        nodes(scene).single {
                            tag(it) ==
                                "history_category_${category.id}"
                        }.config[SemanticsProperties.ToggleableState],
                    )
                    click(scene, "history_category_cancel")
                    settle(scene) { nodes(scene).none { tag(it) == "history_category_confirm" } }
                    assertEquals(
                        "Favorite",
                        nodes(scene).single {
                            tag(it) == "history_search_input"
                        }.config[SemanticsProperties.EditableText].text,
                    )
                    assertFalse(requireNotNull(Injekt.get<GetManga>().await(manga.id)).favorite)
                }
            }
        } finally {
            scene.close()
            testSource?.let { mihon.desktop.test.http.HistoryCatalogTestSourceBridge.clear(it) }
            server?.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    private suspend fun settle(scene: ImageComposeScene, ready: () -> Boolean) = withTimeout(5_000) {
        do {
            scene.render().close()
            yield()
        } while (!ready())
    }
    private fun pointerClick(scene: ImageComposeScene, name: String) {
        val node = nodes(scene).single { tag(it) == name }
        val point = node.boundsInRoot.center
        scene.sendPointerEvent(
            androidx.compose.ui.input.pointer.PointerEventType.Press,
            point,
            button = androidx.compose.ui.input.pointer.PointerButton.Primary,
        )
        scene.sendPointerEvent(
            androidx.compose.ui.input.pointer.PointerEventType.Release,
            point,
            button = androidx.compose.ui.input.pointer.PointerButton.Primary,
        )
    }

    private fun click(scene: ImageComposeScene, name: String) {
        assertTrue(requireNotNull(nodes(scene).single { tag(it) == name }.config[SemanticsActions.OnClick].action)())
    }
    private fun tag(
        node: SemanticsNode,
    ) = if (node.config.contains(SemanticsProperties.TestTag)) node.config[SemanticsProperties.TestTag] else null
    private fun nodes(
        scene: ImageComposeScene,
    ): List<SemanticsNode> = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
