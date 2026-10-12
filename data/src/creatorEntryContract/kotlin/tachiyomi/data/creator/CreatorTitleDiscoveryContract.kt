package tachiyomi.data.creator

import eu.kanade.tachiyomi.source.AuthorSearchPage
import eu.kanade.tachiyomi.source.AuthorSearchSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.ArchiveWatchPolicy
import tachiyomi.domain.creator.model.CreatorRelationOrigin
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.CatalogueCreatorDiscoverySourceAdapter
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorSourceDetailsResult
import tachiyomi.domain.creator.service.TitleCreditParser
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

suspend fun verifyClickedTitleSurvivesOrdinaryIndex(handler: DatabaseHandler, sameName: Boolean) {
    val creators = CreatorRepositoryImpl(handler)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators)
    val manga = mangas.insertNetworkManga(listOf(titleFixtureManga(if (sameName) "Artist" else "Uploader"))).single()
    val mention = TitleCreditParser.parse(manga.title).first()
    val id = (
        ManageCreatorIdentity(
            creators,
        ).resolve(manga, mention) as tachiyomi.domain.creator.model.CreatorMentionResolution.Resolved
        ).creatorId
    creators.followCreator(id)
    val beforeWatch = creators.getFollowedCreators().single()
    creators.indexLibraryManga(manga, ExtractCreatorsFromManga().await(manga))
    val links = creators.getMangaCreatorsForCreator(id)
    check(links.singleOrNull()?.evidence == "title-attribution:v1:TITLE_CREATOR") {
        "Explicit title click must survive normal field reindexing, including an existing same-name root: $links"
    }
    check(creators.hasTitleAttribution(id)) { "Persisted title credit must select creator title discovery" }
    check(creators.getFollowedCreators().single() == beforeWatch)
    check(mangas.getMangaById(manga.id) == manga) { "Clicking a title credit cannot rewrite raw manga fields" }
    handler.await {
        check(
            author_archiveQueries.getArchiveMangaLinksForIndex(manga.id).executeAsList().single {
                it.creator_id == id
            }.origin ==
                "USER",
        )
    }
}

suspend fun verifyRestoredTitleAuthority(handler: DatabaseHandler) {
    val creators = CreatorRepositoryImpl(handler)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators)
    val manga = mangas.insertNetworkManga(listOf(titleFixtureManga("Artist"))).single()
    val id = checkNotNull(creators.resolveCreatorIdByExactName("Artist"))
    handler.await(inTransaction = true) {
        author_archiveQueries.upsertArchiveMangaLink(
            manga.id,
            id,
            "ARTIST",
            7,
            "RESTORE",
            "restored source",
            0.8,
            "restored authority",
            10,
            11,
        )
        val sourceWork = author_archiveQueries.getArchiveSourceWorkByKey(manga.source, manga.url).executeAsOne()
        author_archiveQueries.upsertArchiveSourceWorkCreator(
            sourceWork._id,
            id,
            "ARTIST",
            7,
            "RESTORE",
            "VERIFIED",
            "restored source",
            0.8,
            "restored authority",
            10,
            11,
        )
    }
    val beforeManga = handler.await {
        author_archiveQueries.getArchiveMangaLinksForIndex(manga.id).executeAsList().single()
    }
    val beforeSource = handler.await {
        author_archiveQueries.getArchiveSourceWorkBindingsForBackup().executeAsList().single()
    }
    val mention = TitleCreditParser.parse(manga.title).first()
    ManageCreatorIdentity(creators).resolve(manga, mention)
    val afterManga = handler.await {
        author_archiveQueries.getArchiveMangaLinksForIndex(manga.id).executeAsList().single()
    }
    val afterSource = handler.await {
        author_archiveQueries.getArchiveSourceWorkBindingsForBackup().executeAsList().single()
    }
    check(afterManga.copy(evidence = beforeManga.evidence) == beforeManga)
    check(afterSource.copy(evidence = beforeSource.evidence) == beforeSource)
    check(afterManga.evidence == "restored authority\ntitle-attribution:v1:TITLE_CREATOR")
    check(afterSource.evidence == afterManga.evidence)
    ManageCreatorIdentity(creators).resolve(manga, mention)
    creators.indexLibraryManga(manga, ExtractCreatorsFromManga().await(manga))
    check(
        handler.await { author_archiveQueries.getArchiveMangaLinksForIndex(manga.id).executeAsList().single() } ==
            afterManga,
    )
    check(creators.hasTitleAttribution(id))
}

suspend fun verifyStaleTitleClick(handler: DatabaseHandler) {
    val creators = CreatorRepositoryImpl(handler)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators)
    listOf("source", "url", "title").forEachIndexed { index, changed ->
        val original = mangas.insertNetworkManga(
            listOf(titleFixtureManga("Uploader").copy(url = "/stale-$index", favorite = false)),
        ).single()
        val mention = TitleCreditParser.parse(original.title).first()
        val update = when (changed) {
            "source" -> MangaUpdate(original.id, source = 100)
            "url" -> MangaUpdate(original.id, url = "/moved-$index")
            else -> MangaUpdate(original.id, title = "[Circle (Other)] Updated work")
        }
        check(mangas.update(update))
        var staleError: IllegalStateException? = null
        try {
            ManageCreatorIdentity(creators).resolve(original, mention)
        } catch (failure: IllegalStateException) {
            staleError = failure
        }
        check(staleError?.message?.contains("已变化") == true) {
            "Changed $changed must reject the old clicked-title snapshot with explicit stale feedback"
        }
        check(creators.resolveCreatorIdByExactName("Artist") == null) {
            "Rejected click cannot leave an orphan creator"
        }
    }
}

suspend fun verifyTitleEvidenceMarkers(handler: DatabaseHandler) {
    val creators = CreatorRepositoryImpl(handler)
    val creator = creators.upsertCreator("Marker fixture")
    val key = SourceWorkNaturalKey(52, "/marker")
    creators.upsertSourceWork(52, "/marker", null, "Raw title", null, null, null, 1)
    suspend fun put(
        evidence: String,
        verification: CreatorRelationVerification = CreatorRelationVerification.VERIFIED,
        origin: CreatorRelationOrigin = CreatorRelationOrigin.USER,
    ) {
        creators.upsertSourceWorkCreator(
            key,
            creator.id,
            CreatorRole.UNKNOWN,
            0,
            origin,
            verification,
            null,
            1.0,
            evidence,
        )
    }
    put("title-attribution:v1:TITLE_CREATOR", origin = CreatorRelationOrigin.AUTOMATIC)
    check(!creators.hasTitleAttribution(creator.id)) {
        "Source automatic title policy cannot upgrade the creator to cross-source title mode"
    }
    listOf(
        "ordinary title-attribution:v1:TITLE_CREATOR explanation",
        "title-attribution:v1:TITLE_CREATOR-extra",
        "title-attribution:v9:TITLE_CREATOR",
    ).forEach {
        put(it)
        check(!creators.hasTitleAttribution(creator.id)) {
            "Substring cannot turn arbitrary evidence into a title mode: $it"
        }
    }
    put("title-attribution:v1:TITLE_CREATOR", CreatorRelationVerification.POSSIBLE)
    check(!creators.hasTitleAttribution(creator.id))
    put("title-attribution:v1:TITLE_CREATOR")
    check(creators.hasTitleAttribution(creator.id))
    put("legacy evidence\ntitle-attribution:v1:TITLE_CIRCLE\nother evidence")
    check(creators.hasTitleAttribution(creator.id))
}

suspend fun verifyTitleCreatorDiscoveryAcrossSources(handler: DatabaseHandler) {
    val creators = CreatorRepositoryImpl(handler)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators)
    val original = mangas.insertNetworkManga(listOf(titleFixtureManga("Uploader").copy(favorite = false))).single()
    val mention = TitleCreditParser.parse(original.title).first()
    val id = (
        ManageCreatorIdentity(
            creators,
        ).resolve(original, mention) as tachiyomi.domain.creator.model.CreatorMentionResolution.Resolved
        ).creatorId
    creators.addManualCreatorAlias(id, "青岚")
    creators.upsertWatchPolicy(
        ArchiveWatchPolicy(id, false, 60_000, emptySet(), emptySet(), includeProbable = true, includeUnknown = true),
        1,
    )
    val sources = listOf(10L, 20L, 30L, 40L).map { sourceId ->
        val source = if (sourceId == 20L) mockk<CatalogueSource>() else mockk<AuthorSearchSource>()
        every { source.id } returns sourceId
        every { source.name } returns "Fixture $sourceId"
        every { source.lang } returns "en"
        every { source.getFilterList() } returns FilterList()
        if (source is AuthorSearchSource) {
            coEvery { source.getAuthorSearchManga(any(), any()) } returns
                AuthorSearchPage(emptyList(), false)
        }
        coEvery { source.getSearchManga(any(), any(), any()) } returns
            MangasPage(listOf(titleFixtureSourceManga("/work", "[Circle (Artist)] misleading card", "Artist")), false)
        coEvery { source.getMangaDetails(any()) } returns titleFixtureSourceManga(
            "/work",
            when (sourceId) {
                10L -> "[青岚] Work"
                20L -> "[Another circle (Artist)] Work"
                else -> "[Circle (Other)] Other artist"
            },
            "Uploader",
        )
        source
    }
    val adapter = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = { sources.filter { it.id != 40L } },
        sourceResolver = { id ->
            sources.firstOrNull {
                it.id ==
                    id
            }
        },
    )
    val titleDetailRequests = mutableListOf<SourceWorkNaturalKey>()
    val ordinaryDetailRequests = mutableListOf<SourceWorkNaturalKey>()
    val port = object : CreatorDiscoverySourcePort by adapter {
        override suspend fun loadDetails(key: SourceWorkNaturalKey): CreatorSourceDetailsResult {
            ordinaryDetailRequests += key
            return adapter.loadDetails(key)
        }

        override suspend fun loadDetails(key: SourceWorkNaturalKey, titleSearch: Boolean): CreatorSourceDetailsResult {
            check(titleSearch) { "Title discovery must propagate its detail context" }
            titleDetailRequests += key
            return adapter.loadDetails(key, titleSearch)
        }
    }
    val service = CreatorDiscoveryService(creators, creators, port)
    service.discoverCreator(id)
    val found = creators.getCreatorWorkArchive(id).pending.filter {
        it.naturalKey.sourceId in setOf(10L, 20L, 30L, 40L)
    }
    check(
        found.map { it.naturalKey }.toSet() ==
            setOf(SourceWorkNaturalKey(10, "/work"), SourceWorkNaturalKey(20, "/work")),
    ) {
        "Title root must use every enabled source and still reject another creator: ${found.map { it.naturalKey }}"
    }
    sources.filterIsInstance<AuthorSearchSource>().forEach {
        coVerify(exactly = 0) { it.getAuthorSearchManga(any(), any()) }
    }
    coVerify(exactly = 0) { sources.last().getSearchManga(any(), any(), any()) }
    check(
        titleDetailRequests.toSet() ==
            setOf(
                SourceWorkNaturalKey(10, "/work"),
                SourceWorkNaturalKey(20, "/work"),
                SourceWorkNaturalKey(30, "/work"),
            ),
    )
    check(ordinaryDetailRequests.isEmpty()) { "Title scans cannot read the ordinary search cache" }
    check(creators.getUnreadDiscoveries(100).isEmpty())
    check(mangas.getMangaById(original.id) == original)
}

suspend fun verifyTitleModeBaselineTransition(handler: DatabaseHandler, delayedOldScan: Boolean) = coroutineScope {
    val creators = CreatorRepositoryImpl(handler)
    creators.indexLibraryMangaBatch(emptyList())
    val mangas = MangaRepositoryImpl(handler, creators)
    val manga = mangas.insertNetworkManga(listOf(titleFixtureManga("Artist"))).single()
    val id = checkNotNull(creators.resolveCreatorIdByExactName("Artist"))
    creators.upsertWatchPolicy(
        ArchiveWatchPolicy(
            id, true, 60_000,
            setOf(
                10,
                20,
            ),
            emptySet(), includeProbable = true, includeUnknown = true, notifyProbable = true, notifyUnknown = true,
        ),
        1,
    )
    var now = 1_000L
    var metadataNew = false
    var showTitles = false
    var titleNew = false
    var holdOld = false
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val sources = listOf(10L, 20L).map { sourceId ->
        val source = mockk<CatalogueSource>()
        every { source.id } returns sourceId
        every { source.name } returns "Baseline source $sourceId"
        every { source.lang } returns "en"
        every { source.getFilterList() } returns FilterList()
        coEvery { source.getSearchManga(any(), any(), any()) } answers {
            val works = if (showTitles) {
                buildList {
                    add(
                        titleFixtureSourceManga(
                            "/historical",
                            if (sourceId ==
                                10L
                            ) {
                                "[Else (Artist)] Historical"
                            } else {
                                "[Artist] Historical"
                            },
                            "Uploader",
                        ),
                    )
                    if (titleNew &&
                        sourceId == 20L
                    ) {
                        add(titleFixtureSourceManga("/new-title", "[Artist] Later work", "Uploader"))
                    }
                }
            } else {
                buildList {
                    if (sourceId == 10L) {
                        add(titleFixtureSourceManga("/field-baseline", "Metadata baseline", "Artist"))
                        if (metadataNew) add(titleFixtureSourceManga("/field-new", "Metadata new work", "Artist"))
                    } else if (holdOld) {
                        add(titleFixtureSourceManga("/late-old", "Delayed metadata work", "Artist"))
                    }
                }
            }
            MangasPage(works, false)
        }
        coEvery { source.getMangaDetails(any()) } coAnswers {
            val listed = firstArg<SManga>()
            if (holdOld && sourceId == 20L && listed.url == "/late-old") {
                entered.complete(Unit)
                release.await()
            }
            listed.copy()
        }
        source
    }
    val adapter = CatalogueCreatorDiscoverySourceAdapter(
        enabledSourcesProvider = {
            sources
        },
        sourceResolver = { id -> sources.firstOrNull { it.id == id } },
        clock = { now },
    )
    val service = CreatorDiscoveryService(creators, creators, adapter, clock = { now })
    service.discoverCreator(id)
    check(
        creators.getWatchSourceBaselines(id).all {
            it.baselineState ==
                tachiyomi.domain.creator.model.WatchBaselineState.BASELINED
        },
    )
    metadataNew = true
    now += 86_400_000
    service.discoverCreator(id)
    val outboxBefore = creators.getPendingNotificationOutbox(now, 10)
    check(outboxBefore.size == 1) { "Fixture must retain one existing pending reminder" }
    val oldScan = if (delayedOldScan) {
        holdOld = true
        now += 86_400_000
        async { service.discoverCreator(id) }.also { entered.await() }
    } else {
        null
    }
    val mention = TitleCreditParser.parse(manga.title).first()
    ManageCreatorIdentity(creators).resolve(manga, mention)
    check(
        creators.getWatchSourceBaselines(id).all {
            it.baselineState ==
                tachiyomi.domain.creator.model.WatchBaselineState.NEEDS_BASELINE
        },
    ) {
        "First creator title mode must rebuild existing source baselines quietly"
    }
    if (oldScan != null) {
        release.complete(Unit)
        oldScan.await()
        check(
            creators.getWatchSourceBaselines(id).all {
                it.baselineState ==
                    tachiyomi.domain.creator.model.WatchBaselineState.NEEDS_BASELINE
            },
        ) {
            "Old metadata checkpoint cannot consume the replacement title baseline"
        }
        check(creators.getCreatorWorkArchive(id).pending.none { it.naturalKey.stableSourceUrl == "/late-old" })
    }
    showTitles = true
    now += 86_400_000
    service.discoverCreator(id)
    check(creators.getCreatorWorkArchive(id).pending.any { it.naturalKey == SourceWorkNaturalKey(20, "/historical") })
    check(creators.getPendingNotificationOutbox(now, 10) == outboxBefore) {
        "Historical title works cannot create new reminders or remove old ones"
    }
    val rebuilt = creators.getWatchSourceBaselines(id)
    check(rebuilt.all { it.baselineState == tachiyomi.domain.creator.model.WatchBaselineState.BASELINED })
    ManageCreatorIdentity(creators).resolve(manga, mention)
    check(creators.getWatchSourceBaselines(id) == rebuilt) { "Repeated title clicks cannot reset a completed baseline" }
    titleNew = true
    now += 86_400_000
    service.discoverCreator(id)
    check(creators.getPendingNotificationOutbox(now, 10).size == outboxBefore.size + 1)
    check(creators.getWatchPolicy(id)?.sourceIds == setOf(10L, 20L))
}

private fun titleFixtureManga(author: String) = Manga.create().copy(
    source = 99,
    url = "/clicked-title",
    title = "[Circle (Artist)] Work",
    author = author,
    favorite = true,
)

private fun titleFixtureSourceManga(url: String, title: String, author: String) = SManga.create().also {
    it.url = url
    it.title = title
    it.author = author
}
