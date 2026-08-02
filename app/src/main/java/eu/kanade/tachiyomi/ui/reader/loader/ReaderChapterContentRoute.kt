package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
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
    localFormat: (() -> Format)? = null,
): ReaderChapterContentRoute = when {
    downloaded -> ReaderChapterContentRoute.Download
    source is LocalSource -> when (val format = checkNotNull(localFormat).invoke()) {
        is Format.Directory -> ReaderChapterContentRoute.LocalDirectory(format.file)
        is Format.Archive -> ReaderChapterContentRoute.LocalArchive(format.file)
        is Format.Epub -> ReaderChapterContentRoute.LocalEpub(format.file)
    }
    source is HttpSource -> ReaderChapterContentRoute.Online
    source is StubSource -> ReaderChapterContentRoute.MissingSource
    else -> ReaderChapterContentRoute.Unsupported
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
