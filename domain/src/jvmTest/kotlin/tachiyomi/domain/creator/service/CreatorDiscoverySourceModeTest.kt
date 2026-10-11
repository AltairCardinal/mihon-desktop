package tachiyomi.domain.creator.service

import eu.kanade.tachiyomi.source.AuthorSearchManga
import eu.kanade.tachiyomi.source.AuthorSearchPage
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CreatorDiscoverySourceModeTest {
    @Test
    fun `title keyword hit cannot overwrite language evidence of the ordinary author hit`() = runBlocking {
        val source = mockk<AuthorSearchSource>()
        every { source.id } returns 7L
        every { source.name } returns "Multilingual fixture"
        every { source.lang } returns "all"
        every { source.getFilterList() } returns FilterList()
        val listed = SManga.create().also {
            it.url = "/same"
            it.title = "[Circle (Artist)] Work"
            it.author = "Artist"
        }
        coEvery { source.getAuthorSearchManga(any(), any()) } returns AuthorSearchPage(
            listOf(AuthorSearchManga(listed, emptyList(), readingLanguageTag = "en")),
            false,
        )
        coEvery { source.getSearchManga(any(), any(), any()) } returns MangasPage(listOf(listed.copy()), false)
        coEvery { source.getMangaDetails(any()) } returns listed.copy()
        val adapter = CatalogueCreatorDiscoverySourceAdapter(
            enabledSourcesProvider = { listOf(source) },
            sourceResolver = { source },
            clock = { 1_000 },
        )
        adapter.enabledSourcesSnapshot()
        val ordinary = adapter.searchPage(BoundedAuthorSearchPageRequest(7, "Artist", 1, 1, 2_000))
        val key = assertInstanceOf(CreatorSourcePageResult.Content::class.java, ordinary).works.single().key
        adapter.searchPage(BoundedAuthorSearchPageRequest(7, "Artist", 1, 1, 2_000, titleSearch = true))
        val details = assertInstanceOf(CreatorSourceDetailsResult.Content::class.java, adapter.loadDetails(key)).details
        assertEquals("en", details.readingLanguageTag)
        val titleDetails = assertInstanceOf(
            CreatorSourceDetailsResult.Content::class.java,
            adapter.loadDetails(key, titleSearch = true),
        ).details
        assertNull(titleDetails.readingLanguageTag)
    }
}
