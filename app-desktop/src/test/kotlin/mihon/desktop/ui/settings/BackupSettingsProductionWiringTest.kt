package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.platform.DesktopBackupFilePicker
import mihon.desktop.platform.DesktopBackupFilePickerRequest
import mihon.desktop.platform.DesktopBackupFilePickerResult
import mihon.desktop.platform.createDesktopBackupFileChooser
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@OptIn(ExperimentalComposeUiApi::class)
class BackupSettingsProductionWiringTest {
    @Test
    fun `Swing adapter consumes directory and backup file request configuration`() {
        val directory = createDesktopBackupFileChooser(DesktopBackupFilePickerRequest.Directory("Choose directory"))
        assertEquals("Choose directory", directory.dialogTitle)
        assertEquals(JFileChooser.DIRECTORIES_ONLY, directory.fileSelectionMode)

        val request = DesktopBackupFilePickerRequest.BackupFile("Choose backup", "Mihon backup", setOf("tachibk"))
        val backup = createDesktopBackupFileChooser(request)
        val filter = assertInstanceOf(FileNameExtensionFilter::class.java, backup.fileFilter)
        assertEquals("Choose backup", backup.dialogTitle)
        assertEquals(JFileChooser.FILES_ONLY, backup.fileSelectionMode)
        assertEquals("Mihon backup", filter.description)
        assertEquals(listOf("tachibk"), filter.extensions.toList())
    }

    @Test
    fun `real Create and Restore buttons route picker outcomes and visible feedback`() = runBlocking {
        val directory = File("build/backup-target")
        val backup = File(directory, "library.tachibk")
        val previousLocale = Locale.getDefault()
        val cases = listOf(
            ButtonCase(true, DesktopBackupFilePickerResult.Cancelled, MR.strings.desktop_backup_create_cancelled),
            ButtonCase(true, DesktopBackupFilePickerResult.Selected(directory), MR.strings.desktop_backup_saved, backup),
            ButtonCase(true, DesktopBackupFilePickerResult.Selected(directory), MR.strings.desktop_backup_failed, failure = "disk full"),
            ButtonCase(true, DesktopBackupFilePickerResult.Selected(directory), MR.strings.creating_backup_error, failure = ""),
            ButtonCase(false, DesktopBackupFilePickerResult.Cancelled, MR.strings.desktop_backup_restore_selection_cancelled),
            ButtonCase(false, DesktopBackupFilePickerResult.Selected(backup), null),
        ).flatMap { case -> listOf(Locale.US, Locale.forLanguageTag("zh-CN")).map { it to case } }
        cases.forEach { (locale, case) ->
            Locale.setDefault(locale)
            val picker = RecordingPicker(case.pickerResult)
            val model = model(scope = this)
            val factory = mockk<BackupRestoreScreenModelFactory> {
                every { create() } returns model
                if (case.failure == null) {
                    coEvery { createBackup(any()) } returns (case.createdFile ?: backup)
                } else {
                    coEvery { createBackup(any()) } throws IllegalStateException(case.failure)
                }
            }
            val scene = scene(factory, picker)
            try {
                render(scene)
                click(scene, if (case.create) MR.strings.pref_create_backup.localized(locale) else MR.strings.file_select_backup.localized(locale))
                val copy = render(scene)
                val request = requireNotNull(picker.request)
                if (case.create) {
                    assertInstanceOf(DesktopBackupFilePickerRequest.Directory::class.java, request)
                    assertEquals(MR.strings.onboarding_storage_action_select.localized(locale), request.title)
                    if (case.pickerResult is DesktopBackupFilePickerResult.Selected) coVerify { factory.createBackup(directory) }
                } else {
                    val fileRequest = assertInstanceOf(DesktopBackupFilePickerRequest.BackupFile::class.java, request)
                    assertEquals(MR.strings.file_select_backup.localized(locale), fileRequest.title)
                    assertEquals(setOf("tachibk"), fileRequest.extensions)
                    assertEquals(MR.strings.desktop_backup_file_filter.localized(locale), fileRequest.description)
                }
                case.feedback?.let { resource ->
                    val expected = when {
                        case.createdFile != null -> resource.localized(locale, backup.name)
                        !case.failure.isNullOrBlank() -> resource.localized(locale, case.failure)
                        else -> resource.localized(locale)
                    }
                    assertTrue(expected in copy, "Missing '$expected': $copy")
                }
                if (!case.create && case.pickerResult is DesktopBackupFilePickerResult.Selected) {
                    assertTrue(model.state.value is BackupRestoreUiState.Preview)
                    assertEquals(backup, (model.state.value as BackupRestoreUiState.Preview).file)
                }
            } finally {
                scene.close()
                Locale.setDefault(previousLocale)
            }
        }
    }

    private fun model(scope: kotlinx.coroutines.CoroutineScope) =
        BackupRestoreScreenModel(
            loadPreview = { BackupPreview(1, 0, 0, 0, 0, 0, 0) },
            restore = { _, _ -> error("restore must require confirmation") },
            scope = scope,
        )

    private fun scene(factory: BackupRestoreScreenModelFactory, picker: DesktopBackupFilePicker): ImageComposeScene =
        ImageComposeScene(900, 2_000) {
            val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                every { appPreferences } returns DesktopAppPreferences(InMemoryPreferenceStore())
                every { backupRestoreScreenModelFactory } returns factory
                every { backupFilePicker } returns picker
            }
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(BackupSettingsScreen()) { CurrentScreen() }
            }
        }

    private suspend fun render(scene: ImageComposeScene): Set<String> {
        repeat(5) { scene.render(); yield() }
        return nodes(scene).flatMap { node ->
            if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList()
        }.toSet()
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && it.config.toString().contains(label) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { flatten(it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class RecordingPicker(private val result: DesktopBackupFilePickerResult) : DesktopBackupFilePicker {
        var request: DesktopBackupFilePickerRequest? = null
        override suspend fun choose(request: DesktopBackupFilePickerRequest): DesktopBackupFilePickerResult {
            assertFalse(this.request != null)
            this.request = request
            return result
        }
    }

    private data class ButtonCase(
        val create: Boolean,
        val pickerResult: DesktopBackupFilePickerResult,
        val feedback: StringResource?,
        val createdFile: File? = null,
        val failure: String? = null,
    )
}
