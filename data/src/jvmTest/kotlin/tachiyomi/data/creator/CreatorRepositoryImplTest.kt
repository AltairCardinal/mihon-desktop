package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.WorkMatchState

class CreatorRepositoryImplTest {

    private lateinit var repository: CreatorRepositoryImpl

    @BeforeEach
    fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(
                last_readAdapter = DateColumnAdapter,
            ),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        repository = CreatorRepositoryImpl(JvmDatabaseHandler(database, driver))
    }

    @Test
    fun `upsertCreator reuses normalized name`() = runBlocking {
        val first = repository.upsertCreator(" ONE ")
        val second = repository.upsertCreator("one")

        first.id shouldBe second.id
        repository.getCreator(first.id) shouldBe first
        repository.getCreatorsAsFlow().first().size shouldBe 1
    }

    @Test
    fun `upsertCreator returns a readable CJK author immediately after insert`() = runBlocking {
        val creator = repository.upsertCreator("藤本树")

        repository.getCreator(creator.id) shouldBe creator
        creator.displayName shouldBe "藤本树"
    }

    @Test
    fun `followed creators persist source and language filters`() = runBlocking {
        val creator = repository.upsertCreator("Inio Asano")
        repository.followCreator(creator.id, sourceIds = listOf(1L, 2L), languageTags = listOf("en", "ja"))

        val watch = repository.getFollowedCreators().single()
        watch.creatorId shouldBe creator.id
        watch.sourceIds.shouldContainExactly(1L, 2L)
        watch.languageTags.shouldContainExactly("en", "ja")
    }

    @Test
    fun `discovery candidates are deduped by source and url`() = runBlocking {
        val creator = repository.upsertCreator("ONE")
        val first = repository.upsertDiscoveryCandidate(
            source = 1L,
            url = "/manga/one",
            title = "One Work",
            authorText = "ONE",
            artistText = null,
            languageTag = "en",
            languageConfidence = 0.65,
            languageEvidence = "SOURCE_LANGUAGE",
            thumbnailUrl = null,
            detailsFetchedAt = null,
        )
        repository.linkDiscoveryCandidateCreator(first.id, creator.id, CreatorRole.AUTHOR, "ONE", 1.0, "test")
        val second = repository.upsertDiscoveryCandidate(
            source = 1L,
            url = "/manga/one",
            title = "One Work Updated",
            authorText = "ONE",
            artistText = null,
            languageTag = "ja",
            languageConfidence = 1.0,
            languageEvidence = "EXPLICIT_METADATA",
            thumbnailUrl = "https://example.com/cover.jpg",
            detailsFetchedAt = 20L,
        )

        first.id shouldBe second.id
        second.title shouldBe "One Work Updated"
        second.languageTag shouldBe "ja"
        repository.getDiscoveryCandidatesForCreator(creator.id).single().id shouldBe first.id
        repository.getMangaCreatorsForCreator(creator.id).size shouldBe 0
    }

    @Test
    fun `discovery candidate can be loaded by id for comparison`() = runBlocking {
        val candidate = repository.upsertDiscoveryCandidate(
            source = 7L,
            url = "/manga/compare",
            title = "Compare Work",
            authorText = "ONE",
            artistText = "Yusuke Murata",
            languageTag = "en",
            languageConfidence = 0.75,
            languageEvidence = "SOURCE_LANGUAGE",
            thumbnailUrl = "https://example.com/cover.jpg",
            detailsFetchedAt = 200L,
        )

        repository.getDiscoveryCandidate(candidate.id) shouldBe candidate
    }

    @Test
    fun `manga creator links can be listed by creator`() = runBlocking {
        val creator = repository.upsertCreator("ONE")
        repository.linkMangaCreator(11L, creator.id, CreatorRole.AUTHOR, "ONE", 1.0, "manual")
        repository.linkMangaCreator(12L, creator.id, CreatorRole.ARTIST, "ONE", 0.8, "details")

        val links = repository.getMangaCreatorsForCreator(creator.id)

        links.map { it.mangaId }.shouldContainExactly(11L, 12L)
        links.map { it.role }.shouldContainExactly(CreatorRole.AUTHOR, CreatorRole.ARTIST)
    }

    @Test
    fun `discovery candidate links can be listed by creator without polluting manga links`() = runBlocking {
        val creator = repository.upsertCreator("ONE")
        val candidate = repository.upsertDiscoveryCandidate(
            source = 1L,
            url = "/manga/candidate",
            title = "Candidate Work",
            authorText = "ONE",
            artistText = null,
            languageTag = "en",
            languageConfidence = 0.75,
            languageEvidence = "SOURCE_LANGUAGE",
            thumbnailUrl = null,
            detailsFetchedAt = null,
        )

        repository.linkDiscoveryCandidateCreator(candidate.id, creator.id, CreatorRole.AUTHOR, "ONE", 0.8, "search")

        repository.getDiscoveryCandidateCreatorsForCreator(creator.id).single().candidateId shouldBe candidate.id
        repository.getDiscoveryCandidatesForCreator(creator.id).single().id shouldBe candidate.id
        repository.getMangaCreatorsForCreator(creator.id).size shouldBe 0
    }

    @Test
    fun `canonical work match can be confirmed`() = runBlocking {
        val creator = repository.upsertCreator("ONE")
        val work = repository.createCanonicalWork("One Work", creator.id, "ja")

        val match = repository.upsertMangaWorkMatch(
            mangaId = 99L,
            workId = work.id,
            confidence = 0.95,
            matchReason = "manual",
            state = WorkMatchState.CONFIRMED,
            manuallyConfirmed = true,
        )

        match.workId shouldBe work.id
        match.state shouldBe WorkMatchState.CONFIRMED
        match.manuallyConfirmed shouldBe true
    }

    @Test
    fun `candidate state defaults to new`() = runBlocking {
        val candidate = repository.upsertDiscoveryCandidate(
            source = 1L,
            url = "/manga/new",
            title = "New Work",
            authorText = null,
            artistText = null,
            languageTag = "unknown",
            languageConfidence = 0.0,
            languageEvidence = "UNKNOWN",
            thumbnailUrl = null,
            detailsFetchedAt = null,
        )

        candidate.state shouldBe DiscoveryCandidateState.NEW
    }
}
