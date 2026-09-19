package tachiyomi.data.creator

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.model.SourceDateQualityStatus
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class SourceDateQualityRepositoryTest {

    @Test
    fun `real repository keeps quality isolated by extension version and field`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            val database = Database(
                driver = driver,
                historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
            val repository = CreatorRepositoryImpl(JvmDatabaseHandler(database, driver))
            val now = DAY * 40
            val chapterIdentity =
                SourceDateQualityIdentity("example.extension", "1.0", 42L, SourceDateField.CHAPTER_UPDATED)
            val stable = buildList {
                repeat(3) { work ->
                    repeat(3) { chapter ->
                        val value = DAY * (10L + chapter)
                        add(observation(chapterIdentity, work, chapter, value, now - DAY))
                        add(observation(chapterIdentity, work, chapter, value, now))
                    }
                }
            }

            val trusted = repository.recordSourceDateQualityObservations(stable, now)
            assertEquals(SourceDateQualityStatus.TRUSTED, trusted?.status)

            val afterNetworkFailure = repository.recordSourceDateQualityObservations(
                listOf(
                    observation(
                        chapterIdentity,
                        work = 0,
                        chapter = 0,
                        valueAt = null,
                        observedAt = now + DAY,
                        networkFailure = true,
                        reason = "offline",
                    ),
                ),
                now + DAY,
            )
            assertEquals(SourceDateQualityStatus.TRUSTED, afterNetworkFailure?.status)
            assertEquals(
                SourceDateQualityStatus.TRUSTED,
                repository.getSourceDateQualitySnapshot(chapterIdentity)?.status,
            )

            val upgradedIdentity = chapterIdentity.copy(extensionVersion = "2.0")
            val upgraded = repository.recordSourceDateQualityObservations(
                listOf(observation(upgradedIdentity, 0, 0, DAY * 10, now + DAY)),
                now + DAY,
            )
            assertEquals(SourceDateQualityStatus.UNKNOWN, upgraded?.status)
            assertNotNull(repository.getSourceDateQualitySnapshot(upgradedIdentity))

            val workIdentity = chapterIdentity.copy(field = SourceDateField.WORK_PUBLISHED)
            val workSnapshot = repository.recordSourceDateQualityObservations(
                listOf(observation(workIdentity, 0, null, DAY * 10, now)),
                now,
            )
            assertEquals(SourceDateQualityStatus.UNKNOWN, workSnapshot?.status)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `diagnostic retention removes old samples but leaves the quality row`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val database = Database(
                driver = driver,
                historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
            val repository = CreatorRepositoryImpl(JvmDatabaseHandler(database, driver))
            val identity = SourceDateQualityIdentity("example.extension", "1.0", 42L, SourceDateField.WORK_PUBLISHED)
            val now = DAY * 40
            val snapshot = repository.recordSourceDateQualityObservations(
                listOf(observation(identity, 1, null, DAY * 2, now - 31 * DAY)),
                now,
            )

            assertEquals(0, snapshot?.sampleCount)
            assertNotNull(repository.getSourceDateQualitySnapshot(identity))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `creator archive projection exposes trusted chapter quality and projected date`() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val database = Database(
                driver = driver,
                historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
            val repository = CreatorRepositoryImpl(JvmDatabaseHandler(database, driver))
            val creator = repository.upsertCreator("Projection author")
            val identity = SourceDateQualityIdentity(
                "example.extension",
                "1.0",
                42L,
                SourceDateField.CHAPTER_UPDATED,
            )
            val now = DAY * 40
            val keys = (0..2).map { work -> SourceWorkNaturalKey(42L, "/projection-$work") }
            keys.forEachIndexed { index, key ->
                repository.upsertSourceWork(
                    sourceId = key.sourceId,
                    stableSourceUrl = key.stableSourceUrl,
                    mangaId = null,
                    title = "Projection work $index",
                    authorText = creator.displayName,
                    artistText = null,
                    thumbnailUrl = null,
                    detailsFetchedAt = now,
                )
                repository.upsertSourceWorkCreator(
                    sourceWork = key,
                    creatorId = creator.id,
                    role = CreatorRole.AUTHOR,
                    order = 0L,
                    origin = CreatorRelationOrigin.AUTOMATIC,
                    verification = CreatorRelationVerification.VERIFIED,
                    sourceText = creator.displayName,
                    confidence = 1.0,
                    evidence = "quality projection fixture",
                )
                repository.updateSourceWorkCatalog(
                    sourceWork = key,
                    chapterCount = 3L,
                    completeness = ChapterCatalogCompleteness.COMPLETE,
                    latestChapterAt = DAY * (12L + index * 10L),
                    observedAt = now,
                    mangaId = null,
                )
            }
            val observations = buildList {
                repeat(3) { work ->
                    repeat(3) { chapter ->
                        val value = DAY * (10L + work * 10L + chapter)
                        add(observation(identity, work, chapter, value, now - DAY))
                        add(observation(identity, work, chapter, value, now))
                    }
                }
            }
            val recorded = repository.recordSourceDateQualityObservations(observations, now)
            assertEquals(SourceDateQualityStatus.TRUSTED, recorded?.status)
            val readSnapshot = repository.getSourceDateQualitySnapshot(identity)
            assertEquals(DAY * 32L, readSnapshot?.projectedDateAt)

            val publicationIdentity = identity.copy(field = SourceDateField.WORK_PUBLISHED)
            val publicationObservations = buildList {
                repeat(3) { work ->
                    val value = DAY * (20L + work)
                    add(
                        observation(
                            publicationIdentity,
                            work,
                            null,
                            value,
                            now - DAY,
                            workNaturalKey = "/projection-$work",
                        ),
                    )
                    add(
                        observation(
                            publicationIdentity,
                            work,
                            null,
                            value,
                            now,
                            workNaturalKey = "/projection-$work",
                        ),
                    )
                }
            }
            val publication = repository.recordSourceDateQualityObservations(publicationObservations, now)
            assertEquals(SourceDateQualityStatus.TRUSTED, publication?.status)

            repository.recordSourceDateQualityObservations(
                listOf(
                    observation(
                        publicationIdentity,
                        work = 0,
                        chapter = null,
                        valueAt = DAY * 90L,
                        observedAt = now + 1L,
                        semanticConfirmed = false,
                        workNaturalKey = "/projection-0",
                    ),
                    observation(
                        publicationIdentity,
                        work = 0,
                        chapter = null,
                        valueAt = now + DAY,
                        observedAt = now + 2L,
                        semanticConfirmed = false,
                        workNaturalKey = "/projection-0",
                    ),
                    observation(
                        publicationIdentity,
                        work = 0,
                        chapter = null,
                        valueAt = DAY * 92L,
                        observedAt = now + 3L,
                        semanticConfirmed = false,
                        networkFailure = true,
                        workNaturalKey = "/projection-0",
                    ),
                ),
                now = now + 3L,
            )
            assertEquals(
                SourceDateQualityStatus.TRUSTED,
                repository.getSourceDateQualitySnapshot(publicationIdentity)?.status,
            )
            val beforeUpgrade = repository.getCreatorWorkArchive(creator.id).pending
                .associateBy { it.naturalKey.stableSourceUrl }
            assertEquals(DAY * 20L, beforeUpgrade.getValue("/projection-0").publishedDateAt)
            val upgradedIdentity = publicationIdentity.copy(extensionVersion = "2.0")
            repository.recordSourceDateQualityObservations(
                listOf(
                    observation(
                        upgradedIdentity,
                        work = 0,
                        chapter = null,
                        valueAt = DAY * 99L,
                        observedAt = now + 3L,
                        semanticConfirmed = false,
                        workNaturalKey = "/projection-0",
                    ),
                ),
                now = now + 3L,
            )

            val versions = repository.getCreatorWorkArchive(creator.id).pending
            assertEquals(3, versions.size)
            val versionsByUrl = versions.associateBy { it.naturalKey.stableSourceUrl }
            assertEquals(
                SourceDateQualityStatus.TRUSTED,
                versionsByUrl.getValue("/projection-0").latestChapterDateQuality,
            )
            assertEquals(
                SourceDateQualityStatus.UNKNOWN,
                versionsByUrl.getValue("/projection-0").publishedDateQuality,
            )
            assertEquals(DAY * 12L, versionsByUrl.getValue("/projection-0").latestChapterAt)
            assertEquals(DAY * 22L, versionsByUrl.getValue("/projection-1").latestChapterAt)
            assertEquals(DAY * 32L, versionsByUrl.getValue("/projection-2").latestChapterAt)
            assertEquals(null, versionsByUrl.getValue("/projection-0").publishedDateAt)
            assertEquals(null, versionsByUrl.getValue("/projection-1").publishedDateAt)
            assertEquals(null, versionsByUrl.getValue("/projection-2").publishedDateAt)
        } finally {
            driver.close()
        }
    }

    private fun observation(
        identity: SourceDateQualityIdentity,
        work: Int,
        chapter: Int?,
        valueAt: Long?,
        observedAt: Long,
        networkFailure: Boolean = false,
        reason: String? = null,
        semanticConfirmed: Boolean = identity.field == SourceDateField.WORK_PUBLISHED,
        workNaturalKey: String = "work-$work",
    ) = SourceDateObservation(
        identity = identity,
        workNaturalKey = workNaturalKey,
        chapterNaturalKey = chapter?.let { "chapter-$it" },
        rawValue = valueAt?.toString(),
        valueAt = valueAt,
        precision = SourceDatePrecision.DAY,
        semanticConfirmed = semanticConfirmed,
        observedAt = observedAt,
        networkFailure = networkFailure,
        reason = reason,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
