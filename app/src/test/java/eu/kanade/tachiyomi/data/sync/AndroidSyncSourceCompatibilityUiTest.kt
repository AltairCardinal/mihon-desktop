package eu.kanade.tachiyomi.data.sync

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import mihon.data.sync.runtime.SyncPanel
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncRecoveryPlatformAction
import mihon.data.sync.runtime.SyncRecoveryPlatformRequest
import mihon.data.sync.runtime.SyncRecoveryPlatformResult
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w500dp-h900dp-mdpi")
class AndroidSyncSourceCompatibilityUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun `real recovery screen shows frozen source incompatibility without target or identity warning`() {
        val previous = Injekt
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            for (site in listOf("api", "field")) {
                Injekt = InjektScope(DefaultRegistrar())
                ShadowLog.clear()
                val source = mockk<Source>()
                every { source.id } returns 9L
                val owners = MutableStateFlow(listOf(installed(source, "1.6.2")))
                val base = SManga.create().apply {
                    url = "/remote"
                    title = "Recovered"
                }
                val returned = object : SManga by base {
                    override fun getGenres(): List<String>? = throw NoSuchMethodError("private future ABI field")
                }
                coEvery { source.getMangaDetails(any()) } coAnswers {
                    owners.value = listOf(installed(source, "9.9.9"))
                    if (site == "api") throw NoSuchMethodError("private future ABI API")
                    returned
                }
                val manager = mockk<SourceManager> {
                    every { get(9L) } returns source
                    every { getRegisteredSourceId(source) } returns 9L
                }
                val repository = mockk<MangaRepository> {
                    coEvery { getMangaByUrlAndSourceId("/private-work", 9L) } returns null
                }
                val network = mockk<NetworkToLocalManga>(relaxed = true)
                val actions = mutableListOf<SyncPanelAction>()
                val panelState = MutableStateFlow(
                    SyncPanelState(
                        recoveryPlatformRequest = SyncRecoveryPlatformRequest(
                            "compat-$site",
                            SyncRecoveryPlatformAction.READER,
                            objects = listOf(SyncObjectKey(SyncObjectType.MANGA, "9", originalUrl = "/private-work")),
                        ),
                    ),
                )
                val panel = mockk<SyncPanel> {
                    every { this@mockk.state } returns panelState
                    every { dispatch(any()) } answers {
                        actions += firstArg<SyncPanelAction>()
                        Unit
                    }
                }
                Injekt.addSingleton(mockk<SyncRuntime> { every { this@mockk.panel } returns panel })
                Injekt.addSingleton(manager)
                Injekt.addSingleton(repository)
                Injekt.addSingleton(network)
                Injekt.addSingleton(mockk<ExtensionManager> { every { installedExtensionsFlow } returns owners })
                activity.get().setContent {
                    MaterialTheme {
                        Navigator(AndroidSyncRecoveryScreen("compat-$site", SyncRecoveryPlatformAction.READER)) {
                            CurrentScreen()
                        }
                    }
                }
                val expected = activity.get().stringResource(MR.strings.source_error_extension_incompatible)
                compose.waitUntil(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Sync extension (1.6.2)").assertExists()
                coVerify(exactly = 0) { network.invoke(any<Manga>()) }
                val report = ShadowLog.getLogsForTag("MihonExtension").joinToString { it.msg }
                assertTrue(report, report.contains("package=sync.fixture"))
                assertTrue(report, report.contains("version=1.6.2"))
                assertTrue(report, report.contains("operation=DETAILS"))
                assertFalse(report, report.contains("9.9.9"))
                assertFalse(report, report.contains("private-work"))
                assertFalse(report, report.contains("private future ABI"))
                compose.onNodeWithTag("sync-native-recovery-return").performClick()
                activity.get().setContent { }
                compose.waitForIdle()
                val completed = actions.filterIsInstance<SyncPanelAction.RecoveryPlatformCompleted>().single()
                assertEquals(SyncRecoveryPlatformResult.Failed("TARGET_UNAVAILABLE"), completed.result)
            }
        } finally {
            activity.get().setContent { }
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }

    private fun installed(source: Source, version: String) = Extension.Installed(
        "Sync extension", "sync.fixture", version, 162L, 1.6, "en", false,
        null, listOf(source), null, isShared = false,
    )
}
