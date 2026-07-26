package mihon.desktop.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import mihon.desktop.APP_VERSION
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.license.DependencyNoticeProvider
import mihon.desktop.platform.DesktopPlatformPaths
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.update.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.domain.release.model.ReleaseAsset
import tachiyomi.domain.release.model.ReleaseChecksum
import tachiyomi.domain.release.model.ReleaseOs
import tachiyomi.domain.release.model.ReleasePackageType
import tachiyomi.domain.release.model.ReleaseTarget
import tachiyomi.domain.release.model.ReleaseVariant
import tachiyomi.domain.release.model.Release
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.DesktopPreferenceStore
import mihon.domain.license.model.DependencyNotice
import mihon.domain.license.model.LicenseNoticeFailureReason
import mihon.domain.license.model.LicenseNoticeResult
import tachiyomi.i18n.MR
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalComposeUiApi::class)
@Isolated
class AboutUpdateWiringTest {
    @Test
    fun `about routes real injected dependency notices to their first license content`(@TempDir tempDir: Path) = runBlocking {
        val context = initDesktopDIForTest(tempDir.toFile(), DesktopPreferenceStore())
        val dependencies = DesktopUiDependencies.fromInjekt()
        val notices = (dependencies.dependencyNoticeProvider.getNotices() as LicenseNoticeResult.Success).notices
        val coroutinesIndex = notices.indexOfFirst { "kotlinx-coroutines-core" in it.name }
        val scene = ImageComposeScene(900, 900, coroutineContext = coroutineContext) {}
        lateinit var navigator: Navigator
        try {
            assertEquals(192, notices.size)
            assertTrue(coroutinesIndex >= 0)
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(AboutScreen(DesktopPlatformPaths.resolve("Linux", tempDir.toString(), emptyMap()))) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            render(scene)
            click(scene, MR.strings.licenses.localized())
            render(scene)
            assertTrue(navigator.lastItem is LicenseListScreen)

            val list = nodes(scene, true).single { it.config.contains(SemanticsActions.ScrollToIndex) }
            assertTrue(requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(coroutinesIndex))
            render(scene)
            click(scene, notices[coroutinesIndex].name)
            render(scene)

            val detail = navigator.lastItem as LicenseDetailScreen
            assertEquals(notices[coroutinesIndex].name, detail.name)
            assertEquals(notices[coroutinesIndex].license, detail.license)
            assertTrue(requireNotNull(detail.license) in texts(scene))
        } finally {
            scene.close()
            context.closeAndJoin()
        }
    }

    @Test
    fun `license UI uses URI adapter and gives honest localized failure feedback`() = runBlocking {
        val notice = DependencyNotice("Website dependency", "https://example.com/library", "Full license content")
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true)
        every { dependencies.dependencyNoticeProvider } returns
            DependencyNoticeProvider { LicenseNoticeResult.Success(listOf(notice)) }
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val scene = ImageComposeScene(900, 700, coroutineContext = coroutineContext) {}
        lateinit var navigator: Navigator
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            scene.setContent {
                CompositionLocalProvider(
                    LocalDesktopUiDependencies provides dependencies,
                    LocalUriHandler provides uriHandler,
                ) {
                    Navigator(LicenseListScreen()) { nav ->
                        navigator = nav
                        CurrentScreen()
                    }
                }
            }
            render(scene)
            click(scene, notice.name)
            render(scene)
            assertTrue(notice.license in texts(scene))
            click(scene, MR.strings.website.localized())
            assertEquals(listOf(notice.website), opened)

            listOf(Locale.US, Locale.SIMPLIFIED_CHINESE).forEach { locale ->
                Locale.setDefault(locale)
                listOf<String?>(null, "  ").forEach { missingLicense ->
                    navigator.replace(LicenseDetailScreen("Missing content", "  ", missingLicense))
                    render(scene)
                    assertTrue(MR.strings.desktop_license_content_unavailable.localized(locale) in texts(scene))
                    assertFalse(MR.strings.website.localized(locale) in texts(scene))
                }

                every { dependencies.dependencyNoticeProvider } returns DependencyNoticeProvider {
                    LicenseNoticeResult.Failure(LicenseNoticeFailureReason.MALFORMED_METADATA)
                }
                navigator.replace(LicenseListScreen())
                render(scene)
                assertTrue(MR.strings.desktop_license_notices_unavailable.localized(locale) in texts(scene))
            }
        } finally {
            Locale.setDefault(previousLocale)
            scene.close()
        }
    }

    @Test
    fun `catalog result anchors once and preserves diagnostics action and ordering`(@TempDir tempDir: Path) = runBlocking {
        val paths = DesktopPlatformPaths.resolve("Linux", tempDir.toString(), emptyMap())
        paths.networkCacheDir.resolve("response.bin").writeBytes(ByteArray(1_536))
        val updateModel = mockk<DesktopUpdateScreenModel> {
            every { state } returns MutableStateFlow(DesktopUpdateState.Idle)
            every { feedback } returns MutableStateFlow(null)
        }
        val extensionManager = DesktopExtensionManager()
        val appPreferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val dependencies = mockk<DesktopUiDependencies>(relaxed = true) {
            every { this@mockk.extensionManager } returns extensionManager
            every { extensionPresentationService } returns extensionManager
            every { this@mockk.appPreferences } returns appPreferences
            every { updateScreenModel } returns updateModel
        }
        val scene = ImageComposeScene(900, 170) {}
        lateinit var navigator: Navigator
        try {
            scene.setContent {
                CompositionLocalProvider(LocalDesktopUiDependencies provides dependencies) {
                    Navigator(EmptyScreen()) { nav -> navigator = nav; CurrentScreen() }
                }
            }
            render(scene)
            val screens = DesktopSettingsCatalog.screens()
            val fixedMain = listOf(
                "AppearanceSettingsScreen", "LibrarySettingsScreen", "ReaderSettingsScreen",
                "DownloadSettingsScreen", "TrackingSettingsScreen", "ExtensionListScreen",
                "BackupSettingsScreen", "SecuritySettingsScreen", "AdvancedSettingsScreen",
            )
            assertEquals(fixedMain, screens.take(9).map { it.route::class.simpleName })
            assertEquals(listOf("GeneralSettingsScreen", "ExtensionRepoScreen", "AboutScreen"), screens.drop(9).map { it.route::class.simpleName })

            val title = MR.strings.desktop_about_app_data_directory.localized()
            val result = DesktopSettingsCatalog.search(title).single { it.route is AboutScreen && it.anchorTitle == title }
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            navigator.replace(AboutScreen(paths))
            render(scene)
            val highlighted = nodes(scene, true).single {
                it.config.contains(DesktopSettingsAnchorHighlighted) && it.config[DesktopSettingsAnchorHighlighted]
            }
            assertTrue(texts(highlighted).any { title in it })
            assertTrue(highlighted.boundsInRoot.height > 0f)
            assertTrue(scroll(scene).value() > 0f)

            click(scene, MR.strings.desktop_advanced_clear_network_cache.localized())
            render(scene)
            assertFalse(paths.networkCacheDir.exists())
            assertTrue(MR.strings.desktop_about_network_cache_cleared.localized() in texts(scene))

            navigator.replace(EmptyScreen())
            render(scene)
            navigator.replace(AboutScreen(paths))
            render(scene)
            assertNoAnchor(scene)
            DesktopSettingsAnchorOwner.publish(result.route, result.anchorTitle)
            navigator.replace(GeneralSettingsScreen())
            render(scene)
            assertNoAnchor(scene)
            DesktopSettingsAnchorOwner.publish(AboutScreen(paths), "missing-title")
            navigator.replace(AboutScreen(paths))
            render(scene)
            assertNoAnchor(scene)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `about renders full version and routes ready confirmation intents`() = runBlocking {
        val intents = mutableListOf<DesktopUpdateIntent>()
        val scene = ImageComposeScene(800, 600, coroutineContext = coroutineContext) {}
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            scene.setContent {
                MaterialTheme {
                    AboutUpdateSection(APP_VERSION, DesktopUpdateState.Idle.presentation(), "Could not open https://release", intents::add)
                }
            }
            scene.render()
            assertTrue("Version $APP_VERSION" in texts(scene))
            assertTrue("Could not open https://release" in texts(scene))
            click(scene, "Check for updates")
            assertEquals(listOf(DesktopUpdateIntent.CHECK), intents)

            scene.setContent {
                MaterialTheme {
                    AboutUpdateSection(
                        APP_VERSION,
                        DesktopUpdatePresentation("ready", "Ready to install", setOf(DesktopUpdateIntent.CONFIRM, DesktopUpdateIntent.DECLINE)),
                        null,
                        intents::add,
                    )
                }
            }
            scene.render()
            assertTrue("Ready to install" in texts(scene))
            click(scene, "Open on GitHub")
            assertEquals(DesktopUpdateIntent.MANUAL, intents.last())
            click(scene, "Install")
            assertEquals(DesktopUpdateIntent.CONFIRM, intents.last())
        } finally {
            Locale.setDefault(previousLocale)
            scene.close()
        }
    }

    @Test
    fun `screen model owns cancellation arguments and manual fallback intent`() {
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        ui.use {
            runBlocking(ui) {
                var calls = 0
                val entered = CompletableDeferred<Unit>()
                var arguments: tachiyomi.domain.release.interactor.GetApplicationRelease.Arguments? = null
                var opened: String? = null
                var openMode = 0
                val release = Release("0.12.0", "", "https://release", "")
                val controller = DesktopUpdateController(
                    { args ->
                        arguments = args
                        if (calls++ == 0) {
                            entered.complete(Unit)
                            runInterruptible { CountDownLatch(1).await() }
                            error("cancel expected")
                        } else {
                            tachiyomi.domain.release.interactor.GetApplicationRelease.Result.NewUpdate(release)
                        }
                    },
                    { _, _ -> ManualOnly(release.releaseLink) },
                    { _, _ -> InstallManualOnly },
                    { _, _ -> InstallCancelled },
                )
                val model = DesktopUpdateScreenModel(controller, this, openUrl = {
                    opened = it
                    if (openMode == 2) error("browser") else openMode == 1
                })
                assertTrue(model.intent(DesktopUpdateIntent.CHECK))
                entered.await()
                var uiAdvanced = false
                launch { uiAdvanced = true }.join()
                assertTrue(uiAdvanced)
                assertTrue(model.intent(DesktopUpdateIntent.CANCEL))
                model.state.first { it is DesktopUpdateState.Cancelled }
                assertTrue(model.intent(DesktopUpdateIntent.CHECK))
                model.state.first { it is DesktopUpdateState.UpdateAvailable }
                assertEquals("0.11.14", arguments?.versionName)
                assertEquals("mihonapp/mihon", arguments?.repository)
                model.intent(DesktopUpdateIntent.MANUAL)
                assertEquals(release.releaseLink, opened)
                assertTrue(model.feedback.value!!.contains(release.releaseLink))
                openMode = 1
                model.intent(DesktopUpdateIntent.MANUAL)
                assertEquals(null, model.feedback.value)
                openMode = 2
                model.intent(DesktopUpdateIntent.MANUAL)
                assertTrue(model.feedback.value!!.contains(release.releaseLink))
                val disposing = DesktopUpdateScreenModel(
                    DesktopUpdateController(
                        { awaitCancellation() },
                        { _, _ -> ManualOnly("") },
                        { _, _ -> InstallManualOnly },
                        { _, _ -> InstallCancelled },
                    ),
                    this,
                )
                assertTrue(disposing.intent(DesktopUpdateIntent.CHECK))
                disposing.state.first { it is DesktopUpdateState.Checking }
                disposing.dispose()
                disposing.state.first { it is DesktopUpdateState.Cancelled }
            }
        }
    }

    @Test
    fun `cancel kills the real verifier process before publishing Cancelled`(@TempDir tempDir: Path) = runBlocking {
        val target = ReleaseTarget(ReleaseOs.WINDOWS, "x86_64", ReleasePackageType.MSI, ReleaseVariant.STANDARD)
        val bytes = "artifact".toByteArray()
        val file = tempDir.resolve("mihon-desktop-windows-x86_64-v1.msi").also { Files.write(it, bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val asset = ReleaseAsset(file.fileName.toString(), target, ReleaseChecksum("sha256", hash))
        val download = VerifiedDownload(file, asset, hash, bytes.size.toLong())
        val release = Release("v1", "", "https://release", "https://download", asset)
        val processRunner = DesktopUpdateProcessRunner()
        val blockingRunner = DesktopUpdateCommandRunner { _, stdin ->
            processRunner.run(updaterTestCommand("block", tempDir), stdin)
        }
        val installer = DesktopUpdateInstaller(target, InstallerTrust(windowsPublisher = "CN=Mihon"), blockingRunner)
        val controller = DesktopUpdateController({ tachiyomi.domain.release.interactor.GetApplicationRelease.Result.NewUpdate(release) }, { _, _ -> download }, installer::prepare, installer::handoff)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = DesktopUpdateScreenModel(controller, scope)
        try {
            assertTrue(model.intent(DesktopUpdateIntent.CHECK))
            withTimeout(2_000) { model.state.first { it is DesktopUpdateState.UpdateAvailable } }
            assertTrue(model.intent(DesktopUpdateIntent.DOWNLOAD))
            val childPid = awaitUpdaterPid(tempDir)
            assertTrue(ProcessHandle.of(childPid).orElseThrow().isAlive)
            assertTrue(model.intent(DesktopUpdateIntent.CANCEL))
            withTimeout(2_000) { model.state.first { it is DesktopUpdateState.Cancelled } }
            assertTrue(ProcessHandle.of(childPid).map { !it.isAlive }.orElse(true))
            assertEquals(0, processRunner.activeReaderCount)
        } finally {
            model.dispose()
            scope.cancel()
        }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { it.config.contains(SemanticsActions.OnClick) && label in texts(it) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }

    private suspend fun render(scene: ImageComposeScene) = repeat(6) {
        scene.render()
        kotlinx.coroutines.yield()
    }

    private fun assertNoAnchor(scene: ImageComposeScene) {
        assertFalse(nodes(scene, true).any { it.config.contains(DesktopSettingsAnchorHighlighted) })
        assertEquals(0f, scroll(scene).value())
    }

    private fun scroll(scene: ImageComposeScene) = nodes(scene, true)
        .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        .config[SemanticsProperties.VerticalScrollAxisRange]
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap(::texts)
    private fun texts(node: SemanticsNode) = flatten(node).flatMap {
        if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].map { text -> text.text } else emptyList()
    }
    private fun nodes(scene: ImageComposeScene, unmerged: Boolean = false) = scene.semanticsOwners.flatMap {
        flatten(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode)
    }
    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private class EmptyScreen : Screen {
        @androidx.compose.runtime.Composable
        override fun Content() = Unit
    }
}
