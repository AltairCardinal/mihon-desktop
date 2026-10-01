package mihon.desktop.test.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.ui.reader.ReaderScreenModel
import mihon.domain.reader.ReaderTransitionDirection
import java.util.concurrent.atomic.AtomicReference

/** Live production session observation/actions, separate from the synthetic reader fixture. */
internal data class ProductionReaderSnapshot(
    val isOpen: Boolean,
    val currentChapterId: Long,
    val activeChapterId: Long,
    val currentPage: Int,
    val totalPages: Int,
    val mangaTitle: String,
    val chapterTitle: String,
    val hasNextChapter: Boolean,
    val hasPrevChapter: Boolean,
    val loadState: String,
    val chapterIds: List<Long>,
    val currentChapterIndex: Int,
    val initialPage: Int,
    val resumeHeadIds: List<String>,
    val productionClosed: Boolean = false,
)

internal class ProductionReaderBinding(
    private val model: ReaderScreenModel,
    private val chapters: List<ReaderChapterRef>,
    private val transition: (ReaderTransitionDirection, ReaderNavigator?) -> Boolean,
    private val closeReader: () -> Unit,
) {
    private fun navigator(): ReaderNavigator? {
        val state = model.state.value
        val index = chapters.indexOfFirst { it.id == state.context.chapterId }
        return if (index < 0) null else ReaderNavigator(chapters, index, state.skipReadChapters, state.skipFilteredChapters, state.skipDuplicateChapters)
    }
    fun snapshot(): ProductionReaderSnapshot {
        val state = model.state.value
        val navigation = navigator()
        return ProductionReaderSnapshot(
            true, state.context.chapterId, state.session.activeChapter.id.value, state.currentPage,
            state.session.activeChapter.pages.size, state.context.mangaTitle, state.context.chapterTitle,
            navigation?.nextToRead != null, navigation?.previousRead != null, state.session.activeChapter.loadState.toString(),
            chapters.map { it.id }, state.context.chapterIndex, state.context.initialPage,
            state.context.resumeSnapshot?.heads?.values?.flatten()?.map { it.eventId.stableKey + ":" + it.effectId }?.sorted().orEmpty(),
        )
    }
    suspend fun adjacent(direction: ReaderTransitionDirection): Boolean = withContext(Dispatchers.Main) {
        transition(direction, navigator())
    }
    suspend fun page(index: Int): Boolean = withContext(Dispatchers.Main) {
        if (index !in 0 until model.state.value.session.activeChapter.pages.size) {
            false
        } else {
            model.goToPage(index)
            true
        }
    }
    suspend fun close() = withContext(Dispatchers.Main) { closeReader() }
}

internal object ProductionReaderTestModeBridge {
    private val owner = AtomicReference<ProductionReaderBinding?>()
    private val lastClosed = AtomicReference<ProductionReaderSnapshot?>()
    val binding: ProductionReaderBinding? get() = owner.get()
    fun snapshot(): ProductionReaderSnapshot? = owner.get()?.snapshot() ?: lastClosed.get()
    fun install(binding: ProductionReaderBinding) {
        lastClosed.set(null)
        owner.set(binding)
    }
    fun clear(binding: ProductionReaderBinding) {
        if (owner.compareAndSet(binding, null)) lastClosed.set(binding.snapshot().copy(isOpen = false, productionClosed = true))
    }
    fun reset() {
        owner.set(null)
        lastClosed.set(null)
    }
}
