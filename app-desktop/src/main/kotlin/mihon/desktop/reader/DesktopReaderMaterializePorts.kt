package mihon.desktop.reader

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.desktop.source.LocalChapterEntry
import mihon.desktop.source.LocalPage
import mihon.desktop.source.LocalSourceReader
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.content.DownloadArtifactKind
import mihon.domain.reader.content.DownloadArtifactLocator
import mihon.domain.reader.content.DownloadArtifactMatch
import mihon.domain.reader.content.DownloadChapterIdentity
import mihon.domain.reader.content.ReaderChapterContentResolver
import mihon.domain.reader.content.ReaderChapterRoute
import mihon.domain.reader.content.ReaderChapterRouteResolver
import mihon.domain.reader.content.ReaderSourceContentKind
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import tachiyomi.domain.source.service.SourceManager
import java.io.File

class DesktopReaderChapterContentPort(
    private val context: DesktopReaderChapterContext,
    private val downloadProvider: DesktopDownloadProvider,
    private val sourceManager: SourceManager,
    private val downloadArtifactLocator: DownloadArtifactLocator = DownloadArtifactLocator(
        downloadProvider.currentDirectoryArtifactProbe(context.sourceId),
    ),
    private val routeResolver: ReaderChapterRouteResolver = ReaderChapterContentResolver,
) : ReaderChapterContentPort {

    override suspend fun loadChapterContent(request: ReaderChapterContentRequest): List<ReaderPageDescriptor> {
        require(request.chapterId.value == context.chapterId) { "Chapter context does not match the request" }
        val downloaded = context.downloadIdentity()
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

        return when (routeResolver.resolve(downloaded != null, sourceKind)) {
            ReaderChapterRoute.DOWNLOAD -> downloadDescriptors(checkNotNull(downloaded))
            ReaderChapterRoute.LOCAL_DIRECTORY,
            ReaderChapterRoute.LOCAL_ARCHIVE,
            ReaderChapterRoute.LOCAL_EPUB,
            -> localDescriptors(checkNotNull(localPath))
            ReaderChapterRoute.ONLINE -> sourceDescriptors(checkNotNull(source))
            ReaderChapterRoute.MISSING_SOURCE -> throw missingSourceError()
            ReaderChapterRoute.UNSUPPORTED -> throw AppErrorException(
                AppError.MalformedData(IllegalStateException("Unsupported reader source (id=${context.sourceId})")),
            )
        }
    }

    private suspend fun sourceDescriptors(source: CatalogueSource): List<ReaderPageDescriptor> {
        val chapter = SChapter.create().apply {
            url = context.chapterUrl
            name = context.chapterTitle
        }
        val pages = when (val result = safeSourceCall { source.getPageList(chapter) }) {
            is SourceCallResult.Success -> result.value
            is SourceCallResult.Timeout -> throw AppErrorException(result.error)
            is SourceCallResult.Error -> throw AppErrorException(result.error)
        }
        return pages.mapIndexed { index, page ->
            ReaderPageDescriptor(
                sourcePageIndex = index,
                url = page.url,
                imageUrl = page.imageUrl,
            )
        }
    }

    private fun DesktopReaderChapterContext.downloadIdentity() = DownloadChapterIdentity(
        sourceDisplayName = sourceDisplayName,
        mangaTitle = mangaTitle,
        chapterName = chapterTitle,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        disallowNonAsciiFilenames = disallowNonAsciiFilenames,
    )

    private fun downloadDescriptors(match: DownloadArtifactMatch): List<ReaderPageDescriptor> {
        val artifact = File(match.opaqueLocation)
        return when (match.candidate.kind) {
            DownloadArtifactKind.DIRECTORY -> downloadProvider.getDownloadedPages(artifact).mapIndexed(::readyFileDescriptor)
            DownloadArtifactKind.CBZ -> localDescriptors(artifact)
        }
    }

    private fun localDescriptors(path: File): List<ReaderPageDescriptor> {
        val chapter = LocalChapterEntry(context.chapterTitle, path)
        val pages = LocalSourceReader.readChapter(chapter)
        return if (path.isDirectory) {
            pages.mapIndexed { index, page ->
                readyFileDescriptor(index, requireNotNull(page.file) { "Local page has no file: ${page.name}" })
            }
        } else {
            pages.mapIndexed { index, page ->
                ReaderPageDescriptor(
                    sourcePageIndex = index,
                    url = requireNotNull(page.archiveEntry) { "Archive page has no entry: ${page.name}" },
                )
            }
        }
    }

    private fun readyFileDescriptor(index: Int, file: File) = ReaderPageDescriptor(
        sourcePageIndex = index,
        url = file.name,
        imageUrl = file.toURI().toString(),
        encodedPageRef = EncodedPageRef(file.toURI().toString()),
        initialLoadState = ReaderPageLoadState.Ready,
    )

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
) : ReaderPageFetchPort {

    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest): String {
        descriptor.encodedPageRef?.let { return it.value }
        if (context.localChapterPath != null) return archiveImageIdentity(request)
        return sourceFetcher().resolveImageUrl(sourcePage(request))
    }

    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? {
        descriptor.encodedPageRef?.let { ref ->
            return ref.takeIf { encodedPageStore.contains(it) }
        }
        val ref = cacheRef(request)
        return ref.takeIf { encodedPageStore.contains(it) }
    }

    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
        val ref = cacheRef(request)
        val result = try {
            encodedPageStore.store(ref) {
                if (context.localChapterPath != null) {
                    extractArchivePage(request, ref)
                } else {
                    fetchSourcePage(request, ref)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppErrorException) {
            throw error
        } catch (error: Throwable) {
            throw AppErrorException(AppError.Storage(error))
        }
        return when (result) {
            is EncodedPageStoreWriteResult.Stored -> result.entry.ref
            is EncodedPageStoreWriteResult.RejectedQuota -> throw AppErrorException(
                AppError.Storage(
                    IllegalStateException(
                        "Encoded page exceeds cache quota: ${result.entry.byteCount} > ${result.maxBytes}",
                    ),
                ),
            )
        }
    }

    private fun cacheRef(request: ReaderPageFetchRequest): EncodedPageRef = encodedPageStore.cacheRef(
        request.pageId,
        request.imageUrl ?: descriptor.url,
    )

    private fun archiveImageIdentity(request: ReaderPageFetchRequest): String =
        "archive:${context.chapterId}:${request.pageId.sourcePageIndex}:${descriptor.url}"

    private fun extractArchivePage(request: ReaderPageFetchRequest, ref: EncodedPageRef): Long {
        val chapterFile = File(requireNotNull(context.localChapterPath))
        val page = LocalPage(name = File(descriptor.url).name, archiveEntry = descriptor.url)
        return LocalSourceReader.extractPage(
            chapter = LocalChapterEntry(context.chapterTitle, chapterFile),
            page = page,
            destination = encodedPageStore.destinationFile(ref),
        )
    }

    private suspend fun fetchSourcePage(request: ReaderPageFetchRequest, ref: EncodedPageRef): Long {
        val page = sourcePage(request).apply { imageUrl = request.imageUrl }
        return when (val result = sourceFetcher().fetchToDestination(page, encodedPageStore.destinationFile(ref))) {
            is SourcePageFetchResult.Success -> encodedPageStore.destinationFile(ref).length()
            is SourcePageFetchResult.Failure -> throw AppErrorException(result.error)
        }
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
