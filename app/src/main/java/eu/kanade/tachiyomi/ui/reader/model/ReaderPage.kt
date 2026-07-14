package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import mihon.domain.reader.ReaderPageModel
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter
}

fun ReaderPage.toSharedPageModel(): ReaderPageModel = ReaderPageModel(
    index = index,
    url = url,
    imageUrl = imageUrl,
)
