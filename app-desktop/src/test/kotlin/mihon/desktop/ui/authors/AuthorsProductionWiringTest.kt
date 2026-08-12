package mihon.desktop.ui.authors

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.CreatorDiscoveryScheduler
import mihon.desktop.domain.ListedMangaForDetails
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.GetCreatorDetails
import tachiyomi.domain.creator.interactor.GetCreators
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.interactor.SetCreatorFollow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.CreatorLibraryMangaSource
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.creator.service.CreatorDiscoveryResult
import tachiyomi.domain.creator.service.CreatorLibraryIndexState
import tachiyomi.domain.creator.service.CreatorLibraryIndexer
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
class AuthorsProductionWiringTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `mounted work comparison confirms a suggested source version through production repositories`() = runBlocking {
        val creator = Creator(7L, "Jane Doe", "jane doe", null, emptyList(), 1L, 1L)
        val candidate = DiscoveryCandidate(
            id = 30L,
            source = 10L,
            url = "/candidate",
            title = "Shared work",
            normalizedTitle = "shared work",
            authorText = "Jane Doe",
            artistText = null,
            languageTag = "en",
            languageConfidence = 1.0,
            languageEvidence = "structured source language",
            thumbnailUrl = null,
            firstSeenAt = 1L,
            lastSeenAt = 2L,
            detailsFetchedAt = 2L,
            state = DiscoveryCandidateState.NEW,
        )
        val creatorRepository = mockk<CreatorRepository> {
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns listOf(candidate)
            coEvery { getMangaCreatorsForCreator(7L) } returns listOf(
                MangaCreator(11L, 7L, CreatorRole.AUTHOR, "Jane Doe", 1.0, "library index"),
            )
            coEvery { getMangaTitlesForCreator(7L) } returns mapOf(11L to "Shared work")
            coEvery { getDiscoveryCandidate(30L) } returns candidate
            coEvery { createCanonicalWork("Shared work", 7L, null) } returns
                CanonicalWork(90L, "Shared work", "shared work", 7L, null, 1L, 1L)
            coEvery { upsertMangaWorkMatch(any(), any(), any(), any(), any(), any()) } returns mockk()
        }
        val archiveRepository = mockk<CreatorArchiveRepository>(relaxed = true) {
            coEvery { getCreatorWorkArchive(7L) } returns comparisonArchive()
        }
        val saved = Manga.create().copy(id = 12L, source = 10L, url = "/candidate", title = "Shared work")
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { this@mockk.creatorRepository } returns creatorRepository
            every { creatorArchiveRepository } returns archiveRepository
            every { saveSourceMangaForDetails } returns mockk {
                coEvery { awaitListedForDetails(any(), 10L) } returns ListedMangaForDetails(saved, false)
            }
            every { sourceManager } returns mockk(relaxed = true)
        }
        val scene = ImageComposeScene(1100, 800, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(WorkCompareScreen(workId = 30L, creatorId = 7L))
                }
            }
            val action = MR.strings.desktop_ui_confirm_same_work.localized()
            withTimeout(5_000) {
                while (action !in texts(scene)) scene.render()
            }
            clickableTextNode(scene, action).config[SemanticsActions.OnClick].action?.invoke()

            coVerifyOrder {
                archiveRepository.appendUserWorkDecisionIfCurrent(
                    tachiyomi.domain.creator.model.SourceWorkNaturalKey(10L, "/pending"),
                    80L,
                    tachiyomi.domain.creator.model.WorkDecisionState.CONFIRMED,
                    null,
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
            coVerify(exactly = 0) { creatorRepository.createCanonicalWork(any(), any(), any()) }
        } finally {
            scene.close()
        }
    }


    @Test
    fun `mounted author detail confirms manual alias removal through typed repository`() = runBlocking {
        val creator = Creator(
            id = 7L,
            displayName = "Jane Doe",
            normalizedName = "jane doe",
            sortName = null,
            aliases = listOf("J. Doe"),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val creatorRepository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(listOf(creator))
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7L) } returns emptyList()
        }
        val archiveRepository = mockk<CreatorArchiveRepository> {
            coEvery { getManualCreatorAliases(7L) } returnsMany listOf(
                listOf("J. Doe"),
                emptyList(),
            )
            coEvery { removeManualCreatorAlias(7L, "J. Doe") } returns Unit
            every { observeSourceCheckpoints(7L) } returns flowOf(emptyList())
            every { observeCreatorWorkArchive(7L) } returns flowOf(pendingArchive("Pending grouped work"))
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(creatorRepository)
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { setCreatorFollow } returns SetCreatorFollow(creatorRepository)
            every { discoverCreatorWorks } returns mockk()
            every { sourceManager } returns mockk(relaxed = true)
            every { saveSourceMangaForDetails } returns mockk()
            every { creatorArchiveRepository } returns archiveRepository
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
            every { creatorDiscoveryScheduler } returns null
        }
        val removeLabel = MR.strings.desktop_ui_remove_named_author_alias.localized(Locale.getDefault(), "J. Doe")
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creatorId = 7L))
                }
            }
            withTimeout(5_000) {
                while (removeLabel !in texts(scene)) {
                    scene.render()
                }
            }
            assertTrue("Pending grouped work" in texts(scene))

            clickableTextNode(scene, removeLabel).config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
            assertTrue(
                MR.strings.desktop_ui_remove_author_alias_summary.localized(Locale.getDefault(), "J. Doe") in
                    texts(scene),
            )

            clickableTextNode(scene, MR.strings.action_remove.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            coVerify(timeout = 5_000, exactly = 1) {
                archiveRepository.removeManualCreatorAlias(7L, "J. Doe")
            }
            withTimeout(5_000) {
                while (removeLabel in texts(scene)) {
                    scene.render()
                }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `mounted authors root renders production failure and retry reaches empty library`() = runBlocking {
        val source = FailsOnceLibrarySource()
        val indexer = CreatorLibraryIndexer(
            mangaSource = source,
            indexWriter = NoopCreatorLibraryIndexWriter,
            extractCreators = ExtractCreatorsFromManga(),
        )
        val repository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(emptyList())
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
        }
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(repository)
            every { creatorLibraryIndexer } returns indexer
        }
        indexer.start(this)
        withTimeout(5_000) { indexer.state.filterIsInstance<CreatorLibraryIndexState.Failed>().first() }

        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorsRootScreen())
                }
            }
            scene.render()
            assertTrue(
                MR.strings.desktop_ui_author_index_failed.localized(Locale.getDefault(), "disk full") in texts(scene),
            )

            retryNode(scene).config[SemanticsActions.OnClick].action?.invoke()
            withTimeout(5_000) { indexer.state.filterIsInstance<CreatorLibraryIndexState.Empty>().first() }
            scene.render()

            assertTrue(MR.strings.desktop_ui_author_index_empty_library.localized() in texts(scene))
            assertTrue(source.countAttempts == 2)
        } finally {
            scene.close()
            indexer.stop()
        }
    }

    @Test
    fun `mounted author detail manual check runs through the discovery scheduler and can be cancelled`() = runBlocking {
        val creator = Creator(
            id = 7L,
            displayName = "Jane Doe",
            normalizedName = "jane doe",
            sortName = null,
            aliases = emptyList(),
            createdAt = 1L,
            lastModifiedAt = 1L,
        )
        val creatorRepository = mockk<CreatorRepository> {
            every { getCreatorsAsFlow() } returns flowOf(listOf(creator))
            every { getFollowedCreatorsAsFlow() } returns flowOf(emptyList())
            coEvery { getCreator(7L) } returns creator
            coEvery { getDiscoveryCandidatesForCreator(7L) } returns emptyList()
            coEvery { getMangaCreatorsForCreator(7L) } returns emptyList()
        }
        val archiveRepository = mockk<CreatorArchiveRepository> {
            coEvery { getManualCreatorAliases(7L) } returns emptyList()
            every { observeSourceCheckpoints(7L) } returns flowOf(emptyList())
            every { observeCreatorWorkArchive(7L) } returns flowOf(CreatorWorkArchive(emptyList(), emptyList(), emptyList()))
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = CreatorDiscoveryScheduler(
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json"))),
            discoverDue = { CreatorDiscoveryResult(0, 0, emptyList()) },
            discoverCreator = {
                entered.complete(Unit)
                release.await()
                CreatorDiscoveryResult(0, 0, emptyList())
            },
        )
        val dependencies = mockk<DesktopUiDependencies> {
            every { getCreators } returns GetCreators(creatorRepository)
            every { getCreatorDetails } returns GetCreatorDetails(creatorRepository)
            every { setCreatorFollow } returns SetCreatorFollow(creatorRepository)
            every { discoverCreatorWorks } returns mockk()
            every { sourceManager } returns mockk()
            every { saveSourceMangaForDetails } returns mockk()
            every { creatorArchiveRepository } returns archiveRepository
            every { manageCreatorIdentity } returns ManageCreatorIdentity(archiveRepository)
            every { creatorDiscoveryScheduler } returns scheduler
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AuthorDetailScreen(creatorId = 7L))
                }
            }
            withTimeout(5_000) {
                while (MR.strings.desktop_ui_check_new_works.localized() !in contentDescriptions(scene)) {
                    scene.render()
                }
            }

            clickableNodeByDescription(scene, MR.strings.desktop_ui_check_new_works.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            entered.await()

            val running = MR.strings.desktop_ui_author_check_running.localized(Locale.getDefault(), "Jane Doe")
            withTimeout(5_000) {
                while (running !in texts(scene)) scene.render()
            }
            assertTrue(running in texts(scene))

            clickableTextNode(scene, MR.strings.desktop_ui_author_discovery_cancel.localized())
                .config[SemanticsActions.OnClick].action?.invoke()
            release.complete(Unit)
            withTimeout(5_000) {
                while (MR.strings.desktop_ui_author_discovery_cancelled.localized() !in texts(scene)) scene.render()
            }
            assertTrue(MR.strings.desktop_ui_author_discovery_cancelled.localized() in texts(scene))
        } finally {
            scene.close()
            scheduler.stop()
        }
    }

    private fun retryNode(scene: ImageComposeScene): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == MR.strings.action_retry.localized() }
    }

    private fun pendingArchive(title: String): CreatorWorkArchive = CreatorWorkArchive(
        works = emptyList(),
        pending = listOf(
            tachiyomi.domain.creator.model.SourceWorkArchiveVersion(
                sourceWorkId = 30L,
                naturalKey = tachiyomi.domain.creator.model.SourceWorkNaturalKey(10L, "/pending"),
                mangaId = null,
                title = title,
                readingLanguage = tachiyomi.domain.creator.model.LanguageProjectionContract(
                    dimension = tachiyomi.domain.creator.model.LanguageDimension.READING,
                    tag = "und",
                    certainty = tachiyomi.domain.creator.model.LanguageCertainty.UNKNOWN,
                    evidenceKind = tachiyomi.domain.creator.model.LanguageEvidenceKind.UNKNOWN,
                ),
                chapterCount = 0L,
                inLibrary = false,
                detailsFetchedAt = null,
                lastSeenAt = 1L,
                decision = null,
            ),
        ),
        rejected = emptyList(),
    )

    private fun comparisonArchive(): CreatorWorkArchive {
        val candidate = pendingArchive("Shared work").pending.single()
        val libraryVersion = candidate.copy(
            sourceWorkId = 31L,
            naturalKey = tachiyomi.domain.creator.model.SourceWorkNaturalKey(11L, "/library-version"),
            mangaId = 11L,
            inLibrary = true,
        )
        return CreatorWorkArchive(
            works = listOf(
                tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup(
                    workId = 80L,
                    portableKey = "existing-work",
                    title = "Shared work",
                    versions = listOf(libraryVersion),
                ),
            ),
            pending = listOf(candidate),
            rejected = emptyList(),
        )
    }

    private fun clickableTextNode(scene: ImageComposeScene, text: String): SemanticsNode = nodes(scene).single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
            node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { it.text == text }
    }

    private fun clickableNodeByDescription(scene: ImageComposeScene, description: String): SemanticsNode =
        nodes(scene).single { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config.contains(SemanticsProperties.ContentDescription) &&
                node.config[SemanticsProperties.ContentDescription].any { it == description }
        }

    private fun contentDescriptions(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.ContentDescription)) {
            node.config[SemanticsProperties.ContentDescription]
        } else {
            emptyList()
        }
    }

    private fun texts(scene: ImageComposeScene): List<String> = nodes(scene).flatMap { node ->
        if (node.config.contains(SemanticsProperties.Text)) {
            node.config[SemanticsProperties.Text].map { it.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        flatten(owner.rootSemanticsNode)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class FailsOnceLibrarySource : CreatorLibraryMangaSource {
        var countAttempts = 0
            private set

        override suspend fun countLibraryMangaForCreatorIndex(): Long {
            countAttempts += 1
            check(countAttempts > 1) { "disk full" }
            return 0L
        }

        override suspend fun getLibraryMangaForCreatorIndex(afterId: Long, limit: Long): List<Manga> = emptyList()
    }
}
