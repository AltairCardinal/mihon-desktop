package eu.kanade.tachiyomi.extension

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import kotlinx.coroutines.runBlocking
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import org.junit.Rule
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ExtensionInstallFailureUiInstrumentationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun catalogInstallShowsSafePackageFailureAndRetryInstallsOriginalSignedApk() {
        verify(
            ExtensionV16LifecycleInstrumentationTest.V16_FIXTURE,
            "corrupt APK /private/path?token=secret".toByteArray(Charsets.UTF_8),
            MR.strings.extension_install_error_package,
        )
    }

    @Test
    fun catalogInstallRejectsAnotherSignerWithoutLoginOrTrustBypassAndRetryRecovers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val conflicting = instrumentation.context.assets.open("aex04-mangadex-conflicting-signer.apk")
            .use { it.readBytes() }
        verify(
            LifecycleApkFixture(
                "keiyoushi-mangadex-1.6.0.apk",
                "35d220b64162cb9409da47af81fbb09ae96f170ed77eaa0ffb440add65f92f35",
                "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                "eu.kanade.tachiyomi.extension.all.mangadex",
                "MangaDex",
                "1.6.0",
                106000,
                1.6,
            ),
            conflicting,
            MR.strings.extension_install_error_verification,
            trustConflictingSigner = true,
        )
    }

    private fun verify(
        fixture: LifecycleApkFixture,
        invalid: ByteArray,
        message: StringResource,
        trustConflictingSigner: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broken = AtomicBoolean(true)
        var model: ExtensionsScreenModel? = null
        val repositories = Injekt.get<ExtensionRepoRepository>()
        val otherRepository = "http://127.0.0.1/aex04-other-${UUID.randomUUID()}"
        try {
            ExtensionV16LifecycleInstrumentationTest().runLifecycle(
                BasePreferences.ExtensionInstaller.PRIVATE,
                fixture = fixture,
                transformDownload = { if (broken.get()) invalid else it },
                installThroughCatalogUi = {
                    val screenModel = ExtensionsScreenModel().also { model = it }
                    screenModel.search(fixture.name)
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
                    val install = context.stringResource(MR.strings.ext_install)
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithContentDescription(install).fetchSemanticsNodes().size == 1
                    }
                    // Add only after discovery: loader-global trust must not authorize this catalog's APK.
                    if (trustConflictingSigner) {
                        repositories.insertRepo(
                            ExtensionRepo(
                                otherRepository,
                                "Other trusted repository",
                                null,
                                otherRepository,
                                ExtensionV16LifecycleInstrumentationTest.V16_FIXTURE.signer,
                            ),
                        )
                    }
                    compose.onNodeWithContentDescription(install).performClick()
                    // Fixed safe copy, never exception paths, URLs or credentials.
                    val failure = context.stringResource(message)
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(failure).fetchSemanticsNodes().size == 1
                    }
                    compose.onNodeWithText("private/path", substring = true).assertDoesNotExist()
                    compose.onNodeWithText("token=secret", substring = true).assertDoesNotExist()
                    compose.onNodeWithContentDescription(
                        context.stringResource(MR.strings.ext_trust),
                    ).assertDoesNotExist()
                    broken.set(false)
                    compose.onNodeWithContentDescription(context.stringResource(MR.strings.action_retry)).performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodesWithText(failure).fetchSemanticsNodes().isEmpty()
                    }
                },
            ) { _, _ ->
                compose.waitForIdle()
                compose.onNodeWithContentDescription(
                    context.stringResource(MR.strings.action_retry),
                ).assertDoesNotExist()
            }
        } finally {
            model?.onDispose()
            runBlocking { repositories.deleteRepo(otherRepository) }
        }
    }
}
