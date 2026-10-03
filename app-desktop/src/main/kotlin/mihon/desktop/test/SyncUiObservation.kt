package mihon.desktop.test

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mihon.presentation.sync.LocalSyncUiObserver
import mihon.presentation.sync.SyncUiControl
import mihon.presentation.sync.SyncUiObserver
import java.awt.Window
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

internal class SyncUiRegistry : SyncUiObserver {
    private var running = false
    private var generation = 0L
    private val controls = linkedMapOf<Any, SyncUiControl>()
    private var window: Window? = null
    private val tags = setOf("sync-open", "sync-back", "sync-close", "sync-settings", "sync-now", "sync-history", "sync-drag-handle",
        "sync-settings-history", "sync-settings-connect", "sync-disconnect", "sync-switch", "sync-password-help")

    @Synchronized
    override fun observes(tag: String) = running && tag in tags

    @Synchronized
    override fun update(token: Any, control: SyncUiControl) {
        if (observes(control.tag)) controls[token] = control
    }
    @Synchronized
    override fun remove(token: Any) { controls.remove(token) }

    @Synchronized
    fun start() {
        running = true
        generation++
        controls.clear()
        window = null
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        controls.clear()
        window = null
    }

    @Synchronized
    fun bindWindow(value: Window): AutoCloseable {
        val boundGeneration = generation
        if (running) window = value
        return AutoCloseable {
            synchronized(this) {
                if (generation == boundGeneration && window === value) {
                    window = null
                    controls.clear()
                }
            }
        }
    }

    @Synchronized
    fun observer(): SyncUiObserver {
        val capturedGeneration = generation
        return object : SyncUiObserver {
            override fun observes(tag: String) = synchronized(this@SyncUiRegistry) {
                capturedGeneration == generation && this@SyncUiRegistry.observes(tag)
            }
            override fun update(token: Any, control: SyncUiControl) = synchronized(this@SyncUiRegistry) {
                if (capturedGeneration == generation) this@SyncUiRegistry.update(token, control)
            }
            override fun remove(token: Any) = synchronized(this@SyncUiRegistry) {
                if (capturedGeneration == generation) this@SyncUiRegistry.remove(token)
            }
        }
    }

    /** Called on EDT by the HTTP adapter; layout suppliers use each control's actual owner. */
    @Synchronized
    fun snapshot(
        window: JsonObject? = null,
        focusedBounds: List<java.awt.geom.Rectangle2D.Float> = emptyList(),
    ): JsonObject {
        val records = if (running) controls.values.mapNotNull { control ->
            val bounds = try { control.currentScreenBounds?.invoke() } catch (_: RuntimeException) { null }
            if (control.currentScreenBounds != null && bounds == null) return@mapNotNull null
            val left = bounds?.left ?: control.screenLeft
            val top = bounds?.top ?: control.screenTop
            val width = bounds?.width ?: control.width
            val height = bounds?.height ?: control.height
            val scale = control.density
            if (!listOf(left, top, width, height, scale).all { it.isFinite() } || scale <= 0 || width <= 0 || height <= 0) return@mapNotNull null
            buildJsonObject {
                put("tag", control.tag)
                put("group", if (control.tag == "sync-open") "toolbar" else "panel")
                put("focused", if (control.tag == "sync-drag-handle") syncHandleFocused(left / scale, top / scale, width / scale, height / scale, focusedBounds) else control.focused)
                put("ownerFocused", control.ownerFocused)
                put("enabled", control.enabled)
                put("density", scale)
                putJsonObject("bounds") {
                    put("x", left / scale)
                    put("y", top / scale)
                    put("width", width / scale)
                    put("height", height / scale)
                }
            }
        } else emptyList()
        val activeWindow = window.takeIf { running }
        return buildJsonObject {
            put("ready", activeWindow != null && records.any { it["tag"].toString() == "\"sync-open\"" })
            put("pid", ProcessHandle.current().pid())
            put("coordinateSystem", "awt-screen-points")
            activeWindow?.let { put("window", it) }
            putJsonArray("controls") { if (activeWindow != null) records.forEach { add(it) } }
        }
    }

    fun liveSnapshot(): JsonObject {
        var result: JsonObject? = null
        val capture = Runnable {
            val bound = synchronized(this) { window }
            val metadata = try {
                bound?.takeIf { it.isShowing }?.let { actual ->
                    buildJsonObject {
                        put("active", actual.isActive)
                        put("focused", actual.isFocused)
                        put("density", actual.graphicsConfiguration.defaultTransform.scaleX)
                        val content = (actual as? RootPaneContainer)?.contentPane ?: actual
                        val position = content.locationOnScreen
                        putJsonObject("contentBounds") {
                            put("x", position.x)
                            put("y", position.y)
                            put("width", content.width)
                            put("height", content.height)
                        }
                        syncFocusWindows(actual).firstOrNull { it.isFocused }?.let { focused ->
                            putJsonObject("focusedWindow") {
                                put("kind", focused.javaClass.simpleName)
                                put("x", focused.x)
                                put("y", focused.y)
                                put("width", focused.width)
                                put("height", focused.height)
                            }
                        }
                    }
                }
            } catch (_: RuntimeException) {
                null
            }
            val handleMounted = synchronized(this) { running && controls.values.any { it.tag == "sync-drag-handle" } }
            val focused = if (handleMounted && metadata != null && bound != null) {
                focusedSyncAccessibleBounds(syncFocusWindows(bound).filter { it.isShowing && it.isFocused })
            } else emptyList()
            result = snapshot(metadata, focused)
        }
        if (SwingUtilities.isEventDispatchThread()) capture.run() else SwingUtilities.invokeAndWait(capture)
        return requireNotNull(result)
    }
}

internal val syncUiRegistry = SyncUiRegistry()

@Composable
internal fun ProvideDesktopSyncUiObservation(window: Window, content: @Composable () -> Unit) {
    val active = TestMode.isActive()
    val observer = remember(window, active) { if (active) syncUiRegistry.observer() else null }
    DisposableEffect(window, active) {
        val binding = if (active) syncUiRegistry.bindWindow(window) else null
        onDispose { binding?.close() }
    }
    CompositionLocalProvider(LocalSyncUiObserver provides observer, content = content)
}
