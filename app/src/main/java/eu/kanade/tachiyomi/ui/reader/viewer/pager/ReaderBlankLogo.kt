package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One local decoration decode per viewer, independent of source-page preparation. */
internal class ReaderBlankLogo(scope: CoroutineScope, decode: () -> Bitmap?) {
    private val mutableBitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap = mutableBitmap.asStateFlow()
    val loading = scope.launch {
        var decoded: Bitmap? = null
        try {
            // Assign inside IO so cancellation during a non-cancellable codec call cannot leak its result.
            withContext(Dispatchers.IO) { decoded = decode() }
            mutableBitmap.value = decoded
            decoded = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // White paper remains usable when the optional local mark cannot be decoded.
        } finally {
            decoded?.recycle()
        }
    }

    fun close() {
        loading.cancel()
        val previous = mutableBitmap.value
        mutableBitmap.value = null
        previous?.recycle()
    }
}
