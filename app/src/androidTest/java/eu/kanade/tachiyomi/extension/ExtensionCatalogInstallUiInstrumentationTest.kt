package eu.kanade.tachiyomi.extension

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionCatalogInstallUiInstrumentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun v2CatalogInstallButtonDownloadsAuthenticatesAndRegistersWorkingSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = Injekt.get<ExtensionManager>()
        var model: ExtensionsScreenModel? = null
        try {
            ExtensionV16LifecycleInstrumentationTest().runLifecycle(
                BasePreferences.ExtensionInstaller.PRIVATE,
                installThroughCatalogUi = { packageName ->
                    // The default model discovers the persisted repository through the production HTTP adapter.
                    val screenModel = ExtensionsScreenModel().also { model = it }
                    screenModel.search("AEX-00")
                    val installLabel = context.stringResource(MR.strings.ext_install)
                    compose.setContent {
                        MaterialTheme {
                            Navigator(object : Screen() {
                                @Composable
                                override fun Content() {
                                    extensionsTab(screenModel).content(
                                        PaddingValues(),
                                        remember {
                                            SnackbarHostState()
                                        },
                                    )
                                }
                            })
                        }
                    }
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithContentDescription(installLabel).fetchSemanticsNodes().size == 1
                    }
                    val candidate = manager.availableExtensionsFlow.value.single { it.pkgName == packageName }
                    assertEquals(1.6, candidate.libVersion, 0.0)
                    assertTrue(candidate.downloadUrl!!.endsWith("/fixture.apk"))
                    assertTrue(manager.installedExtensionsFlow.value.none { it.pkgName == packageName })
                    compose.onNodeWithContentDescription(installLabel).performClick()
                },
            ) { installed, _ ->
                compose.waitUntil(15_000) {
                    model!!.state.value.items.values.flatten().any {
                        (it.extension as? Extension.Installed)?.pkgName == installed.pkgName
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithContentDescription(
                    context.stringResource(MR.strings.ext_install),
                ).assertDoesNotExist()
            }
        } finally {
            model?.onDispose()
        }
    }
}
