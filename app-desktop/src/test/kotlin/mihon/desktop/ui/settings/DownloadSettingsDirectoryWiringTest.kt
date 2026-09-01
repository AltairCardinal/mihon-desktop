package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.download.DesktopDownloadDirectoryController
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadQueuePort
import mihon.desktop.download.DownloadItem
import mihon.desktop.platform.DesktopDownloadDirectoryAvailability
import mihon.desktop.platform.DesktopDownloadDirectoryPolicy
import mihon.desktop.platform.DesktopDownloadDirectoryProbe
import mihon.desktop.platform.DesktopDownloadDirectoryProbeResult
import mihon.desktop.platform.DesktopDownloadDirectoryState
import mihon.desktop.platform.DesktopFilePicker
import mihon.desktop.platform.DesktopFilePickerRequest
import mihon.desktop.platform.DesktopFilePickerResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR
import java.io.File
import java.nio.file.Path
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class DownloadSettingsDirectoryWiringTest {

    @Test
    fun `screen probes frozen active and pending roots once without changing the saved selection`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val active = tempDir.resolve("active-custom").normalized()
        val pending = tempDir.resolve("pending-custom").normalized()
        val probe = RecordingAvailabilityProbe()
        val unavailable = DesktopDownloadDirectoryProbeResult(
            DesktopDownloadDirectoryAvailability.NOT_WRITABLE,
        )
        val fixture = fixture(
            tempDir = tempDir,
            configuredDirectory = active,
            pendingDirectory = pending,
            availabilityProbe = probe,
            screenProbeResult = unavailable,
        )
        try {
            val text = withTimeout(5_000) {
                while (probe.directories.size < 2) {
                    render(fixture.scene)
                    delay(10)
                }
                renderText(fixture.scene)
            }
            repeat(6) { render(fixture.scene) }

            assertEquals(listOf(active.toPath(), pending.toPath()), probe.directories)
            assertTrue(
                MR.strings.desktop_download_directory_current_unavailable.localized(
                    Locale.getDefault(),
                    MR.strings.desktop_download_directory_not_writable.localized(),
                ) in text,
            )
            assertTrue(
                MR.strings.desktop_download_directory_next_unavailable.localized(
                    Locale.getDefault(),
                    MR.strings.desktop_download_directory_not_writable.localized(),
                ) in text,
            )
            assertEquals(
                2,
                nodes(fixture.scene, true).count {
                    it.config.contains(SemanticsProperties.LiveRegion) &&
                        subtreeText(it).any { text ->
                            MR.strings.desktop_download_directory_not_writable.localized() in text
                        }
                },
            )
            assertEquals(active, fixture.controller.currentState().activeDirectory)
            assertEquals(pending, fixture.controller.currentState().pendingDirectory)
            assertTrue(fixture.directoryPreference.isSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `real screen renders frozen current default and valid pending directory without hot switch`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val fixture = fixture(tempDir)
        try {
            val initialText = renderText(fixture.scene)
            assertTrue(fixture.startupState.activeDirectory.path in initialText)
            assertTrue(fixture.startupState.defaultDirectory.path in initialText)

            fixture.picker.result = DesktopFilePickerResult.Cancelled
            click(fixture.scene, MR.strings.desktop_download_directory.localized())
            render(fixture.scene)
            assertEquals(fixture.startupState.defaultDirectory, fixture.picker.requests.single().initialDirectory)
            assertFalse(fixture.directoryPreference.isSet())
            assertEquals(fixture.startupState, fixture.controller.currentState())

            val selected = tempDir.resolve("selected-downloads")
            fixture.picker.result = DesktopFilePickerResult.Selected(selected)
            click(fixture.scene, MR.strings.desktop_download_directory.localized())
            val selectedText = awaitText(fixture.scene) { selected.normalized().path in it }
            val pending = fixture.controller.currentState()

            assertEquals(fixture.startupState.activeDirectory, pending.activeDirectory)
            assertEquals(selected.normalized(), pending.configuredDirectory)
            assertEquals(selected.normalized(), pending.pendingDirectory)
            assertTrue(pending.restartRequired)
            assertTrue(fixture.startupState.activeDirectory.path in selectedText)
            assertTrue(selected.normalized().path in selectedText)
            assertTrue(MR.strings.desktop_download_directory_restart_required.localized() in selectedText)
            assertTrue(MR.strings.desktop_download_directory_no_migration.localized() in selectedText)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `invalid selection open failures restore default and nonempty queue expose typed feedback`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val custom = tempDir.resolve("active-custom").apply { mkdirs() }.normalized()
        val fixture = fixture(tempDir, configuredDirectory = custom)
        fixture.queue.value = listOf(
            DownloadItem(
                sourceId = 7L,
                mangaTitle = "Manga",
                chapterName = "Chapter",
                chapterId = 8L,
            ),
        )
        try {
            assertTrue(MR.strings.desktop_download_directory_queue_warning.localized() in renderText(fixture.scene))

            fixture.picker.result = DesktopFilePickerResult.Selected(File("relative/downloads"))
            click(fixture.scene, MR.strings.desktop_download_directory.localized())
            val invalidText = awaitText(fixture.scene) {
                MR.strings.desktop_download_directory_invalid_syntax.localized() in it
            }
            assertTrue(MR.strings.desktop_download_directory_invalid_syntax.localized() in invalidText)
            assertTrue(fixture.directoryPreference.isSet())
            assertEquals(custom, fixture.controller.currentState().configuredDirectory)
            assertEquals(custom, fixture.controller.currentState().activeDirectory)

            fixture.opener.result = DesktopDirectoryOpenResult.Rejected
            click(fixture.scene, MR.strings.desktop_download_directory_open.localized())
            assertTrue(
                MR.strings.desktop_download_directory_open_rejected.localized() in awaitText(fixture.scene) {
                    MR.strings.desktop_download_directory_open_rejected.localized() in it
                },
            )
            assertEquals(listOf(custom), fixture.opener.directories)

            fixture.opener.result = DesktopDirectoryOpenResult.Failed(IllegalStateException("desktop unavailable"))
            click(fixture.scene, MR.strings.desktop_download_directory_open.localized())
            assertTrue(
                MR.strings.desktop_download_directory_open_failed.localized() in awaitText(fixture.scene) {
                    MR.strings.desktop_download_directory_open_failed.localized() in it
                },
            )
            assertEquals(listOf(custom, custom), fixture.opener.directories)

            click(fixture.scene, MR.strings.desktop_download_directory_restore_default.localized())
            val restoredText = awaitText(fixture.scene) { fixture.directoryPreference.isSet().not() }
            val restored = fixture.controller.currentState()
            assertEquals(custom, restored.activeDirectory)
            assertNull(restored.configuredDirectory)
            assertEquals(restored.defaultDirectory, restored.pendingDirectory)
            assertTrue(restored.restartRequired)
            assertFalse(fixture.directoryPreference.isSet())
            assertTrue(restored.defaultDirectory.path in restoredText)
            assertTrue(MR.strings.desktop_download_directory_restart_required.localized() in restoredText)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `directory entry and actions are keyboard accessible and both search terms anchor once`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val fixture = fixture(tempDir)
        try {
            render(fixture.scene)
            val directoryTitle = MR.strings.desktop_download_directory.localized()
            val directoryEntry = semanticBranch(fixture.scene, directoryTitle, Role.Button)
            assertTrue(directoryEntry.config.contains(SemanticsActions.OnClick))
            assertTrue(directoryEntry.config.contains(SemanticsActions.RequestFocus))
            listOf(
                MR.strings.desktop_download_directory_open.localized(),
                MR.strings.desktop_download_directory_restore_default.localized(),
            ).forEach { label ->
                val action = semanticBranch(fixture.scene, label, Role.Button)
                assertTrue(action.config.contains(SemanticsActions.OnClick), label)
                assertTrue(action.config.contains(SemanticsActions.RequestFocus), label)
            }

            assertTrue(requireNotNull(directoryEntry.config[SemanticsActions.RequestFocus].action).invoke())
            render(fixture.scene)
            fixture.scene.sendKeyEvent(composeKeyEvent(Key.Enter, KeyEventType.KeyDown))
            render(fixture.scene)
            assertEquals(1, fixture.picker.requests.size)
            fixture.scene.sendKeyEvent(composeKeyEvent(Key.Enter, KeyEventType.KeyUp))
            render(fixture.scene)
            assertEquals(1, fixture.picker.requests.size)
            fixture.scene.sendKeyEvent(composeKeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            render(fixture.scene)
            assertEquals(2, fixture.picker.requests.size)

            listOf(
                directoryTitle,
                MR.strings.pref_storage_location.localized(),
            ).forEach { query ->
                val result = DesktopSettingsCatalog.search(query).single {
                    it.route is DownloadSettingsScreen && it.anchorTitle == directoryTitle
                }
                assertEquals(directoryTitle, result.title)
            }
        } finally {
            fixture.close()
        }

        val anchorFixture = fixture(tempDir.resolve("anchor"))
        try {
            val title = MR.strings.desktop_download_directory.localized()
            val result = DesktopSettingsCatalog.search(title).single {
                it.route is DownloadSettingsScreen && it.anchorTitle == title
            }
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            anchorFixture.scene.setContent { content(anchorFixture.dependencies) }
            render(anchorFixture.scene)
            assertEquals(
                1,
                nodes(anchorFixture.scene, true).count {
                    it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
                },
            )
            anchorFixture.scene.setContent { content(anchorFixture.dependencies) }
            render(anchorFixture.scene)
            assertFalse(nodes(anchorFixture.scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
        } finally {
            anchorFixture.close()
        }
    }

    private fun fixture(
        tempDir: File,
        configuredDirectory: File? = null,
        pendingDirectory: File? = null,
        availabilityProbe: RecordingAvailabilityProbe? = null,
        screenProbeResult: DesktopDownloadDirectoryProbeResult? = null,
    ): Fixture {
        val defaultDirectory = tempDir.resolve("default-downloads").apply { mkdirs() }.normalized()
        val store = InMemoryPreferenceStore()
        val preferences = DesktopDownloadPreferences(store)
        val directoryPreference = preferences.downloadDirectory(
            defaultDirectory = defaultDirectory,
            policy = availabilityProbe?.let(::DesktopDownloadDirectoryPolicy) ?: DesktopDownloadDirectoryPolicy(),
        )
        configuredDirectory?.let { directoryPreference.save(it.path) }
        val startupState = directoryPreference.state()
        val controller = DesktopDownloadDirectoryController(directoryPreference, startupState)
        pendingDirectory?.let { controller.selectDirectory(it) }
        availabilityProbe?.apply {
            directories.clear()
            result = checkNotNull(screenProbeResult)
        }
        val picker = RecordingPicker()
        val opener = RecordingOpener()
        val queue = MutableStateFlow<List<DownloadItem>>(emptyList())
        val queuePort = object : DesktopDownloadQueuePort {
            override val queue = queue
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { downloadPreferences } returns preferences
            every { downloadDirectoryState } returns startupState
            every { downloadDirectoryController } returns controller
            every { filePicker } returns picker
            every { downloadDirectoryOpener } returns opener
            every { downloadQueuePort } returns queuePort
        }
        val scene = ImageComposeScene(1_000, 1_800) {}
        scene.setContent { content(dependencies) }
        return Fixture(
            scene = scene,
            dependencies = dependencies,
            directoryPreference = directoryPreference,
            startupState = startupState,
            controller = controller,
            picker = picker,
            opener = opener,
            queue = queue,
        )
    }

    @androidx.compose.runtime.Composable
    private fun content(dependencies: DesktopUiDependencies) {
        CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
            Navigator(DownloadSettingsScreen()) { CurrentScreen() }
        }
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(8) {
        scene.render()
        yield()
    }

    private suspend fun renderText(scene: ImageComposeScene): String {
        render(scene)
        return nodes(scene, true).flatMap(::subtreeText).joinToString("\n")
    }

    private suspend fun awaitText(scene: ImageComposeScene, predicate: (String) -> Boolean): String =
        withTimeout(5_000) {
            var text: String
            do {
                text = renderText(scene)
                if (!predicate(text)) delay(10)
            } while (!predicate(text))
            text
        }

    private fun click(scene: ImageComposeScene, label: String) {
        val action = semanticBranch(scene, label, Role.Button)
        assertTrue(requireNotNull(action.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun semanticBranch(scene: ImageComposeScene, label: String, role: Role): SemanticsNode =
        nodes(scene, true)
            .filter { it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == role }
            .single { label in subtreeText(it) }

    private fun subtreeText(node: SemanticsNode): List<String> = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) {
            it.config[SemanticsProperties.Text].map { text -> text.text }
        } else {
            emptyList()
        }
    }

    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false): List<SemanticsNode> =
        scene.semanticsOwners.flatMap {
            flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
        }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return androidx.compose.ui.input.key.KeyEvent(native)
    }

    private fun File.normalized(): File = toPath().toAbsolutePath().normalize().toFile()

    private class RecordingPicker : DesktopFilePicker {
        var result: DesktopFilePickerResult = DesktopFilePickerResult.Cancelled
        val requests = mutableListOf<DesktopFilePickerRequest>()

        override suspend fun choose(request: DesktopFilePickerRequest): DesktopFilePickerResult {
            requests += request
            return result
        }
    }

    private class RecordingOpener : DesktopDirectoryOpenPort {
        var result: DesktopDirectoryOpenResult = DesktopDirectoryOpenResult.Opened
        val directories = mutableListOf<File>()

        override fun open(directory: File): DesktopDirectoryOpenResult {
            directories += directory
            return result
        }
    }

    private class RecordingAvailabilityProbe : DesktopDownloadDirectoryProbe {
        var result = DesktopDownloadDirectoryProbeResult.available()
        val directories = mutableListOf<Path>()

        override fun inspect(directory: Path): DesktopDownloadDirectoryProbeResult {
            directories.add(directory)
            return result
        }
    }

    private data class Fixture(
        val scene: ImageComposeScene,
        val dependencies: DesktopUiDependencies,
        val directoryPreference: mihon.desktop.download.DesktopDownloadDirectoryPreference,
        val startupState: DesktopDownloadDirectoryState,
        val controller: DesktopDownloadDirectoryController,
        val picker: RecordingPicker,
        val opener: RecordingOpener,
        val queue: MutableStateFlow<List<DownloadItem>>,
    ) : AutoCloseable {
        override fun close() = scene.close()
    }
}
