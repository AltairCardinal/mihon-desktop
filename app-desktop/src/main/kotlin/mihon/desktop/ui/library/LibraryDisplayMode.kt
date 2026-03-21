package mihon.desktop.ui.library

enum class LibraryDisplayMode {
    /** Small cards — same as current grid, ~4-5 columns. */
    COMPACT_GRID,

    /** Larger cards with title below the cover. */
    COMFORTABLE_GRID,

    /** Horizontal list rows with cover + metadata. */
    LIST,
    ;

    companion object {
        val DEFAULT = COMPACT_GRID
    }
}
