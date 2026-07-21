package tachiyomi.core.common.preference

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.prefs.Preferences

class DesktopPreferenceStoreTest {

    private lateinit var store: DesktopPreferenceStore
    private lateinit var backingPrefs: Preferences

    @BeforeEach
    fun setUp() {
        backingPrefs = Preferences.userRoot().node("/mihon/test/${System.nanoTime()}")
        store = DesktopPreferenceStore(backingPrefs)
    }

    @AfterEach
    fun tearDown() {
        backingPrefs.removeNode()
    }

    // --- getString ---

    @Test
    fun `getString returns default when key not set`() {
        val pref = store.getString("missing", "fallback")
        assertEquals("fallback", pref.get())
    }

    @Test
    fun `getString persists and reads value`() {
        val pref = store.getString("name", "")
        pref.set("Mihon")
        assertEquals("Mihon", pref.get())
    }

    @Test
    fun `getString key reports correct key`() {
        val pref = store.getString("my_key", "")
        assertEquals("my_key", pref.key())
    }

    // --- getInt ---

    @Test
    fun `getInt returns default when key not set`() {
        val pref = store.getInt("count", 42)
        assertEquals(42, pref.get())
    }

    @Test
    fun `getInt persists and reads value`() {
        val pref = store.getInt("count", 0)
        pref.set(99)
        assertEquals(99, pref.get())
    }

    // --- getLong ---

    @Test
    fun `getLong persists and reads value`() {
        val pref = store.getLong("timestamp", 0L)
        pref.set(1234567890L)
        assertEquals(1234567890L, pref.get())
    }

    // --- getFloat ---

    @Test
    fun `getFloat persists and reads value`() {
        val pref = store.getFloat("ratio", 0f)
        pref.set(3.14f)
        assertEquals(3.14f, pref.get())
    }

    // --- getBoolean ---

    @Test
    fun `getBoolean returns default when key not set`() {
        val pref = store.getBoolean("flag", false)
        assertFalse(pref.get())
    }

    @Test
    fun `getBoolean persists and reads value`() {
        val pref = store.getBoolean("flag", false)
        pref.set(true)
        assertTrue(pref.get())
    }

    // --- getStringSet ---

    @Test
    fun `getStringSet returns default when key not set`() {
        val pref = store.getStringSet("tags", setOf("a", "b"))
        assertEquals(setOf("a", "b"), pref.get())
    }

    @Test
    fun `getStringSet persists and reads value`() {
        val pref = store.getStringSet("tags", emptySet())
        pref.set(setOf("x", "y", "z"))
        assertEquals(setOf("x", "y", "z"), pref.get())
    }

    // --- isSet / delete ---

    @Test
    fun `isSet returns false for unset key`() {
        val pref = store.getString("unset", "")
        assertFalse(pref.isSet())
    }

    @Test
    fun `isSet returns true after set`() {
        val pref = store.getString("exists", "")
        pref.set("value")
        assertTrue(pref.isSet())
    }

    @Test
    fun `delete removes value and reverts to default`() {
        val pref = store.getString("temp", "default")
        pref.set("changed")
        pref.delete()
        assertEquals("default", pref.get())
        assertFalse(pref.isSet())
    }

    // --- getObjectFromString ---

    @Test
    fun `getObjectFromString serializes and deserializes`() {
        val pref = store.getObjectFromString(
            key = "color",
            defaultValue = Color.RED,
            serializer = { it.name },
            deserializer = { Color.valueOf(it) },
        )
        pref.set(Color.BLUE)
        assertEquals(Color.BLUE, pref.get())
    }

    // --- getObjectFromInt ---

    @Test
    fun `getObjectFromInt serializes and deserializes`() {
        val pref = store.getObjectFromInt(
            key = "priority",
            defaultValue = Priority.LOW,
            serializer = { it.ordinal },
            deserializer = { Priority.entries[it] },
        )
        pref.set(Priority.HIGH)
        assertEquals(Priority.HIGH, pref.get())
    }

    // --- changes flow ---

    @Test
    fun `changes emits current value on subscribe`() = runTest {
        val pref = store.getString("reactive", "initial")
        val value = pref.changes().first()
        assertEquals("initial", value)
    }

    @Test
    fun `changes collector cleanup tolerates externally removed node`() = runTest {
        val removedNode = backingPrefs.node("removed")
        val pref = DesktopPreferenceStore(removedNode).getString("reactive", "initial")
        val collector = launch { pref.changes().collect() }
        runCurrent()

        removedNode.removeNode()
        collector.cancelAndJoin()
    }

    // --- getAll ---

    @Test
    fun `getAll returns all set preferences`() {
        store.getString("a", "").set("1")
        store.getInt("b", 0).set(2)
        val all = store.getAll()
        assertTrue(all.containsKey("a"))
        assertTrue(all.containsKey("b"))
    }

    @Test
    fun `clearAndFlush clears every stored value from the backing node`() {
        store.getString("text", "").set("value")
        store.getBoolean("flag", false).set(true)
        store.getLong("count", 0).set(3L)

        store.clearAndFlush()

        assertTrue(backingPrefs.keys().isEmpty())
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `clearAndFlush clears legacy child nodes without removing them`() {
        backingPrefs.node("desktop/app").put("theme_mode", "DARK")
        backingPrefs.node("desktop/reader/deeper").put("readingMode", "LTR")

        store.clearAndFlush()

        assertTrue(backingPrefs.node("desktop/app").keys().isEmpty())
        assertTrue(backingPrefs.node("desktop/reader/deeper").keys().isEmpty())
    }

    // --- defaultValue ---

    @Test
    fun `defaultValue returns the configured default`() {
        val pref = store.getString("key", "my_default")
        assertEquals("my_default", pref.defaultValue())
    }

    private enum class Color { RED, GREEN, BLUE }
    private enum class Priority { LOW, MEDIUM, HIGH }
}
