package tachiyomi.data.creator

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.WatchBaselineState
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CatalogueCreatorDiscoverySourceAdapter
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.TitleCreditParser
import tachiyomi.domain.manga.model.Manga

/** Forces the title click after the mode read and before the executor can read source baselines. */
suspend fun verifyCreatorTitleModeReadRace(handler: DatabaseHandler) {
    val creators = CreatorRepositoryImpl(handler)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators)
    val manga = mangas.insertNetworkManga(
        listOf(
            Manga.create().copy(
                source = 99,
                url = "/mode-race",
                title = "[Circle (Artist)] Work",
                author = "Artist",
                favorite = true,
            ),
        ),
    ).single()
    val id = checkNotNull(creators.resolveCreatorIdByExactName("Artist"))
    creators.upsertWatchPolicy(
        ArchiveWatchPolicy(
            id, true, 60_000,
            setOf(
                10,
            ),
            emptySet(), includeProbable = true, includeUnknown = true, notifyProbable = true, notifyUnknown = true,
        ),
        1,
    )
    var triggerClick = false
    val observed = object : CreatorRepository by creators {
        override suspend fun hasTitleAttribution(creatorId: Long): Boolean {
            val before = creators.hasTitleAttribution(creatorId)
            if (triggerClick) {
                triggerClick = false
                ManageCreatorIdentity(creators).resolve(manga, TitleCreditParser.parse(manga.title).first())
            }
            return before
        }
    }
    val source = mockk<CatalogueSource>()
    every { source.id } returns 10L
    every { source.name } returns "Mode read fixture"
    every { source.lang } returns "en"
    every { source.getFilterList() } returns FilterList()
    val raw = SManga.create().also {
        it.url = "/metadata"
        it.title = "Metadata baseline"
        it.author = "Artist"
    }
    coEvery { source.getSearchManga(any(), any(), any()) } returns MangasPage(listOf(raw), false)
    coEvery { source.getMangaDetails(any()) } returns raw
    var now = 1_000L
    val adapter = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = { listOf(source) },
        sourceResolver = { source },
        clock = { now },
    )
    val service = CreatorDiscoveryService(observed, creators, adapter, clock = { now })
    service.discoverCreator(id)
    check(creators.getWatchSourceBaselines(id).single().baselineState == WatchBaselineState.BASELINED)
    triggerClick = true
    now += 86_400_000
    service.discoverCreator(id)
    check(creators.hasTitleAttribution(id))
    check(creators.getWatchSourceBaselines(id).single().baselineState == WatchBaselineState.NEEDS_BASELINE) {
        "A metadata plan observed before the title click cannot consume its replacement baseline"
    }
    check(creators.getPendingNotificationOutbox(now, 10).isEmpty())
}
