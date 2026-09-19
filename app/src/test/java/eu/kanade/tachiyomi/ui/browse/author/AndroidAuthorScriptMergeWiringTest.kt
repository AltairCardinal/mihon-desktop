package eu.kanade.tachiyomi.ui.browse.author

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionState
import tachiyomi.domain.manga.interactor.NetworkToLocalManga

class AndroidAuthorScriptMergeWiringTest {
    private lateinit var jdbcDriver: java.sql.Driver
    private val modelHost = ScreenModelTestHost()

    @BeforeEach
    fun registerJdbcDriver() {
        jdbcDriver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as java.sql.Driver
        java.sql.DriverManager.registerDriver(jdbcDriver)
    }

    @AfterEach
    fun releaseJdbcDriver() {
        modelHost.close()
        java.sql.DriverManager.deregisterDriver(jdbcDriver)
    }

    @Test
    fun `Android review merges traditional and simplified title variants through production archive`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        try {
            val repository = CreatorRepositoryImpl(handler)
            val creator = repository.upsertCreator("Title Author")
            val traditional = SourceWorkNaturalKey(10L, "/traditional")
            val simplified = SourceWorkNaturalKey(11L, "/simplified")
            repository.upsertSourceWork(
                traditional.sourceId,
                traditional.stableSourceUrl,
                101L,
                "詭譎屋",
                creator.displayName,
                null,
                null,
                1L,
            )
            repository.upsertSourceWork(
                simplified.sourceId,
                simplified.stableSourceUrl,
                102L,
                "诡谲屋",
                creator.displayName,
                null,
                null,
                1L,
            )
            listOf(traditional, simplified).forEachIndexed { index, sourceWork ->
                repository.upsertSourceWorkCreator(
                    sourceWork,
                    creator.id,
                    CreatorRole.AUTHOR,
                    index.toLong(),
                    CreatorRelationOrigin.AUTOMATIC,
                    CreatorRelationVerification.VERIFIED,
                    creator.displayName,
                    1.0,
                    "script-variant-test",
                )
            }
            val directArchive = CreatorArchive(repository, repository).get(creator.id)
            assertEquals(2, directArchive.pending.size)
            val model = modelHost.create {
                AndroidAuthorDetailScreenModel(
                    creatorId = creator.id,
                    details = GetCreatorDetails(repository),
                    creators = GetCreators(repository),
                    follow = SetCreatorFollow(repository),
                    discovery = mockk<DiscoverCreatorWorks>(relaxed = true),
                    archive = CreatorArchive(repository, repository),
                    sources = mockk(relaxed = true),
                    identity = ManageCreatorIdentity(repository),
                    networkToLocal = mockk<NetworkToLocalManga>(relaxed = true),
                )
            }
            withTimeout(5_000) {
                model.state.first { it.archive.pending.size == 2 }
            }
            val current = model.state.value.archive.pending.first { it.title == "詭譎屋" }
            model.openReview(current)
            val target = model.state.value.reviewCandidates.single()
            assertEquals("诡谲屋", target.title)

            model.decide(WorkDecisionState.CONFIRMED, target).join()
            assertEquals(false, model.state.value.reviewActionRunning)
            assertEquals(null, model.state.value.error)
            val persistedArchive = CreatorArchive(repository, repository).get(creator.id)
            assertEquals(2, persistedArchive.works.singleOrNull()?.versions?.size)

            withTimeout(5_000) {
                model.state.first { state ->
                    state.error != null ||
                        state.archive.works.singleOrNull()?.versions?.map { it.title }?.toSet() ==
                        setOf("詭譎屋", "诡谲屋")
                }
            }
            assertEquals(null, model.state.value.error)
            assertEquals(2, model.state.value.archive.works.single().versions.size)
        } finally {
            driver.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Android review reuses an existing suggested target decision`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver,
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        try {
            val repository = CreatorRepositoryImpl(handler)
            val creator = repository.upsertCreator("Title Author")
            val traditional = SourceWorkNaturalKey(10L, "/traditional")
            val simplified = SourceWorkNaturalKey(11L, "/simplified")
            repository.upsertSourceWork(
                traditional.sourceId,
                traditional.stableSourceUrl,
                101L,
                "詭譎屋",
                creator.displayName,
                null,
                null,
                1L,
            )
            repository.upsertSourceWork(
                simplified.sourceId,
                simplified.stableSourceUrl,
                102L,
                "诡谲屋",
                creator.displayName,
                null,
                null,
                1L,
            )
            listOf(traditional, simplified).forEachIndexed { index, sourceWork ->
                repository.upsertSourceWorkCreator(
                    sourceWork,
                    creator.id,
                    CreatorRole.AUTHOR,
                    index.toLong(),
                    CreatorRelationOrigin.AUTOMATIC,
                    CreatorRelationVerification.VERIFIED,
                    creator.displayName,
                    1.0,
                    "script-variant-test",
                )
            }
            val suggestedWork = repository.createCanonicalWork("诡谲屋", creator.id, null)
            repository.appendWorkDecision(
                simplified,
                suggestedWork.id,
                WorkDecisionContract(WorkDecisionState.SUGGESTED, DecisionActor.ALGORITHM, false),
                "work-match-v3",
                1.0,
                "script-variant-test",
                2L,
                "suggested-script-variant",
            )
            assertEquals(2, CreatorArchive(repository, repository).get(creator.id).pending.size)
            val model = modelHost.create {
                AndroidAuthorDetailScreenModel(
                    creatorId = creator.id,
                    details = GetCreatorDetails(repository),
                    creators = GetCreators(repository),
                    follow = SetCreatorFollow(repository),
                    discovery = mockk<DiscoverCreatorWorks>(relaxed = true),
                    archive = CreatorArchive(repository, repository),
                    sources = mockk(relaxed = true),
                    identity = ManageCreatorIdentity(repository),
                    networkToLocal = mockk<NetworkToLocalManga>(relaxed = true),
                )
            }
            withTimeout(5_000) {
                model.state.first { it.archive.pending.size == 2 }
            }
            val current = model.state.value.archive.pending.first { it.title == "詭譎屋" }
            model.openReview(current)
            val target = model.state.value.reviewCandidates.single()

            model.decide(WorkDecisionState.CONFIRMED, target).join()

            assertEquals(null, model.state.value.error)
            val persistedArchive = CreatorArchive(repository, repository).get(creator.id)
            assertEquals(suggestedWork.id, persistedArchive.works.singleOrNull()?.workId)
            assertEquals(2, persistedArchive.works.singleOrNull()?.versions?.size)
        } finally {
            driver.close()
            Dispatchers.resetMain()
        }
    }
}
