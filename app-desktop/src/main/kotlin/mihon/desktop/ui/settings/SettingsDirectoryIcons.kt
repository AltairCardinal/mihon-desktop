package mihon.desktop.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material Symbols Rounded glyphs used by the frozen upstream SettingsMainScreen.
 * Packaged SVGs: google/material-design-icons bd8cb85bd4bad964fe6918f79665bb40c3a8efef,
 * symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg. No remote loading at runtime.
 */
internal object SettingsDirectoryIcons {
    val checkCircle by lazy { load("check_circle") }
    val palette by lazy { load("palette") }
    val library by lazy { load("collections_bookmark") }
    val reader by lazy { load("chrome_reader_mode", autoMirror = true) }
    val download by lazy { load("download") }
    val tracking by lazy { load("sync") }
    val browse by lazy { load("explore") }
    val storage by lazy { load("storage") }
    val security by lazy { load("security") }
    val advanced by lazy { load("code") }
    val about by lazy { load("info") }

    private fun load(name: String, autoMirror: Boolean = false): ImageVector {
        val svg = requireNotNull(javaClass.getResourceAsStream("/icons/settings/$name.svg")) {
            "Missing packaged settings icon: $name"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val paths = Regex("""<path\s+d="([^"]+)"""").findAll(svg).map { it.groupValues[1] }.toList()
        require(paths.isNotEmpty()) { "Missing vector path: $name" }
        return ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f, autoMirror = autoMirror).apply {
            addGroup(translationY = 960f)
            paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
            clearGroup()
        }.build()
    }
}
