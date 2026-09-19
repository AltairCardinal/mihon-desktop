package eu.kanade.tachiyomi.ui.browse.author

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorCheckFrequency
import tachiyomi.domain.creator.service.CreatorDiscoveryPreferences
import tachiyomi.domain.creator.service.CreatorDiscoverySchedule
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceCapability
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.CreatorSourceFailure
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.EnabledCreatorSource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AndroidCreatorSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `actual author list saves one draft shows failure and returns focus to real AppBar gear`() {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-settings-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        var fail = false
        val store = AndroidPreferenceStore(activity.get(), shared)
        val guarded = object : PreferenceStore by store {
            override fun getString(key: String, defaultValue: String): Preference<String> {
                val delegate = store.getString(key, defaultValue)
                return object : Preference<String> by delegate {
                    override fun set(value: String) {
                        if (fail) error("disk full") else delegate.set(value)
                    }
                }
            }
        }
        val preferences = CreatorDiscoveryPreferences(guarded)
        try {
            val repository = mockk<CreatorRepository> {
                every { getCreatorsAsFlow() } returns flowOf(emptyList())
                every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            }
            val archiveRepository = mockk<CreatorArchiveRepository> {
                coEvery { getDueWatchSources(any(), any()) } returns emptyList()
            }
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(CreatorArchive(repository, archiveRepository))
            Injekt.addSingleton(preferences)
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))
            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }
            compose.onNodeWithTag("creator-settings-open").assertIsDisplayed().performClick()
            compose.onNodeWithTag("creator-frequency-monthly").performClick()
            assertEquals(CreatorCheckFrequency.DAILY, preferences.current())
            compose.onNodeWithTag("creator-settings-cancel").performClick()
            compose.onNodeWithTag("creator-settings-open").assertIsFocused().performClick()
            compose.onNodeWithTag("creator-frequency-daily").assertIsSelected()
            compose.onNodeWithTag("creator-frequency-weekly").performClick()
            fail = true
            compose.onNodeWithTag("creator-settings-save").performClick()
            compose.onNodeWithText("disk full").assertIsDisplayed()
            compose.onNodeWithTag("creator-frequency-weekly").assertIsSelected()
            assertEquals(CreatorCheckFrequency.DAILY, preferences.current())
            fail = false
            compose.onNodeWithTag("creator-settings-save").performClick()
            compose.onNodeWithTag("creator-settings-open").assertIsFocused()
            compose.onNodeWithTag("creator-settings-save").assertDoesNotExist()
            assertEquals(CreatorCheckFrequency.WEEKLY, CreatorDiscoveryPreferences(store).current())
        } finally {
            activity.pause().stop().destroy()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test fun `mounted author page defaults to followed and all tab uses real SQL repository`(): Unit = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-card-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val repository = CreatorRepositoryImpl(AndroidDatabaseHandler(database, driver))
        try {
            val followed = repository.upsertCreator("Followed writer")
            repository.upsertCreator("Another writer")
            repository.followCreator(followed.id)
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(CreatorArchive(repository, repository))
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))

            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }

            compose.onNodeWithTag("creator-tab-following").assertIsSelected()
            compose.onNodeWithText("Followed writer").assertIsDisplayed()
            compose.onNodeWithText("Another writer").assertDoesNotExist()
            compose.onNodeWithTag("creator-tab-all").performClick()
            compose.onNodeWithText("Followed writer").assertIsDisplayed()
            compose.onNodeWithText("Another writer").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun `mounted narrow author card puts representative shelf below heading and within 320dp`() = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-card-layout-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val repository = CreatorRepositoryImpl(AndroidDatabaseHandler(database, driver))
        try {
            val displayName = "A Very Long Creator Name That Must Wrap Across Several " +
                "Lines In A Narrow Mobile Author List"
            val creator = repository.upsertCreator(displayName)
            repository.followCreator(creator.id)
            listOf(
                "Representative Geometry Work One",
                "Representative Geometry Work Two",
                "Representative Geometry Work Three",
            ).forEachIndexed { index, title ->
                val naturalKey = SourceWorkNaturalKey(41L + index, "/geometry/$index")
                repository.upsertSourceWork(
                    sourceId = naturalKey.sourceId,
                    stableSourceUrl = naturalKey.stableSourceUrl,
                    mangaId = null,
                    title = title,
                    authorText = displayName,
                    artistText = null,
                    thumbnailUrl = "https://source.invalid/geometry-$index.jpg",
                    detailsFetchedAt = 1L,
                )
                repository.upsertSourceWorkCreator(
                    sourceWork = naturalKey,
                    creatorId = creator.id,
                    role = CreatorRole.AUTHOR,
                    order = index.toLong(),
                    origin = CreatorRelationOrigin.USER,
                    verification = CreatorRelationVerification.VERIFIED,
                    sourceText = displayName,
                    confidence = 1.0,
                    evidence = "320dp mounted layout fixture",
                )
            }
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(CreatorArchive(repository, repository))
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))

            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }

            compose.onNodeWithTag("creator-tab-following").assertIsSelected()
            val cardId = "creator-card-${creator.id}"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("$cardId-heading", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() &&
                    (0..2).all { index ->
                        compose.onAllNodesWithTag("$cardId-work-$index", useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
            }
            val heading = compose.onNodeWithTag("$cardId-heading", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("creator-author-list").fetchSemanticsNode().boundsInRoot
            val works = (0..2).map { index ->
                compose.onNodeWithTag("$cardId-work-$index", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
            }

            assertTrue(
                "Author list should stay within the configured 320dp screen: $list",
                list.left >= 0f && list.right <= 320f && list.width > 250f,
            )
            assertEquals("All three representative works should be present", 3, works.size)
            assertTrue(
                "Representative shelf should begin below the full author heading: $heading / $works",
                works.first().top >= heading.bottom,
            )
            assertTrue(
                "All representative works should stay within the 320dp list: $list / $works",
                works.all { it.left >= list.left && it.right <= list.right },
            )
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h900dp-mdpi")
    fun `mounted author work display modes keep title geometry in their production layouts`(): Unit = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-work-layout-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        val repository = CreatorRepositoryImpl(handler)
        try {
            val creator = repository.upsertCreator("A long author name for the narrow work layout")
            repository.followCreator(creator.id)
            val archive = CreatorArchive(repository, repository)
            val work = archive.createWork("A very long representative work title", creator.id, null)
            repeat(3) { index ->
                val key = SourceWorkNaturalKey(71L + index, "/android-layout/$index")
                repository.upsertSourceWork(
                    sourceId = key.sourceId,
                    stableSourceUrl = key.stableSourceUrl,
                    mangaId = null,
                    title = "Version ${index + 1}",
                    authorText = creator.displayName,
                    artistText = null,
                    thumbnailUrl = null,
                    detailsFetchedAt = 1L,
                )
                repository.upsertSourceWorkCreator(
                    sourceWork = key,
                    creatorId = creator.id,
                    role = CreatorRole.AUTHOR,
                    order = index.toLong(),
                    origin = CreatorRelationOrigin.USER,
                    verification = CreatorRelationVerification.VERIFIED,
                    sourceText = creator.displayName,
                    confidence = 1.0,
                    evidence = "Android layout fixture",
                )
                archive.decide(
                    sourceWork = key,
                    workId = work.id,
                    state = WorkDecisionState.CONFIRMED,
                    expectedDecidedAt = null,
                    score = 1.0,
                    evidence = "Android layout fixture",
                    decidedAt = 1L,
                    idempotencyKey = "android-layout:$index",
                )
            }
            val details = GetCreatorDetails(repository)
            val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(
                handler,
                NoopCreatorLibraryIndexWriter,
            )
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(archive)
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))
            Injekt.addSingleton(details)
            Injekt.addSingleton(SetCreatorFollow(repository))
            Injekt.addSingleton(DiscoverCreatorWorks(mockk(relaxed = true), details))
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
            Injekt.addSingleton(ManageCreatorIdentity(repository))
            Injekt.addSingleton(NetworkToLocalManga(mangaRepository))
            val libraryPreferences = LibraryPreferences(store)
            libraryPreferences.displayMode().set(LibraryDisplayMode.ComfortableGrid)
            Injekt.addSingleton(libraryPreferences)

            activity.get().setContent { MaterialTheme { Navigator(AndroidAuthorDetailScreen(creator.id)) } }

            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("creator-work-${work.id}", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val cover = compose.onNodeWithTag("creator-cover-${work.id}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("creator-work-${work.id}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("creator-work-first-seen-${work.id}", useUnmergedTree = true)
                .assertIsDisplayed()
            assertTrue(
                "Comfortable author work layout should put title below cover: $cover / $title",
                cover.bottom <= title.top,
            )

            compose.onNodeWithTag("creator-display-mode-button").performClick()
            compose.onNodeWithTag("creator-display-mode-option-LIST").performClick()
            compose.onNodeWithTag("creator-cover-${work.id}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("creator-work-${work.id}", useUnmergedTree = true).assertIsDisplayed()

            compose.onNodeWithTag("creator-display-mode-button").performClick()
            compose.onNodeWithTag("creator-display-mode-option-COMPACT_GRID").performClick()
            compose.onNodeWithTag("creator-cover-${work.id}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("creator-work-${work.id}", useUnmergedTree = true).assertIsDisplayed()

            compose.onNodeWithTag("creator-source-chip-71").performClick()
            compose.onNodeWithTag("creator-work-card-${work.id}", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("creator-source-version-2", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("creator-source-version-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("creator-source-version-2", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("creator-source-version-3", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("creator-source-cancel", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("creator-work-card-${work.id}", useUnmergedTree = true).assertIsFocused()
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test fun `mounted author card refreshes followed state after repository change`(): Unit = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-follow-refresh-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val repository = CreatorRepositoryImpl(AndroidDatabaseHandler(database, driver))
        try {
            val first = repository.upsertCreator("Already followed")
            val second = repository.upsertCreator("Followed while list is open")
            repository.followCreator(first.id)
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(CreatorArchive(repository, repository))
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))

            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }

            compose.onNodeWithTag("creator-tab-all").performClick()
            val cardTag = "creator-card-${second.id}"
            compose.onNodeWithTag(cardTag).performScrollTo().assertIsDisplayed()
            val followedTag = "creator-card-${second.id}-followed"
            compose.onNodeWithTag(followedTag, useUnmergedTree = true).assertDoesNotExist()
            repository.followCreator(second.id)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(followedTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(followedTag, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag(cardTag).assertTextContains("Followed")

            val alias = "Alias added while author list is open"
            repository.addManualCreatorAlias(second.id, alias)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(alias, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(cardTag).assertTextContains(alias)
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test fun `mounted author scopes keep independent scroll positions`(): Unit = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-scope-scroll-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val repository = CreatorRepositoryImpl(AndroidDatabaseHandler(database, driver))
        try {
            val creators = (1..21).map { index ->
                repository.upsertCreator("Scroll Writer ${index.toString().padStart(2, '0')}")
            }
            creators.dropLast(1).forEach { repository.followCreator(it.id) }
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(CreatorArchive(repository, repository))
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))

            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }

            val listTag = "creator-author-list"
            val followedTargetTag = "creator-card-${creators[15].id}"
            val allTargetTag = "creator-card-${creators.last().id}"
            compose.onNodeWithTag("creator-tab-following").assertIsSelected()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(listTag).performScrollToIndex(15)
            compose.onNodeWithTag(followedTargetTag).assertIsDisplayed()

            compose.onNodeWithTag("creator-tab-all").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(listTag).performScrollToIndex(20)
            compose.onNodeWithTag(allTargetTag).assertIsDisplayed()

            compose.onNodeWithTag("creator-tab-following").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(followedTargetTag).assertIsDisplayed()
            compose.onNodeWithTag("creator-tab-all").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(allTargetTag).assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test fun `mounted author projection retry preserves existing cards`(): Unit = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-projection-retry-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val sqlRepository = CreatorRepositoryImpl(AndroidDatabaseHandler(database, driver))
        val projectionCalls = AtomicInteger()
        try {
            val creator = sqlRepository.upsertCreator("Retry author")
            sqlRepository.followCreator(creator.id)
            val archive = mockk<CreatorArchiveRepository> {
                coEvery { getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any()) } coAnswers {
                    val call = projectionCalls.incrementAndGet()
                    if (call == 1 || call == 3) error("projection failed on attempt $call")
                    val request = invocation.args
                    sqlRepository.getCreatorCardProjectionPage(
                        offset = request[0] as Int,
                        limit = request[1] as Int,
                        followedOnly = request[2] as Boolean,
                        preferredLanguages = request[3] as Set<String>,
                        customCoverExists = request[4] as (Long) -> Boolean,
                        query = request[5] as String,
                    )
                }
            }
            Injekt.addSingleton(GetCreators(sqlRepository))
            Injekt.addSingleton(CreatorArchive(sqlRepository, archive))
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))

            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }

            val firstFailure = "projection failed on attempt 1"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(firstFailure).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Follow authors to get new work suggestions.").assertDoesNotExist()
            compose.onNodeWithText("No authors indexed yet").assertDoesNotExist()
            compose.onNodeWithText("No results found").assertDoesNotExist()

            compose.onNodeWithText("Retry").performClick()
            val cardTag = "creator-card-${creator.id}"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(cardTag).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(cardTag).assertTextContains("Retry author")
            compose.onNodeWithText(firstFailure).assertDoesNotExist()

            val alias = "Alias loaded after second retry"
            sqlRepository.addManualCreatorAlias(creator.id, alias)
            val refreshFailure = "projection failed on attempt 3"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(refreshFailure).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(cardTag).assertTextContains("Retry author")
            compose.onNodeWithText(alias).assertDoesNotExist()
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(alias).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(cardTag).assertTextContains(alias)
            compose.onNodeWithText(refreshFailure).assertDoesNotExist()
            assertEquals(4, projectionCalls.get())
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }

    @Test fun `author detail back restores author scope and scroll`(): Unit = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val shared = activity.get().getSharedPreferences("creator-detail-navigation-ui", Context.MODE_PRIVATE)
        shared.edit().clear().commit()
        val store = AndroidPreferenceStore(activity.get(), shared)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        val repository = CreatorRepositoryImpl(handler)
        try {
            val creators = (1..21).map { index ->
                repository.upsertCreator("Detail author ${index.toString().padStart(2, '0')}")
            }
            creators.forEach { repository.followCreator(it.id) }
            val details = GetCreatorDetails(repository)
            Injekt.addSingleton(GetCreators(repository))
            Injekt.addSingleton(CreatorArchive(repository, repository))
            Injekt.addSingleton(CreatorDiscoveryPreferences(store))
            Injekt.addSingleton(eu.kanade.domain.source.service.SourcePreferences(store))
            Injekt.addSingleton(CoverCache(activity.get()))
            Injekt.addSingleton(details)
            Injekt.addSingleton(SetCreatorFollow(repository))
            Injekt.addSingleton(
                DiscoverCreatorWorks(mockk(relaxed = true), details),
            )
            Injekt.addSingleton(mockk<SourceManager>(relaxed = true))
            Injekt.addSingleton(ManageCreatorIdentity(repository))
            val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(
                handler,
                NoopCreatorLibraryIndexWriter,
            )
            Injekt.addSingleton(NetworkToLocalManga(mangaRepository))
            Injekt.addSingleton(LibraryPreferences(store))

            activity.get().setContent { MaterialTheme { Navigator(SettingsAuthorsScreen()) } }

            val listTag = "creator-author-list"
            compose.onNodeWithTag("creator-tab-following").assertIsSelected()
            compose.onNodeWithTag("creator-tab-all").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty()
            }
            val target = creators.last()
            val targetCardTag = "creator-card-${target.id}"
            compose.onNodeWithTag(listTag).performScrollToIndex(creators.lastIndex)
            compose.onNodeWithTag(targetCardTag).assertIsDisplayed().performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Navigate up").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Navigate up").assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(target.displayName).fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithContentDescription("Navigate up").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("creator-tab-all").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("creator-tab-all").assertIsSelected()
            compose.onNodeWithTag(targetCardTag).assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
            driver.close()
            shared.edit().clear().commit()
            Injekt = previous
        }
    }
}

private class SettingsAuthorsScreen : Screen {
    @Composable override fun Content() {
        TabbedScreen(MR.strings.desktop_ui_authors, persistentListOf(authorsTab()))
    }
}
