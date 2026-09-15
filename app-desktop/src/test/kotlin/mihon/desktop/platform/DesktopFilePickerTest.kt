package mihon.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Isolated
class DesktopFilePickerTest {

    @Test
    fun `save chooser exposes save mode and suggested recovery file name`() {
        val chooser = createDesktopFileChooser(DesktopFilePickerRequest.SaveFile("Save recovery", "recovery.json"))
        assertEquals(JFileChooser.SAVE_DIALOG, chooser.dialogType)
        assertEquals("recovery.json", chooser.selectedFile?.name)
    }

    @Test
    fun `chooser consumes title initial directory and selection mode`(
        @TempDir tempDir: File,
    ) {
        val initial = tempDir.resolve("initial").apply { mkdirs() }
        val directory = createDesktopFileChooser(
            DesktopFilePickerRequest.Directory(
                title = "Choose downloads",
                initialDirectory = initial,
            ),
        )
        assertEquals("Choose downloads", directory.dialogTitle)
        assertEquals(initial, directory.currentDirectory)
        assertEquals(JFileChooser.DIRECTORIES_ONLY, directory.fileSelectionMode)

        val file = createDesktopFileChooser(
            DesktopFilePickerRequest.OpenFile(
                title = "Choose backup",
                description = "Mihon backup",
                extensions = setOf("tachibk"),
                initialDirectory = initial,
            ),
        )
        val filter = assertInstanceOf(FileNameExtensionFilter::class.java, file.fileFilter)
        assertEquals("Choose backup", file.dialogTitle)
        assertEquals(initial, file.currentDirectory)
        assertEquals(JFileChooser.FILES_ONLY, file.fileSelectionMode)
        assertEquals("Mihon backup", filter.description)
        assertEquals(listOf("tachibk"), filter.extensions.toList())
    }

    @Test
    fun `Swing adapter executes dialog on EDT and returns selected cancelled and typed failure`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val selected = tempDir.resolve("selected")
        val failure = IllegalStateException("dialog failed")
        val edtCalls = mutableListOf<Boolean>()
        val picker = SwingDesktopFilePicker(
            DesktopFilePickerDialog { request ->
                edtCalls += SwingUtilities.isEventDispatchThread()
                when (request.title) {
                    "selected" -> DesktopFilePickerResult.Selected(selected)
                    "cancelled" -> DesktopFilePickerResult.Cancelled
                    else -> throw failure
                }
            },
        )

        val selectedResult = withContext(Dispatchers.Default) {
            picker.choose(DesktopFilePickerRequest.Directory("selected", tempDir))
        }
        val cancelledResult = withContext(Dispatchers.Default) {
            picker.choose(DesktopFilePickerRequest.Directory("cancelled", tempDir))
        }
        val failedResult = withContext(Dispatchers.Default) {
            picker.choose(DesktopFilePickerRequest.Directory("failed", tempDir))
        }

        assertEquals(DesktopFilePickerResult.Selected(selected), selectedResult)
        assertEquals(DesktopFilePickerResult.Cancelled, cancelledResult)
        assertSame(failure, assertInstanceOf(DesktopFilePickerResult.Failed::class.java, failedResult).error)
        assertEquals(listOf(true, true, true), edtCalls)
        assertTrue(edtCalls.all { it })
    }
}
