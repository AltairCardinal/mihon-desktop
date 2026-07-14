package eu.kanade.tachiyomi.ui.reader

import mihon.domain.reader.ReaderColorFilterParams

internal fun buildAndroidReaderColorFilterParams(
    tintEnabled: Boolean = false,
    brightnessEnabled: Boolean = false,
    brightness: Float = 0f,
    r: Int = 0,
    g: Int = 0,
    b: Int = 0,
    alpha: Int = 128,
    grayscaleEnabled: Boolean = false,
    invertEnabled: Boolean = false,
): ReaderColorFilterParams = ReaderColorFilterParams(
    tintEnabled = tintEnabled,
    brightnessEnabled = brightnessEnabled,
    brightness = brightness,
    r = r,
    g = g,
    b = b,
    alpha = alpha,
    grayscaleEnabled = grayscaleEnabled,
    invertEnabled = invertEnabled,
)

internal fun buildAndroidLayerFilterParams(
    grayscale: Boolean,
    invertedColors: Boolean,
): ReaderColorFilterParams = buildAndroidReaderColorFilterParams(
    grayscaleEnabled = grayscale,
    invertEnabled = invertedColors,
)
