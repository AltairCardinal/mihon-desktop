package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.ExtensionLoadFailure
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.test.ScreenModelTestHost
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import mihon.domain.error.AppError
import mihon.domain.extension.suggestion.ExtensionInventory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
class AndroidExtensionLoadFailureUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun `initial failed package banner retries through real screen model and manager`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val host = ScreenModelTestHost()
        val calls = AtomicInteger()
        val failure = ExtensionLoadFailure(
            "Broken extension",
            "fixture.broken",
            "1.6.2",
            106002,
            true,
            SourceOperationPhase.FACTORY,
            AppError.ExtensionIncompatible(),
        )
        val preferences = SourcePreferences(AndroidPreferenceStore(activity.get()))
        preferences.enabledLanguages().set(setOf("en"))
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(SecurityPreferences(AndroidPreferenceStore(activity.get())))
        val recovered = Extension.Installed(
            failure.name, failure.pkgName, failure.versionName, failure.versionCode, 1.6,
            "en", false, null, emptyList(), null, isShared = true,
        )
        fun catalog(pkgName: String, name: String) = Extension.Available(
            name, pkgName, "1.6.3", 106003, 1.6, "en", false,
            listOf(Extension.Available.Source(42L, "en", name, "https://fixture.invalid")),
            "fixture.apk", "https://fixture.invalid/icon.png", "https://fixture.invalid",
        )
        val samePackageCatalog = catalog(failure.pkgName, "Broken catalog entry")
            .copy(versionName = failure.versionName, versionCode = failure.versionCode)
        val healthyCatalog = catalog("fixture.healthy", "Healthy catalog entry")
        val candidates = MutableStateFlow(listOf(samePackageCatalog, healthyCatalog))
        val updateRequested = AtomicReference<Extension.Available?>()
        val installer = mockk<ExtensionInstaller>(relaxed = true) {
            every { installErrors } returns MutableStateFlow(emptyMap())
            every { downloadAndInstall(any(), any(), any(), any()) } answers {
                updateRequested.set(secondArg())
                val finish = arg<() -> Unit>(3)
                flow {
                    try {
                        emit(InstallStep.Error)
                    } finally {
                        finish()
                    }
                }
            }
        }
        try {
            val manager = ExtensionManager(
                context = activity.get(), preferences = preferences, trustExtension = mockk(relaxed = true),
                installedExtensionsLoader = { listOf(LoadResult.Failure(failure)) },
                extensionLoader = { _, _ ->
                    calls.incrementAndGet()
                    LoadResult.Success(recovered)
                },
                installerFactory = { installer }, installReceiverRegistrar = {}, scope = scope,
                inventoryProvider = { ExtensionInventory(initialized = true) },
                availableExtensionsProvider = { candidates.value },
            )
            val model = host.create {
                ExtensionsScreenModel(
                    preferences = preferences,
                    basePreferences = mockk<BasePreferences> {
                        every { extensionInstaller() } returns mockk {
                            every { changes() } returns flowOf(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER)
                        }
                    },
                    extensionManager = manager,
                    getExtensions = GetExtensionsByType(preferences, manager),
                    context = activity.get().application,
                )
            }
            val screen = object : Screen {
                override val key = "extension-load-failure-ui"

                @Composable
                override fun Content() {
                    extensionsTab(model).content(PaddingValues(), remember { SnackbarHostState() })
                }
            }
            activity.get().setContent { MaterialTheme { Navigator(screen) { screen.Content() } } }
            compose.waitForIdle()
            compose.waitUntil(5_000) {
                !model.state.value.isLoading && model.state.value.loadFailures[failure.pkgName] == failure
            }
            compose.onNodeWithText("Broken extension · 1.6.2").assertExists()
            assertEquals(0, calls.get())
            scope.launch { manager.findAvailableExtensions() }
            compose.waitUntil(5_000) {
                model.state.value.items.values.flatten().any { it.extension.pkgName == healthyCatalog.pkgName }
            }
            val available = model.state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.Available>()
            assertEquals(listOf(healthyCatalog.pkgName), available.map { it.pkgName })
            compose.onNodeWithText("Broken extension · 1.6.2").assertExists()
            val newer = samePackageCatalog.copy(versionName = "1.6.3", versionCode = 106003)
            candidates.value = listOf(newer, healthyCatalog)
            scope.launch { manager.findAvailableExtensions() }
            compose.waitUntil(5_000) {
                model.state.value.items.values.flatten().any { it.extension == newer }
            }
            val updateLabel = activity.get().stringResource(MR.strings.ext_update)
            compose.onNodeWithContentDescription(updateLabel).performClick()
            compose.waitUntil(5_000) { updateRequested.get() != null }
            assertEquals(newer, updateRequested.get())
            assertEquals(failure, manager.loadFailures.value[failure.pkgName])
            compose.onNodeWithTag("extension-load-retry-${failure.pkgName}").performClick()
            compose.waitForIdle()
            compose.waitUntil(5_000) { manager.loadFailures.value.isEmpty() }
            compose.onNodeWithText("Broken extension · 1.6.2").assertDoesNotExist()
            assertEquals(1, calls.get())
            assertEquals(recovered, manager.installedExtensionsFlow.value.single())
        } finally {
            host.close()
            scope.cancel()
            activity.pause().stop().destroy()
            Injekt = previous
        }
    }
}
