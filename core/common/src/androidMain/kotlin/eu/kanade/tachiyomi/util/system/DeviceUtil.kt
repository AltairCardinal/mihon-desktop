package eu.kanade.tachiyomi.util.system

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object DeviceUtil {

    val isMiui: Boolean by lazy {
        getSystemProperty("ro.miui.ui.version.name")?.isNotEmpty() ?: false
    }

    /**
     * Extracts the MIUI major version code from a string like "V12.5.3.0.QFGMIXM".
     *
     * @return MIUI major version code (e.g., 13) or null if can't be parsed.
     */
    val miuiMajorVersion: Int? by lazy {
        if (!isMiui) return@lazy null

        Build.VERSION.INCREMENTAL
            .substringBefore('.')
            .trimStart('V')
            .toIntOrNull()
    }

    @SuppressLint("PrivateApi")
    fun isMiuiOptimizationDisabled(): Boolean {
        val sysProp = getSystemProperty("persist.sys.miui_optimization")
        if (sysProp == "0" || sysProp == "false") {
            return true
        }

        return try {
            Class.forName("android.miui.AppOpsUtils")
                .getDeclaredMethod("isXOptMode")
                .invoke(null) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    val isSamsung: Boolean by lazy {
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    val oneUiVersion: Double? by lazy {
        try {
            val semPlatformIntField = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT")
            val version = semPlatformIntField.getInt(null) - 90000
            if (version < 0) {
                1.0
            } else {
                ((version / 10000).toString() + "." + version % 10000 / 100).toDouble()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A list of package names that may be incorrectly resolved as usable browsers by
     * the system.
     *
     * If these are resolved for [android.content.Intent.ACTION_VIEW], it prevents the
     * system from opening a proper browser or any usable app .
     *
     * Some of them may only be present on certain manufacturer's devices.
     */
    val invalidDefaultBrowsers = listOf(
        "android",
        // Honor
        "com.hihonor.android.internal.app",
        // Huawei
        "com.huawei.android.internal.app",
        // Lenovo
        "com.zui.resolver",
        // Infinix
        "com.transsion.resolver",
        // Xiaomi Redmi
        "com.android.intentresolver",
    )

    /**
     * ActivityManager#isLowRamDevice is based on a system property, which isn't
     * necessarily trustworthy. 1GB is supposedly the regular threshold.
     *
     * Instead, we consider anything with less than 3GB of RAM as low memory
     * considering how heavy image processing can be.
     */
    fun isLowRamDevice(context: Context): Boolean {
        val memInfo = ActivityManager.MemoryInfo()
        context.getSystemService<ActivityManager>()!!.getMemoryInfo(memInfo)
        val totalMemBytes = memInfo.totalMem
        return totalMemBytes < 3L * 1024 * 1024 * 1024
    }

    @SuppressLint("PrivateApi")
    fun getSystemProperty(key: String?): String? {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java)
                .invoke(null, key) as String
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unable to use SystemProperties.get()" }
            null
        }
    }

    val isEInk: Boolean by lazy {
        isEInkDevice(
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            device = Build.DEVICE,
            sysPropGetter = ::getSystemProperty,
        )
    }

    private val DEDICATED_EINK_BRANDS = setOf(
        "onyx",
        "boox",
        "ireader",
        "hanvon",
        "moaan",
        "iflytek",
        "boyue",
        "likebook",
        "bigme",
        "meebook",
        "haoqing",
        "supernote",
        "ratta",
        "pocketbook",
        "tolino",
        "kobo",
        "rakutenkobo",
        "dasung",
    )

    private val HISENSE_EINK_MODELS = setOf(
        "a5",
        "a5pro",
        "a5c",
        "a6",
        "a6l",
        "a7",
        "a7cc",
        "a9",
        "hireader",
        "touch",
    )

    fun isEInkDevice(
        brand: String?,
        manufacturer: String?,
        model: String?,
        product: String?,
        device: String?,
        sysPropGetter: (String) -> String? = ::getSystemProperty,
    ): Boolean {
        val b = brand?.lowercase().orEmpty()
        val m = manufacturer?.lowercase().orEmpty()
        val mod = model?.lowercase().orEmpty()
        val p = product?.lowercase().orEmpty()
        val d = device?.lowercase().orEmpty()

        // 1. Dedicated E-Ink brands
        if (DEDICATED_EINK_BRANDS.contains(b) || DEDICATED_EINK_BRANDS.contains(m)) {
            return true
        }

        // 2. Huawei MatePad Paper (e.g., HMW-W09, HMW-AL10, HWHMW)
        if (b.contains("huawei") || m.contains("huawei") || b.contains("honor") || m.contains("honor")) {
            if (mod.contains("hmw") || p.contains("hmw") || d.contains("hmw") ||
                mod.contains("matepad paper") || p.contains("matepad paper")
            ) {
                return true
            }
        }

        // 3. Hisense E-Ink devices
        if (b.contains("hisense") || m.contains("hisense")) {
            val isNormalPhone = mod.contains("infinity") || mod.contains("u50") || mod.contains("e50")
            if (!isNormalPhone && HISENSE_EINK_MODELS.any { mod.contains(it) }) {
                return true
            }
        }

        // 4. Xiaomi / Moaan E-Ink (e.g. inkPalm, duokan)
        if (b.contains("xiaomi") || m.contains("xiaomi")) {
            if (mod.contains("inkpalm") || p.contains("inkpalm") || d.contains("inkpalm") ||
                mod.contains("duokan") || p.contains("duokan")
            ) {
                return true
            }
        }

        // 5. TCL NXTPAPER
        if (mod.contains("nxtpaper") || p.contains("nxtpaper") || d.contains("nxtpaper")) {
            return true
        }

        // 6. EPD system property fallback
        val epdProp =
            sysPropGetter("ro.epd.display") ?: sysPropGetter("ro.vendor.eink") ?: sysPropGetter("sys.eink.display")
        if (epdProp != null && (epdProp == "1" || epdProp.equals("true", ignoreCase = true))) {
            return true
        }

        return false
    }
}
