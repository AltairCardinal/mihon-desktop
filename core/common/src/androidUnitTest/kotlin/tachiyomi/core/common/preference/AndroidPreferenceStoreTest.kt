package tachiyomi.core.common.preference

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidPreferenceStoreTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var store: AndroidPreferenceStore

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        preferences = application.getSharedPreferences(
            "android-preference-store-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        store = AndroidPreferenceStore(application, preferences)
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `typed preferences use defaults and persist values in SharedPreferences`() {
        val stringPreference = store.getString("string", "fallback")
        val intPreference = store.getInt("int", 7)
        val longPreference = store.getLong("long", 8L)
        val floatPreference = store.getFloat("float", 1.5F)
        val booleanPreference = store.getBoolean("boolean", false)
        val stringSetPreference = store.getStringSet("set", setOf("fallback"))

        assertEquals("fallback", stringPreference.get())
        assertEquals(7, intPreference.get())
        assertEquals(8L, longPreference.get())
        assertEquals(1.5F, floatPreference.get())
        assertFalse(booleanPreference.get())
        assertEquals(setOf("fallback"), stringSetPreference.get())

        stringPreference.set("stored")
        intPreference.set(10)
        longPreference.set(11L)
        floatPreference.set(2.5F)
        booleanPreference.set(true)
        stringSetPreference.set(setOf("a", "b"))

        assertEquals("stored", preferences.getString("string", null))
        assertEquals(10, preferences.getInt("int", 0))
        assertEquals(11L, preferences.getLong("long", 0L))
        assertEquals(2.5F, preferences.getFloat("float", 0F))
        assertTrue(preferences.getBoolean("boolean", false))
        assertEquals(setOf("a", "b"), preferences.getStringSet("set", emptySet()))
        assertEquals("fallback", stringPreference.defaultValue())
        assertTrue(store.getAll().keys.containsAll(setOf("string", "int", "long", "float", "boolean", "set")))
    }

    @Test
    fun `delete removes stored value and restores default`() {
        val preference = store.getString("temporary", "default")
        preference.set("stored")

        assertTrue(preference.isSet())

        preference.delete()

        assertFalse(preference.isSet())
        assertFalse(preferences.contains("temporary"))
        assertEquals("default", preference.get())
    }

    @Test
    fun `changes emits current value then external SharedPreferences update`() = runTest {
        val preference = store.getString("reactive", "initial")
        val emissions = mutableListOf<String>()
        val collector = launch {
            preference.changes()
                .take(2)
                .toList(emissions)
        }
        runCurrent()

        preferences.edit().putString("reactive", "updated").commit()
        advanceUntilIdle()

        assertEquals(listOf("initial", "updated"), emissions)
        assertTrue(collector.isCompleted)
    }

    @Test
    fun `primitive type mismatch returns default and deletes incompatible value`() {
        preferences.edit().putString("count", "not-an-int").commit()
        val preference = store.getInt("count", 7)

        assertEquals(7, preference.get())
        assertFalse(preferences.contains("count"))
    }

    @Test
    fun `serialized object backing type mismatch returns default and deletes incompatible value`() {
        preferences.edit().putInt("theme", 1).commit()
        val preference = store.getObjectFromString(
            key = "theme",
            defaultValue = Theme.SYSTEM,
            serializer = Theme::name,
            deserializer = Theme::valueOf,
        )

        assertEquals(Theme.SYSTEM, preference.get())
        assertFalse(preferences.contains("theme"))
    }

    @Test
    fun `integer serialized object backing type mismatch returns default and deletes incompatible value`() {
        preferences.edit().putString("theme", "LIGHT").commit()
        val preference = store.getObjectFromInt(
            key = "theme",
            defaultValue = Theme.SYSTEM,
            serializer = Theme::ordinal,
            deserializer = Theme.entries::get,
        )

        assertEquals(Theme.SYSTEM, preference.get())
        assertFalse(preferences.contains("theme"))
    }

    @Test
    fun `string deserializer ClassCastException returns default without deleting valid backing value`() {
        preferences.edit().putString("theme", "future-value").commit()
        val preference = store.getObjectFromString(
            key = "theme",
            defaultValue = Theme.SYSTEM,
            serializer = Theme::name,
            deserializer = { throw ClassCastException("unsupported serialized value") },
        )

        assertEquals(Theme.SYSTEM, preference.get())
        assertEquals("future-value", preferences.getString("theme", null))
    }

    @Test
    fun `integer deserializer ClassCastException returns default without deleting valid backing value`() {
        preferences.edit().putInt("theme", 99).commit()
        val preference = store.getObjectFromInt(
            key = "theme",
            defaultValue = Theme.SYSTEM,
            serializer = Theme::ordinal,
            deserializer = { throw ClassCastException("unsupported serialized value") },
        )

        assertEquals(Theme.SYSTEM, preference.get())
        assertEquals(99, preferences.getInt("theme", -1))
    }

    private enum class Theme {
        SYSTEM,
        LIGHT,
        DARK,
    }
}
