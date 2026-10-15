package eu.kanade.tachiyomi.util.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DeviceUtilEInkTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun identifiesHuaweiMatePadPaper() {
        // Huawei MatePad Paper (e.g. HMW-W09 / HMW-AL10)
        assertTrue(
            DeviceUtil.isEInkDevice(
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "HMW-W09",
                product = "HMW-W09",
                device = "HWHMW",
            ),
        )
        assertTrue(
            DeviceUtil.isEInkDevice(
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "HMW-AL10",
                product = "HMW",
                device = "HMW-AL10",
            ),
        )
        assertTrue(
            DeviceUtil.isEInkDevice(
                brand = "HUAWEI",
                manufacturer = "HUAWEI",
                model = "MatePad Paper",
                product = "MatePad Paper",
                device = "MatePad Paper",
            ),
        )
    }

    @Test
    fun identifiesDedicatedEInkBrands() {
        // Onyx Boox
        assertTrue(DeviceUtil.isEInkDevice("Onyx", "Onyx", "NoteAir2", "NoteAir2", "NoteAir2"))
        assertTrue(DeviceUtil.isEInkDevice("BOOX", "Onyx", "NovaAir", "NovaAir", "NovaAir"))

        // iReader
        assertTrue(DeviceUtil.isEInkDevice("iReader", "iReader", "Smart3", "Smart3", "Smart3"))

        // Hanvon
        assertTrue(DeviceUtil.isEInkDevice("Hanvon", "Hanvon", "N10", "N10", "N10"))

        // Moaan
        assertTrue(DeviceUtil.isEInkDevice("moaan", "moaan", "inkPalm 5", "inkPalm 5", "inkPalm 5"))

        // iFlytek
        assertTrue(DeviceUtil.isEInkDevice("iflytek", "iflytek", "X2", "X2", "X2"))

        // Boyue / Likebook
        assertTrue(DeviceUtil.isEInkDevice("boyue", "boyue", "Likebook Mars", "Mars", "Mars"))

        // Bigme
        assertTrue(DeviceUtil.isEInkDevice("bigme", "bigme", "B1 Pro", "B1Pro", "B1Pro"))

        // Meebook
        assertTrue(DeviceUtil.isEInkDevice("meebook", "meebook", "M6", "M6", "M6"))

        // Supernote / Ratta
        assertTrue(DeviceUtil.isEInkDevice("supernote", "ratta", "A5X", "A5X", "A5X"))

        // PocketBook
        assertTrue(DeviceUtil.isEInkDevice("pocketbook", "pocketbook", "InkPad3", "InkPad3", "InkPad3"))

        // Tolino
        assertTrue(DeviceUtil.isEInkDevice("tolino", "tolino", "Vision 6", "Vision6", "Vision6"))

        // Kobo
        assertTrue(DeviceUtil.isEInkDevice("kobo", "kobo", "Clara 2E", "Clara2E", "Clara2E"))

        // Dasung
        assertTrue(DeviceUtil.isEInkDevice("dasung", "dasung", "Paperlike", "Paperlike", "Paperlike"))
    }

    @Test
    fun identifiesHisenseEInkDevicesOnly() {
        assertTrue(DeviceUtil.isEInkDevice("Hisense", "Hisense", "A5", "A5", "A5"))
        assertTrue(DeviceUtil.isEInkDevice("Hisense", "Hisense", "A5Pro", "A5Pro", "A5Pro"))
        assertTrue(DeviceUtil.isEInkDevice("Hisense", "Hisense", "A7CC", "A7CC", "A7CC"))
        assertTrue(DeviceUtil.isEInkDevice("Hisense", "Hisense", "A9", "A9", "A9"))
        assertTrue(DeviceUtil.isEInkDevice("Hisense", "Hisense", "HiReader", "HiReader", "HiReader"))
        assertTrue(DeviceUtil.isEInkDevice("Hisense", "Hisense", "TOUCH", "TOUCH", "TOUCH"))

        // Normal Hisense smartphone must NOT be detected as E-Ink
        assertFalse(DeviceUtil.isEInkDevice("Hisense", "Hisense", "Infinity H50", "H50", "H50"))
    }

    @Test
    fun identifiesXiaomiAndTclEInkDevicesOnly() {
        // Xiaomi inkPalm
        assertTrue(DeviceUtil.isEInkDevice("Xiaomi", "Xiaomi", "inkPalm 5", "inkPalm", "inkPalm"))

        // Normal Xiaomi devices must NOT be detected as E-Ink
        assertFalse(DeviceUtil.isEInkDevice("Xiaomi", "Xiaomi", "Mi 11", "venus", "venus"))
        assertFalse(DeviceUtil.isEInkDevice("Redmi", "Xiaomi", "K50", "rubens", "rubens"))

        // TCL Nxtpaper
        assertTrue(DeviceUtil.isEInkDevice("TCL", "TCL", "NXTPAPER 11", "nxtpaper", "nxtpaper"))
        assertFalse(DeviceUtil.isEInkDevice("TCL", "TCL", "TCL 20 Pro", "tcl20pro", "tcl20pro"))
    }

    @Test
    fun rejectsNormalTabletsAndSmartphones() {
        // Samsung
        assertFalse(DeviceUtil.isEInkDevice("samsung", "samsung", "SM-X910", "gts9u", "gts9u"))
        assertFalse(DeviceUtil.isEInkDevice("samsung", "samsung", "SM-S918B", "dm3q", "dm3q"))

        // Google Pixel
        assertFalse(DeviceUtil.isEInkDevice("google", "google", "Pixel 8 Pro", "husky", "husky"))

        // Huawei regular tablets and phones
        assertFalse(DeviceUtil.isEInkDevice("HUAWEI", "HUAWEI", "MRX-W09", "MRX-W09", "HWMRX"))
        assertFalse(DeviceUtil.isEInkDevice("HUAWEI", "HUAWEI", "ELS-AN00", "ELS-AN00", "HWELS"))
        // HUAWEI MatePad Pro 13.2" (PCE-W30, OLED 144Hz) must NOT be detected as E-Ink
        assertFalse(DeviceUtil.isEInkDevice("HUAWEI", "HUAWEI", "PCE-W30", "PCE-W30", "HWPCE-L"))
        assertFalse(DeviceUtil.isEInkDevice("HUAWEI", "HUAWEI", "HWPCE", "PCEL", "HWPCE"))
    }

    @Test
    fun detectsEpdDriverPropertyFallback() {
        val unknownWithEpd = DeviceUtil.isEInkDevice(
            brand = "Unknown",
            manufacturer = "Generic",
            model = "CustomReader",
            product = "Custom",
            device = "Custom",
            sysPropGetter = { key: String ->
                if (key == "ro.epd.display") "1" else null
            },
        )
        assertTrue(unknownWithEpd)

        val unknownWithoutEpd = DeviceUtil.isEInkDevice(
            brand = "Unknown",
            manufacturer = "Generic",
            model = "CustomReader",
            product = "Custom",
            device = "Custom",
            sysPropGetter = { _: String -> null },
        )
        assertFalse(unknownWithoutEpd)
    }

    @Test
    fun preferenceDefaultsToTrueOnEInkAndFalseOtherwise() {
        val preferenceStore = AndroidPreferenceStore(context)
        val preferences = ReaderPreferences(preferenceStore)

        // When pref is not set, default matches DeviceUtil.isEInk
        val ripplePref = preferences.pageTurnRipple()
        if (DeviceUtil.isEInk) {
            assertTrue(ripplePref.get())
        } else {
            assertFalse(ripplePref.get())
        }
    }

    @Test
    fun einkAutoInitializesRippleOnlyOnceAndRespectsUserOverride() {
        val preferenceStore = AndroidPreferenceStore(context)
        val preferences = ReaderPreferences(preferenceStore)

        val ripplePref = preferences.pageTurnRipple()
        val autoInitPref = preferenceStore.getBoolean(
            tachiyomi.core.common.preference.Preference.appStateKey("reader_page_turn_ripple_eink_auto_initialized"),
            false,
        )

        // Reset state: ripple was false (e.g. from existing older install), not yet auto-initialized
        ripplePref.set(false)
        autoInitPref.set(false)

        // On non-EInk device: stays false, autoInit remains false
        preferences.initEInkPreferences(isEInk = false)
        assertFalse(ripplePref.get())
        assertFalse(autoInitPref.get())

        // On genuine E-Ink device: should automatically enable ripple and record autoInit = true
        preferences.initEInkPreferences(isEInk = true)
        assertTrue(ripplePref.get())
        assertTrue(autoInitPref.get())

        // User explicitly disables ripple on their E-Ink device
        ripplePref.set(false)
        assertFalse(ripplePref.get())

        // Subsequent launches/inits should NOT overwrite user's preference
        preferences.initEInkPreferences(isEInk = true)
        assertFalse(ripplePref.get())
        assertTrue(autoInitPref.get())
    }
}
