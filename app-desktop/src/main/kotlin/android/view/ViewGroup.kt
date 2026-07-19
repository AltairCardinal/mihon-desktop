package android.view

import android.content.Context

abstract class ViewGroup(context: Context) : View(context) {
    open class LayoutParams(
        @JvmField var width: Int,
        @JvmField var height: Int,
    )
}
