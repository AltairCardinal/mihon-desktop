package mihon.desktop.network

import eu.kanade.tachiyomi.source.Source

fun interface DesktopExtensionCookiePort {
    fun clearCookies(sources: List<Source>): Int
}
