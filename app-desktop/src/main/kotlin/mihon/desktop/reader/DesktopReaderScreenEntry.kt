package mihon.desktop.reader

import cafe.adriel.voyager.core.model.ScreenModel
import mihon.desktop.ui.reader.ReaderScreenModel

/** Voyager owns this same runtime before and after the full model takes over. */
internal class DesktopReaderScreenEntry(
    val runtime: DesktopReaderRuntime,
    private val closeUnclaimed: () -> Unit,
    private val createModel: () -> ReaderScreenModel,
) : ScreenModel {
    private var activeModel: ReaderScreenModel? = null
    private var closed = false

    val hasModel: Boolean @Synchronized get() = activeModel != null

    @Synchronized
    fun model(): ReaderScreenModel {
        check(!closed) { "Reader entry is closed" }
        return activeModel ?: try {
            createModel().also { activeModel = it }
        } catch (error: Throwable) {
            closed = true
            closeUnclaimed()
            throw error
        }
    }

    @Synchronized
    override fun onDispose() {
        if (closed) return
        closed = true
        activeModel?.onDispose() ?: closeUnclaimed()
    }
}
