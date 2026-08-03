package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.backup.BackupPreview
import mihon.desktop.backup.BackupRestoreScreenModelFactory
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.reader.NextChapterPrefetchMode
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.platform.DesktopBackupFilePicker
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@org.junit.jupiter.api.parallel.Isolated
class DesktopSettingsContentAccessibilityTest {
    @Test
    fun `Backup production button activates once on key down and respects disabled state`() = runBlocking {
        val scene = ImageComposeScene(700, 200, coroutineContext = coroutineContext) {}
        val enabled = mutableStateOf(true)
        var calls = 0
        try {
            scene.setContent {
                DesktopSettingsButton(onClick = { calls++ }, enabled = enabled.value) {
                    Text("Backup")
                }
            }
            render(scene)
            val action = nodes(scene, true).single { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(action.config[SemanticsActions.RequestFocus].action).invoke())
            render(scene)
            listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar).forEach { key ->
                calls = 0
                scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                render(scene)
                assertEquals(0, calls)
                scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
                render(scene)
                assertEquals(1, calls)
                scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                render(scene)
                assertEquals(1, calls)
            }
            enabled.value = false
            calls = 0
            render(scene)
            assertTrue(nodes(scene, true).any { it.config.contains(SemanticsProperties.Disabled) })
            scene.sendKeyEvent(composeKeyEvent(Key.Enter, KeyEventType.KeyDown))
            render(scene)
            assertEquals(0, calls)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Library checkbox production row activates once on key down`() = runBlocking {
        val scene = ImageComposeScene(700, 200, coroutineContext = coroutineContext) {}
        val checked = mutableStateOf(false)
        var calls = 0
        try {
            scene.setContent {
                CheckboxSettingsRow("Favorites", checked.value) {
                    checked.value = it
                    calls++
                }
            }
            render(scene)
            val action = nodes(scene, true).single { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(action.config[SemanticsActions.RequestFocus].action).invoke())
            render(scene)
            assertTrue(nodes(scene, true).single { it.config.contains(SemanticsActions.OnClick) }.config[SemanticsProperties.Focused])
            listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar).forEach { key ->
                checked.value = false
                calls = 0
                render(scene)
                scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                render(scene)
                assertEquals(0, calls)
                scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
                render(scene)
                assertEquals(1, calls)
                assertTrue(checked.value)
                scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
                render(scene)
                assertEquals(1, calls)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Reader Library Download and Backup controls expose one labeled action with role and state`() = runBlocking {
        val store = InMemoryPreferenceStore()
        val readerPreferences = ReaderPreferences(store)
        val dependencies = dependencies(store, this, readerPreferences)
        withScene(ReaderSettingsScreen(), dependencies) { scene ->
            assertToggle(scene, MR.strings.desktop_reader_pager_mode.localized(), Role.RadioButton, selected = true)
            assertToggle(scene, MR.strings.desktop_reader_rtl.localized(), Role.Switch, toggled = ToggleableState.Off)
            assertToggle(
                scene,
                MR.strings.desktop_reader_prefetch_full_next_chapter.localized(),
                Role.RadioButton,
                selected = true,
            )
            requireNotNull(
                semanticBranch(
                    scene,
                    MR.strings.desktop_reader_prefetch_first_viewport.localized(),
                    Role.RadioButton,
                ).config[SemanticsActions.OnClick].action,
            ).invoke()
            render(scene)
            assertEquals(NextChapterPrefetchMode.FIRST_VIEWPORT, readerPreferences.nextChapterPrefetchMode)
        }
        withScene(LibrarySettingsScreen(), dependencies, 1_600) { scene ->
            assertToggle(scene, MR.strings.update_never.localized(), Role.RadioButton, selected = true)
            assertToggle(
                scene,
                MR.strings.pref_hide_missing_chapter_indicators.localized(),
                Role.Checkbox,
                toggled = ToggleableState.Off,
            )
            assertToggle(scene, "Favorites", Role.Checkbox, toggled = ToggleableState.Off)
        }
        withScene(DownloadSettingsScreen(), dependencies, 1_400) { scene ->
            assertToggle(scene, MR.strings.save_chapter_as_cbz.localized(), Role.Switch, toggled = ToggleableState.Off)
            assertToggle(scene, MR.strings.desktop_download_sequential.localized(), Role.RadioButton, selected = true)
        }
        withScene(BackupSettingsScreen(), dependencies, 2_000) { scene ->
            assertAction(scene, MR.strings.pref_create_backup.localized(), Role.Button)
            assertAction(scene, MR.strings.file_select_backup.localized(), Role.Button)
            assertToggle(scene, MR.strings.off.localized(), Role.RadioButton, selected = true)
        }
    }

    @Test
    fun `highlighted content anchor remains one shot and focus is an independent enhancement`() = runBlocking {
        val dependencies = dependencies(InMemoryPreferenceStore(), this)
        DesktopSettingsAnchorOwner.publish(DownloadSettingsScreen(), MR.strings.pref_download_new.localized())
        withScene(DownloadSettingsScreen(), dependencies, 300) { scene ->
            val highlighted = nodes(scene, true).single {
                it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
            }
            assertTrue(MR.strings.pref_download_new.localized() in subtreeText(highlighted))
            val action = semanticBranch(scene, MR.strings.pref_download_new.localized(), Role.Switch)
            assertFalse(action.config.contains(SemanticsProperties.Focused) && action.config[SemanticsProperties.Focused])
            assertTrue(requireNotNull(action.config[SemanticsActions.RequestFocus].action).invoke())
            render(scene)
            assertTrue(semanticBranch(scene, MR.strings.pref_download_new.localized(), Role.Switch).config[SemanticsProperties.Focused])
        }
        withScene(DownloadSettingsScreen(), dependencies, 300) { scene ->
            assertTrue(nodes(scene, true).none { it.config.contains(DesktopSettingsAnchorHighlighted) })
        }
    }

    private fun dependencies(
        store: InMemoryPreferenceStore,
        scope: kotlinx.coroutines.CoroutineScope,
        readerPreferences: ReaderPreferences = ReaderPreferences(store),
    ): DesktopUiDependencies {
        val categories = mockk<GetCategories> { coEvery { await() } returns listOf(Category(1, "Favorites", 0, 0)) }
        val model = BackupRestoreScreenModel(
            loadPreview = { BackupPreview(1, 0, 0, 0, 0, 0, 0) },
            restore = { _, _ -> error("not used") },
            scope = scope,
        )
        val factory = mockk<BackupRestoreScreenModelFactory> {
            every { create() } returns model
            coEvery { createBackup(any()) } returns java.io.File("backup.tachibk")
        }
        return mockk(relaxed = true) {
            every { appPreferences } returns DesktopAppPreferences(store)
            every { this@mockk.readerPreferences } returns readerPreferences
            every { downloadPreferences } returns DesktopDownloadPreferences(store)
            every { getCategories } returns categories
            every { backupRestoreScreenModelFactory } returns factory
            every { backupFilePicker } returns mockk<DesktopBackupFilePicker>(relaxed = true)
        }
    }

    private suspend fun withScene(
        screen: Screen,
        dependencies: DesktopUiDependencies,
        height: Int = 1_000,
        block: suspend (ImageComposeScene) -> Unit,
    ) {
        val scene = ImageComposeScene(1_000, height, coroutineContext = kotlinx.coroutines.currentCoroutineContext()) {}
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            render(scene)
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun assertAction(scene: ImageComposeScene, label: String, role: Role) {
        val branch = semanticBranch(scene, label, role)
        assertEquals(1, flatten(branch).count { it.config.contains(SemanticsActions.OnClick) }, label)
        assertTrue(branch.config.contains(SemanticsActions.RequestFocus), label)
    }

    private fun assertToggle(
        scene: ImageComposeScene,
        label: String,
        role: Role,
        selected: Boolean? = null,
        toggled: ToggleableState? = null,
    ) {
        val branch = semanticBranch(scene, label, role)
        assertAction(scene, label, role)
        selected?.let { assertEquals(it, branch.config[SemanticsProperties.Selected]) }
        toggled?.let { assertEquals(it, branch.config[SemanticsProperties.ToggleableState]) }
        assertTrue(branch.config.contains(SemanticsProperties.StateDescription))
    }

    private fun semanticBranch(scene: ImageComposeScene, label: String, role: Role) = nodes(scene, true)
        .filter { it.config.contains(SemanticsProperties.Role) && it.config[SemanticsProperties.Role] == role }
        .single { label in subtreeText(it) }

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return androidx.compose.ui.input.key.KeyEvent(native)
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(6) {
        scene.render()
        yield()
    }

    private fun subtreeText(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean) =
        scene.semanticsOwners.flatMap { flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
}
