package android.webkit

/**
 * Desktop stub for android.webkit.ValueCallback.
 */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}
