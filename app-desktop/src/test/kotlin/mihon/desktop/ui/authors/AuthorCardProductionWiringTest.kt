package mihon.desktop.ui.authors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.CreatorCardProjectionPage
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.creator.service.WorkTitleNormalizer
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalComposeUiApi::class)
class AuthorCardProductionWiringTest {

    @TempDir
    lateinit var directory: java.nio.file.Path

    @Test
    fun `mounted authors root defaults to followed and shows real repository representative work`(): Unit = runBlocking {
        mountedAuthorsScenario(gateRestoredScope = false)
    }

    @Test
    fun `scope restoration survives an empty loading frame before remount`(): Unit = runBlocking {
        mountedAuthorsScenario(gateRestoredScope = true)
    }

    @Test
    fun `mounted root applies saved scope viewport when its list state starts at zero`(): Unit = runBlocking {
        mountedAuthorsScenario(gateRestoredScope = false, seedSavedScope = true)
    }

    private suspend fun mountedAuthorsScenario(gateRestoredScope: Boolean, seedSavedScope: Boolean = false) = kotlinx.coroutines.coroutineScope {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val repository = CreatorRepositoryImpl(handler)

        var pageGate: CompletableDeferred<Unit>? = null
        val archiveRepository = object : CreatorArchiveRepository by repository {
            override suspend fun getCreatorCardProjectionPage(
                offset: Int,
                limit: Int,
                followedOnly: Boolean,
                preferredLanguages: Set<String>,
                customCoverExists: (Long) -> Boolean,
                query: String,
                preferredDisplayScript: WorkTitleNormalizer.DisplayScript?,
            ): CreatorCardProjectionPage {
                pageGate?.await()
                return repository.getCreatorCardProjectionPage(
                    offset,
                    limit,
                    followedOnly,
                    preferredLanguages,
                    customCoverExists,
                    query,
                    preferredDisplayScript,
                )
            }
        }
        val followedCreator = repository.upsertCreator(
            "A Very Long Followed Author Name That Must Wrap Before Its Representative Covers",
        )
        val otherCreator = repository.upsertCreator("Another Author")
        val lateCreators = (1..50).map { index ->
            repository.upsertCreator("Late Author ${index.toString().padStart(2, '0')}")
        }
        val lateCreator = lateCreators.last()
        repository.addManualCreatorAlias(lateCreator.id, "Late Author Search Alias")
        repository.followCreator(followedCreator.id)
        lateCreators.take(19).forEach { repository.followCreator(it.id) }
        val representativeWorks = listOf(
            "/representative" to "Representative Work From SQLite",
            "/representative-2" to "Representative Work From SQLite Two",
            "/representative-3" to "Representative Work From SQLite Three",
        )
        representativeWorks.forEachIndexed { index, (sourceUrl, title) ->
            val workKey = SourceWorkNaturalKey(sourceId = 11L, stableSourceUrl = sourceUrl)
            repository.upsertSourceWork(
                workKey.sourceId,
                workKey.stableSourceUrl,
                null,
                title,
                followedCreator.displayName,
                null,
                null,
                index.toLong() + 1L,
            )
            repository.upsertSourceWorkCreator(
                sourceWork = workKey,
                creatorId = followedCreator.id,
                role = CreatorRole.AUTHOR,
                order = index.toLong(),
                origin = CreatorRelationOrigin.AUTOMATIC,
                verification = CreatorRelationVerification.VERIFIED,
                sourceText = followedCreator.displayName,
                confidence = 1.0,
                evidence = "mounted author card fixture",
            )
        }

        val indexer = CreatorLibraryIndexer(
            mangaSource = object : CreatorLibraryMangaSource {
                override suspend fun countLibraryMangaForCreatorIndex(): Long = 0L
                override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> = emptyList()
            },
            indexWriter = NoopCreatorLibraryIndexWriter,
            extractCreators = ExtractCreatorsFromManga(),
        )
        val preferenceNode = java.util.prefs.Preferences.userRoot().node("/mihon-tests/ax01-author-${UUID.randomUUID()}")
        val desktopPreferences = DesktopAppPreferences(DesktopPreferenceStore(preferenceNode)).apply {
            enabledLanguages.set(setOf("en"))
        }
        val desktopCoverStore = DesktopCustomCoverStore(File(directory.toFile(), "custom-covers"))
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { getCreatorDetails } returns GetCreatorDetails(repository)
            every { setCreatorFollow } returns SetCreatorFollow(repository)
            every { creatorArchiveRepository } returns repository
            every { creatorArchive } returns CreatorArchive(repository, archiveRepository)
            every { manageCreatorIdentity } returns ManageCreatorIdentity(repository)
            every { creatorLibraryIndexer } returns indexer
            every { creatorDiscoveryPreferences } returns null
            every { creatorDiscoveryScheduler } returns null
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { libraryPreferences } returns null
            every { appPreferences } returns desktopPreferences
            every { customCoverStore } returns desktopCoverStore
        }
        var model: AuthorsRootScreenModel? = null
        val observeModel = gateRestoredScope || seedSavedScope
        if (observeModel) {
            mockkObject(AuthorsScreenModelFactory)
            every { AuthorsScreenModelFactory.root(dependencies) } answers {
                callOriginal().also { model = it }
            }
        }
        suspend fun awaitScope(followedOnly: Boolean) {
            if (observeModel) {
                withTimeout(5_000) {
                    checkNotNull(model).state.first { it.followedOnly == followedOnly && !it.loading }
                }
            }
        }
        val scene = ImageComposeScene(320, 560, coroutineContext = coroutineContext) {}
        try {
            listOf("light", "dark").forEach { theme ->
                scene.setContent {
                    MaterialTheme(colorScheme = if (theme == "dark") darkColorScheme() else lightColorScheme()) {
                        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                            TabNavigator(AuthorsTab) { AuthorTabReentryHost() }
                        }
                    }
                }
                withTimeout(5_000) {
                    while (followedCreator.displayName !in texts(scene)) {
                        scene.render()
                        delay(10)
                    }
                }

                val defaultFollowing = nodes(scene).singleOrNull { node ->
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-tab-following"
                }
                assertTrue(defaultFollowing != null, "Authors should expose a Following scope on entry")
                assertTrue(defaultFollowing!!.config.getOrElse(SemanticsProperties.Selected) { false })

                assertFalse(
                    otherCreator.displayName in texts(scene),
                    "Entering Authors should show followed authors only",
                )
                assertTrue("Representative Work From SQLite" in texts(scene))
                assertTrue("Representative Work From SQLite Two" in texts(scene))
                assertTrue("Representative Work From SQLite Three" in texts(scene))
                assertTrue(MR.strings.desktop_ui_followed.localized(Locale.getDefault()) in texts(scene))
                val unmergedNodes = unmergedNodes(scene)
                val authorNameNode = unmergedNodes.single { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .any { it.text == followedCreator.displayName }
                }
                val representativeCoverNodes = unmergedNodes.filter { node ->
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("creator-cover-")
                }
                assertTrue(representativeCoverNodes.size == 3, "The followed author should show three representative covers")
                assertTrue(
                    representativeCoverNodes.all { it.boundsInRoot.top >= authorNameNode.boundsInRoot.bottom },
                    "Representative covers should sit below the author card heading at 320dp",
                )
                assertTrue(representativeCoverNodes.all { it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= 320f })

                val screenshot = checkNotNull(scene.render().encodeToData()).bytes
                val output = java.nio.file.Paths.get("build/ax01/author-card-320-$theme.png")
                java.nio.file.Files.createDirectories(output.parent)
                java.nio.file.Files.write(output, screenshot)
            }

            val followingList = nodes(scene).single { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
            }
            checkNotNull(followingList.config[SemanticsActions.ScrollToIndex].action).invoke(19)
            withTimeout(5_000) {
                while (lateCreators[18].displayName !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }

            if (seedSavedScope) {
                // The persisted viewport can outlive the inactive Compose list's measured position.
                checkNotNull(model).saveScrollPosition(false, index = 49, offset = 1, lastVisibleIndex = 51)
                clickableTagNode(scene, "creator-tab-all").config[SemanticsActions.OnClick].action?.invoke()
                awaitScope(followedOnly = false)
                assertEquals(52, checkNotNull(model).state.value.cards.size)
                val restored = runCatching {
                    withTimeout(5_000) {
                        while (lateCreator.displayName !in texts(scene)) {
                            scene.render()
                            delay(10)
                        }
                    }
                }.isSuccess
                assertTrue(restored, "The mounted scope must apply its saved nonzero viewport, not its initial zero")
                assertTrue(checkNotNull(model).scrollPosition(false).index > 0)
                val restoredList = nodes(scene).single { node ->
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
                }
                checkNotNull(restoredList.config[SemanticsActions.ScrollToIndex].action).invoke(0)
                withTimeout(5_000) {
                    while (checkNotNull(model).scrollPosition(false).index != 0) {
                        scene.render()
                        delay(10)
                    }
                }
                checkNotNull(model).retry()
                awaitScope(followedOnly = false)
                withTimeout(5_000) {
                    while (otherCreator.displayName !in texts(scene)) {
                        scene.render()
                        delay(10)
                    }
                }
                assertEquals(0, checkNotNull(model).scrollPosition(false).index, "Refresh must preserve subsequent user scrolling")
                assertTrue(lateCreator.displayName !in texts(scene), "Refresh must not replay the scope's previous restore target")
                return@coroutineScope
            }
            clickableTagNode(scene, "creator-tab-all").config[SemanticsActions.OnClick].action?.invoke()
            awaitScope(followedOnly = false)
            withTimeout(5_000) {
                while (otherCreator.displayName !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }
            assertTrue(
                MR.strings.creator_no_representative_work.localized(Locale.getDefault()) in texts(scene),
                "Authors without representative works should show the empty card label",
            )

            val authorList = nodes(scene).single { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
            }
            val initialScrollRange = authorList.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
            checkNotNull(authorList.config[SemanticsActions.ScrollToIndex].action).invoke(49)
            withTimeout(5_000) {
                var expanded = false
                while (!expanded) {
                    scene.render()
                    val currentList = nodes(scene).single { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
                    }
                    expanded = currentList.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > initialScrollRange
                    delay(10)
                }
            }
            val loadedList = nodes(scene).single { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
            }
            checkNotNull(loadedList.config[SemanticsActions.ScrollToIndex].action).invoke(51)
            withTimeout(5_000) {
                while (lateCreator.displayName !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }
            clickableTextNode(scene, lateCreator.displayName).config[SemanticsActions.OnClick].action?.invoke()
            val backDescription = MR.strings.action_bar_up_description.localized(Locale.getDefault())
            withTimeout(5_000) {
                while (backDescription !in nodes(scene).flatMap { node ->
                        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    }
                ) {
                    scene.render()
                    delay(10)
                }
            }
            val certaintyFilterLabels = listOf(
                MR.strings.desktop_ui_language_confirmed_count.localized(Locale.getDefault(), 0),
                MR.strings.desktop_ui_language_possible_count.localized(Locale.getDefault(), 0),
                MR.strings.desktop_ui_language_needs_review_count.localized(Locale.getDefault(), 0),
            )
            assertTrue(
                certaintyFilterLabels.none { it in texts(scene) },
                "Ordinary author detail should not expose language certainty filters",
            )
            clickableDescriptionNode(scene, backDescription).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while (
                    backDescription in nodes(scene).flatMap { node ->
                        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                    } || nodes(scene).none { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
                    }
                ) {
                    scene.render()
                    delay(10)
                }
            }
            assertTrue(
                clickableTagNode(scene, "creator-tab-all").config.getOrElse(SemanticsProperties.Selected) { false },
                "Returning from detail should restore the All authors scope",
            )
            assertTrue(lateCreator.displayName in texts(scene), "Returning from detail should restore the selected author row")

            val savedAllPosition = model?.scrollPosition(followedOnly = false)
            clickableTagNode(scene, "creator-tab-following")
                .config[SemanticsActions.OnClick].action?.invoke()
            awaitScope(followedOnly = true)
            val followingScopeRestored = runCatching {
                withTimeout(5_000) {
                    while (!clickableTagNode(scene, "creator-tab-following")
                            .config.getOrElse(SemanticsProperties.Selected) { false }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
            }.isSuccess
            val followedRowRestored = runCatching {
                withTimeout(5_000) {
                    while (lateCreators[18].displayName !in texts(scene)) {
                        scene.render()
                        delay(10)
                    }
                }
            }.isSuccess
            val returnedList = nodes(scene).singleOrNull { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
            }
            val returnedListRange = returnedList?.config?.let { config ->
                runCatching { config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }.getOrNull()
            }
            assertTrue(
                followedRowRestored,
                "Switching back to Following should restore Late Author 19; " +
                    "selected=$followingScopeRestored, scrollRange=$returnedListRange, " +
                    "visibleTexts=${texts(scene).joinToString(" | ")}",
            )
            if (gateRestoredScope) {
                assertEquals(
                    savedAllPosition,
                    checkNotNull(model).scrollPosition(followedOnly = false),
                    "Mounting Following must preserve the inactive All viewport",
                )
            }
            if (gateRestoredScope) pageGate = CompletableDeferred()
            clickableTagNode(scene, "creator-tab-all").config[SemanticsActions.OnClick].action?.invoke()
            if (gateRestoredScope) {
                withTimeout(5_000) { checkNotNull(model).state.first { !it.followedOnly && it.loading } }
                withTimeout(5_000) {
                    while (nodes(scene).any {
                            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
                        }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
                checkNotNull(pageGate).complete(Unit)
                pageGate = null
            }
            awaitScope(followedOnly = false)
            val allScopeRestored = runCatching {
                withTimeout(5_000) {
                    while (!clickableTagNode(scene, "creator-tab-all")
                            .config.getOrElse(SemanticsProperties.Selected) { false }
                    ) {
                        scene.render()
                        delay(10)
                    }
                }
            }.isSuccess
            val allTailRestored = runCatching {
                withTimeout(5_000) {
                    while (lateCreator.displayName !in texts(scene)) {
                        scene.render()
                        delay(10)
                    }
                }
            }.isSuccess
            val allReturnedList = nodes(scene).singleOrNull { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
            }
            val allReturnedListRange = allReturnedList?.config?.let { config ->
                runCatching { config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }.getOrNull()
            }
            assertTrue(
                allTailRestored,
                "Switching back to All should restore Late Author 50; " +
                    "selected=$allScopeRestored, scrollRange=$allReturnedListRange, " +
                    "visibleTexts=${texts(scene).joinToString(" | ")}",
            )

            val search = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            checkNotNull(search.config[SemanticsActions.SetText].action)
                .invoke(AnnotatedString("Late Author Search Alias"))
            withTimeout(5_000) {
                while (true) {
                    val filteredList = nodes(scene).singleOrNull { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
                    }
                    val filteredRange = filteredList?.config?.let { config ->
                        runCatching { config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }.getOrNull()
                    }
                    if (lateCreator.displayName in texts(scene) && filteredRange != null && filteredRange < initialScrollRange / 2f) {
                        break
                    }
                    scene.render()
                    delay(10)
                }
            }
            assertFalse(otherCreator.displayName in texts(scene), "Author search should match the alias after page one")

            val clearSearch = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            checkNotNull(clearSearch.config[SemanticsActions.SetText].action).invoke(AnnotatedString(""))
            val unfilteredAuthorsVisible = runCatching {
                withTimeout(5_000) {
                    while (otherCreator.displayName !in texts(scene)) {
                        scene.render()
                        delay(10)
                    }
                }
            }.isSuccess
            val allSelectedAfterClear = clickableTagNode(scene, "creator-tab-all")
                .config.getOrElse(SemanticsProperties.Selected) { false }
            val listAfterClear = nodes(scene).singleOrNull { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-author-list"
            }
            val listRangeAfterClear = listAfterClear?.config?.let { config ->
                runCatching { config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }.getOrNull()
            }
            val searchConfigAfterClear = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.config
            assertTrue(
                unfilteredAuthorsVisible,
                "Clearing search should show the first All author; " +
                    "allSelected=$allSelectedAfterClear, scrollRange=$listRangeAfterClear, " +
                    "search=$searchConfigAfterClear, visibleTexts=${texts(scene).joinToString(" | ")}",
            )
            clickableTextNode(scene, "Leave authors").config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while ("Away author tab" !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }
            clickableTextNode(scene, "Return to authors").config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while (nodes(scene).none { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-tab-following" &&
                            node.config.getOrElse(SemanticsProperties.Selected) { false }
                    }
                ) {
                    scene.render()
                    delay(10)
                }
            }
            withTimeout(5_000) {
                while (followedCreator.displayName !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }
            assertFalse(otherCreator.displayName in texts(scene), "Re-entering Authors should restore the Followed page")
            val followingTab = nodes(scene).single { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-tab-following"
            }
            assertTrue(followingTab.config.getOrElse(SemanticsProperties.Selected) { false })

            lateCreators.take(19).forEach { repository.unfollowCreator(it.id) }
            repository.unfollowCreator(followedCreator.id)
            val followedEmptyMessage = MR.strings.creator_following_empty.localized(Locale.getDefault())
            withTimeout(5_000) {
                while (followedEmptyMessage !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }
            assertFalse(followedCreator.displayName in texts(scene))
            val allAuthorsAction = MR.strings.creator_following_empty_action.localized(Locale.getDefault())
            clickableTextNode(scene, allAuthorsAction).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while (otherCreator.displayName !in texts(scene)) {
                    scene.render()
                    delay(10)
                }
            }
        } finally {
            scene.close()
            if (observeModel) {
                unmockkObject(AuthorsScreenModelFactory)
                model?.onDispose()
            }
            indexer.stop()
            handler.close()
            preferenceNode.removeNode()
        }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
    }

    private fun clickableTextNode(scene: ImageComposeScene, expected: String): SemanticsNode =
        nodes(scene).single { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == expected }
        }

    private fun clickableDescriptionNode(scene: ImageComposeScene, expected: String): SemanticsNode =
        nodes(scene).single { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                expected in node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        }

    private fun clickableTagNode(scene: ImageComposeScene, expected: String): SemanticsNode =
        nodes(scene).single { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrElse(SemanticsProperties.TestTag) { "" } == expected
        }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        flatten(owner.rootSemanticsNode)
    }

    private fun unmergedNodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        flatten(owner.unmergedRootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}

@Composable
private fun AuthorTabReentryHost() {
    val tabNavigator = LocalTabNavigator.current
    Column {
        Row {
            TextButton(onClick = { tabNavigator.current = AwayAuthorTab }) { Text("Leave authors") }
            TextButton(onClick = { tabNavigator.current = AuthorsTab }) { Text("Return to authors") }
        }
        CurrentTab()
    }
}

private object AwayAuthorTab : Tab {
    override val options: TabOptions
        @Composable
        get() {
            val icon = rememberVectorPainter(Icons.Default.Person)
            return remember {
                TabOptions(
                    index = 99u,
                    title = "Away author tab",
                    icon = icon,
                )
            }
        }

    @Composable
    override fun Content() {
        Text("Away author tab")
    }
}
