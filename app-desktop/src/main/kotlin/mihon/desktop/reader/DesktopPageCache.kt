package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow
import mihon.domain.reader.ByteBudgetPageCache
import mihon.domain.reader.PageCache
import mihon.domain.reader.PageCacheCommitResult
import mihon.domain.reader.PageCacheSnapshot
import mihon.domain.reader.PageCacheWrite

/** Access-ordered, byte-budgeted cache for Desktop decoded pages. */
class DesktopPageCache(private val maxBytes: Long) : PageCache<ImageBitmap> {
    private val delegate = ByteBudgetPageCache<ImageBitmap>(maxBytes)

    override val generation: Long?
        @Synchronized get() = delegate.generation

    override val revision: StateFlow<Long> = delegate.revision

    init {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
    }

    @Synchronized
    override fun get(pageIndex: Int): ImageBitmap? = delegate.get(pageIndex)

    @Synchronized
    override fun beginGeneration(generation: Long, evictPageIndices: Set<Int>): Boolean =
        delegate.beginGeneration(generation, evictPageIndices)

    @Synchronized
    override fun commit(write: PageCacheWrite<ImageBitmap>): PageCacheCommitResult = delegate.commit(write)

    @Synchronized
    override fun remove(pageIndex: Int): ImageBitmap? = delegate.remove(pageIndex)

    @Synchronized
    override fun clear() = delegate.clear()

    @Synchronized
    override fun snapshot(): PageCacheSnapshot = delegate.snapshot()
}
