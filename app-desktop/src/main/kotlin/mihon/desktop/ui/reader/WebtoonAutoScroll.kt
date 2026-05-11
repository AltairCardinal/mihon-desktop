package mihon.desktop.ui.reader

/**
 * Auto-scroll speed presets for Webtoon mode.
 * [pixelsPerSecond] is the nominal scroll rate at 1× display density.
 */
enum class WebtoonAutoScrollSpeed(val displayName: String, val pixelsPerSecond: Float) {
    Slowest("Slowest", 40f),
    Slow("Slow", 80f),
    Normal("Normal", 150f),
    Fast("Fast", 250f),
    Fastest("Fastest", 400f),
}
