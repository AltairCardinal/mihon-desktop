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
import kotlinx.coroutines.withContext
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
    fun `Android presentation groups traditional and simplified title variants without archiving`() = runTest {
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
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    model.state.first { it.archive.pending.size == 2 }
                }
            }
            val presentation = model.state.value.presentationGroups.single()
            assertEquals(null, presentation.canonicalWorkId)
            assertEquals(setOf("詭譎屋", "诡谲屋"), presentation.members.map { it.title }.toSet())
            val persistedArchive = CreatorArchive(repository, repository).get(creator.id)
            assertEquals(2, persistedArchive.pending.size)
            assertEquals(emptyList<Any>(), persistedArchive.works)
        } finally {
            modelHost.close()
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
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    model.state.first { it.archive.pending.size == 2 }
                }
            }
            val presentation = model.state.value.presentationGroups.single()
            assertEquals(null, presentation.canonicalWorkId)
            assertEquals(2, presentation.members.size)
            val persistedArchive = CreatorArchive(repository, repository).get(creator.id)
            assertEquals(2, persistedArchive.pending.size)
            assertEquals(emptyList<Any>(), persistedArchive.works)
        } finally {
            modelHost.close()
            driver.close()
            Dispatchers.resetMain()
        }
    }
}
