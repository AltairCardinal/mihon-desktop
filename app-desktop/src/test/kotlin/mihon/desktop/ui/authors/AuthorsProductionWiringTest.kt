package mihon.desktop.ui.authors

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.ListedMangaForDetails
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorCardProjection
import tachiyomi.domain.creator.model.CreatorCardProjectionPage
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.CreatorDiscoveryResult
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.creator.service.CreatorWorkPresentationExclusions
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.nio.file.Path
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.service.ChapterVariantType
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionProjection
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.SourceDateQualityStatus
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.History
import tachiyomi.data.Mangas


@OptIn(ExperimentalComposeUiApi::class)
class AuthorsProductionWiringTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `desktop archive state applies shared work search and source contract`() {
        tachiyomi.data.creator.verifyCreatorWorkFilterProjection { archive, filter ->
            AuthorDetailState(workArchive = archive, workFilter = filter).visibleWorkArchive
        }
    }

    @Test
    fun `source chooser date labels distinguish trusted and suspect projections`() {
        val version = SourceWorkArchiveVersion(
            sourceWorkId = 1L,
            naturalKey = SourceWorkNaturalKey(241L, "/date-work"),
            mangaId = null,
            title = "Date work",
            readingLanguage = LanguageProjectionContract(
                dimension = LanguageDimension.READING,
                tag = "en",
                certainty = LanguageCertainty.CONFIRMED,
                evidenceKind = LanguageEvidenceKind.STRUCTURED_METADATA,
            ),
            chapterCount = 3L,
            inLibrary = false,
            detailsFetchedAt = null,
            lastSeenAt = 1L,
            decision = null,
            latestChapterAt = 1_735_689_600_000L,
            latestChapterDateQuality = SourceDateQualityStatus.TRUSTED,
        )

        assertTrue(latestChapterDateLabel(version).contains("2025-"))
        assertEquals(
            MR.strings.creator_work_latest_date_pending.localized(),
            latestChapterDateLabel(version.copy(latestChapterDateQuality = SourceDateQualityStatus.SUSPECT)),
        )
    }

    @Test
    fun `desktop author navigation marks unread work only after consumer confirmation`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val creator = repository.upsertCreator("Navigation Author")
        val sourceWork = SourceWorkNaturalKey(42L, "/desktop-navigation-unread")
        repository.upsertWatchPolicy(
            ArchiveWatchPolicy(creator.id, true, 1_000L, setOf(sourceWork.sourceId), emptySet()),
            now = 1L,
        )
        repository.upsertSourceWork(42L, sourceWork.stableSourceUrl, 321L, "Navigation Work", creator.displayName, null, null, 2L)
        repository.upsertSourceWorkCreator(
            sourceWork,
            creator.id,
            CreatorRole.AUTHOR,
            0L,
            CreatorRelationOrigin.AUTOMATIC,
            CreatorRelationVerification.VERIFIED,
            creator.displayName,
            1.0,
            "fixture",
        )
        repository.commitDiscovery(
            DiscoveryCommit(
                creator.id,
                sourceWork,
                DiscoveryKind.NEW_WORK_CANDIDATE,
                "fixture",
                1L,
                100L,
                "TEST",
                "desktop-navigation-unread",
            ),
        )
        val model = AuthorDetailScreenModel(
            creatorId = creator.id,
            collectOnOpen = false,
            getCreatorDetails = GetCreatorDetails(repository),
            getCreators = GetCreators(repository),
            setCreatorFollow = SetCreatorFollow(repository),
            discoveryScheduler = null,
            creatorArchive = CreatorArchive(repository, repository),
            identityActions = AuthorIdentityActions(ManageCreatorIdentity(repository)),
            saveSourceMangaForDetails = mockk(),
        )
        try {
            val effect = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                model.effects.filterIsInstance<AuthorDetailEffect.OpenManga>().first()
            }
            model.openVersion(
                SourceWorkArchiveVersion(
                    sourceWorkId = 1L,
                    naturalKey = sourceWork,
                    mangaId = 321L,
                    title = "Navigation Work",
                    readingLanguage = LanguageProjectionContract(
                        LanguageDimension.READING,
                        "und",
                        LanguageCertainty.UNKNOWN,
                        LanguageEvidenceKind.UNKNOWN,
                    ),
                    chapterCount = 0L,
                    inLibrary = false,
                    detailsFetchedAt = null,
                    lastSeenAt = 2L,
                    decision = null,
                ),
            ).join()
            val opened = effect.await()
            assertEquals(321L, opened.mangaId)
            assertEquals(creator.id, opened.creatorId)
            assertTrue(repository.getUnreadWorkDiscoveries(10L).isNotEmpty())

            model.markWorkSeenAfterNavigation(opened.creatorId, opened.sourceWork)
            assertTrue(repository.getUnreadWorkDiscoveries(10L).isEmpty())
        } finally {
            model.onDispose()
            driver.close()
        }
    }

    @Test
    fun `desktop detail keeps a script title when a lower source arrives and after model recreation`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(
            Database(driver, historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter)), driver,
        )
        val preferenceNode = Preferences.userRoot().node("/mihon-tests/author-title-${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferenceNode))
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val creator = repository.upsertCreator("Title Author")
        suspend fun add(sourceId: Long, url: String, title: String) {
            val key = SourceWorkNaturalKey(sourceId, url)
            repository.upsertSourceWork(sourceId, url, null, title, creator.displayName, null, null, 1L)
            repository.upsertSourceWorkCreator(
                key, creator.id, CreatorRole.AUTHOR, 0L, CreatorRelationOrigin.AUTOMATIC,
                CreatorRelationVerification.VERIFIED, creator.displayName, 1.0, "fixture",
            )
        }
        fun model(script: tachiyomi.domain.creator.service.WorkTitleNormalizer.DisplayScript) = AuthorDetailScreenModel(
            creatorId = creator.id,
            collectOnOpen = false,
            getCreatorDetails = GetCreatorDetails(repository),
            getCreators = GetCreators(repository),
            setCreatorFollow = SetCreatorFollow(repository),
            discoveryScheduler = null,
            creatorArchive = CreatorArchive(repository, repository),
            identityActions = AuthorIdentityActions(ManageCreatorIdentity(repository)),
            libraryPreferences = LibraryPreferences(DesktopPreferenceStore(preferenceNode)),
            preferredDisplayScript = script,
        )
        var detail: AuthorDetailScreenModel? = null
        try {
            add(20L, "/traditional", "詭譎屋")
            add(30L, "/simplified", "诡谲屋")
            detail = model(tachiyomi.domain.creator.service.WorkTitleNormalizer.DisplayScript.TRADITIONAL)
            withTimeout(5_000) { checkNotNull(detail).state.first { it.presentationGroups.singleOrNull()?.sourceCount == 2 } }
            assertEquals("詭譎屋", checkNotNull(detail).state.value.presentationGroups.single().title)

            add(10L, "/new-traditional", "《詭譎屋》")
            withTimeout(5_000) { checkNotNull(detail).state.first { it.presentationGroups.singleOrNull()?.sourceCount == 3 } }
            assertEquals("詭譎屋", checkNotNull(detail).state.value.presentationGroups.single().title)
            detail?.onDispose()
            detail = model(tachiyomi.domain.creator.service.WorkTitleNormalizer.DisplayScript.TRADITIONAL)
            withTimeout(5_000) { checkNotNull(detail).state.first { it.presentationGroups.singleOrNull()?.sourceCount == 3 } }
            assertEquals("詭譎屋", checkNotNull(detail).state.value.presentationGroups.single().title)
            detail?.onDispose()
            detail = model(tachiyomi.domain.creator.service.WorkTitleNormalizer.DisplayScript.SIMPLIFIED)
            withTimeout(5_000) { checkNotNull(detail).state.first { it.presentationGroups.singleOrNull()?.sourceCount == 3 } }
            assertEquals("诡谲屋", checkNotNull(detail).state.value.presentationGroups.single().title)
        } finally {
            detail?.onDispose()
            handler.close()
            driver.close()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `mounted split display focuses the surviving new source card`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(
            Database(driver, historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter)), driver,
        )
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val creator = repository.upsertCreator("Focus Author")
        listOf(20L to "詭譎屋", 30L to "诡谲屋").forEach { (sourceId, title) ->
            val key = SourceWorkNaturalKey(sourceId, "/work-$sourceId")
            repository.upsertSourceWork(sourceId, key.stableSourceUrl, null, title, creator.displayName, null, null, 1L)
            repository.upsertSourceWorkCreator(
                key, creator.id, CreatorRole.AUTHOR, 0L, CreatorRelationOrigin.AUTOMATIC,
                CreatorRelationVerification.VERIFIED, creator.displayName, 1.0, "fixture",
            )
        }
        val preferenceNode = Preferences.userRoot().node("/mihon-tests/author-focus-${UUID.randomUUID()}")
        val sources = mockk<tachiyomi.domain.source.service.SourceManager> {
            every { get(any<Long>()) } returns null
            every { getOrStub(any()) } answers {
                val sourceId = firstArg<Long>()
                mockk { every { id } returns sourceId; every { name } returns "Source $sourceId" }
            }
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { getCreatorDetails } returns GetCreatorDetails(repository)
            every { setCreatorFollow } returns SetCreatorFollow(repository)
            every { creatorArchive } returns CreatorArchive(repository, repository)
            every { creatorArchiveRepository } returns repository
            every { manageCreatorIdentity } returns ManageCreatorIdentity(repository)
            every { creatorDiscoveryScheduler } returns null
            every { sourceManager } returns sources
            every { saveSourceMangaForDetails } returns mockk()
            every { libraryPreferences } returns LibraryPreferences(DesktopPreferenceStore(preferenceNode))
        }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        fun tagged(tag: String) = nodes(scene, unmerged = true).first { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
        }
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creator.id))
                }
            }
            val oldCard = "creator-work-card-title:诡谲屋"
            val newCard = "creator-work-card-source:20:/work-20"
            withTimeout(5_000) {
                while (nodes(scene, unmerged = true).none {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == oldCard
                    }) { scene.render(); kotlinx.coroutines.delay(10) }
            }
            tagged(oldCard).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while (nodes(scene).count { node ->
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                            .any { it.text == MR.strings.creator_work_separate_display.localized() }
                    } < 2) { scene.render(); kotlinx.coroutines.delay(10) }
            }
            nodes(scene).first { node ->
                node.config.contains(SemanticsActions.OnClick) &&
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .any { it.text == MR.strings.creator_work_separate_display.localized() }
            }.config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while (nodes(scene, unmerged = true).none { node ->
                        node.config.getOrElse(SemanticsProperties.TestTag) { "" } == newCard &&
                            node.config.getOrElse(SemanticsProperties.Focused) { false }
                    }) { scene.render(); kotlinx.coroutines.delay(10) }
            }
            assertTrue(nodes(scene, unmerged = true).none {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == oldCard
            })
            assertTrue(tagged(newCard).config.getOrElse(SemanticsProperties.Focused) { false })
        } finally {
            scene.close()
            handler.close()
            driver.close()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `authors root defaults to the followed scope`() {
        assertTrue(AuthorsRootState().followedOnly)
    }

    @Test
    fun `mounted author detail exposes a display mode control backed by device preferences`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val creator = repository.upsertCreator("Display mode author")
        val preferenceNode = Preferences.userRoot().node("mihon/ax02/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferenceNode))
        val archive = CreatorArchive(repository, repository)
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { getCreatorDetails } returns GetCreatorDetails(repository)
            every { setCreatorFollow } returns SetCreatorFollow(repository)
            every { creatorArchive } returns archive
            every { manageCreatorIdentity } returns ManageCreatorIdentity(repository)
            every { creatorDiscoveryScheduler } returns null
            every { sourceManager } returns mockk(relaxed = true)
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { libraryPreferences } returns preferences
        }
        val scene = ImageComposeScene(320, 900, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                androidx.compose.material3.MaterialTheme {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(AuthorDetailScreen(creator.id)) {
                            cafe.adriel.voyager.navigator.CurrentScreen()
                        }
                    }
                }
            }
            withTimeout(5_000) {
                while ("Display mode author" !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }

            assertTrue(
                nodes(scene).any {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-display-mode-button"
                },
                "The mounted author detail should expose its display mode control",
            )
        } finally {
            scene.close()
            handler.close()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `mounted display mode follows shelf until explicit selection and survives remount`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            ),
            driver,
        )
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val creator = repository.upsertCreator("Layout author")
        val archive = CreatorArchive(repository, repository)
        val workTitle = "A long title that must fit in the author grid"
        val work = archive.createWork(workTitle, creator.id, null)
        val manga = mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = 241L,
                    url = "/layout-work",
                    title = workTitle,
                    favorite = true,
                ),
            ),
        ).single()
        val key = tachiyomi.domain.creator.model.SourceWorkNaturalKey(manga.source, manga.url)
        repository.upsertSourceWork(manga.source, manga.url, manga.id, manga.title, creator.displayName, null, null, null)
        repository.upsertSourceWorkCreator(
            key,
            creator.id,
            CreatorRole.AUTHOR,
            0,
            tachiyomi.domain.creator.model.CreatorRelationOrigin.AUTOMATIC,
            tachiyomi.domain.creator.model.CreatorRelationVerification.VERIFIED,
            creator.displayName,
            1.0,
            "ax02 fixture",
        )
        archive.decide(key, work.id, WorkDecisionState.CONFIRMED, null, 1.0, "ax02 fixture", manga.source, "layout")

        val preferenceNode = Preferences.userRoot().node("mihon/ax02/${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferenceNode))
        preferences.displayMode().set(LibraryDisplayMode.ComfortableGrid)
        assertTrue(!preferences.creatorWorkDisplayModeOverride().isSet())
        val sources = mockk<tachiyomi.domain.source.service.SourceManager> {
            every { get(any<Long>()) } returns null
            every { getOrStub(any()) } answers {
                val sourceId = firstArg<Long>()
                mockk {
                    every { id } returns sourceId
                    every { name } returns "Source $sourceId"
                }
            }
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { getCreatorDetails } returns GetCreatorDetails(repository)
            every { setCreatorFollow } returns SetCreatorFollow(repository)
            every { creatorArchive } returns archive
            every { manageCreatorIdentity } returns ManageCreatorIdentity(repository)
            every { creatorDiscoveryScheduler } returns null
            every { sourceManager } returns sources
            every { saveSourceMangaForDetails } returns mockk(relaxed = true)
            every { libraryPreferences } returns preferences
        }

        fun mount() = ImageComposeScene(320, 960, coroutineContext = coroutineContext) {}.also { scene ->
            scene.setContent {
                androidx.compose.material3.MaterialTheme {
                    CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                        Navigator(AuthorDetailScreen(creator.id)) {
                            cafe.adriel.voyager.navigator.CurrentScreen()
                        }
                    }
                }
            }
        }
        suspend fun awaitWork(scene: ImageComposeScene) {
            withTimeout(5_000) {
                while ("A long title that must fit in the author grid" !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        fun tagged(scene: ImageComposeScene, tag: String): SemanticsNode = nodes(scene, unmerged = true).single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
        }
        suspend fun awaitTag(scene: ImageComposeScene, tag: String) {
            withTimeout(5_000) {
                while (nodes(scene, unmerged = true).none {
                    it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag
                }) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        var scene = mount()
        try {
            awaitWork(scene)
            assertTrue(!preferences.creatorWorkDisplayModeOverride().isSet())
            tagged(scene, "creator-display-mode-button").config[SemanticsActions.OnClick].action?.invoke()
            awaitTag(scene, "creator-display-mode-option-COMFORTABLE_GRID")
            tagged(scene, "creator-display-mode-option-COMFORTABLE_GRID")
                .config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
            assertTrue(preferences.creatorWorkDisplayModeOverride().isSet())
            assertEquals(LibraryDisplayMode.ComfortableGrid, preferences.creatorWorkDisplayModeOverride().get())

            preferences.displayMode().set(LibraryDisplayMode.List)
            scene.close()
            scene = mount()
            awaitWork(scene)

            awaitTag(scene, "creator-cover-${work.id}")
            awaitTag(scene, "creator-work-${work.id}")
            awaitTag(scene, "creator-work-first-seen-${work.id}")
            tagged(scene, "creator-work-card-${work.id}").config[SemanticsActions.OnClick].action?.invoke()
            awaitTag(scene, "creator-source-version-1")
            assertTrue(texts(scene).any { it.contains("All known source versions") })
            tagged(scene, "creator-source-cancel").config[SemanticsActions.OnClick].action?.invoke()
            awaitTag(scene, "creator-work-card-${work.id}")
            val cover = tagged(scene, "creator-cover-${work.id}")
            val title = tagged(scene, "creator-work-${work.id}")
            assertTrue(
                cover.boundsInRoot.bottom <= title.boundsInRoot.top,
                "Comfortable grid must place the title below its cover after the shelf mode changes and remounts",
            )
        } finally {
            scene.close()
            handler.close()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `narrow author page wraps names and source buttons in both themes`() =
        verifyNarrowArchive(tachiyomi.domain.creator.model.WorkDecisionState.CONFIRMED)

    @Test
    fun `pending and rejected versions keep cover title source hierarchy and exact navigation`() {
        verifyNarrowArchive(null)
        verifyNarrowArchive(tachiyomi.domain.creator.model.WorkDecisionState.REJECTED)
    }

    private fun verifyNarrowArchive(decision: tachiyomi.domain.creator.model.WorkDecisionState?) = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val handler = JvmDatabaseHandler(Database(driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter)), driver)
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val coverFile = directory.resolve("cover.png").toFile()
        val coverImage = java.awt.image.BufferedImage(128, 176, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = coverImage.createGraphics()
        graphics.color = java.awt.Color(0x245A85)
        graphics.fillRect(0, 0, 128, 176)
        graphics.color = java.awt.Color.WHITE
        graphics.drawString("PARADISE", 12, 35)
        graphics.dispose()
        javax.imageio.ImageIO.write(coverImage, "png", coverFile)
        val coverServer = mockwebserver3.MockWebServer()
        repeat(4) {
            coverServer.enqueue(mockwebserver3.MockResponse.Builder()
                .addHeader("Content-Type", "image/png")
                .body(okio.Buffer().write(coverFile.readBytes())).build())
        }
        coverServer.start()
        val preferenceNode = Preferences.userRoot().node("/mihon-tests/ax03-narrow-${UUID.randomUUID()}")
        val preferences = LibraryPreferences(DesktopPreferenceStore(preferenceNode))
        preferences.displayMode().set(LibraryDisplayMode.List)
        val target = repository.upsertCreator("冈本伦")
        repository.addManualCreatorAlias(target.id, "Okamoto Lynn with a long alternative name")
        repository.addManualCreatorAlias(target.id, "岡本倫")
        val archive = CreatorArchive(repository, repository)
        val work = archive.createWork("平行天堂 Parallel Paradise", target.id, null)
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val mangas = mangaRepository.insertNetworkManga((1L..3L).map { source ->
            Manga.create().copy(source = source, url = "/version", title = "Version $source", favorite = false)
        })
        mangas.forEach { manga ->
            repository.upsertSourceWork(manga.source, manga.url, manga.id, manga.title, "冈本伦", null, coverServer.url("/cover.png").toString(), null)
            val key = tachiyomi.domain.creator.model.SourceWorkNaturalKey(manga.source, manga.url)
            repository.upsertSourceWorkCreator(key, target.id, CreatorRole.AUTHOR, 0,
                tachiyomi.domain.creator.model.CreatorRelationOrigin.AUTOMATIC,
                tachiyomi.domain.creator.model.CreatorRelationVerification.VERIFIED, "冈本伦", 1.0, "fixture")
            if (decision != null) archive.decide(key, work.id, decision,
                null, 1.0, "fixture", manga.source, "version-${manga.source}")
        }
        val grouped = decision == tachiyomi.domain.creator.model.WorkDecisionState.CONFIRMED
        val currentArchive = repository.getCreatorWorkArchive(target.id)
        val firstVersion = (currentArchive.pending + currentArchive.rejected).firstOrNull()
        val rowKey = if (grouped) {
            work.id.toString()
        } else if (decision == null) {
            val naturalKey = checkNotNull(firstVersion).naturalKey
            "source:${naturalKey.sourceId}:${naturalKey.stableSourceUrl}"
        } else {
            "version-${checkNotNull(firstVersion).sourceWorkId}"
        }
        val totalWorks = if (grouped) 1 else 3
        val sources = mockk<tachiyomi.domain.source.service.SourceManager> {
            every { get(any<Long>()) } returns null
            every { getOrStub(any()) } answers {
                val sourceId = firstArg<Long>()
                mockk { every { id } returns sourceId; every { name } returns "Source $sourceId long edition" }
            }
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { getCreatorDetails } returns GetCreatorDetails(repository)
            every { setCreatorFollow } returns SetCreatorFollow(repository)
            every { creatorArchive } returns archive
            every { manageCreatorIdentity } returns ManageCreatorIdentity(repository)
            every { creatorDiscoveryScheduler } returns null
            every { sourceManager } returns sources
            every { saveSourceMangaForDetails } returns mockk()
            every { libraryPreferences } returns preferences
        }
        fun sourceLabel(id: Int) = "Source $id long edition · ${MR.strings.desktop_ui_source_missing.localized()}"
        try {
            for (dark in listOf(false, true)) {
                val scene = ImageComposeScene(320, if (grouped) 1100 else 2200, coroutineContext = coroutineContext) {}
                var mountedNavigator: Navigator? = null
                try {
                    scene.setContent {
                        androidx.compose.material3.MaterialTheme(colorScheme = if (dark)
                            androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                                Navigator(AuthorDetailScreen(target.id)) { nav ->
                                    mountedNavigator = nav
                                    cafe.adriel.voyager.navigator.CurrentScreen()
                                }
                            }
                        }
                    }
                    withTimeout(5000) {
                        while (sourceLabel(1) !in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) }
                    }
                    val cover = nodes(scene, unmerged = true).single {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-cover-$rowKey"
                    }
                    val title = nodes(scene, unmerged = true).single {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-work-$rowKey"
                    }
                    assertTrue(kotlin.math.abs(cover.boundsInRoot.top - title.boundsInRoot.top) < 1f)
                    val buttons = nodes(scene).filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("creator-version-") }
                    assertTrue(buttons.size == 3)
                    assertTrue(buttons.first().boundsInRoot.top >= title.boundsInRoot.bottom)
                    val menus = nodes(scene).filter {
                        it.config.contains(SemanticsActions.OnClick) &&
                            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                                .contains(MR.strings.action_edit.localized())
                    }
                    assertTrue(menus.size == 3)
                    assertTrue(menus.all { it.boundsInRoot.width >= 24 && it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 320 })
                    assertTrue(buttons.map { it.boundsInRoot.top }.distinct().size > 1)
                    assertTrue(buttons.all { it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= 320 })
                    val output = java.nio.file.Paths.get("build/ga02/author-${decision?.name?.lowercase() ?: "pending"}-320-${if (dark) "dark" else "light"}.png")
                    java.nio.file.Files.createDirectories(output.parent)
                    var rendered = byteArrayOf()
                    try { withTimeout(5000) {
                        rendered = checkNotNull(scene.render().encodeToData()).bytes
                        assertTrue(cover.boundsInRoot.width > 0 && cover.boundsInRoot.height > 0)
                    }
                    } finally { java.nio.file.Files.write(output, rendered) }
                    if (!grouped) {
                        if (decision != null) {
                            menus.first().config[SemanticsActions.OnClick].action?.invoke()
                            val review = mountedNavigator!!.lastItem as WorkCompareScreen
                            org.junit.jupiter.api.Assertions.assertEquals(checkNotNull(firstVersion).sourceWorkId, review.workId)
                            org.junit.jupiter.api.Assertions.assertEquals(target.id, review.creatorId)
                        }
                        mountedNavigator!!.pop()
                    }
                    val search = nodes(scene).single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "creator-work-search" }
                    search.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("ZZZ-no-match"))
                    scene.render()
                    assertTrue(MR.strings.creator_work_version_count.localized(Locale.getDefault(), totalWorks, 3) in texts(scene))
                    search.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(""))
                    scene.render()
                    if (decision == null) {
                        assertTrue(
                            nodes(scene).any {
                                it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                                    "creator-version-${checkNotNull(firstVersion).sourceWorkId}"
                            },
                        )
                    } else {
                        clickableTextNode(scene, MR.strings.creator_work_sources_all.localized()).config[SemanticsActions.OnClick].action?.invoke()
                        scene.render()
                        clickableTextNode(scene, "Source 2 long edition").config[SemanticsActions.OnClick].action?.invoke()
                        withTimeout(5000) { while (sourceLabel(3) in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) } }
                    }
                    assertTrue(MR.strings.creator_work_version_count.localized(Locale.getDefault(), totalWorks, 3) in texts(scene))
                    if (decision != null) {
                        clickableTextNode(scene, sourceLabel(2)).config[SemanticsActions.OnClick].action?.invoke()
                        withTimeout(5000) {
                            while (mountedNavigator?.lastItem !is mihon.desktop.ui.library.MangaDetailScreen) kotlinx.coroutines.delay(10)
                        }
                        org.junit.jupiter.api.Assertions.assertEquals(mangas[1].id,
                            (mountedNavigator!!.lastItem as mihon.desktop.ui.library.MangaDetailScreen).mangaId)
                    }
                } finally { scene.close() }
            }
        } finally {
            handler.close()
            coverServer.close()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `actual desktop detail editor obeys shared identity contract`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter,
                UpdateStrategyColumnAdapter))
        val handler = JvmDatabaseHandler(database, driver)
        var model: AuthorDetailScreenModel? = null
        try {
            tachiyomi.data.creator.verifyCreatorIdentityEditor(handler) { repository, manager, id ->
                val dependencies = mockk<DesktopUiDependencies> {
                    every { getCreators } returns GetCreators(repository)
                    every { getCreatorDetails } returns GetCreatorDetails(repository)
                    every { setCreatorFollow } returns SetCreatorFollow(repository)
                    every { creatorArchive } returns CreatorArchive(repository, repository)
                    every { manageCreatorIdentity } returns manager
                    every { creatorDiscoveryScheduler } returns null
                    every { saveSourceMangaForDetails } returns mockk()
                    every { libraryPreferences } returns null
                }
                AuthorsScreenModelFactory.detail(id, false, dependencies).also { model = it }.identityEditor
            }
        } finally { model?.onDispose(); handler.close() }
    }

    @Test
    fun `mounted author adds selected alias and confirms owned display name through real SQL`() = runBlocking {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter,
                UpdateStrategyColumnAdapter))
        val handler = JvmDatabaseHandler(database, driver)
        var failNext = false
        var attempts = 0
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler, identityMutationHook = {
            attempts++
            if (failNext) { failNext = false; error("write failed") }
        })
        val target = repository.upsertCreator("Primary")
        repository.upsertCreator("Other")
        attempts = 0
        failNext = true
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { getCreatorDetails } returns GetCreatorDetails(repository)
            every { setCreatorFollow } returns SetCreatorFollow(repository)
            every { creatorArchive } returns CreatorArchive(repository, repository)
            every { creatorArchiveRepository } returns repository
            every { manageCreatorIdentity } returns ManageCreatorIdentity(repository)
            every { creatorDiscoveryScheduler } returns null
            every { sourceManager } returns mockk(relaxed = true)
            every { saveSourceMangaForDetails } returns mockk()
            every { libraryPreferences } returns null
        }
        val scene = ImageComposeScene(600, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(target.id))
                }
            }
            withTimeout(5000) { while (MR.strings.desktop_ui_add_author_alias.localized() !in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) } }
            clickableTextNode(scene, MR.strings.desktop_ui_add_author_alias.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5000) { while ("Other" !in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) } }
            clickableTextNode(scene, "Other").config[SemanticsActions.OnClick].action?.invoke()
            clickableTextNode(scene, MR.strings.action_add.localized()).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5000) { while ("write failed" !in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) } }
            assertTrue(repository.getIdentitySnapshot(target.id).aliases.isEmpty())
            assertTrue("Other" in texts(scene))
            val submit = clickableTextNode(scene, MR.strings.action_add.localized()).config[SemanticsActions.OnClick].action
            submit?.invoke()
            submit?.invoke()
            withTimeout(5000) {
                while (repository.getIdentitySnapshot(target.id).aliases != listOf("Other")) { scene.render(); kotlinx.coroutines.delay(10) }
            }
            org.junit.jupiter.api.Assertions.assertEquals(2, attempts)
            withTimeout(5000) {
                while (!clickableTextNode(scene, MR.strings.desktop_ui_add_author_alias.localized())
                    .config.getOrElse(SemanticsProperties.Focused) { false }) { scene.render(); kotlinx.coroutines.delay(10) }
            }
            clickableTextNode(scene, "Other").config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5000) { while (MR.strings.action_cancel.localized() !in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) } }
            clickableTextNode(scene, MR.strings.action_cancel.localized()).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5000) {
                while (!clickableTextNode(scene, "Other").config.getOrElse(SemanticsProperties.Focused) { false }) {
                    scene.render(); kotlinx.coroutines.delay(10)
                }
            }
            clickableTextNode(scene, "Other").config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5000) { while (MR.strings.action_ok.localized() !in texts(scene)) { scene.render(); kotlinx.coroutines.delay(10) } }
            clickableTextNode(scene, MR.strings.action_ok.localized()).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5000) {
                while (repository.getIdentitySnapshot(target.id).displayName != "Other") { scene.render(); kotlinx.coroutines.delay(10) }
            }
            assertTrue(repository.getIdentitySnapshot(target.id).aliases == listOf("Primary"))
            withTimeout(5000) {
                while (nodes(scene).none { node ->
                    node.config.getOrElse(SemanticsProperties.Focused) { false } &&
                        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Other" }
                }) { scene.render(); kotlinx.coroutines.delay(10) }
            }
        } finally {
            scene.close()
            handler.close()
        }
    }

    @Test
    fun `mounted work comparison confirms a suggested source version through production repositories`() = runBlocking {
        val creator = Creator(7L, "Jane Doe", "jane doe", null, emptyList(), 1L, 1L)
        val candidate = DiscoveryCandidate(
            id = 30L,
            source = 10L,
            url = "/candidate",
            title = "Shared work",
            normalizedTitle = "shared work",
            authorText = "Jane Doe",
            artistText = null,
            languageTag = "en",
            languageConfidence = 1.0,
            languageEvidence = "structured source language",
            thumbnailUrl = null,
            firstSeenAt = 1L,
            lastSeenAt = 2L,
            detailsFetchedAt = 2L,
            state = DiscoveryCandidateState.NEW,
        )
        val creatorRepository = mockk<CreatorRepository> {
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns listOf(candidate)
            coEvery { getMangaCreatorsForCreator(7L) } returns listOf(
                MangaCreator(11L, 7L, CreatorRole.AUTHOR, "Jane Doe", 1.0, "library index"),
            )
            coEvery { getMangaTitlesForCreator(7L) } returns mapOf(11L to "Shared work")
            coEvery { getDiscoveryCandidate(30L) } returns candidate
            coEvery { createCanonicalWork("Shared work", 7L, null) } returns
                CanonicalWork(90L, "Shared work", "shared work", 7L, null, 1L, 1L)
            coEvery { upsertMangaWorkMatch(any(), any(), any(), any(), any(), any()) } returns mockk()
        }
        val archiveRepository = mockk<CreatorArchiveRepository>(relaxed = true) {
            coEvery { getCreatorWorkArchive(7L) } returns comparisonArchive()
        }
        val saved = Manga.create().copy(id = 12L, source = 10L, url = "/candidate", title = "Shared work")
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { this@mockk.creatorRepository } returns creatorRepository
            every { creatorArchiveRepository } returns archiveRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { saveSourceMangaForDetails } returns mockk {
                coEvery { awaitListedForDetails(any(), 10L) } returns ListedMangaForDetails(saved, false)
            }
            every { getChaptersByMangaId } returns mockk {
                coEvery { await(any()) } returns listOf(
                    tachiyomi.domain.chapter.model.Chapter.create().copy(
                        id = 1L,
                        mangaId = 12L,
                        url = "/chapter-1-part-2",
                        name = "Ch. 1 Part 2",
                        chapterNumber = 1.0,
                    ),
                )
            }
            every { sourceManager } returns mockk(relaxed = true)
            every { libraryPreferences } returns null
        }
        val scene = ImageComposeScene(1100, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(WorkCompareScreen(workId = 30L, creatorId = 7L))
                }
            }
            val action = MR.strings.desktop_ui_confirm_same_work.localized()
            withTimeout(5_000) {
                while (action !in texts(scene)) scene.render()
            }
            withTimeout(5000) {
                while (!(                MR.strings.desktop_ui_chapter_variant_summary.localized(
                    Locale.getDefault(),
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                ) in texts(scene))) { scene.render(); kotlinx.coroutines.delay(10) }
            }
            withTimeout(5000) {
                while (MR.strings.creator_work_chapters_unknown.localized() !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(texts(scene).none { it.contains("chapter_coverage") })
            clickableTextNode(scene, action).config[SemanticsActions.OnClick].action?.invoke()

            coVerifyOrder {
                archiveRepository.appendUserWorkDecisionIfCurrent(
                    SourceWorkNaturalKey(10L, "/pending"),
                    80L,
                    WorkDecisionState.CONFIRMED,
                    null,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            coVerify(exactly = 0) { creatorRepository.createCanonicalWork(any(), any(), any()) }
            coVerify {
                archiveRepository.replaceChapterVariants(
                    SourceWorkNaturalKey(10L, "/pending"),
                    match { it.single().type == ChapterVariantType.SPLIT },
                    any(),
                )
            }

            val correctLanguage = MR.strings.desktop_ui_correct_reading_language.localized()
            clickableTextNode(scene, correctLanguage).config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
            val input = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString("ja")))
            clickableTextNode(scene, MR.strings.action_ok.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            coVerify(timeout = 5_000) {
                archiveRepository.setManualLanguage(
                    ArchiveLanguageSubject.SourceWork(
                        SourceWorkNaturalKey(10L, "/pending"),
                    ),
                    LanguageDimension.READING,
                    "ja",
                    any(),
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted work comparison offers explicit merge for simplified and traditional title variants`() = runBlocking {
        val creatorRepository = mockk<CreatorRepository>(relaxed = true)
        val archiveRepository = mockk<CreatorArchiveRepository>(relaxed = true) {
            coEvery { getCreatorWorkArchive(7L) } returns scriptVariantArchive()
        }
        val saved = Manga.create().copy(id = 12L, source = 10L, url = "/traditional", title = "詭譎屋")
        val dependencies = mockk<DesktopUiDependencies> {
            every { creatorArchiveRepository } returns archiveRepository
            every { this@mockk.creatorRepository } returns creatorRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { saveSourceMangaForDetails } returns mockk {
                coEvery { awaitListedForDetails(any(), 10L) } returns ListedMangaForDetails(saved, false)
            }
            every { getChaptersByMangaId } returns mockk {
                coEvery { await(any()) } returns emptyList()
            }
            every { sourceManager } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(1100, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(WorkCompareScreen(workId = 30L, creatorId = 7L))
                }
            }
            val action = MR.strings.desktop_ui_confirm_same_work.localized()
            withTimeout(5_000) {
                while (action !in texts(scene)) scene.render()
            }
            assertTrue(texts(scene).any { it.contains("title_script_variant") })
            clickableTextNode(scene, action).config[SemanticsActions.OnClick].action?.invoke()
            coVerify {
                archiveRepository.appendUserWorkDecisionIfCurrent(
                    SourceWorkNaturalKey(10L, "/traditional"),
                    80L,
                    WorkDecisionState.CONFIRMED,
                    null,
                    any(),
                    match { it.contains("title_script_variant") },
                    any(),
                    any(),
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted work comparison uses one transaction for two pending script variants`() = runBlocking {
        val creatorRepository = mockk<CreatorRepository>(relaxed = true)
        val archiveRepository = mockk<CreatorArchiveRepository>(relaxed = true) {
            coEvery { getCreatorWorkArchive(7L) } returns scriptVariantPendingArchive()
            coEvery {
                createCanonicalWorkWithUserWorkDecisions(any(), any(), any())
            } returns CanonicalWork(80L, "诡谲屋", "诡谲屋", 7L, null, 1L, 1L)
        }
        val saved = Manga.create().copy(id = 12L, source = 10L, url = "/traditional", title = "詭譎屋")
        val dependencies = mockk<DesktopUiDependencies> {
            every { creatorArchiveRepository } returns archiveRepository
            every { this@mockk.creatorRepository } returns creatorRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { saveSourceMangaForDetails } returns mockk {
                coEvery { awaitListedForDetails(any(), 10L) } returns ListedMangaForDetails(saved, false)
            }
            every { getChaptersByMangaId } returns mockk {
                coEvery { await(any()) } returns emptyList()
            }
            every { sourceManager } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(1100, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(WorkCompareScreen(workId = 30L, creatorId = 7L))
                }
            }
            val action = MR.strings.desktop_ui_confirm_same_work.localized()
            withTimeout(5_000) {
                while (action !in texts(scene)) scene.render()
            }
            clickableTextNode(scene, action).config[SemanticsActions.OnClick].action?.invoke()
            coVerify {
                archiveRepository.createCanonicalWorkWithUserWorkDecisions(
                    "诡谲屋",
                    7L,
                    match { decisions -> decisions.size == 2 },
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted work comparison confirms an existing suggested script variant target`() = runBlocking {
        val creatorRepository = mockk<CreatorRepository>(relaxed = true)
        val archiveRepository = mockk<CreatorArchiveRepository>(relaxed = true) {
            coEvery { getCreatorWorkArchive(7L) } returns scriptVariantSuggestedArchive()
        }
        val saved = Manga.create().copy(id = 12L, source = 10L, url = "/traditional", title = "詭譎屋")
        val dependencies = mockk<DesktopUiDependencies> {
            every { creatorArchiveRepository } returns archiveRepository
            every { this@mockk.creatorRepository } returns creatorRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { saveSourceMangaForDetails } returns mockk {
                coEvery { awaitListedForDetails(any(), 10L) } returns ListedMangaForDetails(saved, false)
            }
            every { getChaptersByMangaId } returns mockk {
                coEvery { await(any()) } returns emptyList()
            }
            every { sourceManager } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(1100, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(WorkCompareScreen(workId = 30L, creatorId = 7L))
                }
            }
            val action = MR.strings.desktop_ui_confirm_same_work.localized()
            withTimeout(5_000) {
                while (action !in texts(scene)) scene.render()
            }
            clickableTextNode(scene, action).config[SemanticsActions.OnClick].action?.invoke()
            coVerify {
                archiveRepository.appendUserWorkDecisionIfCurrent(
                    SourceWorkNaturalKey(11L, "/suggested"),
                    80L,
                    WorkDecisionState.CONFIRMED,
                    2L,
                    any(),
                    match { it.contains("title_script_variant") },
                    any(),
                    any(),
                )
                archiveRepository.appendUserWorkDecisionIfCurrent(
                    SourceWorkNaturalKey(10L, "/traditional"),
                    80L,
                    WorkDecisionState.CONFIRMED,
                    null,
                    any(),
                    match { it.contains("title_script_variant") },
                    any(),
                    any(),
                )
            }
        } finally {
            scene.close()
        }
    }


    @Test
    fun `mounted author explains unsupported split without changing identity`() = runBlocking {
        val creator = Creator(
            id = 7L,
            displayName = "Jane Doe",
            normalizedName = "jane doe",
            sortName = null,
            aliases = listOf("J. Doe"),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val creatorRepository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(listOf(creator))
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7L) } returns emptyList()
        }
        val archiveRepository = mockk<CreatorArchiveRepository> {
            every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
            coEvery { getPresentationExclusions(any()) } returns emptySet()
            coEvery { getManualCreatorAliases(7L) } returnsMany listOf(
                listOf("J. Doe"),
                emptyList(),
            )
            coEvery { removeManualCreatorAlias(7L, "J. Doe") } returns Unit
            every { observeSourceCheckpoints(7L) } returns flowOf(emptyList())
            every { observeCreatorWorkArchive(7L) } returns flowOf(pendingArchive("Pending grouped work"))
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(creatorRepository)
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { setCreatorFollow } returns SetCreatorFollow(creatorRepository)
            every { discoverCreatorWorks } returns mockk()
            every { sourceManager } returns mockk(relaxed = true)
            every { saveSourceMangaForDetails } returns mockk()
            every { creatorArchiveRepository } returns archiveRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
            every { creatorDiscoveryScheduler } returns null
            every { libraryPreferences } returns null
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creatorId = 7L))
                }
            }
            withTimeout(5000) {
                while (MR.strings.desktop_ui_split_author_identity.localized() !in texts(scene)) {
                    scene.render(); kotlinx.coroutines.delay(10)
                }
            }
            clickableTextNode(scene, MR.strings.desktop_ui_split_author_identity.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
            assertTrue(MR.strings.creator_split_unavailable.localized() in texts(scene))
            coVerify(exactly = 0) { archiveRepository.removeManualCreatorAlias(any(), any()) }
        } finally { scene.close() }
    }

    @Test
    fun `desktop author list imports legacy exclusions before its first SQL card page`() = runBlocking {
        val node = Preferences.userRoot().node("/mihon-tests/author-list-migration-${UUID.randomUUID()}")
        try {
            val preferences = LibraryPreferences(DesktopPreferenceStore(node))
            val sourceWork = SourceWorkNaturalKey(82L, "/legacy-separated")
            CreatorWorkPresentationExclusions(preferences.creatorWorkPresentationExclusions()).exclude(7L, sourceWork)
            val creatorRepository = mockk<CreatorRepository> {
                every { getCreatorsAsFlow() } returns flowOf(emptyList())
                every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            }
            val archiveRepository = mockk<CreatorArchiveRepository> {
                every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
                coEvery { importPresentationExclusions(any()) } returns mapOf(7L to setOf(sourceWork))
                coEvery { getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any()) } returns
                    CreatorCardProjectionPage(0, 50, false, emptyList())
            }
            val indexer = CreatorLibraryIndexer(
                mangaSource = object : CreatorLibraryMangaSource {
                    override suspend fun countLibraryMangaForCreatorIndex(): Long = 0L
                    override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> = emptyList()
                },
                indexWriter = NoopCreatorLibraryIndexWriter,
                extractCreators = ExtractCreatorsFromManga(),
            )
            val model = AuthorsRootScreenModel(
                getCreators = GetCreators(creatorRepository),
                creatorArchive = CreatorArchive(creatorRepository, archiveRepository),
                indexer = indexer,
                libraryPreferences = preferences,
            )
            try {
                withTimeout(5_000) { model.state.first { !it.loading } }
                coVerifyOrder {
                    archiveRepository.importPresentationExclusions(mapOf(7L to setOf(sourceWork)))
                    archiveRepository.getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any())
                }
            } finally { model.onDispose() }
        } finally { node.removeNode() }
    }

    @Test
    fun `mounted authors root renders production failure and retry reaches empty library`() = runBlocking {
        val source = FailsOnceLibrarySource()
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = NoopCreatorLibraryIndexWriter,
            extractCreators = ExtractCreatorsFromManga(),
        )
        val preferenceNode = java.util.prefs.Preferences.userRoot()
            .node("/mihon-tests/ax01-authors-index-${java.util.UUID.randomUUID()}")
        val desktopPreferences = mihon.desktop.settings.DesktopAppPreferences(
            tachiyomi.core.common.preference.DesktopPreferenceStore(preferenceNode),
        )
        val customCoverStore = mihon.desktop.domain.DesktopCustomCoverStore(directory.resolve("covers").toFile())
        val repository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(emptyList())
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        }
        val archiveRepository = mockk<CreatorArchiveRepository> {
            every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
            coEvery { getPresentationExclusions(any()) } returns emptySet()
            coEvery {
                getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any())
            } returns tachiyomi.domain.creator.model.CreatorCardProjectionPage(0, 50, false, emptyList())
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { creatorLibraryIndexer } returns indexer
            every { creatorDiscoveryPreferences } returns null
            every { creatorArchiveRepository } returns archiveRepository
            every { creatorArchive } returns CreatorArchive(repository, archiveRepository)
            every { appPreferences } returns desktopPreferences
            every { this@mockk.customCoverStore } returns customCoverStore
            every { libraryPreferences } returns null
        }
        indexer.start(this)
        withTimeout(5_000) { indexer.state.filterIsInstance<CreatorLibraryIndexState.Failed>().first() }

        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorsRootScreen())
                }
            }
            scene.render()
            assertTrue(
                MR.strings.desktop_ui_author_index_failed.localized(Locale.getDefault(), "disk full") in texts(scene),
            )

            retryNode(scene).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) { indexer.state.filterIsInstance<CreatorLibraryIndexState.Empty>().first() }
            val emptyLibraryText = MR.strings.desktop_ui_author_index_empty_library.localized()
            withTimeout(5_000) {
                while (emptyLibraryText !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(source.countAttempts == 2)
        } finally {
            scene.close()
            indexer.stop()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `mounted author cards remain visible when projection refresh fails and retry recovers`() = runBlocking {
        val creator = Creator(
            id = 7L,
            displayName = "Existing Local Author",
            normalizedName = "existing local author",
            sortName = null,
            aliases = emptyList(),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val creators = MutableStateFlow(listOf(creator))
        val creatorRepository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns creators
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        }
        val page = CreatorCardProjectionPage(
            offset = 0,
            limit = 50,
            hasMore = false,
            creators = listOf(CreatorCardProjection(creator = creator, followed = true, uniqueWorkCount = 0)),
        )
        val projectionCalls = AtomicInteger()
        val archiveRepository = mockk<CreatorArchiveRepository> {
            every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
            coEvery { getPresentationExclusions(any()) } returns emptySet()
            coEvery {
                getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any())
            } coAnswers {
                if (projectionCalls.incrementAndGet() == 2) error("projection temporarily unavailable")
                page
            }
        }
        val indexer = CreatorLibraryIndexer(
            mangaSource = object : CreatorLibraryMangaSource {
                override suspend fun countLibraryMangaForCreatorIndex(): Long = 0L
                override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> = emptyList()
            },
            indexWriter = NoopCreatorLibraryIndexWriter,
            extractCreators = ExtractCreatorsFromManga(),
        )
        val preferenceNode = java.util.prefs.Preferences.userRoot()
            .node("/mihon-tests/ax01-author-projection-retry-${java.util.UUID.randomUUID()}")
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(creatorRepository)
            every { creatorLibraryIndexer } returns indexer
            every { creatorDiscoveryPreferences } returns null
            every { creatorArchiveRepository } returns archiveRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { appPreferences } returns mihon.desktop.settings.DesktopAppPreferences(
                tachiyomi.core.common.preference.DesktopPreferenceStore(preferenceNode),
            )
            every { customCoverStore } returns mihon.desktop.domain.DesktopCustomCoverStore(
                directory.resolve("projection-retry-covers").toFile(),
            )
            every { creatorDiscoveryScheduler } returns null
            every { libraryPreferences } returns null
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorsRootScreen())
                }
            }
            withTimeout(5_000) {
                while (creator.displayName !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
            kotlinx.coroutines.delay(50)

            creators.value = listOf(creator.copy(lastModifiedAt = 2L))
            withTimeout(5_000) {
                while ("projection temporarily unavailable" !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }

            assertTrue(creator.displayName in texts(scene), "A failed refresh should keep the previous author card")
            assertTrue(
                MR.strings.creator_following_empty.localized() !in texts(scene),
                "A projection failure should not be shown as an empty followed list",
            )

            retryNode(scene).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) {
                while ("projection temporarily unavailable" in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(creator.displayName in texts(scene), "Retry should retain the successfully reloaded card")
            check(projectionCalls.get() == 3) { "Expected initial read, failed refresh, and successful retry" }
        } finally {
            scene.close()
            indexer.stop()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `mounted author detail manual check runs through the discovery scheduler and can be cancelled`() = runBlocking {
        val creator = Creator(
            id = 7L,
            displayName = "Jane Doe",
            normalizedName = "jane doe",
            sortName = null,
            aliases = emptyList(),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val creatorRepository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(listOf(creator))
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7L) } returns emptyList()
        }
        val archiveRepository = mockk<CreatorArchiveRepository> {
            every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
            coEvery { getPresentationExclusions(any()) } returns emptySet()
            coEvery { getManualCreatorAliases(7L) } returns emptyList()
            every { observeSourceCheckpoints(7L) } returns flowOf(emptyList())
            every { observeCreatorWorkArchive(7L) } returns flowOf(CreatorWorkArchive(emptyList(), emptyList(), emptyList()))
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = CreatorDiscoveryScheduler(
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))),
            discoverDue = { CreatorDiscoveryResult(0, 0, emptyList()) },
            discoverCreator = {
                entered.complete(Unit)
                release.await()
                CreatorDiscoveryResult(0, 0, emptyList())
            },
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(creatorRepository)
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { setCreatorFollow } returns SetCreatorFollow(creatorRepository)
            every { discoverCreatorWorks } returns mockk()
            every { sourceManager } returns mockk()
            every { saveSourceMangaForDetails } returns mockk()
            every { creatorArchiveRepository } returns archiveRepository
            every { creatorArchive } returns CreatorArchive(creatorRepository, archiveRepository)
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
            every { creatorDiscoveryScheduler } returns scheduler
            every { libraryPreferences } returns null
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creatorId = 7L))
                }
            }
            withTimeout(5_000) {
                while (MR.strings.desktop_ui_check_new_works.localized() !in contentDescriptions(scene)) {
                    scene.render()
                }
            }

            clickableNodeByDescription(scene, MR.strings.desktop_ui_check_new_works.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            entered.await()

            val running = MR.strings.desktop_ui_author_check_running.localized(Locale.getDefault(), "Jane Doe")
            withTimeout(5_000) {
                while (running !in texts(scene)) scene.render()
            }
            assertTrue(running in texts(scene))

            clickableTextNode(scene, MR.strings.desktop_ui_author_discovery_cancel.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            release.complete(Unit)
            withTimeout(5_000) {
                while (MR.strings.desktop_ui_author_discovery_cancelled.localized() !in texts(scene)) scene.render()
            }
            assertTrue(MR.strings.desktop_ui_author_discovery_cancelled.localized() in texts(scene))
        } finally {
            scene.close()
            scheduler.stop()
        }
    }

    @Test
    fun `mounted author list settings keeps draft until save and restores gear focus`() = runBlocking {
        val node = java.util.prefs.Preferences.userRoot().node("/mihon-tests/ga03-ui-${java.util.UUID.randomUUID()}")
        val preferences = tachiyomi.domain.creator.service.CreatorDiscoveryPreferences(tachiyomi.core.common.preference.DesktopPreferenceStore(node))
        val indexer = CreatorLibraryIndexer(FailsOnceLibrarySource(), NoopCreatorLibraryIndexWriter, ExtractCreatorsFromManga())
        val repository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(emptyList())
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        }
        val archive = mockk<CreatorArchiveRepository> {
            every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
            coEvery { getPresentationExclusions(any()) } returns emptySet()
            coEvery { getDueWatchSources(any(), any()) } returns emptyList()
        }
        coEvery {
            archive.getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any())
        } returns tachiyomi.domain.creator.model.CreatorCardProjectionPage(0, 50, false, emptyList())
        val desktopPreferences = mihon.desktop.settings.DesktopAppPreferences(
            tachiyomi.core.common.preference.DesktopPreferenceStore(node),
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { creatorLibraryIndexer } returns indexer
            every { creatorDiscoveryPreferences } returns preferences
            every { creatorArchiveRepository } returns archive
            every { creatorArchive } returns CreatorArchive(repository, archive)
            every { appPreferences } returns desktopPreferences
            every { customCoverStore } returns mihon.desktop.domain.DesktopCustomCoverStore(directory.resolve("covers").toFile())
            every { creatorDiscoveryScheduler } returns null
            every { libraryPreferences } returns null
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        fun tagged(tag: String) = nodes(scene).single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
        fun click(tag: String) { tagged(tag).config[SemanticsActions.OnClick].action?.invoke(); scene.render() }
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) { Navigator(AuthorsRootScreen()) }
            }
            scene.render()
            click("creator-settings-open")
            click("creator-frequency-monthly")
            check(preferences.current() == tachiyomi.domain.creator.service.CreatorCheckFrequency.DAILY)
            click("creator-settings-cancel")
            scene.render()
            click("creator-settings-open")
            click("creator-frequency-weekly")
            click("creator-settings-save")
            repeat(5) { scene.render(); kotlinx.coroutines.yield() }
            check(preferences.current() == tachiyomi.domain.creator.service.CreatorCheckFrequency.WEEKLY)
            check(tagged("creator-settings-open").config[SemanticsProperties.Focused])
            kotlinx.coroutines.withTimeout(5_000) {
                while (MR.strings.creator_settings_saved.localized() !in texts(scene)) {
                    scene.render()
                    kotlinx.coroutines.delay(10)
                }
            }
        } finally { scene.close(); indexer.stop(); node.removeNode() }
    }

    private fun retryNode(scene: ImageComposeScene): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == MR.strings.action_retry.localized() }
    }

    private fun pendingArchive(title: String): CreatorWorkArchive = CreatorWorkArchive(
        works = emptyList(),
        pending = listOf(
            SourceWorkArchiveVersion(
                sourceWorkId = 30L,
                naturalKey = SourceWorkNaturalKey(10L, "/pending"),
                mangaId = null,
                title = title,
                readingLanguage = LanguageProjectionContract(
                    dimension = LanguageDimension.READING,
                    tag = "und",
                    certainty = LanguageCertainty.UNKNOWN,
                    evidenceKind = LanguageEvidenceKind.UNKNOWN,
                ),
                chapterCount = 0L,
                inLibrary = false,
                detailsFetchedAt = null,
                lastSeenAt = 1L,
                decision = null,
            ),
        ),
        rejected = emptyList(),
    )

    private fun comparisonArchive(): CreatorWorkArchive {
        val candidate = pendingArchive("Shared work").pending.single()
        val libraryVersion = candidate.copy(
            sourceWorkId = 31L,
            naturalKey = SourceWorkNaturalKey(11L, "/library-version"),
            mangaId = 11L,
            inLibrary = true,
        )
        return CreatorWorkArchive(
            works = listOf(
                CanonicalWorkArchiveGroup(
                    workId = 80L,
                    portableKey = "existing-work",
                    title = "Shared work",
                    versions = listOf(libraryVersion),
                ),
            ),
            pending = listOf(candidate),
            rejected = emptyList(),
        )
    }

    private fun scriptVariantArchive(): CreatorWorkArchive {
        val pending = pendingArchive("詭譎屋").pending.single().copy(
            mangaId = 12L,
            naturalKey = SourceWorkNaturalKey(10L, "/traditional"),
        )
        val simplified = pending.copy(
            sourceWorkId = 31L,
            naturalKey = SourceWorkNaturalKey(11L, "/simplified"),
            mangaId = 11L,
            title = "诡谲屋",
        )
        return CreatorWorkArchive(
            works = listOf(
                CanonicalWorkArchiveGroup(
                    workId = 80L,
                    portableKey = "script-variant-work",
                    title = "诡谲屋",
                    versions = listOf(simplified),
                ),
            ),
            pending = listOf(pending),
            rejected = emptyList(),
        )
    }

    private fun scriptVariantPendingArchive(): CreatorWorkArchive {
        val pending = pendingArchive("詭譎屋").pending.single().copy(
            mangaId = 12L,
            naturalKey = SourceWorkNaturalKey(10L, "/traditional"),
        )
        val simplified = pending.copy(
            sourceWorkId = 31L,
            naturalKey = SourceWorkNaturalKey(11L, "/simplified"),
            mangaId = 11L,
            title = "诡谲屋",
        )
        return CreatorWorkArchive(works = emptyList(), pending = listOf(pending, simplified), rejected = emptyList())
    }

    private fun scriptVariantSuggestedArchive(): CreatorWorkArchive {
        val pending = pendingArchive("詭譎屋").pending.single().copy(
            mangaId = 12L,
            naturalKey = SourceWorkNaturalKey(10L, "/traditional"),
        )
        val suggested = pending.copy(
            sourceWorkId = 31L,
            naturalKey = SourceWorkNaturalKey(11L, "/suggested"),
            mangaId = 11L,
            title = "诡谲屋",
            decision = WorkDecisionProjection(
                workId = 80L,
                workPortableKey = "script-variant-work",
                workTitle = "诡谲屋",
                decision = WorkDecisionContract(WorkDecisionState.SUGGESTED, DecisionActor.ALGORITHM, false),
                score = 1.0,
                evidence = "title_script_variant",
                decidedAt = 2L,
            ),
        )
        return CreatorWorkArchive(works = emptyList(), pending = listOf(pending, suggested), rejected = emptyList())
    }

    private fun clickableTextNode(scene: ImageComposeScene, text: String): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == text }
    }

    private fun clickableNodeByDescription(scene: ImageComposeScene, description: String): SemanticsNode =
        nodes(scene).single { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config[SemanticsProperties.ContentDescription].any { it == description }
        }

    private fun contentDescriptions(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.ContentDescription)) {
            node.config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { owner ->
        flatten(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class FailsOnceLibrarySource : CreatorLibraryMangaSource {
        var countAttempts = 0
            private set

        override suspend fun countLibraryMangaForCreatorIndex(): Long {
            countAttempts += 1
            check(countAttempts > 1) { "disk full" }
            return 0L
        }

        override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> = emptyList()
    }
}
