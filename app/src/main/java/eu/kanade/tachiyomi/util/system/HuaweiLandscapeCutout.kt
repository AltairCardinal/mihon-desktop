package eu.kanade.tachiyomi.util.system

import android.app.Activity
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import kotlin.math.ceil

private const val HUAWEI_LANDSCAPE_NOTCH = "hw_mc.pad.landscape.notch_area"

internal val isHuaweiDevice: Boolean
    get() = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

private val systemPropertyGetter by lazy {
    runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
    }.getOrNull()
}

private fun readHuaweiLandscapeNotch(): String? =
    runCatching { systemPropertyGetter?.invoke(null, HUAWEI_LANDSCAPE_NOTCH, "") as? String }.getOrNull()

internal fun Activity.readerDisplayCutoutInsets(windowInsets: WindowInsetsCompat?): Insets {
    val platformInsets = windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
    if (platformInsets != Insets.NONE || windowInsets?.displayCutout != null) return platformInsets
    return huaweiDisplayCutoutInsets() ?: Insets.NONE
}

@Suppress("DEPRECATION")
internal fun Activity.huaweiDisplayCutoutInsets(): Insets? {
    if (!isHuaweiDevice || isInMultiWindowMode) return null

    val display = windowManager.defaultDisplay
    if (display.displayId != Display.DEFAULT_DISPLAY || display.cutoutIsReported()) return null

    val property = readHuaweiLandscapeNotch() ?: return null
    val values = property.split(',')
    if (values.size != 4) return null
    // The PCE-W30 tuple describes a top-edge rectangle in the normal landscape orientation.
    val (width, depth, left, top) = values.map { it.trim().toIntOrNull() ?: return null }
    val mode = display.mode
    val physicalWidth = maxOf(mode.physicalWidth, mode.physicalHeight)
    val physicalHeight = minOf(mode.physicalWidth, mode.physicalHeight)
    if (top != 0 || width <= 0 || left < 0 || width > physicalWidth - left ||
        depth <= 0 || depth >= physicalHeight / 2
    ) {
        return null
    }

    val size = Point().also(display::getRealSize)
    val logicalShortSide = minOf(size.x, size.y)
    if (logicalShortSide <= 0 || size.x == size.y) return null

    val rotation = display.rotation
    val quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val naturalLandscape = (size.x > size.y) != quarterTurn
    val landscapeRotation = if (naturalLandscape) Surface.ROTATION_0 else Surface.ROTATION_90

    // The property is in panel pixels. Add one before converting to logical pixels.
    val inset = ceil((depth + 1.0) * logicalShortSide / physicalHeight).toInt()
    return when (Math.floorMod(rotation - landscapeRotation, 4)) {
        Surface.ROTATION_0 -> Insets.of(0, inset, 0, 0)
        Surface.ROTATION_90 -> Insets.of(inset, 0, 0, 0)
        Surface.ROTATION_180 -> Insets.of(0, 0, 0, inset)
        Surface.ROTATION_270 -> Insets.of(0, 0, inset, 0)
        else -> null
    }
}

private fun Display.cutoutIsReported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cutout != null
