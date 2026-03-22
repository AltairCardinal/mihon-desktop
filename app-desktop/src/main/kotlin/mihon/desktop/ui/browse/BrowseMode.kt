package mihon.desktop.ui.browse

enum class BrowseMode { POPULAR, LATEST }

fun availableBrowseModes(supportsLatest: Boolean): List<BrowseMode> =
    if (supportsLatest) listOf(BrowseMode.POPULAR, BrowseMode.LATEST)
    else listOf(BrowseMode.POPULAR)
