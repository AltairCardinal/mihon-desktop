package mihon.desktop.reader

/** Opening intent shared by the session demand and the UI projection. */
internal object ReaderInitialPageResolution {
    const val LAST: Int = Int.MAX_VALUE

    fun resolve(requestedPage: Int, pageCount: Int): Int = when {
        pageCount <= 0 -> 0
        requestedPage == LAST -> pageCount - 1
        else -> requestedPage.takeIf { it in 0 until pageCount } ?: 0
    }

    fun invalidSavedPage(requestedPage: Int, pageCount: Int): Boolean =
        pageCount > 0 && requestedPage != LAST && requestedPage !in 0 until pageCount
}

/** One opening's permission; checking current settings never runs under the session lock. */
internal class DesktopReaderInitialEncodedDemand(
    val chapterId: Long,
    val requestedPage: Int,
    val stillEligible: () -> Boolean,
)
