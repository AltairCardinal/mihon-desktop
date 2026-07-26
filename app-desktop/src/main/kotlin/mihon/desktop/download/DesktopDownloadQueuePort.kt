package mihon.desktop.download

import kotlinx.coroutines.flow.StateFlow

interface DesktopDownloadQueuePort {
    val queue: StateFlow<List<DownloadItem>>
}
