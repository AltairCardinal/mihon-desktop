package android.text

/** Link-only Android text contract used by extension verifier descriptors. */
interface Editable : CharSequence

/**
 * Android-compatible callback descriptors required by extension verification.
 * Desktop does not construct text widgets or invoke these callbacks.
 */
interface TextWatcher {
    fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int)

    fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int)

    fun afterTextChanged(editable: Editable?)
}
