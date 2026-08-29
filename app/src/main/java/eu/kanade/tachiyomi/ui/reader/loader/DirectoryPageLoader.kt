package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.domain.reader.content.ReaderImageCandidatePolicy
import mihon.domain.reader.content.ReaderImageSortMode
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from a directory given on [file].
 */
internal class DirectoryPageLoader(val file: UniFile) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        return file.listFiles()
            ?.filter {
                val name = it.name ?: return@filter false
                !it.isDirectory && ReaderImageCandidatePolicy.accepts(name) {
                    ImageUtil.findImageType { it.openInputStream() } != null
                }
            }
            ?.sortedWith { first, second ->
                ReaderImageCandidatePolicy.compare(
                    ReaderImageSortMode.LOCAL_NATURAL_CASE_INSENSITIVE,
                    first.name.orEmpty(),
                    second.name.orEmpty(),
                )
            }
            ?.mapIndexed { i, file ->
                val streamFn = { file.openInputStream() }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
            .orEmpty()
    }
}
