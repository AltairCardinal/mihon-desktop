package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.every
import io.mockk.mockk
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourcePageError
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceQueryState
import tachiyomi.domain.source.service.SourceRecoveryAction

class GlobalSearchAuthorityProjectionTest {

    @Test
    fun `selected sources keep loading empty content and error rows with exact progress`() {
        val content = source(1, "Zulu", "en")
        val empty = source(2, "Empty", "en")
        val failure = source(3, "Failure", "en")
        val loading = source(4, "Loading", "en")
        val missing = source(5, "Missing", "en")
        val contentError = SourcePageError(AppError.Server(500), SourceRecoveryAction.Retry)
        val state = DesktopGlobalSearchState(
            generation = 7,
            isSearching = true,
            queryStates = mapOf(
                content.id to SourceQueryState.Content(request(content, 7), listOf(manga("/kept")), false, pageError = contentError),
                empty.id to SourceQueryState.Empty(request(empty, 7)),
                failure.id to SourceQueryState.Failure(request(failure, 7), AppError.Server(503), SourceRecoveryAction.Retry),
                loading.id to SourceQueryState.Loading(request(loading, 7)),
            ),
        )

        val ui = GlobalSearchStateProjector.project(
            listOf(content, empty, failure, loading, missing),
            state,
            pinnedIds = setOf(empty.id.toString()),
        )

        assertEquals(5, ui.total)
        assertEquals(3, ui.completed)
        assertTrue(ui.loading)
        assertFalse(ui.empty)
        assertEquals(
            mapOf(
                content.id to GlobalSearchRowKind.Content,
                empty.id to GlobalSearchRowKind.Empty,
                failure.id to GlobalSearchRowKind.Error,
                loading.id to GlobalSearchRowKind.Loading,
                missing.id to GlobalSearchRowKind.Loading,
            ),
            ui.results.associate { it.source.id to it.kind },
        )
        assertEquals(listOf("/kept"), ui.results.first { it.source.id == content.id }.results.map(SManga::url))
        assertInstanceOf(AppError.Server::class.java, ui.results.first { it.source.id == content.id }.error?.error)
        assertInstanceOf(AppError.Server::class.java, ui.results.first { it.source.id == failure.id }.error?.error)
    }

    @Test
    fun `sort follows nonempty success then pinned then lowercase name and language`() {
        val pinnedSuccess = source(1, "alpha", "fr")
        val success = source(2, "Zulu", "en")
        val pinnedEmpty = source(3, "Zeta", "en")
        val failure = source(4, "alpha", "en")
        val missing = source(5, "Alpha", "fr")
        val sources = listOf(missing, failure, pinnedEmpty, success, pinnedSuccess)
        val state = DesktopGlobalSearchState(
            generation = 2,
            queryStates = mapOf(
                pinnedSuccess.id to SourceQueryState.Content(request(pinnedSuccess, 2), listOf(manga("/pinned")), false),
                success.id to SourceQueryState.Content(request(success, 2), listOf(manga("/success-1"), manga("/success-2")), false),
                pinnedEmpty.id to SourceQueryState.Empty(request(pinnedEmpty, 2)),
                failure.id to SourceQueryState.Failure(request(failure, 2), AppError.Server(500), SourceRecoveryAction.Retry),
            ),
        )

        val ui = GlobalSearchStateProjector.project(
            sources,
            state,
            pinnedIds = setOf(pinnedSuccess.id.toString(), pinnedEmpty.id.toString()),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ui.results.map { it.source.id })
        assertFalse(GlobalSearchStateProjector.project(listOf(failure), state).empty)
        assertFalse(GlobalSearchStateProjector.project(listOf(pinnedEmpty, failure), state).empty)
        assertTrue(GlobalSearchStateProjector.project(listOf(pinnedEmpty), state).empty)
        assertEquals(
            listOf(1L, 2L),
            GlobalSearchStateProjector.project(sources, state, pinnedIds = emptySet(), onlyShowHasResults = true)
                .results.map { it.source.id },
        )
    }

    private fun request(source: CatalogueSource, generation: Long) =
        SourcePageRequest(source.id, 1, generation, SourceQuery.Popular)

    private fun source(id: Long, name: String, lang: String): CatalogueSource = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { this@mockk.lang } returns lang
    }

    private fun manga(url: String) = SManga.create().apply {
        this.url = url
        title = url
    }
}
