package mihon.desktop.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import tachiyomi.i18n.MR
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

interface DesktopFilePicker {
    suspend fun choose(request: DesktopFilePickerRequest): DesktopFilePickerResult
}

sealed interface DesktopFilePickerRequest {
    val title: String
    val initialDirectory: File?

    data class Directory(
        override val title: String,
        override val initialDirectory: File? = null,
    ) : DesktopFilePickerRequest

    data class OpenFile(
        override val title: String,
        val description: String,
        val extensions: Set<String>,
        override val initialDirectory: File? = null,
    ) : DesktopFilePickerRequest {
        init {
            require(extensions.isNotEmpty())
        }
    }

    data class SaveFile(
        override val title: String,
        val suggestedName: String,
        override val initialDirectory: File? = null,
    ) : DesktopFilePickerRequest
}

sealed interface DesktopFilePickerResult {
    data class Selected(val file: File) : DesktopFilePickerResult
    data object Cancelled : DesktopFilePickerResult
    data class Failed(val error: Throwable) : DesktopFilePickerResult
}

internal fun interface DesktopFilePickerDialog {
    fun choose(request: DesktopFilePickerRequest): DesktopFilePickerResult
}

internal class SwingDesktopFilePicker(
    private val dialog: DesktopFilePickerDialog = DesktopFilePickerDialog(::showDesktopFileChooser),
) : DesktopFilePicker {
    override suspend fun choose(request: DesktopFilePickerRequest): DesktopFilePickerResult =
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                if (!continuation.isActive) return@invokeLater
                val result = runCatching { dialog.choose(request) }
                    .getOrElse(DesktopFilePickerResult::Failed)
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
}

internal fun createDesktopFileChooser(request: DesktopFilePickerRequest): JFileChooser =
    JFileChooser().apply {
        dialogTitle = request.title
        currentDirectory = request.initialDirectory ?: File(System.getProperty("user.home"))
        when (request) {
            is DesktopFilePickerRequest.SaveFile -> {
                dialogType = JFileChooser.SAVE_DIALOG
                fileSelectionMode = JFileChooser.FILES_ONLY
                selectedFile = File(currentDirectory, request.suggestedName)
            }
            is DesktopFilePickerRequest.Directory -> fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            is DesktopFilePickerRequest.OpenFile -> {
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter(request.description, *request.extensions.toTypedArray())
            }
        }
    }

private fun showDesktopFileChooser(request: DesktopFilePickerRequest): DesktopFilePickerResult {
    val chooser = createDesktopFileChooser(request)
    return if (chooser.showDialog(null, null) == JFileChooser.APPROVE_OPTION) {
        if (request is DesktopFilePickerRequest.SaveFile && chooser.selectedFile.exists() &&
            JOptionPane.showConfirmDialog(
                null,
                MR.strings.sync_replace_file.localized(),
                request.title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
            ) != JOptionPane.YES_OPTION
        ) {
            return DesktopFilePickerResult.Cancelled
        }
        DesktopFilePickerResult.Selected(chooser.selectedFile)
    } else {
        DesktopFilePickerResult.Cancelled
    }
}
