package tachiyomi.domain.creator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDateObservation
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceDateQualityIdentity
import tachiyomi.domain.creator.model.SourceDateQualityPolicy
import tachiyomi.domain.creator.model.SourceDateQualityStatus

class SourceDateQualityPolicyTest {

    @Test
    fun `chapter dates become trusted only after bounded cross day stable evidence`() {
        val observedAt = DAY * 40
        val identity = SourceDateQualityIdentity(
            extensionPackage = "example.extension",
            extensionVersion = "1.0",
            sourceId = 42L,
            field = SourceDateField.CHAPTER_UPDATED,
        )

        val samples = buildList {
            repeat(3) { work ->
                repeat(3) { chapter ->
                    val chapterDate = DAY * (10L + chapter)
                    add(observation(identity, work, chapter, chapterDate, observedAt - DAY))
                    add(observation(identity, work, chapter, chapterDate, observedAt))
                }
            }
        }

        val result = SourceDateQualityPolicy.evaluate(identity, samples, observedAt)

        assertEquals(SourceDateQualityStatus.TRUSTED, result.status)
        assertEquals(3, result.observedWorkCount)
        assertEquals(9, result.stableChapterCount)
        assertEquals(DAY * 12L, result.projectedDateAt)
    }

    @Test
    fun `single day chapter batch remains unknown`() {
        val observedAt = DAY * 40
        val identity = SourceDateQualityIdentity(
            extensionPackage = "example.extension",
            extensionVersion = "1.0",
            sourceId = 42L,
            field = SourceDateField.CHAPTER_UPDATED,
        )
        val samples = buildList {
            repeat(3) { work ->
                repeat(3) { chapter ->
                    add(observation(identity, work, chapter, DAY * 10L, observedAt))
                }
            }
        }

        assertEquals(
            SourceDateQualityStatus.UNKNOWN,
            SourceDateQualityPolicy.evaluate(identity, samples, observedAt).status,
        )
    }

    @Test
    fun `historic chapter dates drifting to observation day become suspect`() {
        val observedAt = DAY * 40
        val identity = SourceDateQualityIdentity(
            extensionPackage = "example.extension",
            extensionVersion = "1.0",
            sourceId = 42L,
            field = SourceDateField.CHAPTER_UPDATED,
        )
        val samples = buildList {
            repeat(3) { work ->
                repeat(3) { chapter ->
                    val keyDate = DAY * (10L + chapter)
                    add(observation(identity, work, chapter, keyDate, observedAt - DAY))
                    add(observation(identity, work, chapter, observedAt, observedAt))
                }
            }
        }

        assertEquals(
            SourceDateQualityStatus.SUSPECT,
            SourceDateQualityPolicy.evaluate(identity, samples, observedAt).status,
        )
    }

    @Test
    fun `work publication dates use an independent semantic evidence threshold`() {
        val observedAt = DAY * 40
        val identity = SourceDateQualityIdentity(
            extensionPackage = "example.extension",
            extensionVersion = "1.0",
            sourceId = 42L,
            field = SourceDateField.WORK_PUBLISHED,
        )
        val samples = buildList {
            repeat(3) { work ->
                val value = DAY * (work + 2L)
                add(observation(identity, work, null, value, observedAt - DAY, semanticConfirmed = true))
                add(observation(identity, work, null, value, observedAt, semanticConfirmed = true))
            }
        }

        assertEquals(
            SourceDateQualityStatus.TRUSTED,
            SourceDateQualityPolicy.evaluate(identity, samples, observedAt).status,
        )
    }

    @Test
    fun `network failures do not consume bounded valid work capacity`() {
        val observedAt = DAY * 40
        val identity = SourceDateQualityIdentity(
            extensionPackage = "example.extension",
            extensionVersion = "1.0",
            sourceId = 42L,
            field = SourceDateField.CHAPTER_UPDATED,
        )
        val stable = buildList {
            repeat(3) { work ->
                repeat(3) { chapter ->
                    val value = DAY * (10L + chapter)
                    add(observation(identity, work, chapter, value, observedAt - DAY))
                    add(observation(identity, work, chapter, value, observedAt))
                }
            }
        }
        val failures = (0 until SourceDateQualityPolicy.MAX_WORK_SAMPLES).map { work ->
            observation(
                identity = identity,
                work = work + 100,
                chapter = 0,
                valueAt = null,
                observedAt = observedAt + 1L,
                networkFailure = true,
            )
        }

        val retained = SourceDateQualityPolicy.retain(stable + failures, observedAt + 1L)

        assertEquals(
            SourceDateQualityStatus.TRUSTED,
            SourceDateQualityPolicy.evaluate(identity, retained, observedAt + 1L).status,
        )
    }

    @Test
    fun `unconfirmed publication values do not satisfy semantic history threshold`() {
        val observedAt = DAY * 40
        val identity = SourceDateQualityIdentity(
            extensionPackage = "example.extension",
            extensionVersion = "1.0",
            sourceId = 42L,
            field = SourceDateField.WORK_PUBLISHED,
        )
        val confirmed = buildList {
            repeat(3) { work ->
                add(observation(identity, work, null, DAY * 2L, observedAt - DAY, semanticConfirmed = true))
                add(observation(identity, work, null, DAY * 2L, observedAt, semanticConfirmed = true))
            }
        }
        val unconfirmed = listOf(
            observation(identity, 10, null, DAY * 3L, observedAt, semanticConfirmed = false),
            observation(identity, 11, null, DAY * 4L, observedAt, semanticConfirmed = false),
        )

        assertEquals(
            SourceDateQualityStatus.UNKNOWN,
            SourceDateQualityPolicy.evaluate(identity, confirmed + unconfirmed, observedAt).status,
        )
    }

    private fun observation(
        identity: SourceDateQualityIdentity,
        work: Int,
        chapter: Int?,
        valueAt: Long?,
        observedAt: Long,
        semanticConfirmed: Boolean = false,
        networkFailure: Boolean = false,
    ) = SourceDateObservation(
        identity = identity,
        workNaturalKey = "work-$work",
        chapterNaturalKey = chapter?.let { "chapter-$it" },
        rawValue = valueAt?.toString(),
        valueAt = valueAt,
        precision = SourceDatePrecision.DAY,
        observedAt = observedAt,
        semanticConfirmed = semanticConfirmed,
        networkFailure = networkFailure,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
