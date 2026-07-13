package mihon.domain.download

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadQueueStateMachineTest {
    private val machine = DownloadQueueStateMachine()

    @Test
    fun `running entries recover as queued after restart without losing progress`() {
        val recovered = machine.recover(listOf(entry(1, DownloadQueueStatus.DOWNLOADING, progress = 3)))

        recovered.single().status shouldBe DownloadQueueStatus.QUEUED
        recovered.single().progress shouldBe 3
    }

    @Test
    fun `scheduler is fair between sources while preserving per source order`() {
        val queue = listOf(entry(1, sourceId = 10), entry(2, sourceId = 10), entry(3, sourceId = 20))

        machine.schedule(queue, limit = 3).map { it.chapterId }.shouldContainExactly(1, 3, 2)
    }

    @Test
    fun `non positive limit schedules nothing`() {
        machine.schedule(listOf(entry(1)), limit = 0) shouldBe emptyList()
        machine.schedule(listOf(entry(1)), limit = -1) shouldBe emptyList()
    }

    @Test
    fun `running entries consume concurrency slots`() {
        val queue = listOf(
            entry(1, status = DownloadQueueStatus.DOWNLOADING, sourceId = 10),
            entry(2, sourceId = 20),
            entry(3, sourceId = 30),
        )

        machine.schedule(queue, limit = 2).map { it.chapterId }.shouldContainExactly(2)
    }

    @Test
    fun `retry uses Android exponential backoff and stops after three retries`() {
        machine.retryDelayMillis(0) shouldBe 2_000L
        machine.retryDelayMillis(1) shouldBe 4_000L
        machine.retryDelayMillis(2) shouldBe 8_000L
        machine.retryDelayMillis(3) shouldBe null
    }

    @Test
    fun `only valid transitions are accepted`() {
        machine.transition(entry(1), DownloadQueueStatus.DOWNLOADING)?.status shouldBe DownloadQueueStatus.DOWNLOADING
        machine.transition(entry(1), DownloadQueueStatus.COMPLETED) shouldBe null
    }

    private fun entry(
        chapterId: Long,
        status: DownloadQueueStatus = DownloadQueueStatus.QUEUED,
        progress: Int = 0,
        mangaId: Long = chapterId,
        sourceId: Long = 1,
    ) = DownloadQueueEntry(
        chapterId = chapterId,
        mangaId = mangaId,
        sourceId = sourceId,
        mangaTitle = "Manga $mangaId",
        chapterName = "Chapter $chapterId",
        chapterUrl = "/chapter/$chapterId",
        pageUrls = listOf("https://example.com/$chapterId.jpg"),
        status = status,
        progress = progress,
        position = chapterId,
    )
}
