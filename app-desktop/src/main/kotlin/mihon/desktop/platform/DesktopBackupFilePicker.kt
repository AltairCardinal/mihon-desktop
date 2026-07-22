package mihon.desktop.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface DesktopBackupFilePicker {
    suspend fun choose(request: DesktopBackupFilePickerRequest): DesktopBackupFilePickerResult
}

sealed interface DesktopBackupFilePickerRequest {
    val title: String

    data class Directory(override val title: String) : DesktopBackupFilePickerRequest

    data class BackupFile(
        override val title: String,
        val description: String,
        val extensions: Set<String>,
    ) : DesktopBackupFilePickerRequest {
        init {
            require(extensions.isNotEmpty())
        }
    }
}

sealed interface DesktopBackupFilePickerResult {
    data class Selected(val file: File) : DesktopBackupFilePickerResult
    data object Cancelled : DesktopBackupFilePickerResult
}

class SwingDesktopBackupFilePicker : DesktopBackupFilePicker {
    override suspend fun choose(request: DesktopBackupFilePickerRequest): DesktopBackupFilePickerResult =
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                if (!continuation.isActive) return@invokeLater
                runCatching {
                    val chooser = createDesktopBackupFileChooser(request)
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        DesktopBackupFilePickerResult.Selected(chooser.selectedFile)
                    } else {
                        DesktopBackupFilePickerResult.Cancelled
                    }
                }.onSuccess { result ->
                    if (continuation.isActive) continuation.resume(result)
                }.onFailure { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
}

internal fun createDesktopBackupFileChooser(request: DesktopBackupFilePickerRequest): JFileChooser =
    JFileChooser().apply {
        dialogTitle = request.title
        currentDirectory = File(System.getProperty("user.home"))
        when (request) {
            is DesktopBackupFilePickerRequest.Directory -> fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            is DesktopBackupFilePickerRequest.BackupFile -> {
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter(request.description, *request.extensions.toTypedArray())
            }
        }
    }
