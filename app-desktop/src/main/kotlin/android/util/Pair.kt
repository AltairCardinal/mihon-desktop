package android.util

/**
 * Desktop stub for android.util.Pair.
 */
data class Pair<F, S>(val first: F, val second: S) {
    companion object {
        @JvmStatic
        fun <A, B> create(a: A, b: B): Pair<A, B> = Pair(a, b)
    }
}
