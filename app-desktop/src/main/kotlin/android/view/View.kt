package android.view

open class View {
    open fun setLayoutParams(params: ViewGroup.LayoutParams) {
        unsupported("setLayoutParams")
    }

    open fun measure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        unsupported("measure")
    }

    open fun layout(left: Int, top: Int, right: Int, bottom: Int) {
        unsupported("layout")
    }

    private fun unsupported(operation: String): Nothing {
        throw UnsupportedOperationException("android.view.View.$operation requires a platform UI engine")
    }

    object MeasureSpec {
        const val EXACTLY: Int = 0x40000000

        @JvmStatic
        fun makeMeasureSpec(size: Int, mode: Int): Int {
            return (size and 0x3fffffff) or (mode and 0xc0000000.toInt())
        }
    }
}
