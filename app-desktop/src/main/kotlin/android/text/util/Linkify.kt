package android.text.util

import android.text.Spannable

/** Minimal Android Linkify ABI for extension preference text. */
object Linkify {
    const val WEB_URLS: Int = 0x01

    @JvmStatic
    fun addLinks(text: Spannable, mask: Int): Boolean =
        mask and WEB_URLS != 0 && text.isNotEmpty()
}
