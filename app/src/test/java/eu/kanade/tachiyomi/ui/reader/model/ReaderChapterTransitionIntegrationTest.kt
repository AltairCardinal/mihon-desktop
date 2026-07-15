package eu.kanade.tachiyomi.ui.reader.model

import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.File

class ReaderChapterTransitionIntegrationTest {

    @Test
    fun `current chapter error exposes the same shared retry command`() {
        val current = chapter(10)
        current.state = ReaderChapter.State.Error(IllegalStateException("current failed"))

        val error = current.sharedStateFlow.value as ReaderChapterState.Error

        assertEquals(ReaderNavigationCommand.RetryChapter(10), error.retryCommand())
    }

    @Test
    fun `previous and next errors retain their own retry target`() {
        val current = chapter(10)
        val previous = chapter(9).apply { state = ReaderChapter.State.Error(IllegalStateException("prev failed")) }
        val next = chapter(11).apply { state = ReaderChapter.State.Error(IllegalStateException("next failed")) }

        val previousCommand = ChapterTransition.Prev(current, previous)
            .toSharedTransitionModel(previous.sharedStateFlow.value)
            .retryCommand()
        val nextCommand = ChapterTransition.Next(current, next)
            .toSharedTransitionModel(next.sharedStateFlow.value)
            .retryCommand()

        assertEquals(ReaderNavigationCommand.RetryChapter(9), previousCommand)
        assertEquals(ReaderNavigationCommand.RetryChapter(11), nextCommand)
    }

    @Test
    fun `both chapter edges map to explicit shared boundaries without a target`() {
        val current = chapter(10)

        val previous = ChapterTransition.Prev(current, null).toSharedTransitionModel().retryCommand()
        val next = ChapterTransition.Next(current, null).toSharedTransitionModel().retryCommand()

        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.PREVIOUS), previous)
        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT), next)
    }

    @Test
    fun `pager and webtoon transition holders subscribe to every shared production state`() {
        val stateMarkers = listOf(
            "is ReaderChapterState.Loading -> setLoading()",
            "is ReaderChapterState.Error ->",
            "is ReaderChapterState.Wait, is ReaderChapterState.Loaded ->",
        )
        assertSharedStateSubscription(
            path = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt",
            methodMarker = "private fun observeStatus(chapter: ReaderChapter)",
            flowMarker = "chapter.sharedStateFlow",
            collectorMarker = ".collectLatest { state ->",
            stateMarkers = stateMarkers,
        )
        assertSharedStateSubscription(
            path = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonTransitionHolder.kt",
            methodMarker = "private fun observeStatus(chapter: ReaderChapter, transition: ChapterTransition)",
            flowMarker = "chapter.sharedStateFlow",
            collectorMarker = ".collectLatest { state ->",
            stateMarkers = stateMarkers,
        )
    }

    private fun chapter(id: Long) = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))

    private fun assertSharedStateSubscription(
        path: String,
        methodMarker: String,
        flowMarker: String,
        collectorMarker: String,
        stateMarkers: List<String>,
    ) {
        val source = productionSource(path)
        val observeStatus = bracedBlock(source, methodMarker)

        assertEquals(1, occurrenceCount(observeStatus, flowMarker), path)
        val collector = bracedBlock(observeStatus, collectorMarker)
        stateMarkers.forEach { marker -> assertTrue(collector.contains(marker), "$path must handle $marker") }
    }

    private fun productionSource(path: String): String {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null && !current.resolve("settings.gradle.kts").isFile) current = current.parentFile
        return requireNotNull(current) { "Repository root not found" }.resolve(path).readText()
    }

    private fun bracedBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "Missing production block: $marker" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed production block: $marker")
    }

    private fun occurrenceCount(source: String, marker: String): Int = Regex(
        Regex.escape(marker),
    ).findAll(source).count()
}
