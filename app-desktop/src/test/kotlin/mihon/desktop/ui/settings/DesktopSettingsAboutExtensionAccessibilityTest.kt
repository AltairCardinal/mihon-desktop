package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.license.DependencyNoticeProvider
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.update.DesktopUpdateState
import mihon.domain.extensionrepo.interactor.CreateExtensionRepo
import mihon.domain.extensionrepo.interactor.DeleteExtensionRepo
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.ReplaceExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.license.model.LicenseNoticeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.i18n.MR
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class DesktopSettingsAboutExtensionAccessibilityTest {
    @Test
    fun `About actions expose labeled keyboard activation without changing immediate cache contract`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val paths = DesktopPlatformPaths.resolve("Linux", tempDir.toString(), emptyMap())
        val cached = paths.networkCacheDir.resolve("response.bin").apply { parentFile.mkdirs(); writeText("cached") }
        val update = mockk<DesktopUpdateScreenModel> {
            every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
            every { feedback } returns MutableStateFlow(null)
            every { intent(any()) } returns true
        }
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { extensionManager } returns DesktopExtensionManager()
            every { updateScreenModel } returns update
            every { dependencyNoticeProvider } returns DependencyNoticeProvider { LicenseNoticeResult.Success(emptyList()) }
        }
        val scene = ImageComposeScene(900, 1_600, coroutineContext = coroutineContext) {}
        lateinit var navigator: Navigator
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AboutScreen(paths)) { nav -> navigator = nav; CurrentScreen() }
                }
            }
            render(scene)
            activate(scene, MR.strings.check_for_updates.localized(), Key.Enter)
            verify(exactly = 1) { update.intent(DesktopUpdateIntent.CHECK) }

            activate(scene, MR.strings.licenses.localized(), Key.Spacebar)
            assertEquals(1, navigator.items.count { it is LicenseListScreen })
            navigator.pop()
            render(scene)

            activate(
                scene,
                MR.strings.desktop_advanced_clear_network_cache.localized(),
                Key.NumPadEnter,
                afterInitialKeyUp = { assertTrue(cached.exists()) },
                afterKeyDown = {
                    assertFalse(paths.networkCacheDir.exists())
                    paths.networkCacheDir.resolve("after-key-down.bin").apply { parentFile.mkdirs(); writeText("keep") }
                },
                afterFinalKeyUp = {
                    assertTrue(paths.networkCacheDir.resolve("after-key-down.bin").exists())
                    assertTrue(MR.strings.desktop_about_network_cache_cleared.localized() in sceneLabels(scene))
                },
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `About update dialog confirms dismisses and opens exactly once on physical keys`() = runBlocking {
        val intents = mutableListOf<DesktopUpdateIntent>()
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        try {
            scene.setContent {
                MaterialTheme {
                    AboutUpdateSection(
                        "1.0",
                        DesktopUpdatePresentation("ready", "Ready", setOf(DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE)),
                        null,
                        intents::add,
                    )
                }
            }
            render(scene)
            activate(scene, MR.strings.update_check_open.localized(), Key.Enter)
            activate(scene, MR.strings.action_install.localized(), Key.Spacebar)
            activate(scene, MR.strings.action_not_now.localized(), Key.NumPadEnter)
            assertEquals(
                listOf(DesktopUpdateIntent.MANUAL, DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE),
                intents,
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Extension repository card and delete dialog expose exact keyboard actions`() = runBlocking {
        val repository = FakeExtensionRepoRepository()
        val repo = ExtensionRepo("https://repo.example", "Repo", "R", "https://repo.example", "fingerprint")
        repository.insertRepo(repo.baseUrl, repo.name, repo.shortName, repo.website, repo.signingKeyFingerprint)
        val delete = mockk<DeleteExtensionRepo> { coEvery { await(any()) } returns Unit }
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        val scene = extensionScene(repository, delete = delete, clipboard = clipboard)
        try {
            activate(scene, MR.strings.action_open_in_browser.localized(), Key.Enter)
            activate(scene, MR.strings.action_copy_link.localized(), Key.Spacebar)
            verify(exactly = 1) { clipboard.setText(AnnotatedString("${repo.baseUrl}/index.min.json")) }

            activate(scene, MR.strings.action_delete_repo.localized(), Key.NumPadEnter)
            assertTrue(MR.strings.desktop_extension_repo_delete_consequence.localized() in sceneLabels(scene))
            activate(scene, MR.strings.action_cancel.localized(), Key.Spacebar)
            coVerify(exactly = 0) { delete.await(any()) }

            click(scene, MR.strings.action_delete_repo.localized())
            render(scene)
            activate(scene, MR.strings.action_remove.localized(), Key.Enter)
            coVerify(exactly = 1) { delete.await(repo.baseUrl) }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Extension add is disabled safely then conflict replace runs exactly once`() = runBlocking {
        val repository = FakeExtensionRepoRepository()
        val oldRepo = ExtensionRepo("https://old.example", "Old", null, "https://old.example", "same")
        val newRepo = ExtensionRepo("https://new.example", "New", null, "https://new.example", "same")
        val create = mockk<CreateExtensionRepo> {
            coEvery { await(newRepo.baseUrl) } returns CreateExtensionRepo.Result.DuplicateFingerprint(oldRepo, newRepo)
        }
        val replace = mockk<ReplaceExtensionRepo> { coEvery { await(any()) } returns Unit }
        val scene = extensionScene(repository, ExtensionRepoScreen(newRepo.baseUrl), create, replace = replace)
        try {
            setText(scene, "")
            render(scene)
            val disabled = action(scene, MR.strings.action_add.localized())
            assertTrue(disabled.config.contains(SemanticsProperties.Disabled))
            activate(scene, MR.strings.action_add.localized(), Key.Spacebar, expectFocus = false)
            coVerify(exactly = 0) { create.await(any()) }

            setText(scene, newRepo.baseUrl)
            render(scene)
            activate(scene, MR.strings.action_add.localized(), Key.Enter)
            coVerify(exactly = 1) { create.await(newRepo.baseUrl) }
            render(scene)
            activate(scene, MR.strings.action_replace_repo.localized(), Key.Spacebar)
            coVerify(exactly = 1) { replace.await(newRepo) }
        } finally {
            scene.close()
        }
    }

    private suspend fun extensionScene(
        repository: FakeExtensionRepoRepository,
        screen: ExtensionRepoScreen = ExtensionRepoScreen(),
        create: CreateExtensionRepo = mockk(relaxed = true),
        delete: DeleteExtensionRepo = mockk(relaxed = true),
        replace: ReplaceExtensionRepo = mockk(relaxed = true),
        clipboard: ClipboardManager = mockk(relaxed = true),
    ): ImageComposeScene {
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { getExtensionRepo } returns GetExtensionRepo(repository)
            every { createExtensionRepo } returns create
            every { deleteExtensionRepo } returns delete
            every { replaceExtensionRepo } returns replace
            every { updateExtensionRepo } returns mockk<UpdateExtensionRepo>(relaxed = true)
        }
        return ImageComposeScene(900, 1_200, coroutineContext = kotlinx.coroutines.currentCoroutineContext()).also { scene ->
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalClipboardManager provides clipboard,
                ) {
                    Navigator(screen) { CurrentScreen() }
                }
            }
            render(scene)
        }
    }

    private suspend fun activate(
        scene: ImageComposeScene,
        label: String,
        key: Key,
        afterInitialKeyUp: () -> Unit = {},
        afterKeyDown: () -> Unit = {},
        afterFinalKeyUp: () -> Unit = {},
        expectFocus: Boolean = true,
    ) {
        val node = action(scene, label)
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        if (expectFocus) {
            assertTrue(requireNotNull(node.config[SemanticsActions.RequestFocus].action).invoke())
            render(scene)
        }
        scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
        render(scene)
        afterInitialKeyUp()
        scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyDown))
        render(scene)
        afterKeyDown()
        scene.sendKeyEvent(composeKeyEvent(key, KeyEventType.KeyUp))
        render(scene)
        afterFinalKeyUp()
    }

    private fun action(scene: ImageComposeScene, label: String) = nodes(scene, true)
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .single { label in labels(it) }

    private fun click(scene: ImageComposeScene, label: String) {
        assertTrue(requireNotNull(action(scene, label).config[SemanticsActions.OnClick].action).invoke())
    }

    private fun setText(scene: ImageComposeScene, value: String) {
        val field = nodes(scene, true).single { it.config.contains(SemanticsActions.SetText) }
        assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
    }

    private fun labels(node: SemanticsNode) = flatten(node).flatMap {
        buildList {
            if (it.config.contains(SemanticsProperties.Text)) addAll(it.config[SemanticsProperties.Text].map { text -> text.text })
            if (it.config.contains(SemanticsProperties.ContentDescription)) addAll(it.config[SemanticsProperties.ContentDescription])
        }
    }

    private fun sceneLabels(scene: ImageComposeScene) = nodes(scene).flatMap(::labels)
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun composeKeyEvent(key: Key, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return androidx.compose.ui.input.key.KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(8) {
        scene.render()
        yield()
    }
}
