package mihon.desktop.library

import mihon.desktop.domain.DesktopCoverUpdater
import mihon.desktop.domain.DesktopCustomCoverStore
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.download.DesktopDownloadIdentityResolver
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.ui.library.DesktopCoverFilePicker
import mihon.desktop.ui.library.MangaCoverAdapter
import mihon.desktop.ui.library.MangaDetailScreenModel
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.creator.interactor.LinkMangaCreator
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MangaDetailScreenModelFactory {
    fun create(mangaId: Long): MangaDetailScreenModel = create(mangaId, DesktopCoverFilePicker())

    fun create(mangaId: Long, coverFilePicker: mihon.desktop.ui.library.CoverFilePicker): MangaDetailScreenModel {
        val downloadManager = runCatching { Injekt.get<DesktopDownloadManager>() }.getOrNull()
        val downloadIdentityResolver = runCatching { Injekt.get<DesktopDownloadIdentityResolver>() }.getOrNull()
        val downloadProvider = runCatching { Injekt.get<mihon.desktop.download.DesktopDownloadProvider>() }.getOrNull()
        val coverStore = Injekt.get<DesktopCustomCoverStore>()
        val coverUpdater = DesktopCoverUpdater(coverStore, Injekt.get())
        return MangaDetailScreenModel(
            readingProgress = Injekt.get<tachiyomi.domain.reader.interactor.RecordReadingProgress>(),
            readerPreferences = Injekt.get<mihon.desktop.reader.ReaderPreferences>(),
            mangaId = mangaId,
            migrateManga = { targetSourceId, item ->
                val source = Injekt.get<tachiyomi.domain.manga.interactor.GetManga>().await(mangaId)
                    ?: error("Source manga no longer exists")
                require(source.source != targetSourceId || source.url != item.url) {
                    "Cannot migrate onto the same manga"
                }
                val targetSource =
                    Injekt.get<SourceManager>().get(targetSourceId) as? eu.kanade.tachiyomi.source.CatalogueSource
                        ?: error("Target source is not installed")
                val useCase = Injekt.get<mihon.desktop.domain.DesktopMigrateMangaUseCase>()
                val options = mihon.desktop.domain.MigrationOptions(copyNotes = source.notes.isNotBlank())
                val accepted = useCase.accept(source, options, true)
                useCase.await(source, item, targetSourceId, targetSource.getChapterList(item), options, true, accepted)
            },
            manualTracking = mihon.desktop.tracking.DesktopManualTracking.fromInjekt(),
            getMangaWithChapters = Injekt.get<GetMangaWithChapters>(),
            sourceManager = Injekt.get<SourceManager>(),
            updateChecker = Injekt.get<LibraryUpdateChecker>(),
            getAvailableScanlators = Injekt.get<GetAvailableScanlators>(),
            getExcludedScanlators = Injekt.get<GetExcludedScanlators>(),
            setExcludedScanlators = Injekt.get<SetExcludedScanlators>(),
            getCategories = Injekt.get<GetCategories>(),
            libraryPreferences = Injekt.get<LibraryPreferences>(),
            updateChapter = Injekt.get<UpdateChapter>(),
            setChapterReadStatus = Injekt.get<SetChapterReadStatus>(),
            updateManga = Injekt.get<UpdateManga>(),
            setMangaChapterFlags = Injekt.get<SetMangaChapterFlags>(),
            setMangaDefaultChapterFlags = Injekt.get<tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags>(),
            setMangaCategories = Injekt.get<SetMangaCategories>(),
            linkMangaCreator = Injekt.get<LinkMangaCreator>(),
            manageCreatorIdentity = ManageCreatorIdentity(Injekt.get<CreatorArchiveRepository>()),
            enqueueAccepted = downloadManager?.let { manager -> manager::enqueue },
            startDownloadNow = downloadManager?.let { manager -> manager::startDownloadNow },
            downloadQueue = downloadManager?.queue,
            downloadAvailability = downloadManager?.availabilityRevision,
            isDownloaded = downloadManager?.let { manager ->
                { manga, chapter ->
                    val identity = requireNotNull(downloadIdentityResolver) { "Download identity resolver is required" }
                        .resolve(manga, chapter)
                    manager.isDownloaded(manga.source, identity)
                }
            },
            cancelAccepted = downloadManager?.let { manager ->
                { chapterId -> manager.cancel(chapterId) }
            },
            retryAccepted = downloadManager?.let { manager ->
                { chapterId -> manager.retryItem(chapterId) }
            },
            updateLibraryMembership = Injekt.get<UpdateLibraryMembership>(),
            coverAdapter = MangaCoverAdapter(coverFilePicker, coverUpdater::invoke),
            deleteCover = coverUpdater::delete,
            resolveCoverModel = coverStore::resolveModel,
            getDuplicateLibraryManga = Injekt.get<tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga>(),
            hasCustomCover = coverStore::customCoverExists,
            captureDownloadDeletion = if (downloadManager != null && downloadProvider != null &&
                downloadIdentityResolver != null
            ) {
                { manga, chapters ->
                    val targets = chapters.distinctBy { it.id }
                    val currentAliases = targets.associate { chapter ->
                        chapter.id to
                            downloadProvider.chapterDownloadArtifacts(
                                manga.source,
                                downloadIdentityResolver.resolve(manga, chapter),
                            ).toSet()
                    }
                    val originalAttempts = downloadManager.captureDownloadAttempts(
                        targets.map {
                            it.id
                        },
                    ).map { target ->
                        target.copy(
                            item = target.item.copy(
                                downloadIdentity = target.item.downloadIdentity
                                    ?: downloadIdentityResolver.resolve(
                                        manga.copy(source = target.item.sourceId, title = target.item.mangaTitle),
                                        targets.first { it.id == target.item.chapterId },
                                    ),
                            ),
                        )
                    }
                    val queuedAliases = originalAttempts.associate { target ->
                        target.item.chapterId to
                            downloadProvider.chapterDownloadArtifacts(
                                manga.source,
                                requireNotNull(target.item.downloadIdentity),
                            ).toSet()
                    }
                    val aliases = currentAliases.mapValues { (id, paths) -> paths + queuedAliases[id].orEmpty() }
                    val artifacts = aliases.values.flatten().filterTo(mutableSetOf()) { it.exists() }
                    val applicable = targets.filter { chapter ->
                        aliases.getValue(chapter.id).any { it in artifacts } ||
                            originalAttempts.any { it.item.chapterId == chapter.id }
                    }.mapTo(mutableSetOf()) { it.id }
                    val files = mihon.desktop.download.CapturedDownloadFiles(
                        artifacts,
                        originalAttempts.toMutableList(),
                        queuedAliases,
                    )
                    val pending = targets.mapTo(mutableSetOf()) { it.id }
                    val execute: suspend () -> tachiyomi.domain.chapter.interactor.BatchChapterResult = {
                        val result = downloadManager.deleteCapturedDownloadFiles(files)
                        val failed = pending.filter { id ->
                            id in result.refusedAttempts ||
                                aliases.getValue(id).any { it in result.failedArtifacts }
                        }
                        val completed = pending - failed.toSet()
                        pending.removeAll(completed)
                        files.pendingArtifacts.retainAll(result.failedArtifacts.toSet())
                        tachiyomi.domain.chapter.interactor.BatchChapterResult(
                            completed.filter { it in applicable },
                            failed.map {
                                tachiyomi.domain.chapter.interactor.BatchChapterFailure(
                                    it,
                                    "Unable to delete captured download",
                                )
                            },
                            completed.filter { it !in applicable },
                        )
                    }
                    execute
                }
            } else {
                null
            },
            captureMangaDownloadDeletion = if (downloadManager != null && downloadProvider != null &&
                downloadIdentityResolver != null
            ) {
                { manga ->
                    val originalAttempts = downloadManager.captureDownloadAttempts(
                        downloadManager.queue.value.map {
                            it.chapterId
                        },
                    )
                    val artifacts = downloadProvider.captureMangaDownloadArtifacts(
                        manga.source,
                        manga.title,
                        downloadIdentityResolver.resolve(manga),
                    ).toMutableSet()
                    val chapters = Injekt.get<GetMangaWithChapters>().awaitChapters(
                        manga.id,
                        applyScanlatorFilter = false,
                    )
                    val chapterIds = chapters.mapTo(mutableSetOf()) { it.id }
                    val owned = originalAttempts.filter {
                        it.item.mangaId == manga.id || it.item.chapterId in chapterIds
                    }.map { target ->
                        target.copy(
                            item = target.item.copy(
                                downloadIdentity = target.item.downloadIdentity
                                    ?: downloadIdentityResolver.resolve(target.item),
                            ),
                        )
                    }
                    val queuedAliases = owned.associate { target ->
                        target.item.chapterId to
                            downloadProvider.chapterDownloadArtifacts(
                                manga.source,
                                requireNotNull(target.item.downloadIdentity),
                            ).toSet()
                    }
                    val files = mihon.desktop.download.CapturedDownloadFiles(
                        artifacts,
                        owned.toMutableList(),
                        queuedAliases,
                    )
                    val execute: suspend () -> Boolean = {
                        val result = downloadManager.deleteCapturedDownloadFiles(files)
                        files.pendingArtifacts.retainAll(result.failedArtifacts.toSet())
                        result.failedArtifacts.isEmpty() && result.refusedAttempts.isEmpty()
                    }
                    execute
                }
            } else {
                null
            },

        )
    }
}
