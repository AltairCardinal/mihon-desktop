package mihon.desktop.reader

/**
 * Image scale type for the reader pager viewer.
 * Maps to Compose [androidx.compose.ui.layout.ContentScale] values.
 */
enum class ScaleType(val displayName: String) {
    FIT_SCREEN("Fit Screen"),
    FIT_WIDTH("Fit Width"),
    FIT_HEIGHT("Fit Height"),
    ORIGINAL_SIZE("Original Size"),
    SMART_FIT("Smart Fit"),
    ;

    companion object {
        val DEFAULT = FIT_SCREEN
    }
}
