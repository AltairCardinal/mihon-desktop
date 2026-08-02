package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderPageModel
import mihon.domain.reader.session.ReaderPageLoadState
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    private var sharedStatePublisher: ((ReaderPageLoadState) -> Unit)? = null

    override var status: State
        get() = super.status
        set(value) {
            super.status = value
            publishSharedState()
        }

    override var progress: Int
        get() = super.progress
        set(value) {
            super.progress = value
            if (status is State.DownloadImage) {
                publishSharedState()
            }
        }

    internal fun bindSharedState(publisher: (ReaderPageLoadState) -> Unit) {
        sharedStatePublisher = publisher
        publishSharedState()
    }

    internal fun unbindSharedState() {
        sharedStatePublisher = null
    }

    internal fun toSharedLoadState(): ReaderPageLoadState = when (val current = status) {
        State.Queue -> ReaderPageLoadState.Queued
        State.LoadPage -> ReaderPageLoadState.ResolvingImage
        State.DownloadImage -> ReaderPageLoadState.Downloading(progress.takeIf { it in 0..100 })
        State.Ready -> ReaderPageLoadState.Ready
        is State.Error -> ReaderPageLoadState.Error(AppError.Unknown(current.error))
    }

    private fun publishSharedState() {
        sharedStatePublisher?.invoke(toSharedLoadState())
    }
}

fun ReaderPage.toSharedPageModel(): ReaderPageModel = ReaderPageModel(
    index = index,
    url = url,
    imageUrl = imageUrl,
)
