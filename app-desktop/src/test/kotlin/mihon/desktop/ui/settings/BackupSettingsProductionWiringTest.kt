package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
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
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.platform.DesktopBackupFilePicker
import mihon.desktop.platform.DesktopBackupFilePickerRequest
import mihon.desktop.platform.DesktopBackupFilePickerResult
import mihon.desktop.platform.createDesktopBackupFileChooser
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.task.TaskState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.reflect.KClass

@OptIn(ExperimentalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
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

    @Test
    fun `download catalog result anchors once and preserves preference writes`() = runBlocking {
        anchorFixture(this, 150).use { fixture ->
            val title = MR.strings.pref_download_new.localized()
            open(fixture, title, DownloadSettingsScreen::class)
            assertAnchor(fixture.scene, title)
            click(fixture.scene, title)
            render(fixture.scene)
            assertTrue(fixture.downloadPreferences.autoDownloadNewChapters.get())
            assertOneShot(fixture, DownloadSettingsScreen())
        }
    }

    @Test
    fun `backup catalog result anchors once and preserves picker feedback`() = runBlocking {
        anchorFixture(this, 240).use { fixture ->
            val title = MR.strings.pref_restore_backup.localized()
            open(fixture, title, BackupSettingsScreen::class)
            assertAnchor(fixture.scene, title)
            click(fixture.scene, MR.strings.pref_create_backup.localized())
            val copy = render(fixture.scene)
            assertInstanceOf(DesktopBackupFilePickerRequest.Directory::class.java, fixture.picker.request)
            assertTrue(MR.strings.desktop_backup_create_cancelled.localized() in copy)
            assertOneShot(fixture, BackupSettingsScreen())
        }
    }

    @Test
    fun `automatic backup failure is visible on the real backup settings screen`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupLastError.set("disk full")
        }
        val factory = mockk<BackupRestoreScreenModelFactory> { every { create() } returns model(this@runBlocking) }
        val scene = scene(
            factory = factory,
            picker = RecordingPicker(DesktopBackupFilePickerResult.Cancelled),
            preferences = preferences,
        )
        try {
            val copy = render(scene)
            assertTrue(MR.strings.desktop_backup_failed.localized(Locale.getDefault(), "disk full") in copy)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `automatic backup success time is visible on the real backup settings screen`() = runBlocking {
        val timestamp = 100_000_000L
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupLastSuccessAt.set(timestamp)
        }
        val factory = mockk<BackupRestoreScreenModelFactory> { every { create() } returns model(this@runBlocking) }
        val scene = scene(
            factory = factory,
            picker = RecordingPicker(DesktopBackupFilePickerResult.Cancelled),
            preferences = preferences,
        )
        try {
            val expected = MR.strings.last_auto_backup_info.localized(
                Locale.getDefault(),
                DateFormat.getDateTimeInstance().format(Date(timestamp)),
            )
            assertTrue(expected in render(scene))
        } finally {
            scene.close()
        }
    }

    @Test
    fun `wrong route and unknown title never highlight`() = runBlocking {
        anchorFixture(this, 180).use { fixture ->
            val result = result(MR.strings.pref_download_new.localized(), DownloadSettingsScreen::class)
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            fixture.navigator.replace(BackupSettingsScreen())
            render(fixture.scene)
            assertNoAnchor(fixture.scene)
            DesktopSettingsAnchorOwner.publish(DownloadSettingsScreen(), "missing-title")
            fixture.navigator.replace(DownloadSettingsScreen())
            render(fixture.scene)
            assertNoAnchor(fixture.scene)
        }
    }

    @Test
    fun `six typed backup failures execute through the real screen in English and Chinese`() = runBlocking {
        val previousLocale = Locale.getDefault()
        val backup = File("library.tachibk")
        val cases = listOf(
            FailureCase(BackupRestoreFailureReason.EmptyBackup, MR.strings.invalid_backup_file_missing_manga),
            FailureCase(BackupRestoreFailureReason.UnsupportedVersion, MR.strings.desktop_backup_unsupported_version),
            FailureCase(BackupRestoreFailureReason.EmptyFile, MR.strings.desktop_backup_empty_file),
            FailureCase(BackupRestoreFailureReason.MissingData, MR.strings.desktop_backup_missing_data),
            FailureCase(BackupRestoreFailureReason.Corrupted, MR.strings.invalid_backup_file_unknown),
            FailureCase(BackupRestoreFailureReason.RestoreNotStarted, MR.strings.desktop_backup_restore_not_started),
        )
        try {
            listOf(Locale.US, Locale.forLanguageTag("zh-CN")).forEach { locale ->
                Locale.setDefault(locale)
                cases.forEach { case ->
                    val model = failureModel(case.reason, this)
                    model.select(backup)
                    repeat(3) { yield() }
                    if (case.reason == BackupRestoreFailureReason.RestoreNotStarted) {
                        model.confirmRestore()
                        repeat(3) { yield() }
                    }
                    val failure = assertInstanceOf(BackupRestoreUiState.Failure::class.java, model.state.value)
                    assertEquals(case.reason, failure.reason)
                    val factory = mockk<BackupRestoreScreenModelFactory> { every { create() } returns model }
                    val scene = scene(factory, RecordingPicker(DesktopBackupFilePickerResult.Cancelled))
                    try {
                        val expected = case.resource.localized(locale)
                        val copy = render(scene)
                        assertTrue(expected in copy, "Missing '$expected': $copy")
                    } finally {
                        scene.close()
                    }
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun model(scope: kotlinx.coroutines.CoroutineScope) =
        BackupRestoreScreenModel(
            loadPreview = { BackupPreview(1, 0, 0, 0, 0, 0, 0) },
            restore = { _, _ -> error("restore must require confirmation") },
            scope = scope,
        )

    private fun failureModel(reason: BackupRestoreFailureReason, scope: kotlinx.coroutines.CoroutineScope) =
        BackupRestoreScreenModel(
            loadPreview = {
                when (reason) {
                    BackupRestoreFailureReason.EmptyBackup -> BackupPreview(0, 0, 0, 0, 0, 0, 0)
                    BackupRestoreFailureReason.UnsupportedVersion -> error("unsupported backup version")
                    BackupRestoreFailureReason.EmptyFile -> error("empty backup")
                    BackupRestoreFailureReason.MissingData -> error("missing manga payload")
                    BackupRestoreFailureReason.Corrupted -> error("corrupted backup")
                    BackupRestoreFailureReason.RestoreNotStarted -> BackupPreview(1, 0, 0, 0, 0, 0, 0)
                    is BackupRestoreFailureReason.Restore -> error("restore failures are not part of this test")
                }
            },
            restore = { _, _ -> TaskState.Idle },
            scope = scope,
        )

    private fun scene(
        factory: BackupRestoreScreenModelFactory,
        picker: DesktopBackupFilePicker,
        preferences: DesktopAppPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
    ): ImageComposeScene =
        ImageComposeScene(900, 2_000) {
            val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
                every { appPreferences } returns preferences
                every { backupRestoreScreenModelFactory } returns factory
                every { backupFilePicker } returns picker
            }
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(BackupSettingsScreen()) { CurrentScreen() }
            }
        }

    private suspend fun anchorFixture(scope: kotlinx.coroutines.CoroutineScope, height: Int): AnchorFixture {
        val store = InMemoryPreferenceStore()
        val downloadPreferences = DesktopDownloadPreferences(store)
        val picker = RecordingPicker(DesktopBackupFilePickerResult.Cancelled)
        val factory = mockk<BackupRestoreScreenModelFactory> { every { create() } returns model(scope) }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(store)
            every { this@mockk.downloadPreferences } returns downloadPreferences
            every { backupRestoreScreenModelFactory } returns factory
            every { backupFilePicker } returns picker
        }
        val scene = ImageComposeScene(900, height) {}
        lateinit var navigator: Navigator
        scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                Navigator(EmptyScreen()) { nav -> navigator = nav; CurrentScreen() }
            }
        }
        render(scene)
        return AnchorFixture(scene, navigator, downloadPreferences, picker)
    }

    private suspend fun open(fixture: AnchorFixture, title: String, route: KClass<out Screen>) {
        val result = result(title, route)
        assertEquals(title, result.anchorTitle)
        DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
        fixture.navigator.replace(result.route)
        render(fixture.scene)
        assertTrue(route.isInstance(fixture.navigator.lastItem))
    }

    private fun result(title: String, route: KClass<out Screen>) =
        DesktopSettingsCatalog.search(title).single { route.isInstance(it.route) && it.anchorTitle == title }

    private fun assertAnchor(scene: ImageComposeScene, title: String) {
        val highlighted = nodes(scene, true).single {
            it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
        }
        assertTrue(flatten(highlighted).any { title in text(it) })
        assertTrue(highlighted.boundsInRoot.height > 0f)
        assertTrue(scroll(scene).value() > 0f)
    }

    private suspend fun assertOneShot(fixture: AnchorFixture, screen: Screen) {
        fixture.navigator.replace(EmptyScreen())
        render(fixture.scene)
        fixture.navigator.replace(screen)
        render(fixture.scene)
        assertNoAnchor(fixture.scene)
    }

    private fun assertNoAnchor(scene: ImageComposeScene) {
        assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
        assertEquals(0f, scroll(scene).value())
    }

    private fun scroll(scene: ImageComposeScene) = nodes(scene, true)
        .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        .config[SemanticsProperties.VerticalScrollAxisRange]

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

    private fun text(node: SemanticsNode) = if (node.config.contains(SemanticsProperties.Text)) {
        node.config[SemanticsProperties.Text].map { it.text }
    } else {
        emptyList()
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private data class AnchorFixture(
        val scene: ImageComposeScene,
        val navigator: Navigator,
        val downloadPreferences: DesktopDownloadPreferences,
        val picker: RecordingPicker,
    ) : AutoCloseable {
        override fun close() = scene.close()
    }

    private class EmptyScreen : Screen {
        @androidx.compose.runtime.Composable
        override fun Content() = Unit
    }

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

    private data class FailureCase(
        val reason: BackupRestoreFailureReason,
        val resource: StringResource,
    )
}
