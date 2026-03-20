package mihon.desktop.reader

/**
 * Background colour theme for the reader, mirroring Android's reader background setting.
 *
 * Android reference: ReaderPreferences.readerTheme()
 *   0 = Black, 1 = Gray, 2 = White, 3 = Automatic (follows system dark/light)
 */
enum class ReaderBackgroundTheme {
    BLACK,
    GRAY,
    WHITE,
    AUTOMATIC,
    ;

    companion object {
        val DEFAULT = BLACK
    }
}
