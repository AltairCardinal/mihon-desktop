package eu.kanade.tachiyomi.ui.browse.author

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorCardProjectionPage
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCommit
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class AndroidAuthorArchiveWiringTest {
    private lateinit var jdbcDriver: java.sql.Driver
    private val modelHost = ScreenModelTestHost()

    @BeforeEach
    fun registerJdbcDriver() {
        // Mixed JVM/Robolectric suites cannot rely on DriverManager's one-time service discovery.
        jdbcDriver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        java.sql.DriverManager.registerDriver(jdbcDriver)
    }

    @AfterEach
    fun releaseJdbcDriver() {
        java.sql.DriverManager.deregisterDriver(jdbcDriver)
    }

    @Test
    fun `Android author list imports legacy exclusions before its first SQL card page`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        try {
            var legacyValues = emptySet<String>()
            val legacyPreference = mockk<tachiyomi.core.common.preference.Preference<Set<String>>> {
                every { get() } answers { legacyValues }
                every { set(any()) } answers { legacyValues = firstArg() }
            }
            val store = mockk<tachiyomi.core.common.preference.PreferenceStore>(relaxed = true) {
                every { getStringSet(any(), any()) } returns legacyPreference
            }
            val preferences = tachiyomi.domain.library.service.LibraryPreferences(store)
            val sourceWork = SourceWorkNaturalKey(82L, "/legacy-separated")
            tachiyomi.domain.creator.service.CreatorWorkPresentationExclusions(
                preferences.creatorWorkPresentationExclusions(),
            ).exclude(7L, sourceWork)
            val creatorRepository = mockk<tachiyomi.domain.creator.repository.CreatorRepository> {
                every { getCreatorsAsFlow() } returns flowOf(emptyList())
                every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            }
            val archiveRepository = mockk<tachiyomi.domain.creator.repository.CreatorArchiveRepository> {
                every { observeUnreadWorkDiscoveries(any()) } returns flowOf(emptyList())
                coEvery { importPresentationExclusions(any()) } returns mapOf(7L to setOf(sourceWork))
                coEvery { getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any(), any()) } returns
                    CreatorCardProjectionPage(0, 50, false, emptyList())
            }
            val model = modelHost.create {
                AndroidAuthorsScreenModel(
                    creatorArchive = CreatorArchive(creatorRepository, archiveRepository),
                    getCreators = GetCreators(creatorRepository),
                    sourcePreferences = mockk<eu.kanade.domain.source.service.SourcePreferences> {
                        every { enabledLanguages() } returns mockk {
                            every { get() } returns emptySet()
                        }
                    },
                    coverCache = mockk(),
                    preferences = tachiyomi.domain.creator.service.CreatorDiscoveryPreferences(store),
                    onSettingsSaved = {},
                    libraryPreferences = preferences,
                )
            }
            kotlinx.coroutines.withTimeout(5_000) { model.state.first { !it.loading } }
            coVerifyOrder {
                archiveRepository.importPresentationExclusions(mapOf(7L to setOf(sourceWork)))
                archiveRepository.getCreatorCardProjectionPage(any(), any(), any(), any(), any(), any(), any())
            }
        } finally {
            modelHost.close()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `Android archive state applies shared work search and source contract`() {
        tachiyomi.data.creator.verifyCreatorWorkFilterProjection { archive, filter ->
            AuthorState(archive = archive, workFilter = filter).visibleArchive
        }
    }

    @Test
    fun `actual Android detail editor obeys shared identity contract`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                UpdateStrategyColumnAdapter,
            ),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        var model: AndroidAuthorDetailScreenModel? = null
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(
            handler,
            tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
        )
        val networkToLocal = NetworkToLocalManga(mangaRepository)
        try {
            tachiyomi.data.creator.verifyCreatorIdentityEditor(handler) { repository, manager, id ->
                modelHost.create {
                    AndroidAuthorDetailScreenModel(
                        id, GetCreatorDetails(repository), GetCreators(repository),
                        SetCreatorFollow(repository), mockk(), CreatorArchive(repository, repository), mockk(), manager,
                        networkToLocal,
                    )
                }.also { model = it }.identityEditor
            }
            val detail = requireNotNull(model)
            val version = SourceWorkArchiveVersion(
                1, SourceWorkNaturalKey(42, "/specific-version"), 321, "Specific",
                LanguageProjectionContract(
                    LanguageDimension.READING,
                    "und",
                    LanguageCertainty.UNKNOWN,
                    LanguageEvidenceKind.UNKNOWN,
                ),
                0, false, null, 0, null,
            )
            val existing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { detail.openManga.first() }
            detail.openVersion(version).join()
            assertEquals(321L, existing.await().mangaId)
            val listed = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { detail.openManga.first() }
            detail.openVersion(version.copy(mangaId = null)).join()
            val stored = mangaRepository.getMangaById(listed.await().mangaId)
            assertEquals(42L, stored.source)
            assertEquals("/specific-version", stored.url)
            assertEquals(false, stored.favorite)
        } finally {
            modelHost.close()
            driver.close()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `actual Android author navigation marks unread work only after consumer confirmation`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        val mangaRepository = tachiyomi.data.manga.MangaRepositoryImpl(
            handler,
            tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter,
        )
        val networkToLocal = NetworkToLocalManga(mangaRepository)
        var detail: AndroidAuthorDetailScreenModel? = null
        try {
            val creator = repository.upsertCreator("Navigation Author")
            val sourceWork = SourceWorkNaturalKey(42L, "/navigation-unread")
            repository.upsertWatchPolicy(
                ArchiveWatchPolicy(creator.id, true, 1_000L, setOf(sourceWork.sourceId), emptySet()),
                now = 1L,
            )
            repository.upsertSourceWork(
                42L,
                sourceWork.stableSourceUrl,
                321L,
                "Navigation Work",
                creator.displayName,
                null,
                null,
                2L,
            )
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
                    "navigation-unread",
                ),
            )
            detail = modelHost.create {
                AndroidAuthorDetailScreenModel(
                    creatorId = creator.id,
                    details = GetCreatorDetails(repository),
                    creators = GetCreators(repository),
                    follow = SetCreatorFollow(repository),
                    discovery = mockk(),
                    archive = CreatorArchive(repository, repository),
                    sources = mockk(relaxed = true),
                    identity = ManageCreatorIdentity(repository),
                    networkToLocal = networkToLocal,
                )
            }
            val version = SourceWorkArchiveVersion(
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
            )
            val request = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { detail.openManga.first() }
            detail.openVersion(version).join()
            val opened = request.await()
            assertEquals(321L, opened.mangaId)
            assertEquals(creator.id, opened.creatorId)
            assertTrue(repository.getUnreadWorkDiscoveries(10L).isNotEmpty())

            detail.markWorkSeenAfterNavigation(opened.creatorId, opened.sourceWork)
            assertTrue(repository.getUnreadWorkDiscoveries(10L).isEmpty())
        } finally {
            modelHost.close()
            driver.close()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `uncollected Android creator entry resolves real SQL identity and retries rolled back failure`() = runTest {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                StringListColumnAdapter,
                UpdateStrategyColumnAdapter,
            ),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        try {
            tachiyomi.data.creator.verifyExactCreatorEntry(handler) { manager, manga, mention ->
                val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = manager)
                var opened: Long? = null
                AndroidCreatorOpenCoordinator(
                    resolve = navigator::resolve,
                    onResolved = { opened = it },
                    onAmbiguous = { error("Exact identity cannot require a choice") },
                    onFailure = { throw it },
                ).open(manga, mention)
                CreatorMentionResolution.Resolved(requireNotNull(opened))
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `author detail is a Voyager screen`() {
        assertTrue(AndroidAuthorDetailScreen(7) is Screen)
    }

    @Test
    fun `Android author surface consumes the shared commands`() {
        assertNotNull(GetCreators::class.java)
        assertNotNull(GetCreatorDetails::class.java)
        assertNotNull(SetCreatorFollow::class.java)
        assertNotNull(DiscoverCreatorWorks::class.java)
        assertNotNull(CreatorArchive::class.java)
        assertNotNull(ManageCreatorIdentity::class.java)
        assertNotNull(AndroidMangaCreatorNavigator::class.java)
    }

    @Test
    fun `discovery owns a distinct Android worker`() {
        assertNotNull(CreatorDiscoveryJob::class.java)
    }

    @Test
    fun `manga creator navigation splits author and artist fields into people`() {
        val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = mockk())
        val manga = Manga.create().copy(author = "ONE / Murata", artist = "Murata, Boichi")

        assertEquals(listOf("ONE", "Murata", "Boichi"), navigator.mentions(manga).map { it.displayName })
    }

    @Test
    fun `Android manga creator navigation executes shared resolution and retries preparation failure`() = runTest {
        val manga = Manga.create().copy(author = "ONE")
        val manager = mockk<ManageCreatorIdentity>()
        val navigator = AndroidMangaCreatorNavigator(manageCreatorIdentity = manager)
        val mention = navigator.mentions(manga).single()
        coEvery { manager.resolve(manga, mention) } throws IllegalStateException("preparing") andThen
            CreatorMentionResolution.Resolved(41L)
        val opened = mutableListOf<Long>()
        var failures = 0
        val coordinator = AndroidCreatorOpenCoordinator(
            resolve = navigator::resolve,
            onResolved = opened::add,
            onAmbiguous = {},
            onFailure = {
                failures += 1
                true
            },
        )

        coordinator.open(manga, mention)

        assertEquals(1, failures)
        assertEquals(listOf(41L), opened)
    }
}
