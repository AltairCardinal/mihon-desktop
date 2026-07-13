package mihon.desktop.ui.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mihon.domain.task.TaskState
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

fun interface CoverFilePicker {
    suspend fun chooseBytes(): ByteArray?
}

class MangaCoverAdapter(
    private val picker: CoverFilePicker,
    private val updateCover: suspend (Long, ByteArray) -> TaskState<Unit>,
) {
    suspend fun chooseAndUpdate(mangaId: Long): TaskState<Unit>? {
        val bytes = picker.chooseBytes() ?: return null
        return updateCover(mangaId, bytes)
    }
}

class DesktopCoverFilePicker : CoverFilePicker {
    override suspend fun chooseBytes(): ByteArray? {
        val file = chooseFile() ?: return null
        return withContext(Dispatchers.IO) { file.readBytes() }
    }

    private suspend fun chooseFile(): File? = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            val chooser = JFileChooser().apply {
                fileFilter = FileNameExtensionFilter("Image files", "jpg", "jpeg", "png", "webp", "gif")
            }
            val selected = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            if (continuation.isActive) continuation.resume(selected)
        }
    }
}
