package tachiyomi.data.creator

import eu.kanade.tachiyomi.source.AuthorSearchCreatorMatch
import eu.kanade.tachiyomi.source.AuthorSearchCreatorRole
import eu.kanade.tachiyomi.source.AuthorSearchManga
import eu.kanade.tachiyomi.source.AuthorSearchPage
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.interactor.CreatorArchive
import tachiyomi.domain.creator.interactor.DiscoverCreatorWorks
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.CatalogueCreatorDiscoverySourceAdapter
import tachiyomi.domain.creator.service.CreatorDiscoveryService

/** Both platforms execute the real source adapter, discovery interactor, SQL writes and archive projection. */
suspend fun verifyCreatorDiscoveryMetadata(handler: DatabaseHandler) {
    val repository = CreatorRepositoryImpl(handler)
    val creator = repository.upsertCreator("ONE", listOf("Alias 1", "Alias 2", "Alias 3", "ワン"))
    repository.upsertWatchPolicy(
        ArchiveWatchPolicy(
            creator.id,
            false,
            60_000L,
            emptySet(),
            emptySet(),
            includeProbable = true,
            includeUnknown = true,
        ),
        now = 1L,
    )
    val accepted = listOf("/author", "/artist", "/both", "/alias", "/coauthor")
    val details = listOf(
        discoveryManga("/author", "Valid author", " ONE "),
        discoveryManga("/artist", "Valid artist", "Other", "ONE"),
        discoveryManga("/both", "Both roles", "ONE", "ONE"),
        discoveryManga("/alias", "Known alias beyond search budget", "ワン"),
        discoveryManga("/coauthor", "Multiple authors", "one, ONE、Other"),
        discoveryManga("/similar", "Similar author", "ONE TWO"),
        discoveryManga("/title", "ONE", "Someone else"),
        discoveryManga("/missing", "Missing metadata", null),
        discoveryManga("/blank", "Blank metadata", "  ", ""),
    ).associateBy { it.url }
    val detailRequests = java.util.Collections.synchronizedList(mutableListOf<SourceWorkNaturalKey>())
    val sources = listOf(10L, 20L).map { sourceId ->
        val source = if (sourceId == 20L) mockk<AuthorSearchSource>() else mockk<CatalogueSource>()
        every { source.id } returns sourceId
        every { source.name } returns "Fixture $sourceId"
        every { source.lang } returns "en"
        every { source.getFilterList() } returns FilterList()
        // A misleading author on the search card must never override the fetched detail metadata.
        val listed = details.values.map { discoveryManga(it.url, it.title, "ONE") }
        coEvery { source.getSearchManga(any(), any(), any()) } returns MangasPage(listed, false)
        if (source is AuthorSearchSource) {
            coEvery { source.getAuthorSearchManga(any(), any()) } returns AuthorSearchPage(
                listed.map {
                    AuthorSearchManga(
                        it,
                        listOf(AuthorSearchCreatorMatch("ONE", AuthorSearchCreatorRole.AUTHOR, "search:one")),
                    )
                },
                false,
            )
        }
        coEvery { source.getMangaDetails(any()) } answers {
            val url = firstArg<SManga>().url
            detailRequests += SourceWorkNaturalKey(sourceId, url)
            details.getValue(url)
        }
        source
    }
    val adapter = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = { sources },
        sourceResolver = { id -> sources.firstOrNull { it.id == id } },
    )
    val service = CreatorDiscoveryService(repository, repository, adapter)
    val discovery = DiscoverCreatorWorks(service, GetCreatorDetails(repository))
    discovery.await(creator.id)

    val expected = sources.flatMap { source -> accepted.map { SourceWorkNaturalKey(source.id, it) } }.toSet()
    val archive = CreatorArchive(repository, repository).observe(creator.id).first()
    check(archive.pending.map { it.naturalKey }.toSet() == expected) {
        "Only detail-verified works may appear: ${archive.pending.map { it.naturalKey }}"
    }
    check(detailRequests.size == details.size * sources.size) { "Each unique result must fetch details exactly once" }
    handler.await {
        val relations = author_archiveQueries.getArchiveSourceWorkBindingsForBackup().executeAsList()
        check(relations.size == expected.size && relations.all { it.verification == "VERIFIED" })
    }
    discovery.await(creator.id)
    check(repository.getCreatorWorkArchive(creator.id).pending.map { it.naturalKey }.toSet() == expected)
    check(repository.getUnreadDiscoveries(100).isEmpty()) { "Manual unfollowed discovery cannot create reminders" }
}

suspend fun verifyCreatorDiscoveryArchiveProjection(handler: DatabaseHandler) {
    val repository = CreatorRepositoryImpl(handler)
    val creator = repository.upsertCreator("Archive Author")
    val possible = SourceWorkNaturalKey(30L, "/old-fuzzy-match")
    val manual = SourceWorkNaturalKey(30L, "/manual")
    for (key in listOf(possible, manual)) {
        repository.upsertSourceWork(key.sourceId, key.stableSourceUrl, null, key.stableSourceUrl, null, null, null, 1L)
        repository.upsertSourceWorkCreator(
            key, creator.id, CreatorRole.UNKNOWN, 0L,
            if (key == manual) CreatorRelationOrigin.USER else CreatorRelationOrigin.AUTOMATIC,
            if (key == manual) CreatorRelationVerification.VERIFIED else CreatorRelationVerification.POSSIBLE,
            null, 0.2, "historical fixture",
        )
    }
    check(repository.getCreatorWorkArchive(creator.id).pending.map { it.naturalKey } == listOf(manual))
    check(
        CreatorArchive(repository, repository).observe(creator.id).first().pending.map { it.naturalKey } ==
            listOf(manual),
    )
    handler.await {
        check(
            author_archiveQueries.getArchiveSourceWorkByKey(possible.sourceId, possible.stableSourceUrl)
                .executeAsOneOrNull() != null,
        ) { "Filtering must not delete historical data" }
    }
    repository.upsertSourceWorkCreator(
        possible, creator.id, CreatorRole.AUTHOR, 0L, CreatorRelationOrigin.AUTOMATIC,
        CreatorRelationVerification.VERIFIED, "Archive Author", 0.95, "exact creator metadata field",
    )
    check(repository.getCreatorWorkArchive(creator.id).pending.size == 2) { "Later verification must reveal the work" }
}

private fun discoveryManga(url: String, title: String, author: String?, artist: String? = null) = SManga.create().also {
    it.url = url
    it.title = title
    it.author = author
    it.artist = artist
}
