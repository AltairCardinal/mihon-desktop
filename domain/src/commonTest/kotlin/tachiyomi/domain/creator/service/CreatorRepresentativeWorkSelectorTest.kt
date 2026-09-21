package tachiyomi.domain.creator.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorCardWorkCandidate
import tachiyomi.domain.creator.model.CreatorCoverRequest
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRepresentativeWorkCache
import tachiyomi.domain.creator.model.CreatorSelectedWorkKey
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionState

class CreatorRepresentativeWorkSelectorTest {

    @Test
    fun `filters ineligible relations and rejected work while ranking and deduplicating verified work`() {
        val result = CreatorRepresentativeWorkSelector.select(
            candidates = listOf(
                candidate("canonical:read-and-favorite", 1, favorite = true, lastReadAt = 200L, cover = "cover-a"),
                candidate("canonical:read-and-favorite", 2, favorite = false, cover = "cover-b"),
                candidate("canonical:favorite", 3, favorite = true),
                candidate("canonical:read", 4, lastReadAt = 300L),
                candidate("source:unranked", 5),
                candidate(
                    "source:possible",
                    6,
                    favorite = true,
                    lastReadAt = 500L,
                    verification = CreatorRelationVerification.POSSIBLE,
                ),
                candidate(
                    "source:rejected",
                    7,
                    favorite = true,
                    lastReadAt = 900L,
                    decisionState = WorkDecisionState.REJECTED,
                ),
            ),
        )

        assertEquals(
            listOf("canonical:read-and-favorite", "canonical:favorite", "canonical:read"),
            result.representatives.map { it.workKey },
        )
        assertEquals(1L, result.representatives.first().sourceWorkId)
        assertEquals(
            listOf("canonical:read-and-favorite", "canonical:favorite", "canonical:read"),
            result.cache.selected.map { it.workKey },
        )
    }

    @Test
    fun `initial tie breaks by available cover then latest reading then stable key`() {
        val result = CreatorRepresentativeWorkSelector.select(
            candidates = listOf(
                candidate("source:z", 1),
                candidate("source:b", 2, cover = "cover-b"),
                candidate("source:a", 3, lastReadAt = 100L, cover = "cover-a"),
                candidate("source:c", 4, lastReadAt = 200L, cover = "cover-c"),
            ),
        )

        assertEquals(listOf("source:c", "source:a", "source:b"), result.representatives.map { it.workKey })
    }

    @Test
    fun `enabled language preference selects matching source version when no selection is cached`() {
        val result = CreatorRepresentativeWorkSelector.select(
            candidates = listOf(
                candidate("canonical:translation", 1, sourceLanguage = "en"),
                candidate("canonical:translation", 2, sourceLanguage = "ja"),
            ),
            preferredLanguages = setOf("ja"),
        )

        assertEquals(2L, result.representatives.single().sourceWorkId)
    }

    @Test
    fun `display script preference supersedes cached source version when language changes`() {
        val simplified = candidate("canonical:script", 1, title = "龙")
        val traditional = candidate("canonical:script", 2, title = "龍")

        val simplifiedResult = CreatorRepresentativeWorkSelector.select(
            candidates = listOf(simplified, traditional),
            previous = cache(listOf(traditional)),
            preferredDisplayScript = WorkTitleNormalizer.DisplayScript.SIMPLIFIED,
        )
        assertEquals(1L, simplifiedResult.representatives.single().sourceWorkId)

        val traditionalResult = CreatorRepresentativeWorkSelector.select(
            candidates = listOf(simplified, traditional),
            previous = simplifiedResult.cache,
            preferredDisplayScript = WorkTitleNormalizer.DisplayScript.TRADITIONAL,
        )
        assertEquals(2L, traditionalResult.representatives.single().sourceWorkId)
    }

    @Test
    fun `same-tier new work does not replace or reorder cached selections`() {
        val old = listOf(
            candidate("source:old-a", 1),
            candidate("source:old-b", 2),
            candidate("source:old-c", 3),
        )
        val previous = cache(old)

        val result = CreatorRepresentativeWorkSelector.select(
            candidates = old + candidate("source:aaa-new-with-cover", 4, cover = "new-cover"),
            previous = previous,
        )

        assertEquals(old.map { it.workKey }, result.representatives.map { it.workKey })
        assertEquals(previous, result.cache)
    }

    @Test
    fun `favorite promotion replaces only the lowest-tier cached position`() {
        val old = listOf(
            candidate("source:old-a", 1),
            candidate("source:old-b", 2),
            candidate("source:old-c", 3),
        )

        val result = CreatorRepresentativeWorkSelector.select(
            candidates = old + candidate("source:new-favorite", 4, favorite = true),
            previous = cache(old),
        )

        assertEquals(
            listOf("source:new-favorite", "source:old-a", "source:old-b"),
            result.representatives.map {
                it.workKey
            },
        )
    }

    @Test
    fun `keeps an uncovered work as a title placeholder and leaves empty authors empty`() {
        val empty = CreatorRepresentativeWorkSelector.select(candidates = emptyList())
        val uncovered = CreatorRepresentativeWorkSelector.select(candidates = listOf(candidate("source:no-cover", 1)))

        assertEquals(emptyList<CreatorCardWorkCandidate>(), empty.representatives)
        assertNull(uncovered.representatives.single().coverRequest.url)
        assertEquals(listOf("source:no-cover"), uncovered.representatives.map { it.workKey })
    }

    private fun cache(candidates: List<CreatorCardWorkCandidate>) = CreatorRepresentativeWorkCache(
        strategyVersion = CreatorRepresentativeWorkSelector.STRATEGY_VERSION,
        selected = candidates.map { CreatorSelectedWorkKey(it.workKey, it.naturalKey) },
    )

    private fun candidate(
        key: String,
        sourceId: Long,
        favorite: Boolean = false,
        lastReadAt: Long? = null,
        cover: String? = null,
        sourceLanguage: String? = null,
        verification: CreatorRelationVerification = CreatorRelationVerification.VERIFIED,
        decisionState: WorkDecisionState? = null,
        title: String = key,
    ) = CreatorCardWorkCandidate(
        workKey = key,
        sourceWorkId = sourceId,
        naturalKey = SourceWorkNaturalKey(sourceId, "/$key"),
        title = title,
        coverRequest = CreatorCoverRequest(
            sourceWorkId = sourceId,
            mangaId = if (favorite) sourceId else null,
            sourceId = sourceId,
            url = cover,
            lastModifiedAt = sourceId,
        ),
        inLibrary = favorite,
        lastReadAt = lastReadAt,
        relationVerification = verification,
        decisionState = decisionState,
        sourceLanguage = sourceLanguage,
    )
}
