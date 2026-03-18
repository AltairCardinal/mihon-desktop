package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Common interface for pager-based viewers, used by [PagerTransitionHolder] and [ReaderButton]
 * to avoid a hard dependency on [PagerViewer].
 */
interface ViewerWithPager {
    val activity: ReaderActivity
    val downloadManager: DownloadManager
    val pager: Pager
}
