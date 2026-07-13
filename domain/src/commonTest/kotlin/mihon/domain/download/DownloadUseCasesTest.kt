package mihon.domain.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DownloadUseCasesTest {
    @Test
    fun `shared use cases expose queue cancel retry transition and recovery`() = runTest {
        val repository = FakeRepository()
        ObserveDownloadQueue(repository)().first().single().chapterId shouldBe 1
        CancelDownload(repository)(1) shouldBe true
        RetryDownload(repository)(1) shouldBe true
        TransitionDownload(repository)(1, DownloadQueueStatus.DOWNLOADING) shouldBe true
        RecoverDownloads(repository)().single().status shouldBe DownloadQueueStatus.QUEUED
    }

    private class FakeRepository : DownloadRepository {
        override val queueEntries = MutableStateFlow(listOf(entry(DownloadQueueStatus.DOWNLOADING)))
        override fun enqueue(entry: DownloadQueueEntry) = Unit
        override fun isDownloaded(sourceId: Long, mangaTitle: String, chapterName: String) = false
        override fun cancel(chapterId: Long) = true
        override fun retry(chapterId: Long) = true
        override fun transition(chapterId: Long, target: DownloadQueueStatus) = true
        override fun recover() = listOf(entry(DownloadQueueStatus.QUEUED))
    }

    companion object {
        private fun entry(status: DownloadQueueStatus) = DownloadQueueEntry(
            1, 1, 1, "m", "c", "/c", emptyList(), status, position = 1,
        )
    }
}
