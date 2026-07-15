package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

/**
 * RED — ReaderBackgroundTheme and ReaderColorFilter do not exist yet.
 * Tests will fail until the production classes are implemented.
 */
class ReaderSettingsModelsTest {

    // ── ReaderBackgroundTheme ─────────────────────────────────────────────────

    @Test
    fun `ReaderBackgroundTheme has four values`() {
        val values = ReaderBackgroundTheme.entries
        assertEquals(4, values.size)
    }

    @Test
    fun `ReaderBackgroundTheme entries are BLACK GRAY WHITE AUTOMATIC`() {
        val names = ReaderBackgroundTheme.entries.map { it.name }
        assertTrue("BLACK" in names)
        assertTrue("GRAY" in names)
        assertTrue("WHITE" in names)
        assertTrue("AUTOMATIC" in names)
    }

    @Test
    fun `ReaderBackgroundTheme default is BLACK`() {
        // Android default is Black background
        assertEquals(ReaderBackgroundTheme.BLACK, ReaderBackgroundTheme.DEFAULT)
    }

    // ── ReaderColorFilter ─────────────────────────────────────────────────────

    @Test
    fun `ReaderColorFilter defaults to disabled with zero values`() {
        val filter = ReaderColorFilter()
        assertFalse(filter.tintEnabled)
        assertFalse(filter.brightnessEnabled)
        assertEquals(0f, filter.brightness)
        assertEquals(0, filter.r)
        assertEquals(0, filter.g)
        assertEquals(0, filter.b)
        assertEquals(128, filter.alpha) // mid-opacity by default
        assertFalse(filter.grayscaleEnabled)
        assertFalse(filter.invertEnabled)
    }

    @Test
    fun `ReaderColorFilter brightness range is minus 0_75 to 1_0`() {
        val min = ReaderColorFilter(brightness = ReaderColorFilter.BRIGHTNESS_MIN)
        val max = ReaderColorFilter(brightness = ReaderColorFilter.BRIGHTNESS_MAX)
        assertEquals(-0.75f, min.brightness, 0.001f)
        assertEquals(1.0f, max.brightness, 0.001f)
    }

    @Test
    fun `ReaderColorFilter isEffective when enabled`() {
        val filter = ReaderColorFilter(brightnessEnabled = true, brightness = 0.5f)
        assertTrue(filter.isEffective)
    }

    @Test
    fun `ReaderColorFilter is not effective when disabled`() {
        val filter = ReaderColorFilter(brightnessEnabled = false, brightness = 0.5f)
        assertFalse(filter.isEffective)
    }

    @Test
    fun `ReaderColorFilter is not effective when enabled but all zero and no brightness`() {
        val filter = ReaderColorFilter(tintEnabled = true, brightness = 0f, r = 0, g = 0, b = 0, alpha = 0)
        assertFalse(filter.isEffective)
    }

    @Test
    fun `grayscale and invert are effective shared filter modes`() {
        assertTrue(ReaderColorFilter(grayscaleEnabled = true).isEffective)
        assertTrue(ReaderColorFilter(invertEnabled = true).isEffective)
    }

    @Test
    fun `grayscale and invert survive preference round trip`() {
        val root = Preferences.userRoot().node("/mihon/reader-filter-test/${System.nanoTime()}")
        try {
            val store = DesktopPreferenceStore(root.node("store"))
            val legacy = root.node("legacy")
            ReaderPreferences(store, legacy).saveColorFilter(
                ReaderColorFilter(
                    tintEnabled = true,
                    alpha = 0,
                    grayscaleEnabled = true,
                    invertEnabled = true,
                ),
            )

            val restored = ReaderPreferences(store, legacy).loadColorFilter()

            assertTrue(restored.tintEnabled)
            assertTrue(restored.grayscaleEnabled)
            assertTrue(restored.invertEnabled)
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `loading legacy out of range filter values normalizes every shared bound`() {
        val root = Preferences.userRoot().node("/mihon/reader-filter-corrupt/${System.nanoTime()}")
        try {
            val preferences = ReaderPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy")).apply {
                colorFilterBrightness = 5f
                colorFilterR = -10
                colorFilterG = 300
                colorFilterB = 999
                colorFilterAlpha = -1
            }

            val loaded = preferences.loadColorFilter()

            assertEquals(ReaderColorFilter.BRIGHTNESS_MAX, loaded.brightness)
            assertEquals(0, loaded.r)
            assertEquals(255, loaded.g)
            assertEquals(255, loaded.b)
            assertEquals(0, loaded.alpha)
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `loading valid UI filter values keeps them unchanged`() {
        val root = Preferences.userRoot().node("/mihon/reader-filter-valid/${System.nanoTime()}")
        try {
            val expected = ReaderColorFilter(
                tintEnabled = true,
                brightnessEnabled = true,
                brightness = 0.25f,
                r = 12,
                g = 34,
                b = 56,
                alpha = 78,
                grayscaleEnabled = true,
                invertEnabled = true,
            )
            val preferences = ReaderPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))
            preferences.saveColorFilter(expected)

            assertEquals(expected, preferences.loadColorFilter())
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `filtered and duplicate chapter skip preferences survive round trip`() {
        val root = Preferences.userRoot().node("/mihon/reader-skip-test/${System.nanoTime()}")
        try {
            val store = DesktopPreferenceStore(root.node("store"))
            val legacy = root.node("legacy")
            ReaderPreferences(store, legacy).apply {
                skipFilteredChapters = true
                skipDuplicateChapters = true
            }

            val restored = ReaderPreferences(store, legacy)

            assertTrue(restored.skipFilteredChapters)
            assertTrue(restored.skipDuplicateChapters)
        } finally {
            root.removeNode()
        }
    }

    // ── WebtoonSidePadding ─────────────────────────────────────────────────────

    @Test
    fun `WebtoonSidePadding has expected entries`() {
        val entries = WebtoonSidePadding.entries
        assertTrue(entries.size >= 4, "Should have at least NONE, SMALL, MEDIUM, LARGE")
    }

    @Test
    fun `WebtoonSidePadding NONE has zero ratio`() {
        assertEquals(0f, WebtoonSidePadding.NONE.ratio)
    }

    @Test
    fun `WebtoonSidePadding MEDIUM has non-zero ratio`() {
        assertTrue(WebtoonSidePadding.MEDIUM.ratio > 0f)
    }

    @Test
    fun `WebtoonSidePadding default is NONE`() {
        assertEquals(WebtoonSidePadding.NONE, WebtoonSidePadding.DEFAULT)
    }

    // ── ScaleType ────────────────────────────────────────────────────────────────

    @Test
    fun `ScaleType has five values`() {
        assertEquals(5, ScaleType.entries.size)
    }

    @Test
    fun `ScaleType entries are FIT_SCREEN FIT_WIDTH FIT_HEIGHT ORIGINAL_SIZE SMART_FIT`() {
        val names = ScaleType.entries.map { it.name }
        assertTrue("FIT_SCREEN" in names)
        assertTrue("FIT_WIDTH" in names)
        assertTrue("FIT_HEIGHT" in names)
        assertTrue("ORIGINAL_SIZE" in names)
        assertTrue("SMART_FIT" in names)
    }

    @Test
    fun `ScaleType default is FIT_SCREEN`() {
        assertEquals(ScaleType.FIT_SCREEN, ScaleType.DEFAULT)
    }

    @Test
    fun `ScaleType displayName is human-readable`() {
        assertEquals("Fit Screen", ScaleType.FIT_SCREEN.displayName)
        assertEquals("Fit Width", ScaleType.FIT_WIDTH.displayName)
        assertEquals("Smart Fit", ScaleType.SMART_FIT.displayName)
    }
}
