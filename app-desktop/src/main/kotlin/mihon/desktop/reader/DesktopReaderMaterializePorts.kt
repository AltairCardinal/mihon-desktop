package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactLookup
import mihon.domain.reader.content.DownloadArtifactMatch
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.content.ReaderChapterContentResolver
import mihon.domain.reader.content.ReaderChapterRoute
import mihon.domain.reader.content.ReaderChapterRouteResolver
import mihon.domain.reader.content.ReaderSourceContentKind
import mihon.domain.reader.content.ReaderImageSortMode
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.partial.DisabledPartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialDownloadSnapshot
import mihon.domain.reader.partial.PartialDownloadSnapshotLookup
import mihon.domain.reader.partial.PartialReaderOnlinePage
import mihon.domain.reader.partial.PartialReaderPage
import mihon.domain.reader.partial.PartialReaderPageCandidate
import mihon.domain.reader.partial.PartialReaderPageListEvaluation
import mihon.domain.reader.partial.PartialReaderPageListPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderEncodedPageProvenance
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import tachiyomi.domain.source.service.SourceManager
import java.io.File

class DesktopReaderChapterContentPort(
    private val context: DesktopReaderChapterContext,
    private val downloadProvider: DesktopDownloadProvider,
    private val sourceManager: SourceManager,
    private val contentAdapter: DesktopReaderContentAdapter = DesktopReaderContentAdapter(),
    private val downloadArtifactLocator: DownloadArtifactLookup = downloadProvider.downloadArtifactLookup(context.sourceId),
    private val disallowNonAsciiFilenames: Boolean = context.disallowNonAsciiFilenames,
    private val leaseGeneration: Long = 0L,
    private val routeResolver: ReaderChapterRouteResolver = ReaderChapterContentResolver,
    private val partialDownloadSnapshotLookup: PartialDownloadSnapshotLookup = DisabledPartialDownloadSnapshotLookup,
) : ReaderChapterContentPort, DesktopReaderChapterDownloadState {

    override var chapterDownloaded: Boolean? = null
        private set

    override suspend fun loadChapterContent(request: ReaderChapterContentRequest): List<ReaderPageDescriptor> {
        require(request.chapterId.value == context.chapterId) { "Chapter context does not match the request" }
        val downloadIdentity = context.downloadIdentity(sourceManager, disallowNonAsciiFilenames)
        val downloaded = downloadIdentity
            .takeIf { context.mangaTitle.isNotBlank() }
            ?.let(downloadArtifactLocator::locate)
        val localPath = context.localChapterPath?.let(::File)
        val source = if (downloaded == null && localPath == null) findSource() else null
        val sourceKind = when {
            localPath?.isDirectory == true -> ReaderSourceContentKind.LOCAL_DIRECTORY
            localPath != null && localPath.extension.equals("epub", ignoreCase = true) ->
                ReaderSourceContentKind.LOCAL_EPUB
            localPath != null -> ReaderSourceContentKind.LOCAL_ARCHIVE
            source != null -> ReaderSourceContentKind.ONLINE
            else -> ReaderSourceContentKind.MISSING_SOURCE
        }

        val route = routeResolver.resolve(downloaded != null, sourceKind)
        chapterDownloaded = route == ReaderChapterRoute.DOWNLOAD ||
            route == ReaderChapterRoute.LOCAL_DIRECTORY ||
            route == ReaderChapterRoute.LOCAL_ARCHIVE ||
            route == ReaderChapterRoute.LOCAL_EPUB
        return when (route) {
            ReaderChapterRoute.DOWNLOAD -> downloadDescriptors(checkNotNull(downloaded))
            ReaderChapterRoute.LOCAL_DIRECTORY,
            ReaderChapterRoute.LOCAL_ARCHIVE,
            ReaderChapterRoute.LOCAL_EPUB,
            -> localDescriptors(checkNotNull(localPath))
            ReaderChapterRoute.ONLINE -> sourceDescriptors(checkNotNull(source), downloadIdentity)
            ReaderChapterRoute.MISSING_SOURCE -> throw missingSourceError()
            ReaderChapterRoute.UNSUPPORTED -> throw AppErrorException(
                AppError.MalformedData(IllegalStateException("Unsupported reader source (id=${context.sourceId})")),
            )
        }
    }

    private suspend fun sourceDescriptors(
        source: CatalogueSource,
        identity: DownloadChapterIdentity,
    ): List<ReaderPageDescriptor> {
        val snapshot = currentSnapshot(identity)
        val evaluation = PartialReaderPageListPolicy.evaluate(snapshot)
        if (snapshot != null && evaluation is PartialReaderPageListEvaluation.Ready) {
            val latestSnapshot = currentSnapshot(identity)
            if (PartialReaderPageListPolicy.isCurrent(snapshot, latestSnapshot)) {
                val currentEvaluation = PartialReaderPageListPolicy.evaluate(latestSnapshot)
                if (currentEvaluation is PartialReaderPageListEvaluation.Ready) {
                    return currentEvaluation.pageList.pages.map { page -> page.toDescriptor() }
                }
            }
        }

        val pages = loadSourcePages(source)
        val latestSnapshot = currentSnapshot(identity)
        return PartialReaderPageListPolicy.mergeOnline(
            originalSnapshot = snapshot ?: latestSnapshot,
            latestSnapshot = latestSnapshot,
            onlinePages = pages.mapIndexed { readerOrdinal, page ->
                PartialReaderOnlinePage(
                    readerOrdinal = readerOrdinal,
                    sourcePageIndex = page.index,
                    pageUrl = page.url,
                    imageUrl = page.imageUrl,
                )
            },
        ).pages.map { page -> page.toDescriptor() }
    }

    private suspend fun loadSourcePages(source: CatalogueSource): List<Page> {
        val chapter = SChapter.create().apply {
            url = context.chapterUrl
            name = context.chapterTitle
        }
        val pages = when (val result = safeSourceCall { source.getPageList(chapter) }) {
            is SourceCallResult.Success -> result.value
            is SourceCallResult.Timeout -> throw AppErrorException(result.error)
            is SourceCallResult.Error -> throw AppErrorException(result.error)
        }
        return pages
    }

    private fun PartialReaderPage.toDescriptor() = ReaderPageDescriptor(
        sourcePageIndex = sourcePageIndex,
        url = pageUrl,
        imageUrl = imageUrl,
        partialPageCandidate = committedCandidate,
        partialPageOrdinal = readerOrdinal,
    )

    private fun currentSnapshot(identity: DownloadChapterIdentity): PartialDownloadSnapshot? =
        partialDownloadSnapshotLookup.snapshot(context.chapterId, identity)
            ?.takeIf { snapshot -> snapshot.chapterId == context.chapterId && snapshot.identity == identity }

    private fun downloadDescriptors(match: DownloadArtifactMatch): List<ReaderPageDescriptor> {
        val artifact = File(match.opaqueLocation)
        return when (match.candidate.kind) {
            DownloadArtifactKind.DIRECTORY -> contentAdapter.directoryDescriptors(
                artifact,
                ReaderImageSortMode.DOWNLOAD_LEXICAL_CASE_SENSITIVE,
            )
            DownloadArtifactKind.CBZ -> contentAdapter.archiveDescriptors(
                chapterId = context.chapterId,
                archive = artifact,
                leaseGeneration = leaseGeneration,
            )
        }
    }

    private fun localDescriptors(path: File): List<ReaderPageDescriptor> {
        return if (path.isDirectory) {
            contentAdapter.directoryDescriptors(path, ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE)
        } else {
            contentAdapter.archiveDescriptors(
                chapterId = context.chapterId,
                archive = path,
                epub = path.extension.equals("epub", ignoreCase = true),
                leaseGeneration = leaseGeneration,
            )
        }
    }

    private fun findSource(): CatalogueSource? = sourceManager.getCatalogueSources().firstOrNull { it.id == context.sourceId }

    private fun missingSourceError() = AppErrorException(
        AppError.MalformedData(IllegalStateException("Source not found (id=${context.sourceId})")),
    )
}

class DesktopReaderPageFetchPort(
    private val context: DesktopReaderChapterContext,
    private val descriptor: ReaderPageDescriptor,
    private val sourceManager: SourceManager,
    private val networkHelper: NetworkHelper,
    private val encodedPageStore: DesktopReaderEncodedPageStore,
    private val contentAdapter: DesktopReaderContentAdapter = DesktopReaderContentAdapter(),
    partialDownloadSnapshotLookup: PartialDownloadSnapshotLookup = DisabledPartialDownloadSnapshotLookup,
    private val partialPageFallbackCoordinator: DesktopReaderPartialPageFallbackCoordinator =
        DesktopReaderPartialPageFallbackCoordinator(),
    partialPageCopyPort: DesktopReaderPartialPageCopyPort = DesktopReaderPartialPageFileCopyPort(),
) : ReaderPageFetchPort {

    private val partialPageInput = DesktopReaderPartialPageInput(
        chapterId = context.chapterId,
        identity = context.downloadIdentity(sourceManager, context.disallowNonAsciiFilenames),
        readerOrdinal = descriptor.partialPageOrdinal,
        sourcePageIndex = descriptor.sourcePageIndex,
        initialCandidate = descriptor.partialPageCandidate,
        lookup = partialDownloadSnapshotLookup,
        fallbackCoordinator = partialPageFallbackCoordinator,
        copyPort = partialPageCopyPort,
    )
    private val partialCandidatesByRef = mutableMapOf<EncodedPageRef, PartialReaderPageCandidate>()
    private val sourceImageUrlsByRef = mutableMapOf<EncodedPageRef, String>()

    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String {
        descriptor.encodedPageRef?.let { return it.value }
        if (contentAdapter.owns(descriptor)) return archiveImageIdentity(request)
        preferredPartialCandidate()?.let { return partialImageIdentity(it) }
        return sourceFetcher().resolveImageUrl(sourcePage(request))
    }

    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? {
        descriptor.encodedPageRef?.let { ref ->
            return ref.takeIf { encodedPageStore.contains(it) }
        }
        request.imageUrl
            ?.takeIf { imageUrl -> !isPartialImageIdentity(imageUrl) }
            ?.let { imageUrl ->
                val sourceRef = cacheRef(request, imageUrl)
                if (encodedPageStore.contains(sourceRef)) {
                    sourceImageUrlsByRef[sourceRef] = imageUrl
                    return sourceRef
                }
            }
        initialPartialCandidate()?.let { candidate ->
            findPartialCache(request, candidate)?.let { return it }
        }
        return preferredPartialCandidate()?.let { candidate -> findPartialCache(request, candidate) }
    }

    override suspend fun findEncodedPageForRefresh(request: ReaderPageFetchRequest): EncodedPageRef? {
        initialPartialCandidate()?.let { candidate ->
            findPartialCache(request, candidate)?.let { return it }
        }
        return preferredPartialCandidate()?.let { candidate -> findPartialCache(request, candidate) }
    }

    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
        if (!contentAdapter.owns(descriptor)) {
            preferredPartialCandidate()?.let { candidate ->
                try {
                    return storePartialPage(request, candidate)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: DesktopReaderPartialPageUnavailableException) {
                    // The downloader input raced, disappeared, or is staging-only; source fallback is bounded once.
                }
            }
            return fetchSourcePage(request)
        }

        val ref = cacheRef(request, request.imageUrl ?: descriptor.url)
        val result = try {
            encodedPageStore.store(ref) {
                extractArchivePage(request, ref)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            throw AppErrorException(AppError.Storage(error))
        }
        return result.requireStoredRef()
    }

    override fun materializedImageUrl(request: ReaderPageFetchRequest, ref: EncodedPageRef): String =
        sourceImageUrlsByRef[ref]
            ?: request.imageUrl?.takeUnless(::isPartialImageIdentity)
            ?: descriptor.imageUrl?.takeUnless(::isPartialImageIdentity)
            ?: partialCandidatesByRef[ref]?.let(::partialImageIdentity)
            ?: request.imageUrl
            ?: descriptor.url

    override fun encodedPageProvenance(
        request: ReaderPageFetchRequest,
        ref: EncodedPageRef,
    ): ReaderEncodedPageProvenance? = partialCandidatesByRef[ref]
        ?.let { candidate -> ReaderEncodedPageProvenance.Partial(candidate) }
        ?: descriptor.encodedPageProvenance.takeIf { descriptor.encodedPageRef == ref }

    private fun cacheRef(request: ReaderPageFetchRequest, discriminator: String): EncodedPageRef = encodedPageStore.cacheRef(
        request.pageId,
        discriminator,
    )

    private fun partialCacheRef(
        request: ReaderPageFetchRequest,
        candidate: PartialReaderPageCandidate,
    ): EncodedPageRef = cacheRef(
        request,
        "partial:${candidate.attemptGeneration}:${candidate.readerOrdinal}:" +
            "${candidate.sourcePageIndex}:${candidate.committedRevision}",
    )

    private fun archiveImageIdentity(request: ReaderPageFetchRequest): String =
        "archive:${context.chapterId}:${request.pageId.sourcePageIndex}:${descriptor.url}"

    private suspend fun extractArchivePage(request: ReaderPageFetchRequest, ref: EncodedPageRef): Long =
        contentAdapter.copyArchivePage(
            chapterId = context.chapterId,
            pageIndex = request.pageId.sourcePageIndex,
            opaquePageRef = descriptor.url,
            destination = encodedPageStore.destinationFile(ref),
        )

    private suspend fun storePartialPage(
        request: ReaderPageFetchRequest,
        candidate: PartialReaderPageCandidate,
    ): EncodedPageRef {
        val ref = partialCacheRef(request, candidate)
        val result = encodedPageStore.storeIfAbsent(ref) {
            partialPageInput.copy(candidate, encodedPageStore.destinationFile(ref))
        }
        return result.requireStoredRef().also { partialCandidatesByRef[it] = candidate }
    }

    private suspend fun fetchSourcePage(request: ReaderPageFetchRequest): EncodedPageRef {
        val imageUrl = sourceImageUrl(request)
        val resolvedRequest = request.copy(imageUrl = imageUrl)
        val ref = cacheRef(resolvedRequest, imageUrl)
        val writer: suspend () -> Long = {
            val page = sourcePage(resolvedRequest).apply { this.imageUrl = imageUrl }
            when (val result = sourceFetcher().fetchToDestination(page, encodedPageStore.destinationFile(ref))) {
                is SourcePageFetchResult.Success -> encodedPageStore.destinationFile(ref).length()
                is SourcePageFetchResult.Failure -> throw AppErrorException(result.error)
            }
        }
        val result = try {
            if (request.forceRefresh) {
                encodedPageStore.store(ref, writer)
            } else {
                encodedPageStore.storeIfAbsent(ref, writer)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            throw AppErrorException(AppError.Storage(error))
        }
        return result.requireStoredRef().also { sourceImageUrlsByRef[it] = imageUrl }
    }

    private suspend fun sourceImageUrl(request: ReaderPageFetchRequest): String = request.imageUrl
        ?.takeIf(String::isNotBlank)
        ?.takeUnless(::isPartialImageIdentity)
        ?: descriptor.imageUrl
            ?.takeIf(String::isNotBlank)
            ?.takeUnless(::isPartialImageIdentity)
        ?: sourceFetcher().resolveImageUrl(sourcePage(request).apply { imageUrl = null })

    private suspend fun findPartialCache(
        request: ReaderPageFetchRequest,
        candidate: mihon.domain.reader.partial.PartialReaderPageCandidate,
    ): EncodedPageRef? {
        val ref = partialCacheRef(request, candidate)
        if (!encodedPageStore.contains(ref)) return null
        partialCandidatesByRef[ref] = candidate
        return ref
    }

    private fun initialPartialCandidate() = partialPageInput.initialCandidate()

    private fun preferredPartialCandidate() = partialPageInput.preferredCandidate()

    private fun partialImageIdentity(candidate: PartialReaderPageCandidate): String =
        "partial:${context.chapterId}:${candidate.attemptGeneration}:" +
            "${candidate.readerOrdinal}:${candidate.committedRevision}"

    private fun isPartialImageIdentity(imageUrl: String): Boolean = imageUrl.startsWith("partial:")

    private fun EncodedPageStoreWriteResult.requireStoredRef(): EncodedPageRef = when (this) {
        is EncodedPageStoreWriteResult.Stored -> entry.ref
        is EncodedPageStoreWriteResult.RejectedQuota -> throw AppErrorException(
            AppError.Storage(
                IllegalStateException("Encoded page exceeds cache quota: ${entry.byteCount} > $maxBytes"),
            ),
        )
    }

    private fun sourcePage(request: ReaderPageFetchRequest) = Page(
        index = request.pageId.sourcePageIndex,
        url = request.url,
        imageUrl = request.imageUrl,
    )

    private fun sourceFetcher(): SourcePageFetcher {
        val source = sourceManager.getCatalogueSources().firstOrNull { it.id == context.sourceId }
            ?: throw AppErrorException(
                AppError.MalformedData(IllegalStateException("Source not found (id=${context.sourceId})")),
            )
        return SourcePageFetcher(source, networkHelper.clientForSource(context.sourceId))
    }
}

internal fun DesktopReaderChapterContext.downloadIdentity(
    sourceManager: SourceManager,
    disallowNonAsciiFilenames: Boolean,
) = DownloadChapterIdentity(
    sourceDisplayName = sourceManager.get(sourceId)?.toString() ?: sourceDisplayName,
    mangaTitle = mangaTitle,
    chapterName = chapterTitle,
    scanlator = scanlator,
    chapterUrl = chapterUrl,
    disallowNonAsciiFilenames = disallowNonAsciiFilenames,
)
