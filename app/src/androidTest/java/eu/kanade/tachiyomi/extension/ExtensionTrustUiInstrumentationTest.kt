package eu.kanade.tachiyomi.extension

import android.view.KeyEvent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ExtensionTrustUiInstrumentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun trustDialogDismissalAndConfirmationControlRealInstalledSourceRegistration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = Injekt.get<ExtensionManager>()
        val sources = Injekt.get<SourceManager>()
        val trustPreference = Injekt.get<SourcePreferences>().trustedExtensions()
        val priorTrust = trustPreference.get()
        val wasTrustSet = trustPreference.isSet()
        try {
            ExtensionV16LifecycleInstrumentationTest().runLifecycle(BasePreferences.ExtensionInstaller.PRIVATE) {
                    installed,
                    repoUrl,
                ->
                val ids = installed.sources.map { it.id }.toSet()
                Injekt.get<ExtensionRepoRepository>().deleteRepo(repoUrl)
                trustPreference.set(trustPreference.get().filterNot { it.startsWith("${installed.pkgName}:") }.toSet())
                // Real app broadcast -> loader -> untrusted manager state, not a fake source or injected event result.
                ExtensionInstallReceiver.notifyReplaced(context, installed.pkgName)
                withTimeout(15_000) {
                    manager.untrustedExtensionsFlow.first { list -> list.any { it.pkgName == installed.pkgName } }
                    sources.querySources.first { list -> list.none { it.id in ids } }
                }
                val model = ExtensionsScreenModel()
                val trustLabel = context.stringResource(MR.strings.ext_trust)
                try {
                    compose.setContent {
                        MaterialTheme {
                            Navigator(object : Screen() {
                                @Composable
                                override fun Content() {
                                    extensionsTab(model).content(PaddingValues(), remember { SnackbarHostState() })
                                }
                            })
                        }
                    }
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithContentDescription(trustLabel).fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithContentDescription(trustLabel).performClick()
                    compose.onNode(isDialog()).assertExists()
                    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    compose.waitForIdle()
                    compose.onNode(isDialog()).assertDoesNotExist()
                    assertTrue(manager.untrustedExtensionsFlow.value.any { it.pkgName == installed.pkgName })
                    assertTrue(trustPreference.get().none { it.startsWith("${installed.pkgName}:") })
                    assertTrue(sources.querySources.first().none { it.id in ids })

                    compose.onNodeWithContentDescription(trustLabel).performClick()
                    compose.onNodeWithText(trustLabel).performClick()
                    val trusted = withTimeout(15_000) {
                        manager.installedExtensionsFlow.first { list -> list.any { it.pkgName == installed.pkgName } }
                            .single { it.pkgName == installed.pkgName }
                    }
                    withTimeout(15_000) {
                        sources.querySources.first { list -> list.map { it.id }.containsAll(ids) }
                    }
                    assertEquals(ids, trusted.sources.map { it.id }.toSet())
                    trusted.sources.forEach { assertSame(it, sources.get(it.id)) }
                    assertTrue(manager.untrustedExtensionsFlow.value.none { it.pkgName == installed.pkgName })
                    val trustedPrefix = "${installed.pkgName}:${installed.versionCode}:"
                    assertTrue(trustPreference.get().any { it.startsWith(trustedPrefix) })
                    compose.waitForIdle()
                    compose.onNode(isDialog()).assertDoesNotExist()
                    compose.onNodeWithContentDescription(trustLabel).assertDoesNotExist()
                } finally {
                    model.onDispose()
                }
            }
        } finally {
            if (wasTrustSet) trustPreference.set(priorTrust) else trustPreference.delete()
        }
    }
}
