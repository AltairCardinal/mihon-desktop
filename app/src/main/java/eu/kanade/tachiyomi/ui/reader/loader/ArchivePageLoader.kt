package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.ArchiveReader
import mihon.domain.reader.content.ReaderImageCandidatePolicy
import mihon.domain.reader.content.ReaderImageSortMode
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(private val reader: ArchiveReader) : PageLoader() {
    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> = reader.useEntries { entries ->
        entries
            .filter {
                it.isFile && ReaderImageCandidatePolicy.accepts(it.name) {
                    ImageUtil.findImageType { reader.getInputStream(it.name)!! } != null
                }
            }
            .sortedWith { first, second ->
                ReaderImageCandidatePolicy.compare(
                    ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE,
                    first.name,
                    second.name,
                )
            }
            .mapIndexed { i, entry ->
                ReaderPage(i).apply {
                    stream = { reader.getInputStream(entry.name)!! }
                    status = Page.State.Ready
                }
            }
            .toList()
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
