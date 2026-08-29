package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.domain.reader.content.ReaderChapterContentResolver
import mihon.domain.reader.content.ReaderChapterRoute
import mihon.domain.reader.content.ReaderChapterRouteResolver
import mihon.domain.reader.content.ReaderSourceContentKind
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

internal sealed interface ReaderChapterContentRoute {
    data object Download : ReaderChapterContentRoute
    data class LocalDirectory(val file: UniFile) : ReaderChapterContentRoute
    data class LocalArchive(val file: UniFile) : ReaderChapterContentRoute
    data class LocalEpub(val file: UniFile) : ReaderChapterContentRoute
    data object Online : ReaderChapterContentRoute
    data object MissingSource : ReaderChapterContentRoute
    data object Unsupported : ReaderChapterContentRoute
}

class ReaderPageLoaderFactories internal constructor(
    internal val download: (ReaderChapter) -> PageLoader,
    internal val localDirectory: (UniFile) -> PageLoader,
    internal val localArchive: (UniFile) -> PageLoader,
    internal val localEpub: (UniFile) -> PageLoader,
    internal val online: (ReaderChapter, HttpSource) -> PageLoader,
    internal val missingSource: (Source) -> PageLoader,
    internal val unsupported: (Source) -> PageLoader,
)

internal fun selectReaderChapterContentRoute(
    downloaded: Boolean,
    source: Source,
    routeResolver: ReaderChapterRouteResolver = ReaderChapterContentResolver,
    localFormat: (() -> Format)? = null,
): ReaderChapterContentRoute {
    val resolvedLocalFormat = if (source is LocalSource && !downloaded) {
        checkNotNull(localFormat).invoke()
    } else {
        null
    }
    val sourceKind = when {
        resolvedLocalFormat is Format.Directory -> ReaderSourceContentKind.LOCAL_DIRECTORY
        resolvedLocalFormat is Format.Archive -> ReaderSourceContentKind.LOCAL_ARCHIVE
        resolvedLocalFormat is Format.Epub -> ReaderSourceContentKind.LOCAL_EPUB
        source is HttpSource -> ReaderSourceContentKind.ONLINE
        source is StubSource -> ReaderSourceContentKind.MISSING_SOURCE
        else -> ReaderSourceContentKind.UNSUPPORTED
    }
    return when (routeResolver.resolve(downloaded, sourceKind)) {
        ReaderChapterRoute.DOWNLOAD -> ReaderChapterContentRoute.Download
        ReaderChapterRoute.LOCAL_DIRECTORY -> ReaderChapterContentRoute.LocalDirectory(
            (resolvedLocalFormat as Format.Directory).file,
        )
        ReaderChapterRoute.LOCAL_ARCHIVE -> ReaderChapterContentRoute.LocalArchive(
            (resolvedLocalFormat as Format.Archive).file,
        )
        ReaderChapterRoute.LOCAL_EPUB -> ReaderChapterContentRoute.LocalEpub(
            (resolvedLocalFormat as Format.Epub).file,
        )
        ReaderChapterRoute.ONLINE -> ReaderChapterContentRoute.Online
        ReaderChapterRoute.MISSING_SOURCE -> ReaderChapterContentRoute.MissingSource
        ReaderChapterRoute.UNSUPPORTED -> ReaderChapterContentRoute.Unsupported
    }
}

internal val ReaderChapterContentRoute.sharedRoute: ReaderChapterRoute
    get() = when (this) {
        ReaderChapterContentRoute.Download -> ReaderChapterRoute.DOWNLOAD
        is ReaderChapterContentRoute.LocalDirectory -> ReaderChapterRoute.LOCAL_DIRECTORY
        is ReaderChapterContentRoute.LocalArchive -> ReaderChapterRoute.LOCAL_ARCHIVE
        is ReaderChapterContentRoute.LocalEpub -> ReaderChapterRoute.LOCAL_EPUB
        ReaderChapterContentRoute.Online -> ReaderChapterRoute.ONLINE
        ReaderChapterContentRoute.MissingSource -> ReaderChapterRoute.MISSING_SOURCE
        ReaderChapterContentRoute.Unsupported -> ReaderChapterRoute.UNSUPPORTED
    }

internal fun createReaderPageLoader(
    route: ReaderChapterContentRoute,
    chapter: ReaderChapter,
    source: Source,
    factories: ReaderPageLoaderFactories,
): PageLoader = when (route) {
    ReaderChapterContentRoute.Download -> factories.download(chapter)
    is ReaderChapterContentRoute.LocalDirectory -> factories.localDirectory(route.file)
    is ReaderChapterContentRoute.LocalArchive -> factories.localArchive(route.file)
    is ReaderChapterContentRoute.LocalEpub -> factories.localEpub(route.file)
    ReaderChapterContentRoute.Online -> factories.online(chapter, source as HttpSource)
    ReaderChapterContentRoute.MissingSource -> factories.missingSource(source)
    ReaderChapterContentRoute.Unsupported -> factories.unsupported(source)
}

internal val ReaderChapterContentRoute.isStoredContent: Boolean
    get() = when (this) {
        ReaderChapterContentRoute.Download,
        is ReaderChapterContentRoute.LocalDirectory,
        is ReaderChapterContentRoute.LocalArchive,
        is ReaderChapterContentRoute.LocalEpub,
        -> true
        ReaderChapterContentRoute.Online,
        ReaderChapterContentRoute.MissingSource,
        ReaderChapterContentRoute.Unsupported,
        -> false
    }
