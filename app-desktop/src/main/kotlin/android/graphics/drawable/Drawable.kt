package android.graphics.drawable

/**
 * Desktop stub for android.graphics.drawable.Drawable.
 * Provides a minimal API surface so extensions that reference Drawable
 * can compile and load without errors on desktop.
 */
abstract class Drawable {
    open fun setAlpha(alpha: Int) {}
    open fun getAlpha(): Int = 255
    open fun getIntrinsicWidth(): Int = -1
    open fun getIntrinsicHeight(): Int = -1
    open fun isStateful(): Boolean = false
}

open class BitmapDrawable : Drawable()

open class ColorDrawable(val color: Int = 0) : Drawable()
