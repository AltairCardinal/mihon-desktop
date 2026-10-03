package mihon.desktop.test

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.presentation.sync.SyncUiControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncUiRegistryTest {
    @Test
    fun `window unmount clears controls and old binding cannot clear a restarted session`() {
        val registry = SyncUiRegistry()
        val window = io.mockk.mockk<java.awt.Window>()
        val metadata = buildJsonObject { put("active", true) }
        val control = SyncUiControl("sync-open", 20f, 30f, 48f, 48f, 1f, false, true, true)
        registry.start()
        val oldBinding = registry.bindWindow(window)
        registry.update(Any(), control)
        oldBinding.close()
        assertTrue(registry.snapshot(metadata).getValue("controls").jsonArray.isEmpty())
        registry.stop()
        registry.start()
        registry.bindWindow(window)
        registry.update(Any(), control)
        oldBinding.close()
        assertEquals(1, registry.snapshot(metadata).getValue("controls").jsonArray.size)
    }

    @Test
    fun `moving owner is read at snapshot time and inactive mounted window remains observable`() {
        val registry = SyncUiRegistry()
        registry.start()
        var left = 240f
        registry.update(Any(), SyncUiControl("sync-open", left, 100f, 96f, 96f, 2f, false, false, true,
            currentScreenBounds = { mihon.presentation.sync.SyncUiScreenBounds(left, 100f, 96f, 96f) }))
        val window = buildJsonObject { put("active", false) }
        left = 480f
        val snapshot = registry.snapshot(window)
        assertTrue(snapshot.getValue("ready").jsonPrimitive.content.toBoolean())
        assertEquals(240f, snapshot.getValue("controls").jsonArray.single().jsonObject.getValue("bounds").jsonObject.getValue("x").jsonPrimitive.content.toFloat())
    }

    @Test
    fun `invalid geometry detached and stale observers never reach http snapshot`() {
        val registry = SyncUiRegistry()
        registry.start()
        val old = registry.observer()
        val window = buildJsonObject { put("active", true) }
        val control = SyncUiControl("sync-open", 20f, 30f, 48f, 48f, 1f, false, true, true)
        old.update(Any(), control.copy(screenLeft = Float.NaN))
        old.update(Any(), control.copy(density = 0f))
        old.update(Any(), control.copy(width = 0f))
        old.update(Any(), control.copy(currentScreenBounds = { null }))
        old.update(Any(), control.copy(currentScreenBounds = { error("owner disposed") }))
        assertTrue(registry.snapshot(window).getValue("controls").jsonArray.isEmpty())
        registry.stop()
        registry.start()
        old.update(Any(), control)
        assertFalse(old.observes("sync-open"))
        assertTrue(registry.snapshot(window).getValue("controls").jsonArray.isEmpty())
        registry.observer().update(Any(), control)
        assertFalse(registry.snapshot().getValue("ready").jsonPrimitive.content.toBoolean())
        assertTrue(registry.snapshot().getValue("controls").jsonArray.isEmpty())
    }

    @Test
    fun `whitelist transforms each owner screen pixels independently and clears lifetime`() {
        val registry = SyncUiRegistry()
        val window = buildJsonObject { put("active", true) }
        val toolbar = Any()
        val sheet = Any()
        val control = SyncUiControl("sync-open", 240f, 360f, 96f, 96f, 2f, true, false, true)
        registry.update(toolbar, control)
        assertFalse(registry.snapshot(window).getValue("ready").jsonPrimitive.content.toBoolean())
        registry.start()
        assertTrue(registry.observes("sync-open"))
        assertFalse(registry.observes("sync-password"))
        registry.update(toolbar, control)
        registry.update(sheet, control.copy(tag = "sync-close", screenLeft = 900f, screenTop = 700f, ownerFocused = true))
        registry.update(Any(), control.copy(tag = "sync-password", screenLeft = 1f))
        val snapshot = registry.snapshot(window)
        assertTrue(snapshot.getValue("ready").jsonPrimitive.content.toBoolean())
        val controls = snapshot.getValue("controls").jsonArray.map { it.jsonObject }
        assertEquals(2, controls.size)
        val open = controls.single { it.getValue("tag").jsonPrimitive.content == "sync-open" }
        assertEquals(120f, open.getValue("bounds").jsonObject.getValue("x").jsonPrimitive.content.toFloat())
        assertEquals(48f, open.getValue("bounds").jsonObject.getValue("width").jsonPrimitive.content.toFloat())
        assertFalse(open.getValue("ownerFocused").jsonPrimitive.content.toBoolean())
        val close = controls.single { it.getValue("tag").jsonPrimitive.content == "sync-close" }
        assertEquals(450f, close.getValue("bounds").jsonObject.getValue("x").jsonPrimitive.content.toFloat())
        registry.remove(sheet)
        assertEquals(1, registry.snapshot(window).getValue("controls").jsonArray.size)
        registry.stop()
        registry.start()
        assertFalse(registry.snapshot(window).getValue("ready").jsonPrimitive.content.toBoolean())
        assertEquals(0, registry.snapshot(window).getValue("controls").jsonArray.size)
    }
}
