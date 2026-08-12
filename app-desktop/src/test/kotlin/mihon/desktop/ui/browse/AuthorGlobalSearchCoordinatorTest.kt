package mihon.desktop.ui.browse

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceCapability
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.CreatorSourceWorkSnapshot
import tachiyomi.domain.creator.service.CreatorStructuredIdentityMatch
import tachiyomi.domain.creator.service.EnabledCreatorSource

class AuthorGlobalSearchCoordinatorTest {

    @Test
    fun `author mode preserves verified and possible identity buckets`() = runBlocking {
        val coordinator = AuthorGlobalSearchCoordinator(
            sourcePort = object : CreatorDiscoverySourcePort {
                override suspend fun enabledSourcesSnapshot() = listOf(
                    EnabledCreatorSource(7L, "Source", setOf(CreatorSourceCapability.AUTHOR_SEARCH)),
                )

                override suspend fun searchPage(request: BoundedAuthorSearchPageRequest) =
                    CreatorSourcePageResult.Content(
                        works = listOf(
                            work("/verified").copy(
                                structuredCreatorMatches = listOf(
                                    CreatorStructuredIdentityMatch("Jane Doe", CreatorRole.AUTHOR, "source author id"),
                                ),
                            ),
                            work("/possible"),
                        ),
                        hasNextPage = true,
                    )

                override suspend fun loadDetails(key: SourceWorkNaturalKey) =
                    CreatorSourceDetailsResult.Failure(tachiyomi.domain.creator.service.CreatorSourceFailure.MissingSource)
            },
            clock = { 100L },
        )

        coordinator.search("Jane Doe")

        assertEquals(1, coordinator.state.value.verifiedCount)
        assertEquals(1, coordinator.state.value.possibleCount)
        assertEquals(
            listOf(CreatorRelationVerification.VERIFIED, CreatorRelationVerification.POSSIBLE),
            coordinator.state.value.rows.single().items.map { it.identity.verification },
        )
    }

    private fun work(url: String) = CreatorSourceWorkSnapshot(
        key = SourceWorkNaturalKey(7L, url),
        title = url,
        authorText = null,
        artistText = null,
        thumbnailUrl = null,
    )
}
