package mihon.desktop.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyLocation
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.desktop.DesktopUiDependencies
import mihon.desktop.LocalDesktopUiDependencies
import mihon.desktop.di.initDesktopDIForTest
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.LibraryUpdateScheduler
import mihon.desktop.settings.DesktopLibraryCategoryPolicy
import mihon.desktop.test.http.testHttpServer
import mihon.desktop.ui.library.MangaDetailScreen
import mihon.desktop.ui.theme.DesktopTheme
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
@Isolated
class LibrarySettingsPolicyInteractionTest {
    @Test
    fun `actual Root two wheel segments refresh the complete category beyond visible search`(
        @TempDir root: File,
    ) = runBlocking {
        val updated = mutableListOf<Long>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        withSettings(root, fontScale = 2f, updateManga = { manga ->
            updated += manga.id
            release.await()
            LibraryUpdateChecker.UpdateResult(0)
        }) { scene ->
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Wheel current", 0, 0))
            val category = categories.getAll().single { it.name == "Wheel current" }
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf("Visible", "Hidden", "Outside").map { title ->
                    Manga.create().copy(source = 123, url = "/$title", title = title, favorite = true)
                },
            )
            manga.take(2).forEach {
                repository.updateAtomically(LibraryMembershipUpdate(it.id, true, 100, listOf(category.id)))
            }
            try {
                scene.mountRoot()
                scene.renderUntil { scene.rootModel?.state?.value?.allItems?.size == 3 }
                val model = requireNotNull(scene.rootModel)
                scene.renderUntil { model.state.value.categories.any { it.id == category.id } }
                model.setSelectedCategoryIndex(model.state.value.categories.indexOfFirst { it.id == category.id })
                model.setSearchQuery("Visible")
                scene.renderUntil {
                    model.visibleItems().size == 1 && "Visible" in scene.text() &&
                        "Hidden" !in scene.text() && "Outside" !in scene.text() &&
                        scene.activeFocused()?.config?.getOrElse(SemanticsProperties.EditableText) {
                            androidx.compose.ui.text.AnnotatedString("")
                        }?.text == "Visible"
                }
                scene.wheel(androidx.compose.ui.geometry.Offset(450f, 420f), -4f)
                delay(200)
                scene.renderUntil { true }
                assertTrue(
                    scene.nodes().any {
                        MR.strings.desktop_refresh_armed.localized(java.util.Locale.getDefault(), category.name) in
                            scene.labels(it)
                    },
                    "Actual Root wheel must expose the current category armed hint",
                )
                assertTrue(updated.isEmpty(), "The first segment never refreshes")
                delay(500)
                scene.wheel(androidx.compose.ui.geometry.Offset(450f, 420f), -3f)
                scene.renderUntil { updated.isNotEmpty() }
                assertTrue(model.state.value.isUpdating)
                scene.wheel(androidx.compose.ui.geometry.Offset(450f, 420f), -10f)
                release.complete(Unit)
                scene.renderUntil { updated.size == 2 && !model.state.value.isUpdating }
                assertEquals(manga.take(2).map { it.id }.toSet(), updated.toSet())
                val context = requireNotNull(Injekt.get<LibraryUpdateScheduler>().taskSnapshot()?.libraryUpdate)
                assertEquals(mihon.desktop.task.LibraryUpdateScope.CATEGORY, context.scope)
                assertEquals(category.id, context.categoryId)
                assertEquals(2, updated.size, "Running wheel input cannot request another occurrence")
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                    eu.kanade.domain.ui.model.ThemeMode.LIGHT,
                )
                scene.resize(320, 680)
                scene.renderUntil { true }
                delay(900)
                scene.wheel(androidx.compose.ui.geometry.Offset(150f, 420f), -6f)
                val hint = MR.strings.desktop_refresh_armed.localized(java.util.Locale.getDefault(), category.name)
                scene.renderUntil { hint in scene.text() }
                assertTrue(
                    scene.nodes().any {
                        hint in scene.labels(it) && it.boundsInRoot != Rect.Zero &&
                            it.boundsInRoot.right <= 320f &&
                            it.boundsInRoot.bottom <= 680f
                    },
                )
                scene.savePng(
                    File(
                        System.getenv("MIHON_RI17_VISUAL_DIR") ?: File(root, "visual").absolutePath,
                        "ri17-library-light-320-font200.png",
                    ),
                )
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `Root wheel intent is revoked by actual menu selection query and focus owners`(
        @TempDir root: File,
    ) = runBlocking {
        for (boundary in listOf("menu", "selection", "query", "focus")) {
            var requests = 0
            withSettings(File(root, boundary), updateManga = {
                requests++
                LibraryUpdateChecker.UpdateResult(0)
            }) { scene ->
                scene.mountRoot()
                scene.renderUntil { scene.rootModel?.state?.value?.isLoading == false }
                val point = androidx.compose.ui.geometry.Offset(450f, 420f)
                scene.wheel(point, -4f)
                scene.renderUntil { true }
                assertFalse(scene.text().any { it.startsWith("Scroll up again") }, "Empty content cannot arm")
                Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(Manga.create().copy(source = 123, url = "/revoke", title = "Visible", favorite = true)),
                )
                scene.renderUntil { scene.rootModel?.state?.value?.allItems?.size == 1 && "Visible" in scene.text() }
                val model = requireNotNull(scene.rootModel)
                fun armed() = scene.text().any { it.startsWith("Scroll up again") }
                scene.wheel(point, -4f)
                scene.renderUntil("Original intent is armed before $boundary") { armed() }
                when (boundary) {
                    "menu" -> {
                        scene.click(MR.strings.action_menu.localized())
                        scene.renderUntil("Menu owns focus and revokes intent") {
                            !armed() &&
                                scene.activeFocused()?.let {
                                    MR.strings.action_update_library.localized() in
                                        scene.labels(it)
                                } ==
                                true
                        }
                        scene.wheel(point, -4f)
                        scene.renderUntil { true }
                        assertFalse(armed(), "An open menu owns background wheel input")
                        scene.key(Key.Escape)
                        scene.renderUntil("Menu dismissed by Escape") {
                            MR.strings.action_update_library.localized() !in
                                scene.text()
                        }
                    }
                    "selection" -> {
                        val row = scene.nodes().last {
                            "Visible" in scene.labels(it) &&
                                it.config.contains(SemanticsActions.OnClick) &&
                                it.boundsInRoot != Rect.Zero
                        }
                        scene.pointerClick(
                            row.boundsInRoot.center,
                            PointerKeyboardModifiers(isCtrlPressed = true),
                        )
                        scene.renderUntil("Selection revokes the original intent") { !armed() }
                        scene.wheel(point, -4f)
                        scene.renderUntil { true }
                        assertFalse(armed(), "Selected content cannot rearm")
                    }
                    "query" -> {
                        model.setSearchQuery("No matching work")
                        scene.renderUntil {
                            model.visibleItems().isEmpty() &&
                                MR.strings.no_results_found.localized() in scene.text()
                        }
                        assertFalse(armed())
                        scene.wheel(point, -4f)
                        scene.renderUntil { true }
                        assertFalse(armed(), "Empty query results cannot arm")
                    }
                    "focus" -> {
                        scene.windowFocused = false
                        scene.renderUntil("Window focus loss revokes intent") { !armed() }
                        scene.windowFocused = true
                        scene.renderUntil { true }
                    }
                }
                delay(500)
                scene.wheel(point, -2f)
                scene.renderUntil { true }
                assertEquals(0, requests, "The old armed segment cannot survive $boundary")
            }
        }
    }

    @Test
    fun `device conditions are resolved once through actual DI UI and scheduler`(@TempDir root: File) = runBlocking {
        withSettings(root) {
            val port = Injekt.get<mihon.desktop.platform.DesktopDeviceConditions>()
            org.junit.jupiter.api.Assertions.assertSame(port, DesktopUiDependencies.fromInjekt().deviceConditions)
            val field = LibraryUpdateScheduler::class.java.getDeclaredField("deviceConditions").apply {
                isAccessible =
                    true
            }
            org.junit.jupiter.api.Assertions.assertSame(port, field.get(Injekt.get<LibraryUpdateScheduler>()))
        }
    }

    @Test
    fun `Windows device settings expose real persistent options and settings search route`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            scene.mountLibrary()
            scene.renderUntil { scene.categoryEntryReady() }
            val title = MR.strings.pref_library_update_restriction.localized()
            assertTrue(scene.text().contains(title), "Library settings expose supported automatic device restrictions")
            scene.click(MR.strings.connected_to_wifi.localized())
            scene.renderUntil {
                LibraryPreferences.DEVICE_ONLY_ON_WIFI in
                    Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions().get()
            }
            scene.mountSettings()
            scene.renderUntil { MR.strings.action_search_settings.localized() in scene.text() }
            scene.click(MR.strings.action_search_settings.localized())
            scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.SetText) } }
            scene.setText(title)
            scene.renderUntil { true }
            assertTrue(scene.nodes().any { title in scene.labels(it) && it.config.contains(SemanticsActions.OnClick) })
            scene.click(title)
            scene.renderUntil { scene.nodes().any { it.config.getOrElse(DesktopSettingsAnchorHighlighted) { false } } }
        }
    }

    @Test
    fun `actual Root manual refresh bypasses an automatic wait and retains the new checking owner`(
        @TempDir root: File,
    ) = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val port = object : mihon.desktop.platform.DesktopDeviceConditions {
            override val supported = setOf(mihon.desktop.platform.DeviceCondition.WIFI)
            override fun query() = mihon.desktop.platform.DeviceConditionsSnapshot()
        }
        try {
            withSettings(root, deviceConditions = port, updateManga = {
                entered.complete(Unit)
                release.await()
                LibraryUpdateChecker.UpdateResult(0)
            }) { scene ->
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 123,
                            url = "/waiting-work",
                            title = "Waiting work",
                            favorite = true,
                        ),
                    ),
                ).single()
                val tasks = Injekt.get<mihon.desktop.task.DesktopTaskScheduler>()
                tasks.beginLibraryUpdate(
                    LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                    mihon.desktop.task.LibraryUpdateContext(
                        mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                        mihon.desktop.task.LibraryUpdateScope.ALL,
                        units = listOf(mihon.desktop.task.LibraryUpdateUnit(manga.id, manga.source, manga.url)),
                        deviceRestrictions = setOf("wifi"),
                        waitingForDevice = mapOf("wifi" to "UNKNOWN"),
                    ),
                )
                val scheduler = Injekt.get<LibraryUpdateScheduler>()
                val oldOwner = scheduler.resumeUpdate()
                scene.mountRoot()
                scene.renderUntil {
                    "Waiting work" in scene.text() && MR.strings.action_menu.localized() in scene.text()
                }
                scene.click(MR.strings.action_menu.localized())
                scene.renderUntil { MR.strings.action_update_library.localized() in scene.text() }
                scene.click(MR.strings.action_update_library.localized())
                scene.renderUntil {
                    entered.isCompleted || MR.strings.update_already_running.localized() in scene.text()
                }
                assertTrue(
                    entered.isCompleted,
                    "Actual manual menu reaches the scheduler while automatic conditions wait",
                )
                scene.renderUntil { oldOwner.isCompleted }
                val model = requireNotNull(scene.rootModel)
                assertTrue(
                    model.state.value.isUpdating,
                    "Old waiting owner completion must preserve the new manual spinner",
                )
                assertEquals(mihon.domain.task.TaskStatus.Running, scheduler.taskSnapshot()!!.status)
                assertEquals(
                    mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
                    scheduler.taskSnapshot()!!.libraryUpdate!!.trigger,
                )
                release.complete(Unit)
                scene.renderUntil { scheduler.taskSnapshot()?.status == mihon.domain.task.TaskStatus.Completed }
                scene.renderUntil { !model.state.value.isUpdating }
                assertEquals(1, scheduler.taskSnapshot()!!.completedUnitIds.size)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `actual DI diagnostics expose the same device adapter as automatic checks`(@TempDir root: File) = runBlocking {
        val port = object : mihon.desktop.platform.DesktopDeviceConditions {
            override val supported = mihon.desktop.platform.DeviceCondition.entries.toSet()
            override fun query() = mihon.desktop.platform.DeviceConditionsSnapshot(
                mihon.desktop.platform.DeviceConditionState.UNSATISFIED,
                mihon.desktop.platform.DeviceConditionState.UNKNOWN,
                mihon.desktop.platform.DeviceConditionState.SATISFIED,
            )
        }
        withSettings(root, deviceConditions = port) {
            val diagnostic = Injekt.get<mihon.desktop.test.http.LibraryMangaTestModeController>().snapshot()
            assertEquals(setOf("wifi", "network_not_metered", "ac"), diagnostic.supportedDeviceConditions)
            assertEquals(
                mapOf("wifi" to "UNSATISFIED", "network_not_metered" to "UNKNOWN", "ac" to "SATISFIED"),
                diagnostic.deviceConditions,
            )
            assertTrue(diagnostic.waitingForDevice.isEmpty())
            val server = io.ktor.server.engine.embeddedServer(io.ktor.server.cio.CIO, host = "127.0.0.1", port = 0) {
                testHttpServer()
            }.start()
            try {
                val address = "http://127.0.0.1:${server.resolvedConnectors().single().port}/test/state"
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI(address)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(),
                    )
                }
                assertEquals(200, response.statusCode())
                val state = kotlinx.serialization.json.Json.parseToJsonElement(
                    response.body(),
                ) as kotlinx.serialization.json.JsonObject
                val library = state.getValue("library") as kotlinx.serialization.json.JsonObject
                val current = library.getValue("deviceConditions") as kotlinx.serialization.json.JsonObject
                assertEquals("SATISFIED", (current.getValue("ac") as kotlinx.serialization.json.JsonPrimitive).content)
                assertEquals(
                    "UNKNOWN",
                    (current.getValue("network_not_metered") as kotlinx.serialization.json.JsonPrimitive).content,
                )
            } finally {
                server.stop(0, 0)
            }
        }
    }

    @Test
    fun `automatic resume keeps actual Root waiting feedback without a false checking spinner`(
        @TempDir root: File,
    ) = runBlocking {
        val port = object : mihon.desktop.platform.DesktopDeviceConditions {
            override val supported = setOf(mihon.desktop.platform.DeviceCondition.WIFI)
            override fun query() = mihon.desktop.platform.DeviceConditionsSnapshot()
        }
        withSettings(root, deviceConditions = port) { scene ->
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 123, url = "/resume-wait", title = "Resume wait", favorite = true),
                ),
            ).single()
            Injekt.get<mihon.desktop.task.DesktopTaskScheduler>().beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = listOf(mihon.desktop.task.LibraryUpdateUnit(manga.id, manga.source, manga.url)),
                    deviceRestrictions = setOf("wifi"),
                    waitingForDevice = mapOf("wifi" to "UNKNOWN"),
                ),
            )
            val scheduler = Injekt.get<LibraryUpdateScheduler>()
            scheduler.resumeUpdate()
            scene.mountRoot()
            scene.renderUntil { "Resume wait" in scene.text() && scene.rootModel?.state?.value?.isUpdating == false }
            val owner = scheduler.taskSnapshot()!!.task.idempotencyKey
            requireNotNull(scene.rootModel).resumeLibraryUpdate()
            scene.renderUntil { true }
            assertTrue(
                !requireNotNull(scene.rootModel).state.value.isUpdating,
                "Automatic resume must not call an unchanged device wait a source check",
            )
            assertEquals(owner, scheduler.taskSnapshot()!!.task.idempotencyKey)
            assertEquals(
                mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                scheduler.taskSnapshot()!!.libraryUpdate!!.trigger,
            )
            assertTrue(scheduler.taskSnapshot()!!.completedUnitIds.isEmpty())
        }
    }

    @Test
    fun `actual waiting task explains unmet conditions and keeps modal keyboard ownership`(
        @TempDir root: File,
    ) = runBlocking {
        val port = object : mihon.desktop.platform.DesktopDeviceConditions {
            override val supported = mihon.desktop.platform.DeviceCondition.entries.toSet()
            override fun query() = mihon.desktop.platform.DeviceConditionsSnapshot()
        }
        withSettings(root, deviceConditions = port) { scene ->
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 123,
                        url = "/waiting-feedback",
                        title = "Waiting feedback",
                        favorite = true,
                    ),
                ),
            ).single()
            Injekt.get<mihon.desktop.task.DesktopTaskScheduler>().beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = listOf(
                        mihon.desktop.task.LibraryUpdateUnit(manga.id, manga.source, manga.url, title = manga.title),
                    ),
                    deviceRestrictions = setOf("wifi"),
                    waitingForDevice = mapOf("wifi" to "UNKNOWN"),
                ),
            )
            val scheduler = Injekt.get<LibraryUpdateScheduler>()
            scheduler.resumeUpdate()
            scene.mountRoot()
            val results = MR.strings.desktop_library_update_results.localized()
            scene.renderUntil {
                scene.nodes().any {
                    results in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
            }
            assertTrue(
                scene.text().any { MR.strings.desktop_library_update_waiting.localized() in it },
                "Actual task feedback explains why automatic source checks are waiting",
            )
            scene.requestFocus(results)
            scene.key(Key.Spacebar)
            scene.renderUntil { scene.ownerCount() == 2 }
            assertTrue(
                scene.activeNodes().any {
                    scene.labels(it).any { label ->
                        MR.strings.connected_to_wifi.localized() in
                            label
                    }
                },
            )
            assertTrue(
                scene.activeNodes().any {
                    scene.labels(it).any { label ->
                        MR.strings.desktop_device_condition_unknown.localized() in
                            label
                    }
                },
            )
            assertTrue(
                scene.activeNodes().any {
                    MR.strings.action_cancel.localized() in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                },
                "Automatic waiting is still cancellable through the actual result modal",
            )
            scene.click(MR.strings.action_resume.localized())
            scene.renderUntil { true }
            assertTrue(!requireNotNull(scene.rootModel).state.value.isUpdating)
            assertEquals(
                mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                scheduler.taskSnapshot()!!.libraryUpdate!!.trigger,
            )
            val before = scene.navigator.lastItem
            scene.pointerClick(androidx.compose.ui.geometry.Offset(12f, 70f))
            scene.renderUntil { true }
            assertEquals(before, scene.navigator.lastItem)
            if (scene.ownerCount() == 1) {
                scene.requestFocus(results)
                scene.key(Key.Spacebar)
                scene.renderUntil { scene.ownerCount() == 2 }
            }
            for (shift in listOf(false, true)) {
                scene.requestFocus(MR.strings.action_close.localized())
                scene.key(Key.Tab, shift)
                scene.renderUntil { scene.activeFocused() != null }
                assertTrue(scene.activeNodes().any { it.id == scene.activeFocused()!!.id })
            }
            scene.key(Key.Escape)
            scene.renderUntil { scene.ownerCount() == 1 }
            scene.renderUntil { scene.activeFocused()?.let { results in scene.labels(it) } == true }
            assertTrue(scheduler.currentUpdateJob()?.isActive == true)
            assertTrue(scheduler.taskSnapshot()!!.completedUnitIds.isEmpty())
        }
    }

    @Test
    fun `native supported conditions at 320 font200 retain bounds keyboard and preference failure retry`(
        @TempDir root: File,
    ) = runBlocking {
        val port = object : mihon.desktop.platform.DesktopDeviceConditions {
            override val supported = mihon.desktop.platform.DeviceCondition.entries.toSet()
            override fun query() = mihon.desktop.platform.DeviceConditionsSnapshot(
                mihon.desktop.platform.DeviceConditionState.SATISFIED,
                mihon.desktop.platform.DeviceConditionState.SATISFIED,
                mihon.desktop.platform.DeviceConditionState.SATISFIED,
            )
        }
        for (dark in listOf(false, true)) {
            var reject = false
            withSettings(
                File(root, "device-native-$dark"),
                size = IntSize(320, 680),
                fontScale = 2f,
                deviceConditions = port,
                storeAdapter = { actual ->
                    object : PreferenceStore by actual {
                        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                            val preference = actual.getStringSet(key, defaultValue)
                            if (key != "library_update_restriction") return preference
                            return object : Preference<Set<String>> by preference {
                                override fun set(value: Set<String>) {
                                    if (reject) {
                                        reject = false
                                        if (dark) preference.set(value)
                                        throw IOException("Device preference storage rejected")
                                    }
                                    preference.set(value)
                                }
                            }
                        }
                    }
                },
            ) { scene ->
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                    if (dark) eu.kanade.domain.ui.model.ThemeMode.DARK else eu.kanade.domain.ui.model.ThemeMode.LIGHT,
                )
                val preference = Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions()
                preference.set(emptySet())
                DesktopSettingsAnchorOwner.publish(
                    LibrarySettingsScreen(),
                    MR.strings.pref_library_update_restriction.localized(),
                )
                scene.mountLibrary(withParent = true)
                scene.renderUntil { scene.categoryEntryReady() }
                val labels =
                    listOf(
                        MR.strings.connected_to_wifi.localized(),
                        MR.strings.network_not_metered.localized(),
                        MR.strings.desktop_device_external_power.localized(),
                    )
                scene.scrollToControl(labels.first())
                scene.requestFocus(labels.first())
                reject = true
                scene.key(Key.Spacebar)
                scene.renderUntil { MR.strings.internal_error.localized() in scene.text() }
                assertTrue(
                    preference.get().isEmpty(),
                    "Write-before and write-after refusal preserve the shared option",
                )
                for ((index, label) in labels.withIndex()) {
                    scene.scrollToControl(label)
                    scene.requestFocus(label)
                    scene.key(Key.Spacebar)
                    val key = listOf("wifi", "network_not_metered", "ac")[index]
                    scene.renderUntil { key in preference.get() }
                    val node = scene.nodes().last {
                        label in scene.labels(it) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }
                    assertTrue(
                        node.boundsInRoot != Rect.Zero &&
                            node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 320 &&
                            node.boundsInRoot.top >= 64 && node.boundsInRoot.bottom <= 680,
                        "Actual condition row stays fully visible: $label",
                    )
                }
                for (shift in listOf(false, true)) {
                    scene.scrollToControl(if (shift) labels.last() else labels.first())
                    scene.requestFocus(if (shift) labels.last() else labels.first())
                    val visited = mutableSetOf<String>()
                    repeat(4) {
                        scene.renderUntil { scene.activeFocused() != null }
                        val focused = scene.activeFocused()!!
                        visited += labels.filter { it in scene.labels(focused) }
                        scene.key(Key.Tab, shift)
                    }
                    assertTrue(visited.containsAll(labels), "Native Tab reaches every condition in both directions")
                }
                assertEquals(setOf("wifi", "network_not_metered", "ac"), preference.get())
                scene.requestFocus(labels.first())
                scene.renderUntil { scene.activeFocused()?.let { labels.first() in scene.labels(it) } == true }
                scene.scrollToControl(labels.first())
                if (!dark) {
                    scene.savePng(
                        File(
                            System.getenv("MIHON_RI16_VISUAL_DIR") ?: File(root, "visual").absolutePath,
                            "ri16-device-settings-light-320-font200.png",
                        ),
                    )
                }
                scene.click(MR.strings.action_bar_up_description.localized())
                scene.renderUntil { scene.navigator.size == 1 }
                assertEquals("library-settings-parent", scene.navigator.lastItem.key)
            }
        }
    }

    @Test
    fun `unsupported settings retain Windows preferences without exposing unavailable controls`(
        @TempDir root: File,
    ) = runBlocking {
        val port = mihon.desktop.platform.createDesktopDeviceConditions(windows = false) {
            error("Windows native calls on unsupported OS")
        }
        withSettings(root, deviceConditions = port) { scene ->
            val preference = Injekt.get<LibraryPreferences>().autoUpdateDeviceRestrictions()
            val original = setOf("wifi", "network_not_metered", "ac")
            preference.set(original)
            scene.mountLibrary()
            scene.renderUntil { scene.categoryEntryReady() }
            assertTrue(MR.strings.pref_library_update_restriction.localized() !in scene.text())
            assertTrue(MR.strings.connected_to_wifi.localized() !in scene.text())
            assertEquals(original, preference.get())
        }
    }

    @Test
    fun `native waiting results at 320 font200 keep cancellation keyboard and Escape focus reachable`(
        @TempDir root: File,
    ) = runBlocking {
        val port = object : mihon.desktop.platform.DesktopDeviceConditions {
            override val supported = setOf(mihon.desktop.platform.DeviceCondition.WIFI)
            override fun query() = mihon.desktop.platform.DeviceConditionsSnapshot()
        }
        withSettings(root, deviceConditions = port, size = IntSize(320, 680), fontScale = 2f) { scene ->
            Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                eu.kanade.domain.ui.model.ThemeMode.DARK,
            )
            val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 123, url = "/native-wait", title = "Native wait", favorite = true),
                ),
            ).single()
            Injekt.get<mihon.desktop.task.DesktopTaskScheduler>().beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = listOf(
                        mihon.desktop.task.LibraryUpdateUnit(manga.id, manga.source, manga.url, title = manga.title),
                    ),
                    deviceRestrictions = setOf("wifi"),
                    waitingForDevice = mapOf("wifi" to "UNKNOWN"),
                ),
            )
            val scheduler = Injekt.get<LibraryUpdateScheduler>()
            scheduler.resumeUpdate()
            scene.mountRoot()
            val results = MR.strings.desktop_library_update_results.localized()
            scene.renderUntil {
                scene.nodes().any {
                    results in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
            }
            scene.requestFocus(results)
            scene.key(Key.Spacebar)
            scene.renderUntil { scene.ownerCount() == 2 }
            val labels =
                listOf(
                    MR.strings.action_cancel.localized(),
                    MR.strings.action_resume.localized(),
                    MR.strings.action_close.localized(),
                )
            for (label in labels) {
                val node = scene.activeNodes().last {
                    label in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                assertTrue(
                    node.boundsInRoot != Rect.Zero &&
                        node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 320 &&
                        node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 680,
                    "Waiting action remains visible at large font: $label",
                )
            }
            for (shift in listOf(false, true)) {
                scene.requestFocus(labels.last())
                val visited = mutableSetOf<String>()
                repeat(8) {
                    scene.renderUntil { scene.activeFocused() != null }
                    visited += labels.filter { it in scene.labels(scene.activeFocused()!!) }
                    scene.key(Key.Tab, shift)
                }
                assertTrue(visited.containsAll(labels))
            }
            fun originalWorkVisible() = scene.activeNodes().any {
                it.config.getOrElse(SemanticsProperties.Text) {
                    emptyList()
                }.any { text -> text.text == manga.title } &&
                    it.boundsInRoot != Rect.Zero && it.boundsInRoot.height > 0
            }
            if (!originalWorkVisible()) {
                val scroll = scene.activeNodes().firstOrNull {
                    it.config.contains(SemanticsActions.ScrollBy) &&
                        it.boundsInRoot.height > 0
                }
                assertTrue(scroll != null, "Waiting explanations preserve a real scroll path to the original work row")
                assertTrue(requireNotNull(scroll!!.config[SemanticsActions.ScrollBy].action).invoke(0f, 10000f))
                scene.renderUntil { originalWorkVisible() }
            }
            // Return the explanation to the top for this evidence image; all original work remains reachable.
            scene.activeNodes().firstOrNull {
                it.config.contains(SemanticsActions.ScrollBy) &&
                    it.boundsInRoot.height > 0
            }?.let {
                requireNotNull(it.config[SemanticsActions.ScrollBy].action).invoke(0f, -10000f)
                scene.renderUntil { true }
            }
            scene.savePng(
                File(
                    System.getenv("MIHON_RI16_VISUAL_DIR") ?: File(root, "visual").absolutePath,
                    "ri16-device-waiting-dark-320-font200.png",
                ),
            )
            val before = scene.navigator.lastItem
            scene.pointerClick(androidx.compose.ui.geometry.Offset(12f, 70f))
            scene.renderUntil { true }
            assertEquals(before, scene.navigator.lastItem)
            if (scene.ownerCount() == 1) {
                scene.requestFocus(results)
                scene.key(Key.Spacebar)
                scene.renderUntil { scene.ownerCount() == 2 }
            }
            scene.key(Key.Escape)
            scene.renderUntil { scene.ownerCount() == 1 }
            scene.renderUntil { scene.activeFocused()?.let { results in scene.labels(it) } == true }
            assertTrue(scheduler.currentUpdateJob()?.isActive == true)
            scene.key(Key.Spacebar)
            scene.renderUntil { scene.ownerCount() == 2 }
            scene.click(MR.strings.action_cancel.localized())
            scene.renderUntil { scheduler.taskSnapshot()?.status == mihon.domain.task.TaskStatus.Cancelled }
            assertTrue(scheduler.taskSnapshot()!!.completedUnitIds.isEmpty())
            assertEquals(listOf(manga.id), scheduler.taskSnapshot()!!.workset)
        }
    }

    @Test
    fun `new periodic smart metadata settings search reaches the real shared preference controls`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            val titles =
                listOf(
                    MR.strings.pref_library_update_interval.localized(),
                    MR.strings.pref_library_update_smart_update.localized(),
                    MR.strings.pref_library_update_refresh_metadata.localized(),
                )
            scene.mountSettings()
            scene.renderUntil { MR.strings.action_search_settings.localized() in scene.text() }
            for (title in titles) {
                scene.click(MR.strings.action_search_settings.localized())
                scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.SetText) } }
                scene.setText(title)
                scene.renderUntil { true }
                assertTrue(
                    scene.nodes().any {
                        title in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
                    },
                    "Search exposes $title",
                )
                scene.click(title)
                scene.renderUntil {
                    scene.nodes().any { it.config.getOrElse(DesktopSettingsAnchorHighlighted) { false } }
                }
                assertTrue(
                    scene.navigator.lastItem is SettingsRootScreen,
                    "The ordinary settings child route remains inside its actual Root navigator",
                )
                assertTrue(
                    scene.nodes().any {
                        title in scene.labels(it) &&
                            it.config.getOrElse(DesktopSettingsAnchorHighlighted) { false }
                    },
                )
                if (title == titles.first()) {
                    scene.click(MR.strings.update_48hour.localized())
                    scene.renderUntil { Injekt.get<LibraryPreferences>().autoUpdateInterval().get() == 48 }
                } else if (title == titles.last()) {
                    scene.click(title)
                    scene.renderUntil { Injekt.get<LibraryPreferences>().autoUpdateMetadata().get() }
                }
            }
        }
    }

    @Test
    fun `periodic smart and metadata settings retain authority after pre and post write refusal`(
        @TempDir root: File,
    ) = runBlocking {
        for (afterWrite in listOf(false, true)) {
            var reject: String? = null
            fun <T> guarded(key: String, pref: Preference<T>) = object : Preference<T> by pref {
                override fun set(value: T) {
                    if (reject == key) {
                        reject = null
                        if (afterWrite) pref.set(value)
                        throw IOException("Preference save refused")
                    }
                    pref.set(value)
                }
            }
            withSettings(File(root, "settings-refusal-$afterWrite"), storeAdapter = { actual ->
                object : PreferenceStore by actual {
                    override fun getInt(key: String, defaultValue: Int) = guarded(key, actual.getInt(key, defaultValue))
                    override fun getBoolean(
                        key: String,
                        defaultValue: Boolean,
                    ) = guarded(key, actual.getBoolean(key, defaultValue))
                    override fun getStringSet(
                        key: String,
                        defaultValue: Set<String>,
                    ) = guarded(key, actual.getStringSet(key, defaultValue))
                }
            }) { scene ->
                val preferences = Injekt.get<LibraryPreferences>()
                preferences.autoUpdateMangaRestrictions().set(setOf(LibraryPreferences.MANGA_NON_COMPLETED))
                val checks = listOf(
                    Triple(
                        "pref_library_update_interval_key",
                        MR.strings.update_48hour.localized(),
                        preferences.autoUpdateInterval(),
                    ),
                    Triple(
                        "library_update_manga_restriction",
                        MR.strings.pref_update_only_non_completed.localized(),
                        preferences.autoUpdateMangaRestrictions(),
                    ),
                    Triple(
                        "auto_update_metadata",
                        MR.strings.pref_library_update_refresh_metadata.localized(),
                        preferences.autoUpdateMetadata(),
                    ),
                    Triple(
                        "pref_update_library_manga_titles",
                        MR.strings.pref_update_library_manga_titles.localized(),
                        preferences.updateMangaTitles(),
                    ),
                )
                for ((key, label, pref) in checks) {
                    scene.mountLibrary()
                    scene.renderUntil { label in scene.text() }
                    val before = pref.get()
                    reject = key
                    scene.click(label)
                    scene.renderUntil { MR.strings.internal_error.localized() in scene.text() }
                    assertEquals(before, pref.get(), "Failed saves restore the original authority for $key")
                    scene.mountLibrary()
                    scene.renderUntil { label in scene.text() }
                    assertEquals(before, pref.get())
                    scene.click(label)
                    scene.renderUntil { pref.get() != before }
                }
            }
        }
    }

    @Test
    fun `native update results at 320 font200 retain modal keyboard bounds and return focus`(
        @TempDir root: File,
    ) = runBlocking {
        for (dark in listOf(false, true)) {
            withSettings(
                File(root, "results-$dark"),
                size = IntSize(320, 680),
                fontScale = 2f,
                updateManga = {
                    LibraryUpdateChecker.UpdateResult(0, sourceError = mihon.domain.error.AppError.Server(500))
                },
            ) { scene ->
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                    if (dark) eu.kanade.domain.ui.model.ThemeMode.DARK else eu.kanade.domain.ui.model.ThemeMode.LIGHT,
                )
                val repository = Injekt.get<MangaRepository>()
                val mangas = repository.insertNetworkManga(
                    (1..18).map {
                        Manga.create().copy(
                            source = 123,
                            url = "/manga/$it",
                            title = "Failed work $it",
                            favorite = true,
                        )
                    },
                )
                val scheduler = Injekt.get<LibraryUpdateScheduler>()
                scheduler.runNow().join()
                assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()!!.status)
                scene.mountRoot()
                val title = MR.strings.desktop_library_update_results.localized()
                scene.renderUntil {
                    scene.nodes().any {
                        title in scene.labels(it) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }
                }
                scene.requestFocus(title)
                scene.key(Key.Spacebar)
                scene.renderUntil { scene.ownerCount() == 2 }
                for (label in listOf(
                    MR.strings.action_close.localized(),
                    MR.strings.desktop_ui_retry_failed.localized(),
                    MR.strings.action_resume.localized(),
                )) {
                    val action = scene.activeNodes().last {
                        label in scene.labels(it) &&
                            it.config.contains(SemanticsActions.OnClick)
                    }
                    assertTrue(
                        action.boundsInRoot != Rect.Zero && action.boundsInRoot.left >= 0 &&
                            action.boundsInRoot.right <= 320 &&
                            action.boundsInRoot.top >= 0 &&
                            action.boundsInRoot.bottom <= 680,
                        "Large font modal action stays reachable: $label",
                    )
                }
                for (shift in listOf(false, true)) {
                    scene.requestFocus(MR.strings.action_close.localized())
                    scene.renderUntil { scene.activeFocused() != null }
                    val start = scene.activeFocused()!!.id
                    val visited = mutableSetOf<Int>()
                    var loop = false
                    repeat(8) {
                        scene.key(Key.Tab, shift)
                        scene.renderUntil { scene.activeFocused() != null }
                        val focused = scene.activeFocused()!!
                        visited += focused.id
                        if (focused.id == start && visited.size >= 3) loop = true
                    }
                    assertTrue(loop, "Native bidirectional Tab remains in the result modal")
                }
                val list = scene.activeNodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
                assertTrue(requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(17))
                scene.renderUntil {
                    scene.activeNodes().any {
                        "Failed work 18" in scene.labels(it) &&
                            it.boundsInRoot != Rect.Zero
                    }
                }
                scene.savePng(
                    File(
                        System.getenv("MIHON_RI14_VISUAL_DIR") ?: File(root, "visual").absolutePath,
                        "ri14-update-results-${if (dark) "dark" else "light"}-320-font200.png",
                    ),
                )
                val before = scene.navigator.lastItem
                scene.pointerClick(androidx.compose.ui.geometry.Offset(12f, 70f))
                scene.renderUntil { true }
                assertEquals(before, scene.navigator.lastItem, "Modal blocks background navigation")
                assertEquals(2, scene.ownerCount())
                scene.key(Key.Escape)
                scene.renderUntil { scene.ownerCount() == 1 }
                scene.renderUntil {
                    scene.nodes().any {
                        title in scene.labels(it) &&
                            it.config.getOrElse(SemanticsProperties.Focused) { false }
                    }
                }
                assertEquals(18, scheduler.taskSnapshot()!!.libraryUpdate!!.units.size)
                assertEquals(
                    mangas.map(Manga::id).toSet(),
                    scheduler.taskSnapshot()!!.libraryUpdate!!.units.map {
                        it.mangaId
                    }.toSet(),
                )
            }
        }
    }

    @Test
    fun `library settings expose all real periodic smart and metadata preferences`(@TempDir root: File) = runBlocking {
        withSettings(root) { scene ->
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.autoUpdateMangaRestrictions().set(
                setOf(
                    LibraryPreferences.MANGA_NON_COMPLETED,
                    LibraryPreferences.MANGA_HAS_UNREAD,
                    LibraryPreferences.MANGA_NON_READ,
                    LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD,
                ),
            )
            scene.mountLibrary()
            scene.renderUntil("periodic controls must mount") { MR.strings.update_never.localized() in scene.text() }
            for ((hours, label) in listOf(
                48 to MR.strings.update_48hour.localized(),
                72 to MR.strings.update_72hour.localized(),
            )) {
                assertTrue(
                    scene.nodes().any {
                        label in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
                    },
                    "The supported interval has a real control: $hours",
                )
                scene.click(label)
                scene.renderUntil("save interval $hours") { preferences.autoUpdateInterval().get() == hours }
            }
            val rules = listOf(
                LibraryPreferences.MANGA_NON_COMPLETED to MR.strings.pref_update_only_non_completed.localized(),
                LibraryPreferences.MANGA_HAS_UNREAD to MR.strings.pref_update_only_completely_read.localized(),
                LibraryPreferences.MANGA_NON_READ to MR.strings.pref_update_only_started.localized(),
                LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD to
                    MR.strings.pref_update_only_in_release_period.localized(),
            )
            for ((key, label) in rules) {
                assertTrue(
                    scene.nodes().any {
                        label in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
                    },
                )
                scene.click(label)
                scene.renderUntil("save smart rule $key") { key !in preferences.autoUpdateMangaRestrictions().get() }
            }
            val metadata = MR.strings.pref_library_update_refresh_metadata.localized()
            assertTrue(
                scene.nodes().any {
                    metadata in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
                },
            )
            assertFalse(preferences.autoUpdateMetadata().get())
            scene.click(metadata)
            scene.renderUntil("save metadata switch") { preferences.autoUpdateMetadata().get() }
            scene.mountLibrary()
            scene.renderUntil("periodic controls must mount") { MR.strings.update_never.localized() in scene.text() }
            assertEquals(72, preferences.autoUpdateInterval().get())
            assertTrue(preferences.autoUpdateMetadata().get())
            assertTrue(preferences.autoUpdateMangaRestrictions().get().isEmpty())
        }
    }

    @Test
    fun `shared extended interval shows its actual hours and a valid legacy option corrects the migration warning`(
        @TempDir root: File,
    ) = runBlocking {
        for (hours in listOf(48, 72)) {
            withSettings(File(root, hours.toString())) { scene ->
                val preferences = Injekt.get<LibraryPreferences>()
                val app = Injekt.get<mihon.desktop.settings.DesktopAppPreferences>()
                preferences.autoUpdateInterval().set(hours)
                app.libraryUpdateInterval.set(mihon.desktop.settings.LibraryUpdateInterval.OFF)
                app.libraryUpdateIntervalMigrationInvalid.set(true)
                scene.mountLibrary()
                val label = if (hours ==
                    48
                ) {
                    MR.strings.update_48hour.localized()
                } else {
                    MR.strings.update_72hour.localized()
                }
                scene.renderUntil {
                    scene.nodes().any {
                        label in scene.labels(it) &&
                            it.config.getOrElse(SemanticsProperties.Selected) { false }
                    }
                }
                assertTrue(scene.text().contains(MR.strings.desktop_library_update_interval_invalid.localized()))
                scene.click(MR.strings.update_6hour.localized())
                scene.renderUntil {
                    preferences.autoUpdateInterval().get() == 6 &&
                        !app.libraryUpdateIntervalMigrationInvalid.get()
                }
                assertEquals(mihon.desktop.settings.LibraryUpdateInterval.OFF, app.libraryUpdateInterval.get())
                assertFalse(scene.text().contains(MR.strings.desktop_library_update_interval_invalid.localized()))
            }
        }
    }

    @Test
    fun `new library settings search enters actual Root destinations and highlights their persistent controls`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Search actual", 0, 12))
            val category = repository.getAll().single { it.name == "Search actual" }
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.categorizedDisplaySettings().set(true)
            scene.mountSettings()
            scene.renderUntil { scene.text().contains(MR.strings.action_search_settings.localized()) }
            val titles = listOf(
                MR.strings.default_category.localized(),
                MR.strings.categories.localized(),
                MR.strings.categorized_display_settings.localized(),
            )
            for (title in titles) {
                scene.click(MR.strings.action_search_settings.localized())
                scene.renderUntil { scene.nodes().any { it.config.contains(SemanticsActions.SetText) } }
                scene.setText(title)
                scene.renderUntil { true }
                assertTrue(
                    scene.nodes().any {
                        title in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
                    },
                    "Search must expose the actual new library preference",
                )
                scene.click(title)
                scene.renderUntil {
                    scene.nodes().any { it.config.getOrElse(DesktopSettingsAnchorHighlighted) { false } }
                }
                val highlighted = scene.nodes().single {
                    it.config.getOrElse(DesktopSettingsAnchorHighlighted) { false }
                }
                assertTrue(title in scene.labels(highlighted))
                when (title) {
                    titles[0] -> {
                        scene.click(title)
                        scene.renderUntil { scene.text().contains(category.name) }
                        scene.click(category.name)
                        scene.renderUntil { preferences.defaultCategory().get() == category.id.toInt() }
                    }
                    titles[1] -> {
                        scene.renderUntil { scene.categoryEntryReady() }
                        scene.click(title)
                        scene.renderUntil { scene.text().contains(MR.strings.action_cancel.localized()) }
                        scene.click(category.name)
                        scene.click(MR.strings.action_ok.localized())
                        scene.renderUntil { preferences.updateCategories().get() == setOf(category.id.toString()) }
                    }
                    else -> {
                        scene.click(title)
                        scene.renderUntil { !preferences.categorizedDisplaySettings().get() }
                        assertEquals(
                            preferences.sortingMode().get().flag,
                            requireNotNull(repository.get(category.id)).flags,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `default category dialog preserves unset authority on pre and post write failures and retry`(
        @TempDir root: File,
    ) = runBlocking {
        for (afterWrite in listOf(false, true)) {
            var reject = false
            withSettings(File(root, "default-" + afterWrite), storeAdapter = { actual ->
                object : PreferenceStore by actual {
                    override fun getInt(key: String, defaultValue: Int): Preference<Int> {
                        val preference = actual.getInt(key, defaultValue)
                        return object : Preference<Int> by preference {
                            override fun set(value: Int) {
                                if (reject && key == "default_category") {
                                    reject = false
                                    if (afterWrite) preference.set(value)
                                    throw IOException("Default category write refused")
                                }
                                preference.set(value)
                            }
                        }
                    }
                }
            }) { scene ->
                val repository = Injekt.get<CategoryRepository>()
                repository.insert(Category(0, "Default retry", 0, 0))
                val category = repository.getAll().single { it.name == "Default retry" }
                val preference = Injekt.get<LibraryPreferences>().defaultCategory()
                scene.mountLibrary()
                scene.renderUntil(message = "category entry ready") { scene.categoryEntryReady() }
                scene.click(MR.strings.default_category.localized())
                scene.renderUntil { scene.text().contains(category.name) }
                scene.click(MR.strings.default_category_summary.localized())
                scene.renderUntil { true }
                assertTrue(
                    scene.text().contains(MR.strings.action_cancel.localized()),
                    "Current choice does not close or write",
                )
                assertFalse(preference.isSet())
                reject = true
                scene.click(category.name)
                scene.renderUntil { scene.text().contains(MR.strings.internal_error.localized()) }
                assertEquals(-1, preference.get())
                assertFalse(preference.isSet())
                scene.click(category.name)
                scene.renderUntil { preference.get() == category.id.toInt() }
                assertTrue(preference.isSet())
            }
        }
    }

    @Test
    fun `native category dialogs at 320 font200 keep scrolling keyboard modal isolation and Escape focus return`(
        @TempDir root: File,
    ) = runBlocking {
        for (defaultDialog in listOf(false, true)) {
            withSettings(File(root, "native-" + defaultDialog), size = IntSize(320, 680), fontScale = 2f) { scene ->
                val repository = Injekt.get<CategoryRepository>()
                repeat(30) { repository.insert(Category(0, "Category " + (it + 1), it.toLong(), 0)) }
                Injekt.get<mihon.desktop.settings.DesktopAppPreferences>().themeMode.set(
                    if (defaultDialog) {
                        eu.kanade.domain.ui.model.ThemeMode.DARK
                    } else {
                        eu.kanade.domain.ui.model.ThemeMode.LIGHT
                    },
                )
                val preference = Injekt.get<LibraryPreferences>()
                scene.mountLibrary(withParent = true)
                scene.renderUntil(message = "Native stage 11: default=$defaultDialog") { scene.categoryEntryReady() }
                val title = if (defaultDialog) {
                    MR.strings.default_category.localized()
                } else {
                    MR.strings.categories.localized()
                }
                scene.requestFocus(title)
                scene.key(Key.Spacebar)
                scene.renderUntil(message = "Native stage 15: default=$defaultDialog") { scene.ownerCount() == 2 }
                scene.renderUntil(message = "Native dialog initial focus: default=$defaultDialog") {
                    scene.activeFocused() != null
                }
                for (shift in listOf(false, true)) {
                    scene.requestFocus(MR.strings.action_cancel.localized())
                    scene.renderUntil(message = "Native stage 18: default=$defaultDialog") {
                        scene.activeFocused()?.let(scene::labels) == listOf(MR.strings.action_cancel.localized())
                    }
                    val start = scene.labels(requireNotNull(scene.activeFocused()))
                    val visited = mutableSetOf<List<String>>()
                    val focusTrace = mutableListOf<String>()
                    var closedLoop = false
                    for (step in 0 until 80) {
                        val previousFocus = requireNotNull(scene.activeFocused())
                        val previousLabels = scene.labels(previousFocus)
                        var lastPlacedFocus: Triple<Int, Rect, Float?>? = null
                        var stableFrames = 0
                        // Beyond-bounds search may focus a temporary unplaced LazyColumn row.
                        // Observe a placed visible target and settled scrolling before another key.
                        scene.key(Key.Tab, shift = shift)
                        scene.renderUntil(
                            message = "Native Tab did not advance: default=$defaultDialog shift=$shift " +
                                "step=$step previous=${previousFocus.id}:$previousLabels",
                        ) {
                            val focused = scene.activeFocused()
                            val list = scene.activeNodes().firstOrNull {
                                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                            }
                            val scroll = list?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
                            val bounds = focused?.boundsInRoot
                            val visibleHeight = list?.boundsInRoot?.height ?: 680f
                            val placed = focused != null && focused.id != previousFocus.id &&
                                focused.config.contains(SemanticsActions.RequestFocus) &&
                                focused.layoutInfo.isAttached && focused.layoutInfo.isPlaced &&
                                bounds != null && bounds.width > 0f && bounds.height > 0f &&
                                bounds.height + .5f >= minOf(focused.layoutInfo.height.toFloat(), visibleHeight)
                            val observed = if (placed) Triple(focused!!.id, bounds!!, scroll) else null
                            stableFrames = if (observed != null && observed == lastPlacedFocus) stableFrames + 1 else 0
                            lastPlacedFocus = observed
                            stableFrames >= 2
                        }
                        val focused = requireNotNull(scene.activeFocused())
                        val identity = scene.labels(focused)
                        val scroll = scene.activeNodes().firstOrNull {
                            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                        }?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
                        focusTrace += "$step ${previousFocus.id}:$previousLabels " +
                            "-> ${focused.id}:$identity bounds=${focused.boundsInRoot} scroll=$scroll"
                        visited += identity
                        if (identity == start && visited.size > 1) {
                            closedLoop = true
                            break
                        }
                    }
                    assertTrue(
                        closedLoop && visited.size >= 3,
                        "Native focus loop default=$defaultDialog shift=$shift start=$start visited=$visited " +
                            "actual=" + scene.activeFocused()?.let(scene::labels) +
                            " tail=" + focusTrace.takeLast(12),
                    )
                }
                val lazy = scene.activeNodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
                val lastIndex = if (defaultDialog) 31 else 30
                assertTrue(requireNotNull(lazy.config[SemanticsActions.ScrollToIndex].action).invoke(lastIndex))
                scene.renderUntil(message = "Native stage 34: default=$defaultDialog") {
                    scene.activeNodes().any {
                        "Category 30" in
                            scene.labels(it) &&
                            it.boundsInRoot != Rect.Zero
                    }
                }
                val last = scene.activeNodes().last {
                    "Category 30" in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                assertTrue(last.boundsInRoot.left >= 0 && last.boundsInRoot.right <= 320)
                assertTrue(last.boundsInRoot.top >= 0 && last.boundsInRoot.bottom <= 680)
                val cancel = scene.activeNodes().last {
                    MR.strings.action_cancel.localized() in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                assertTrue(cancel.boundsInRoot != Rect.Zero && cancel.boundsInRoot.bottom <= 680)
                scene.requestFocus(MR.strings.action_cancel.localized())
                scene.renderUntil(message = "Native stage 41: default=$defaultDialog") { scene.activeFocused() != null }
                scene.resize(300, 620)
                scene.renderUntil(message = "Cancel focus survives resize") {
                    scene.activeFocused()?.let(scene::labels) == listOf(MR.strings.action_cancel.localized())
                }
                assertEquals(2, scene.ownerCount())
                assertTrue(
                    scene.activeNodes().any {
                        "Category 30" in scene.labels(it) && it.boundsInRoot != Rect.Zero
                    },
                )
                scene.resize(320, 680)
                scene.renderUntil(message = "Native stage 47: default=$defaultDialog") {
                    scene.activeNodes().any {
                        "Category 30" in
                            scene.labels(it) &&
                            it.boundsInRoot != Rect.Zero
                    }
                }
                scene.savePng(
                    File(
                        System.getenv("MIHON_RI12_VISUAL_DIR") ?: File(root, "visual").absolutePath,
                        if (defaultDialog) {
                            "ri12-default-category-dark-320-font200.png"
                        } else {
                            "ri12-update-categories-light-320-font200.png"
                        },
                    ),
                )
                scene.key(Key.Escape)
                scene.renderUntil(message = "Escape closes modal") { scene.ownerCount() == 1 }
                scene.renderUntil(message = "Escape returns focus to original trigger") {
                    scene.nodes().any {
                        title in
                            scene.labels(it) &&
                            it.config.getOrElse(SemanticsProperties.Focused) { false }
                    }
                }
                assertEquals(-1, preference.defaultCategory().get())
                assertTrue(preference.updateCategories().get().isEmpty())
                assertTrue(preference.updateCategoriesExclude().get().isEmpty())
                scene.key(Key.Escape)
                scene.renderUntil(message = "Native stage 57: default=$defaultDialog") { true }
                assertEquals(1, scene.ownerCount(), "Escape closes one modal only")
                assertTrue(scene.navigator.lastItem is LibrarySettingsScreen)
                scene.key(Key.Spacebar)
                scene.renderUntil(message = "Native stage 61: default=$defaultDialog") { scene.ownerCount() == 2 }
                val backgroundBack = scene.nodes().first {
                    MR.strings.action_bar_up_description.localized() in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                scene.pointerClick(backgroundBack.boundsInRoot.center)
                scene.renderUntil { true }
                assertTrue(
                    scene.navigator.lastItem is LibrarySettingsScreen,
                    "The modal backdrop must not execute the background Back action",
                )
                assertEquals(-1, preference.defaultCategory().get())
                assertTrue(preference.updateCategories().get().isEmpty())
                scene.key(Key.Escape)
                scene.renderUntil(message = "Native stage 71: default=$defaultDialog") { scene.ownerCount() == 1 }
            }
        }
    }

    @Test
    fun `persistent migration recovery refusal blocks real scheduler across restart until a complete retry`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/migration-gate-" + UUID.randomUUID())
        val actual = DesktopPreferenceStore(node)
        var reject = false
        val store = object : PreferenceStore by actual {
            override fun getString(key: String, defaultValue: String): Preference<String> {
                val preference = actual.getString(key, defaultValue)
                return object : Preference<String> by preference {
                    override fun delete() {
                        if (reject &&
                            key == "library_update_categories"
                        ) {
                            throw IOException("Original scope restoration refused")
                        }
                        preference.delete()
                    }
                }
            }
            override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                val preference = actual.getStringSet(key, defaultValue)
                return object : Preference<Set<String>> by preference {
                    override fun set(value: Set<String>) {
                        if (reject &&
                            key == "library_update_categories_exclude"
                        ) {
                            throw IOException("New scope write refused")
                        }
                        preference.set(value)
                    }
                }
            }
        }
        val updated = mutableListOf<Long>()
        var context = initDesktopDIForTest(root, actual, startDownloadWorker = false)
        try {
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Import include", 0, 0))
            categories.insert(Category(0, "Import exclude", 1, 0))
            val a = categories.getAll().single { it.name == "Import include" }.id
            val b = categories.getAll().single { it.name == "Import exclude" }.id
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 123, url = "/migration", title = "Migration scope")),
            ).single()
            repository.updateAtomically(LibraryMembershipUpdate(manga.id, true, 100, listOf(a)))
            context.closeAndJoin()
            actual.getStringSet("library_update_categories", emptySet()).delete()
            actual.getStringSet("library_update_categories_exclude", emptySet()).delete()
            actual.getString("update_category_includes", "").set(a.toString())
            actual.getString("update_category_excludes", "").set(b.toString())
            actual.getInt(mihon.desktop.settings.LibraryPreferenceMigration.MARKER_KEY, 0).set(2)
            reject = true
            repeat(2) {
                context = initDesktopDIForTest(root, store, startDownloadWorker = false, updateManga = {
                    updated += it.id
                    LibraryUpdateChecker.UpdateResult(0)
                })
                assertFalse(Injekt.get<mihon.desktop.settings.LibraryPreferenceMigration>().isComplete())
                assertEquals(
                    DesktopLibraryCategoryPolicy.State.Unavailable(true),
                    Injekt.get<DesktopLibraryCategoryPolicy>().state.value,
                )
                Injekt.get<LibraryUpdateScheduler>().runNow().join()
                assertTrue(updated.isEmpty(), "A half-imported policy must not be consumed on either startup")
                if (it == 0) context.closeAndJoin()
            }
            reject = false
            assertTrue(Injekt.get<DesktopLibraryCategoryPolicy>().recover())
            assertEquals(
                DesktopLibraryCategoryPolicy.Snapshot(setOf(a), setOf(b)),
                Injekt.get<DesktopLibraryCategoryPolicy>().snapshot(),
            )
            Injekt.get<LibraryUpdateScheduler>().runNow().join()
            assertEquals(listOf(manga.id), updated)
            assertEquals(a.toString(), actual.getString("update_category_includes", "").get())
            assertEquals(b.toString(), actual.getString("update_category_excludes", "").get())
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `category sorting preference pre and post write errors restore enabled and its original SQL sorts`(
        @TempDir root: File,
    ) = runBlocking {
        for (afterWrite in listOf(false, true)) {
            var reject = false
            withSettings(File(root, "sort-preference-$afterWrite"), storeAdapter = { actual ->
                object : PreferenceStore by actual {
                    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
                        val preference = actual.getBoolean(key, defaultValue)
                        return object : Preference<Boolean> by preference {
                            override fun set(value: Boolean) {
                                if (reject && key == "categorized_display") {
                                    reject = false
                                    if (afterWrite) preference.set(value)
                                    throw IOException("Rejected category sorting preference")
                                }
                                preference.set(value)
                            }
                        }
                    }
                }
            }) { scene ->
                val repository = Injekt.get<CategoryRepository>()
                repository.insert(Category(0, "Sort preference", 0, 12))
                val category = repository.getAll().single { it.name == "Sort preference" }
                val preferences = Injekt.get<LibraryPreferences>()
                preferences.categorizedDisplaySettings().set(true)
                scene.mountLibrary()
                val title = MR.strings.categorized_display_settings.localized()
                scene.renderUntil { scene.text().contains(title) }
                reject = true
                scene.click(title)
                scene.renderUntil { scene.text().contains(MR.strings.internal_error.localized()) }
                assertTrue(
                    preferences.categorizedDisplaySettings().get(),
                    "Reported failure keeps the original preference enabled",
                )
                assertEquals(12L, requireNotNull(repository.get(category.id)).flags)
                scene.click(title)
                scene.renderUntil { !preferences.categorizedDisplaySettings().get() }
                assertEquals(preferences.sortingMode().get().flag, requireNotNull(repository.get(category.id)).flags)
            }
        }
    }

    @Test
    fun `confirmed deletion reacknowledges a pending ID that only reached memory after failed flush`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = false
        withSettings(root, storeAdapter = { actual ->
            object : PreferenceStore by actual {
                override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                    val preference = actual.getStringSet(key, defaultValue)
                    return object : Preference<Set<String>> by preference {
                        override fun set(value: Set<String>) {
                            preference.set(value)
                            if (reject &&
                                key == Preference.appStateKey("category_deletion_pending")
                            ) {
                                throw IOException("Pending flush rejected")
                            }
                        }
                    }
                }
            }
        }) { _ ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Acknowledgment actual", 0, 0))
            val category = repository.getAll().single { it.name == "Acknowledgment actual" }
            val delete = Injekt.get<DeleteCategory>()
            reject = true
            assertTrue(delete.await(category.id) is DeleteCategory.Result.InternalError)
            assertTrue(repository.get(category.id) != null)
            assertTrue(delete.recoverPending() is DeleteCategory.Result.InternalError)
            assertTrue(
                repository.get(category.id) != null,
                "A RAM pending ID cannot authorize SQL while its acknowledgement still fails",
            )
            assertTrue(delete.await(category.id) is DeleteCategory.Result.InternalError)
            assertTrue(repository.get(category.id) != null)
            reject = false
            assertEquals(DeleteCategory.Result.Success, delete.recoverPending())
            assertEquals(null, repository.get(category.id))
        }
    }

    @Test
    fun `disabling category sorting resets actual SQL flags before switching the shared preference`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Sort actual", 0, 12))
            val category = repository.getAll().single { it.name == "Sort actual" }
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.categorizedDisplaySettings().set(true)
            preferences.randomSortSeed().set(19)
            val originalDisplay = preferences.displayMode().get()
            val originalSort = preferences.sortingMode().get()
            scene.mountLibrary()
            val title = MR.strings.categorized_display_settings.localized()
            scene.renderUntil { scene.text().contains(title) }
            scene.click(title)
            scene.renderUntil {
                !preferences.categorizedDisplaySettings().get() &&
                    scene.categoryState(title) == androidx.compose.ui.state.ToggleableState.Off
            }
            assertEquals(originalSort.flag, requireNotNull(repository.get(category.id)).flags)
            assertEquals(originalSort, preferences.sortingMode().get())
            assertEquals(originalDisplay, preferences.displayMode().get())
            assertEquals(19, preferences.randomSortSeed().get())
            scene.click(title)
            scene.renderUntil { preferences.categorizedDisplaySettings().get() }
            assertEquals(
                originalSort.flag,
                requireNotNull(repository.get(category.id)).flags,
                "Re-enabling cannot revive the old category sort",
            )
        }
    }

    @Test
    fun `SQL reset rejection and post commit error keep category sorting enabled and retryable`(
        @TempDir root: File,
    ) = runBlocking {
        for (afterCommit in listOf(false, true)) {
            var reject = true
            withSettings(File(root, "reset-$afterCommit"), categoryAdapter = { actual ->
                object : CategoryRepository by actual {
                    override suspend fun updateAllFlags(flags: Long?) {
                        if (reject) {
                            reject = false
                            if (afterCommit) actual.updateAllFlags(flags)
                            throw IOException("Actual reset rejected")
                        }
                        actual.updateAllFlags(flags)
                    }
                }
            }) { scene ->
                val repository = Injekt.get<CategoryRepository>()
                repository.insert(Category(0, "Sort retry", 0, 12))
                val category = repository.getAll().single { it.name == "Sort retry" }
                val preferences = Injekt.get<LibraryPreferences>()
                preferences.categorizedDisplaySettings().set(true)
                scene.mountLibrary()
                val title = MR.strings.categorized_display_settings.localized()
                scene.renderUntil { scene.text().contains(title) }
                scene.click(title)
                scene.renderUntil { scene.text().contains(MR.strings.internal_error.localized()) }
                assertTrue(
                    preferences.categorizedDisplaySettings().get(),
                    "A rejected reset must not disable category sorting",
                )
                assertTrue(scene.text().contains(MR.strings.internal_error.localized()))
                assertEquals(12L, requireNotNull(repository.get(category.id)).flags)
                scene.click(title)
                scene.renderUntil { !preferences.categorizedDisplaySettings().get() }
                assertEquals(preferences.sortingMode().get().flag, requireNotNull(repository.get(category.id)).flags)
            }
        }
    }

    @Test
    fun `category scope cannot open before the actual category snapshot arrives`(@TempDir root: File) = runBlocking {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        withSettings(root, categoryAdapter = { actual ->
            object : CategoryRepository by actual {
                override fun getAllAsFlow() = kotlinx.coroutines.flow.flow {
                    release.await()
                    emitAll(actual.getAllAsFlow())
                }
            }
        }) { scene ->
            try {
                val repository = Injekt.get<CategoryRepository>()
                repository.insert(Category(0, "Delayed actual", 0, 0))
                val category = repository.getAll().single { it.name == "Delayed actual" }
                Injekt.get<LibraryPreferences>().updateCategories().set(setOf(category.id.toString()))
                scene.mountLibrary()
                scene.renderUntil { scene.text().contains(MR.strings.categories.localized()) }
                assertTrue(
                    scene.nodes().any {
                        MR.strings.categories.localized() in scene.labels(it) &&
                            it.config.contains(SemanticsActions.OnClick) &&
                            it.config.contains(SemanticsProperties.Disabled)
                    },
                    "The scope entry must wait for the repository's first complete category snapshot",
                )
                release.complete(Unit)
                scene.renderUntil { scene.categoryEntryReady() }
                scene.click(MR.strings.categories.localized())
                scene.renderUntil { scene.text().contains(category.name) }
                assertEquals(androidx.compose.ui.state.ToggleableState.On, scene.categoryState(category.name))
                scene.click(MR.strings.action_cancel.localized())
                assertEquals(setOf(category.id.toString()), Injekt.get<LibraryPreferences>().updateCategories().get())
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun `invalid whole library scope still permits an explicit current category update`(
        @TempDir root: File,
    ) = runBlocking {
        val updated = mutableListOf<Long>()
        withSettings(root, updateManga = {
            updated += it.id
            LibraryUpdateChecker.UpdateResult(0)
        }) { _ ->
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Current actual", 0, 0))
            val category = categories.getAll().single { it.name == "Current actual" }
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 123, url = "/current", title = "Current")),
            ).single()
            repository.updateAtomically(LibraryMembershipUpdate(manga.id, true, 100, listOf(category.id)))
            Injekt.get<LibraryPreferences>().updateCategories().set(setOf("BROKEN"))
            val scheduler = Injekt.get<LibraryUpdateScheduler>()
            scheduler.runNow().join()
            assertTrue(updated.isEmpty(), "Invalid whole-library include must not widen to all")
            scheduler.runNow(category.id).join()
            assertEquals(
                listOf(manga.id),
                updated,
                "An explicit category never consumes the invalid whole-library scope",
            )
        }
    }

    @Test
    fun `failed correction of invalid include and exclude cannot activate a sanitized policy`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = false
        withSettings(root, storeAdapter = { actual ->
            object : PreferenceStore by actual {
                override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                    val preference = actual.getStringSet(key, defaultValue)
                    return object : Preference<Set<String>> by preference {
                        override fun set(value: Set<String>) {
                            if (reject &&
                                key == "library_update_categories_exclude"
                            ) {
                                throw IOException("Rejected correction")
                            }
                            preference.set(value)
                        }
                    }
                }
            }
        }) { _ ->
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Valid old", 0, 0))
            categories.insert(Category(0, "Valid new", 1, 0))
            val a = categories.getAll().single { it.name == "Valid old" }.id
            val b = categories.getAll().single { it.name == "Valid new" }.id
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.updateCategories().set(setOf(a.toString(), "BROKEN"))
            preferences.updateCategoriesExclude().set(setOf("BROKEN"))
            val policy = Injekt.get<DesktopLibraryCategoryPolicy>()
            assertFalse(policy.recover())
            reject = true
            assertFalse(policy.save(setOf(b), emptySet()))
            reject = false
            assertFalse(
                policy.recover(),
                "A rejected correction cannot turn an invalid old policy into an active subset",
            )
            assertTrue(policy.state.value is DesktopLibraryCategoryPolicy.State.Unavailable)
            assertTrue(policy.save(emptySet(), emptySet()), "Only explicit valid confirmation may choose all")
            assertEquals(DesktopLibraryCategoryPolicy.Snapshot(emptySet(), emptySet()), policy.snapshot())
        }
    }

    @Test
    fun `real category policy dialog retains draft and complete old values after pre and post write failures`(
        @TempDir root: File,
    ) = runBlocking {
        for (afterWrite in listOf(false, true)) {
            var rejectNext = false
            withSettings(File(root, if (afterWrite) "after" else "before"), storeAdapter = { actual ->
                object : PreferenceStore by actual {
                    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                        val preference = actual.getStringSet(key, defaultValue)
                        return object : Preference<Set<String>> by preference {
                            override fun set(value: Set<String>) {
                                if (rejectNext && key == "library_update_categories_exclude") {
                                    rejectNext = false
                                    if (afterWrite) preference.set(value)
                                    throw IOException("Rejected policy write")
                                }
                                preference.set(value)
                            }
                        }
                    }
                }
            }) { scene ->
                val categories = Injekt.get<CategoryRepository>()
                categories.insert(Category(0, "Draft actual", 0, 0))
                val category = categories.getAll().single { it.name == "Draft actual" }
                val preferences = Injekt.get<LibraryPreferences>()
                preferences.updateCategories().set(setOf(category.id.toString()))
                preferences.updateCategoriesExclude().set(emptySet())
                scene.mountLibrary()
                scene.renderUntil { scene.categoryEntryReady() }
                scene.click(MR.strings.categories.localized())
                scene.renderUntil { scene.text().contains(MR.strings.action_cancel.localized()) }
                assertEquals(
                    androidx.compose.ui.state.ToggleableState.On,
                    scene.categoryState(category.name),
                    "Opening must retain the persisted include before editing",
                )
                scene.click(category.name)
                rejectNext = true
                scene.click(MR.strings.action_ok.localized())
                scene.renderUntil { scene.text().contains(MR.strings.internal_error.localized()) }
                assertEquals(setOf(category.id.toString()), preferences.updateCategories().get())
                assertEquals(emptySet<String>(), preferences.updateCategoriesExclude().get())
                assertEquals(
                    DesktopLibraryCategoryPolicy.Snapshot(setOf(category.id), emptySet()),
                    Injekt.get<DesktopLibraryCategoryPolicy>().snapshot(),
                )
                val row = scene.nodes().last {
                    category.name in scene.labels(it) &&
                        it.config.contains(SemanticsActions.OnClick)
                }
                assertEquals(
                    androidx.compose.ui.state.ToggleableState.Indeterminate,
                    row.config[SemanticsProperties.ToggleableState],
                )
                scene.click(MR.strings.action_ok.localized())
                scene.renderUntil { !scene.text().contains(MR.strings.action_cancel.localized()) }
                assertEquals(emptySet<String>(), preferences.updateCategories().get())
                assertEquals(setOf(category.id.toString()), preferences.updateCategoriesExclude().get())
            }
        }
    }

    @Test
    fun `interrupted old snapshot cannot revive deleted category or widen scope across two startups`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/library-policy-cross-${UUID.randomUUID()}")
        val actual = DesktopPreferenceStore(node)
        var reject = false
        val failing = object : PreferenceStore by actual {
            override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                val preference = actual.getStringSet(key, defaultValue)
                return object : Preference<Set<String>> by preference {
                    override fun set(value: Set<String>) {
                        if (reject &&
                            key == "library_update_categories_exclude"
                        ) {
                            throw IOException("Interrupted policy compensation")
                        }
                        preference.set(value)
                    }
                }
            }
        }
        var context = initDesktopDIForTest(root, failing, startDownloadWorker = false)
        try {
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Gone include", 0, 0))
            categories.insert(Category(0, "Remain exclude", 1, 0))
            val a = categories.getAll().single { it.name == "Gone include" }.id
            val b = categories.getAll().single { it.name == "Remain exclude" }.id
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.updateCategories().set(setOf(a.toString()))
            preferences.updateCategoriesExclude().set(setOf(b.toString()))
            reject = true
            assertFalse(Injekt.get<DesktopLibraryCategoryPolicy>().save(setOf(b), emptySet()))
            assertEquals(DeleteCategory.Result.Success, Injekt.get<DeleteCategory>().await(a))
            assertEquals(null, categories.get(a))
            context.closeAndJoin()
            reject = false
            repeat(2) {
                context = initDesktopDIForTest(root, actual, startDownloadWorker = false)
                val restored = Injekt.get<LibraryPreferences>()
                assertFalse(a.toString() in restored.updateCategories().get())
                assertFalse(a.toString() in restored.updateCategoriesExclude().get())
                val policy = Injekt.get<DesktopLibraryCategoryPolicy>()
                assertFalse(
                    policy.recover(),
                    "Deleting every original include needs an explicit choice after every restart",
                )
                assertEquals(DesktopLibraryCategoryPolicy.State.Unavailable(false), policy.state.value)
                if (it == 0) context.closeAndJoin()
            }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val scene = PolicyScene(kotlinx.coroutines.currentCoroutineContext())
            try {
                scene.mountLibrary()
                scene.renderUntil { scene.categoryEntryReady() }
                scene.click(MR.strings.categories.localized())
                scene.renderUntil { scene.text().contains("Remain exclude") }
                assertEquals(
                    androidx.compose.ui.state.ToggleableState.Indeterminate,
                    scene.categoryState("Remain exclude"),
                    "Opening must retain the recovered exclude before editing",
                )
                scene.click("Remain exclude")
                scene.click(MR.strings.action_ok.localized())
                scene.renderUntil { !scene.text().contains(MR.strings.action_cancel.localized()) }
            } finally {
                scene.close()
                Dispatchers.resetMain()
            }
            assertEquals(
                DesktopLibraryCategoryPolicy.Snapshot(emptySet(), emptySet()),
                Injekt.get<DesktopLibraryCategoryPolicy>().snapshot(),
            )
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `actual detail asks and cancels before membership then consumes system and custom defaults`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Add target", 0, 0))
            val target = categories.getAll().single { it.name == "Add target" }
            val repository = Injekt.get<MangaRepository>()
            val manga = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/ask", title = "Ask actual")),
            ).single()
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.defaultCategory().set(-1)
            scene.mountDetail(manga.id)
            scene.renderUntil { scene.text().contains(MR.strings.add_to_library.localized()) }
            scene.click(MR.strings.add_to_library.localized())
            scene.renderUntil { scene.text().contains(MR.strings.action_cancel.localized()) }
            assertFalse(repository.getMangaById(manga.id).favorite)
            scene.click(MR.strings.action_cancel.localized())
            scene.renderUntil { !scene.text().contains(MR.strings.action_cancel.localized()) }
            assertFalse(repository.getMangaById(manga.id).favorite)
            preferences.defaultCategory().set(0)
            scene.click(MR.strings.add_to_library.localized())
            scene.renderUntil { repository.getMangaById(manga.id).favorite }
            assertTrue(categories.getCategoriesByMangaId(manga.id).filterNot(Category::isSystemCategory).isEmpty())
            val second = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/custom", title = "Custom actual")),
            ).single()
            preferences.defaultCategory().set(target.id.toInt())
            scene.mountDetail(second.id)
            scene.renderUntil { scene.text().contains(MR.strings.add_to_library.localized()) }
            scene.click(MR.strings.add_to_library.localized())
            scene.renderUntil { repository.getMangaById(second.id).favorite }
            assertEquals(listOf(target.id), categories.getCategoriesByMangaId(second.id).map(Category::id))
            assertTrue(
                categories.getCategoriesByMangaId(manga.id).filterNot(Category::isSystemCategory).isEmpty(),
                "Changing the default never moves the previous favorite",
            )
            assertEquals(DeleteCategory.Result.Success, Injekt.get<DeleteCategory>().await(target.id))
            assertEquals(-1, preferences.defaultCategory().get())
            val third = repository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/no-custom", title = "No custom actual")),
            ).single()
            scene.mountDetail(third.id)
            scene.renderUntil { scene.text().contains(MR.strings.add_to_library.localized()) }
            scene.click(MR.strings.add_to_library.localized())
            scene.renderUntil { repository.getMangaById(third.id).favorite }
            assertTrue(
                categories.getCategoriesByMangaId(third.id).filterNot(Category::isSystemCategory).isEmpty(),
                "SOURCE always-ask falls back to system default when no custom categories exist",
            )
            assertTrue(repository.getMangaById(second.id).favorite)
        }
    }

    @Test
    fun `shared update categories drive the actual scheduler with exclusion taking precedence`(
        @TempDir root: File,
    ) = runBlocking {
        val updated = mutableListOf<Long>()
        withSettings(root, updateManga = { manga ->
            updated += manga.id
            LibraryUpdateChecker.UpdateResult(0)
        }) { _ ->
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Include A", 0, 0))
            categories.insert(Category(0, "Exclude B", 1, 0))
            val a = categories.getAll().single { it.name == "Include A" }.id
            val b = categories.getAll().single { it.name == "Exclude B" }.id
            val repository = Injekt.get<MangaRepository>()
            val entries = repository.insertNetworkManga(
                listOf("Only A", "Only B", "Both", "Default").mapIndexed {
                        index,
                        title,
                    ->
                    Manga.create().copy(source = 123, url = "/scope-$index", title = title)
                },
            )
            val memberships = listOf(listOf(a), listOf(b), listOf(a, b), emptyList())
            entries.zip(memberships).forEach { (manga, ids) ->
                repository.updateAtomically(LibraryMembershipUpdate(manga.id, true, 100, ids))
            }
            Injekt.get<LibraryPreferences>().updateCategories().set(setOf(a.toString()))
            Injekt.get<LibraryPreferences>().updateCategoriesExclude().set(setOf(b.toString()))
            Injekt.get<LibraryUpdateScheduler>().runNow().join()
            assertEquals(
                listOf(entries.first().id),
                updated,
                "Scheduler must consume the complete shared policy instead of legacy CSV",
            )
            updated.clear()
            Injekt.get<LibraryUpdateScheduler>().runNow(categoryId = b).join()
            assertEquals(
                setOf(entries[1].id, entries[2].id),
                updated.toSet(),
                "Explicit current-category update keeps its existing override",
            )
        }
    }

    @Test
    fun `persistent startup recovery refusal blocks the actual update task instead of consuming half cleared scope`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/library-persistent-recovery-${UUID.randomUUID()}")
        val actual = DesktopPreferenceStore(node)
        var reject = false
        val failing = object : PreferenceStore by actual {
            override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                val preference = actual.getStringSet(key, defaultValue)
                return object : Preference<Set<String>> by preference {
                    override fun set(value: Set<String>) {
                        if (reject &&
                            key == "library_update_categories_exclude"
                        ) {
                            throw IOException("Persistent recovery refusal")
                        }
                        preference.set(value)
                    }
                }
            }
        }
        val updated = mutableListOf<Long>()
        val updater: suspend (
            Manga,
        ) -> LibraryUpdateChecker.UpdateResult = {
            updated += it.id
            LibraryUpdateChecker.UpdateResult(0)
        }
        var context = initDesktopDIForTest(root, failing, updateManga = updater, startDownloadWorker = false)
        try {
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Interrupted scope", 0, 0))
            val category = categories.getAll().single { it.name == "Interrupted scope" }
            val mangaRepository = Injekt.get<MangaRepository>()
            val manga = mangaRepository.insertNetworkManga(
                listOf(Manga.create().copy(source = 0, url = "/interrupted", title = "Still favorite")),
            ).single()
            mangaRepository.updateAtomically(LibraryMembershipUpdate(manga.id, true, 100, listOf(category.id)))
            val preferences = Injekt.get<LibraryPreferences>()
            preferences.updateCategories().set(setOf(category.id.toString()))
            preferences.updateCategoriesExclude().set(setOf(category.id.toString()))
            reject = true
            assertTrue(Injekt.get<DeleteCategory>().await(category.id) is DeleteCategory.Result.InternalError)
            assertEquals(null, categories.get(category.id))
            context.closeAndJoin()
            context = initDesktopDIForTest(root, failing, updateManga = updater, startDownloadWorker = false)
            val scheduler = Injekt.get<LibraryUpdateScheduler>()
            scheduler.runNow().join()
            assertTrue(updated.isEmpty(), "Failed startup recovery must not allow an update to consume half a scope")
            assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()?.status)
            assertTrue(Injekt.get<LibraryPreferences>().categoryDeletionPending().get().isNotEmpty())
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `update categories edit one three state draft and cancel leaves both shared keys unchanged`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            val categories = Injekt.get<CategoryRepository>()
            categories.insert(Category(0, "Scope A", 0, 0))
            categories.insert(Category(0, "Scope B", 1, 0))
            categories.insert(Category(0, "Scope overlap", 2, 0))
            val a = categories.getAll().single { it.name == "Scope A" }
            val b = categories.getAll().single { it.name == "Scope B" }
            val overlap = categories.getAll().single { it.name == "Scope overlap" }
            val preferences = Injekt.get<LibraryPreferences>()
            val originalIncluded = setOf(a.id.toString(), overlap.id.toString())
            val originalExcluded = setOf(b.id.toString(), overlap.id.toString())
            preferences.updateCategories().set(originalIncluded)
            preferences.updateCategoriesExclude().set(originalExcluded)
            scene.mountLibrary()
            scene.renderUntil { scene.categoryEntryReady() }
            val title = MR.strings.categories.localized()
            assertTrue(
                scene.nodes().any {
                    title in scene.labels(it) && it.config.contains(SemanticsActions.OnClick)
                },
                "Global updates need one actual category-policy dialog",
            )
            scene.click(title)
            scene.renderUntil { scene.text().contains(MR.strings.action_cancel.localized()) }
            scene.click(a.name)
            assertEquals(originalIncluded, preferences.updateCategories().get())
            assertEquals(originalExcluded, preferences.updateCategoriesExclude().get())
            scene.click(MR.strings.action_cancel.localized())
            scene.renderUntil { !scene.text().contains(MR.strings.action_cancel.localized()) }
            assertEquals(originalIncluded, preferences.updateCategories().get())
            assertEquals(originalExcluded, preferences.updateCategoriesExclude().get())
            scene.click(title)
            scene.renderUntil { scene.text().contains(MR.strings.action_cancel.localized()) }
            scene.click(a.name)
            scene.click(b.name)
            assertEquals(androidx.compose.ui.state.ToggleableState.Indeterminate, scene.categoryState(overlap.name))
            scene.click(overlap.name)
            scene.renderUntil { true }
            assertEquals(
                androidx.compose.ui.state.ToggleableState.Off,
                scene.categoryState(overlap.name),
                "Exclude wins overlap, then one activation clears both sides",
            )
            scene.click(MR.strings.label_default.localized())
            scene.click(MR.strings.action_ok.localized())
            scene.renderUntil { !scene.text().contains(MR.strings.action_cancel.localized()) }
            assertEquals(setOf("0"), preferences.updateCategories().get())
            assertEquals(setOf(a.id.toString()), preferences.updateCategoriesExclude().get())
        }
    }

    @Test
    fun `actual Library settings selects shared default category without moving existing favorites`(
        @TempDir root: File,
    ) = runBlocking {
        withSettings(root) { scene ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Default target", 0, 0))
            val category = repository.getAll().single { it.name == "Default target" }
            val mangas = Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>()
            val favorite = mangas.insertNetworkManga(
                listOf(
                    tachiyomi.domain.manga.model.Manga.create().copy(
                        source = 0,
                        url = "/existing",
                        title = "Existing favorite",
                    ),
                ),
            ).single()
            mangas.updateAtomically(
                tachiyomi.domain.manga.repository.LibraryMembershipUpdate(
                    mangaId = favorite.id,
                    favorite = true,
                    dateAdded = 123,
                    categoryIds = listOf(category.id),
                ),
            )
            scene.mountLibrary()
            scene.renderUntil { true }
            val title = MR.strings.default_category.localized()
            assertTrue(
                scene.nodes().any { title in scene.labels(it) && it.config.contains(SemanticsActions.OnClick) },
                "Library settings must expose the actual shared default-category control",
            )
            scene.click(title)
            scene.renderUntil { scene.text().contains(category.name) }
            scene.click(category.name)
            scene.renderUntil { Injekt.get<LibraryPreferences>().defaultCategory().get() == category.id.toInt() }
            scene.click(title)
            scene.renderUntil { scene.text().contains(MR.strings.default_category_summary.localized()) }
            scene.click(MR.strings.default_category_summary.localized())
            scene.renderUntil { Injekt.get<LibraryPreferences>().defaultCategory().get() == -1 }
            scene.click(title)
            scene.renderUntil { scene.text().contains(MR.strings.label_default.localized()) }
            scene.click(MR.strings.label_default.localized())
            scene.renderUntil { Injekt.get<LibraryPreferences>().defaultCategory().get() == 0 }
            assertTrue(mangas.getMangaById(favorite.id).favorite)
            assertEquals(listOf(category.id), repository.getCategoriesByMangaId(favorite.id).map { it.id })
        }
    }

    @Test
    fun `delete committed category returns failure and retries all five references after preference rejection`(
        @TempDir root: File,
    ) = runBlocking {
        var reject = false
        withSettings(root, storeAdapter = { actual ->
            object : PreferenceStore by actual {
                override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                    val pref = actual.getStringSet(key, defaultValue)
                    return object : Preference<Set<String>> by pref {
                        override fun set(value: Set<String>) {
                            if (reject &&
                                key == "library_update_categories_exclude"
                            ) {
                                throw IOException("Rejected category cleanup")
                            }
                            pref.set(value)
                        }
                    }
                }
            }
        }) { _ ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Delete target", 0, 0))
            val category = repository.getAll().single { it.name == "Delete target" }
            val library = Injekt.get<LibraryPreferences>()
            val downloads = Injekt.get<DownloadPreferences>()
            library.defaultCategory().set(category.id.toInt())
            val references = listOf(
                library.updateCategories(),
                library.updateCategoriesExclude(),
                downloads.removeExcludeCategories(),
                downloads.downloadNewChapterCategories(),
                downloads.downloadNewChapterCategoriesExclude(),
            )
            references.forEach { it.set(setOf(category.id.toString())) }
            reject = true
            var result: DeleteCategory.Result? = null
            assertDoesNotThrow { runBlocking { result = Injekt.get<DeleteCategory>().await(category.id) } }
            assertTrue(result is DeleteCategory.Result.InternalError)
            assertEquals(null, repository.get(category.id), "SQL deletion may be complete before preference failure")
            reject = false
            assertEquals(DeleteCategory.Result.Success, Injekt.get<DeleteCategory>().await(category.id))
            assertEquals(-1, library.defaultCategory().get())
            assertTrue(references.all { it.get().isEmpty() })
        }
    }

    @Test
    fun `delete retry resumes references without deleting an already committed category again`(
        @TempDir root: File,
    ) = runBlocking {
        var afterCommitFailure = true
        withSettings(root, categoryAdapter = { actual ->
            object : CategoryRepository by actual {
                override suspend fun delete(categoryId: Long) {
                    if (actual.get(categoryId) ==
                        null
                    ) {
                        throw IOException("Already committed delete must not execute again")
                    }
                    actual.delete(categoryId)
                    if (afterCommitFailure) throw IOException("SQL committed before transport failure")
                }
            }
        }) { _ ->
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Committed target", 0, 0))
            val category = repository.getAll().single { it.name == "Committed target" }
            val library = Injekt.get<LibraryPreferences>()
            library.defaultCategory().set(category.id.toInt())
            library.updateCategories().set(setOf(category.id.toString()))
            Injekt.get<DeleteCategory>().await(category.id)
            assertEquals(null, repository.get(category.id))
            afterCommitFailure = false
            assertEquals(DeleteCategory.Result.Success, Injekt.get<DeleteCategory>().await(category.id))
            assertEquals(-1, library.defaultCategory().get())
            assertTrue(library.updateCategories().get().isEmpty())
        }
    }

    @Test
    fun `real startup resumes deleted category references before consumers read them`(
        @TempDir root: File,
    ) = runBlocking {
        val node = Preferences.userRoot().node("mihon-tests/library-recovery-${UUID.randomUUID()}")
        val actual = DesktopPreferenceStore(node)
        var reject = false
        val failing = object : PreferenceStore by actual {
            override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
                val pref = actual.getStringSet(key, defaultValue)
                return object : Preference<Set<String>> by pref {
                    override fun set(value: Set<String>) {
                        if (reject &&
                            key == "library_update_categories_exclude"
                        ) {
                            throw IOException("Interrupted reference cleanup")
                        }
                        pref.set(value)
                    }
                }
            }
        }
        var context = initDesktopDIForTest(root, failing, startDownloadWorker = false)
        try {
            val repository = Injekt.get<CategoryRepository>()
            repository.insert(Category(0, "Restart target", 0, 0))
            val category = repository.getAll().single { it.name == "Restart target" }
            val library = Injekt.get<LibraryPreferences>()
            val downloads = Injekt.get<DownloadPreferences>()
            listOf(
                library.updateCategories(),
                library.updateCategoriesExclude(),
                downloads.removeExcludeCategories(),
                downloads.downloadNewChapterCategories(),
                downloads.downloadNewChapterCategoriesExclude(),
            ).forEach {
                it.set(setOf(category.id.toString()))
            }
            library.defaultCategory().set(category.id.toInt())
            reject = true
            runCatching { Injekt.get<DeleteCategory>().await(category.id) }
            assertEquals(null, repository.get(category.id))
            context.closeAndJoin()
            reject = false
            context = initDesktopDIForTest(root, actual, startDownloadWorker = false)
            val restoredLibrary = Injekt.get<LibraryPreferences>()
            val restoredDownloads = Injekt.get<DownloadPreferences>()
            assertEquals(-1, restoredLibrary.defaultCategory().get())
            assertTrue(
                listOf(
                    restoredLibrary.updateCategories(),
                    restoredLibrary.updateCategoriesExclude(),
                    restoredDownloads.removeExcludeCategories(),
                    restoredDownloads.downloadNewChapterCategories(),
                    restoredDownloads.downloadNewChapterCategoriesExclude(),
                ).all { it.get().isEmpty() },
            )
        } finally {
            context.closeAndJoin()
            node.removeNode()
        }
    }

    private suspend fun withSettings(
        root: File,
        storeAdapter: (PreferenceStore) -> PreferenceStore = { it },
        categoryAdapter: ((CategoryRepository) -> CategoryRepository)? = null,
        updateManga: (suspend (Manga) -> LibraryUpdateChecker.UpdateResult)? = null,
        size: IntSize = IntSize(900, 760),
        fontScale: Float = 1f,
        deviceConditions: mihon.desktop.platform.DesktopDeviceConditions? = null,
        block: suspend (PolicyScene) -> Unit,
    ) {
        val node = Preferences.userRoot().node("mihon-tests/library-policy-${UUID.randomUUID()}")
        val context = initDesktopDIForTest(
            root,
            storeAdapter(DesktopPreferenceStore(node)),
            startDownloadWorker = false,
            categoryRepositoryOverride = categoryAdapter,
            updateManga = updateManga,
            deviceConditionsOverride = deviceConditions,
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scene = PolicyScene(kotlinx.coroutines.currentCoroutineContext(), size, fontScale)
        try {
            block(scene)
        } finally {
            scene.close()
            context.closeAndJoin()
            Dispatchers.resetMain()
            node.removeNode()
        }
    }

    private class PolicyScene(
        context: CoroutineContext,
        size: IntSize = IntSize(
            900,
            760,
        ),
        private val fontScale: Float = 1f,
    ) : AutoCloseable {
        private val owners = linkedSetOf<SemanticsOwner>()
        private var windowSize by androidx.compose.runtime.mutableStateOf(size)
        private val bitmap = ImageBitmap(900, 900)
        var windowFocused by androidx.compose.runtime.mutableStateOf(true)
        var rootModel: mihon.desktop.ui.library.LibraryScreenModel? = null
        lateinit var navigator: Navigator
        private val canvas = Canvas(bitmap)
        private val scene = CanvasLayersComposeScene(
            size = size,
            coroutineContext = context,
            platformContext = object : PlatformContext {
                override val windowInfo = object : WindowInfo {
                    override val isWindowFocused get() = windowFocused
                    override val containerSize get() = windowSize
                }
                override val inputModeManager = object : InputModeManager {
                    override val inputMode = InputMode.Keyboard
                    override fun requestInputMode(inputMode: InputMode) = true
                }
                override fun requestFocus() = true
                override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
                        owners += semanticsOwner
                    }
                    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
                        owners -= semanticsOwner
                    }
                    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit
                    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
                }
            },
            invalidate = {},
        )
        fun mountLibrary(withParent: Boolean = false) = scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt(),
                LocalDensity provides Density(1f, fontScale),
            ) {
                DesktopTheme {
                    val pages = if (withParent) {
                        listOf(
                            object : cafe.adriel.voyager.core.screen.Screen {
                                override val key = "library-settings-parent"

                                @androidx.compose.runtime.Composable override fun Content() {
                                    androidx.compose.material3.Text("Settings parent")
                                }
                            },
                            LibrarySettingsScreen(),
                        )
                    } else {
                        listOf(LibrarySettingsScreen())
                    }
                    Navigator(pages) {
                        navigator = it
                        CurrentScreen()
                    }
                }
            }
        }
        fun mountRoot() = scene.setContent {
            CompositionLocalProvider(
                mihon.desktop.ui.library.LocalLibraryScreenModelFactory provides {
                    mihon.desktop.library.LibraryScreenModelFactory.create().also { rootModel = it }
                },
                LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt(),
                LocalDensity provides Density(1f, fontScale),
            ) {
                DesktopTheme {
                    Navigator(mihon.desktop.ui.library.LibraryRootScreen()) {
                        navigator = it
                        CurrentScreen()
                    }
                }
            }
        }
        fun mountSettings() = scene.setContent {
            CompositionLocalProvider(
                LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt(),
                LocalDensity provides Density(1f, fontScale),
            ) {
                DesktopTheme {
                    Navigator(SettingsRootScreen()) {
                        navigator = it
                        CurrentScreen()
                    }
                }
            }
        }
        fun mountDetail(id: Long) = scene.setContent {
            CompositionLocalProvider(LocalDesktopUiDependencies provides DesktopUiDependencies.fromInjekt()) {
                DesktopTheme {
                    androidx.compose.runtime.key(id) { Navigator(MangaDetailScreen(id)) { CurrentScreen() } }
                }
            }
        }
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = owners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun activeNodes() = flatten(owners.last().unmergedRootSemanticsNode)
        fun activeFocused() = activeNodes().singleOrNull { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        fun ownerCount() = owners.size
        fun requestFocus(label: String) {
            val target = activeNodes().last { label in labels(it) && it.config.contains(SemanticsActions.RequestFocus) }
            assertTrue(requireNotNull(target.config[SemanticsActions.RequestFocus].action).invoke())
        }
        fun setText(text: String) {
            val target = nodes().single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(
                requireNotNull(
                    target.config[SemanticsActions.SetText].action,
                ).invoke(androidx.compose.ui.text.AnnotatedString(text)),
            )
        }
        fun key(key: Key, shift: Boolean = false) {
            val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            val factory = events.declaredMethods.single {
                it.name.startsWith("KeyEvent-") &&
                    !it.name.endsWith("\$default")
            }
            for (type in listOf("access\$getKeyDown\$cp", "access\$getKeyUp\$cp")) {
                val value = eventType.getMethod(type).invoke(null)
                scene.sendKeyEvent(
                    ComposeKeyEvent(
                        factory.invoke(
                            null, key.keyCode, value, key.nativeKeyLocation, false, false, false, shift, null,
                        ),
                    ),
                )
            }
        }
        fun savePng(file: File) {
            file.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                val pixels = javax.imageio.ImageIO.read(
                    java.io.ByteArrayInputStream(requireNotNull(image.encodeToData()).bytes),
                )
                javax.imageio.ImageIO.write(pixels.getSubimage(0, 0, windowSize.width, windowSize.height), "png", file)
            }
        }
        fun resize(width: Int, height: Int) {
            windowSize = IntSize(width, height)
            scene.size = windowSize
        }
        fun wheel(position: androidx.compose.ui.geometry.Offset, delta: Float) {
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, position)
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Scroll,
                position,
                scrollDelta = androidx.compose.ui.geometry.Offset(0f, delta),
            )
        }
        fun pointerClick(
            position: androidx.compose.ui.geometry.Offset,
            modifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        ) {
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Press,
                position,
                keyboardModifiers = modifiers,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true),
                button = androidx.compose.ui.input.pointer.PointerButton.Primary,
            )
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Release,
                position,
                keyboardModifiers = modifiers,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(),
                button = androidx.compose.ui.input.pointer.PointerButton.Primary,
            )
        }
        suspend fun scrollToControl(label: String) {
            repeat(4) {
                renderUntil { true }
                val target = nodes().last { label in labels(it) && it.config.contains(SemanticsActions.OnClick) }
                val bounds = target.boundsInRoot
                if (bounds.top >= 80 && bounds.bottom <= windowSize.height - 8) return
                val scroll = nodes().single { it.config.contains(SemanticsActions.ScrollBy) }
                val amount = if (bounds.top < 80) bounds.top - 80 else bounds.bottom - (windowSize.height - 8)
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, amount))
            }
            renderUntil { true }
        }
        fun labels(node: SemanticsNode): List<String> = flatten(node).flatMap {
            buildList {
                if (it.config.contains(SemanticsProperties.Text)) {
                    addAll(
                        it.config[SemanticsProperties.Text].map { value ->
                            value.text
                        },
                    )
                }
                if (it.config.contains(
                        SemanticsProperties.ContentDescription,
                    )
                ) {
                    addAll(it.config[SemanticsProperties.ContentDescription])
                }
            }
        }
        val ownerCount get() = owners.size
        fun text() = nodes().flatMap(::labels)
        fun categoryEntryReady() = nodes().any {
            MR.strings.categories.localized() in labels(it) && it.config.contains(SemanticsActions.OnClick) &&
                !it.config.contains(SemanticsProperties.Disabled)
        }
        fun categoryState(label: String) = nodes().last {
            label in labels(it) && it.config.contains(SemanticsProperties.ToggleableState)
        }.config[SemanticsProperties.ToggleableState]
        fun click(label: String) {
            val target = nodes().last { label in labels(it) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
        }
        suspend fun renderUntil(
            message: String = "production UI condition did not arrive",
            predicate: suspend () -> Boolean,
        ) {
            repeat(80) {
                scene.render(canvas, System.nanoTime())
                delay(10)
                if (predicate()) return
            }
            assertTrue(predicate(), message)
        }
        override fun close() = scene.close()
    }
}
