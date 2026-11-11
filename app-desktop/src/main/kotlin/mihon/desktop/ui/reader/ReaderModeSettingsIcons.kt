package mihon.desktop.ui.reader

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Approved prototype paths; 24-unit grid, round 1.65-unit strokes, decorative and theme-tinted. */
internal enum class ModeSettingsIcon(vararg paths: String) {
    BOOK(
        "M12 5.5c-2.9-2.2-6.1-2.2-9-1v14c2.9-1.1 6.1-1.1 9 1.2 2.9-2.3 6.1-2.3 9-1.2v-14c-2.9-1.2-6.1-1.2-9 1Z",
        "M12 5.5v14.2",
    ),
    LINK(
        "M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    ),
    SLIDERS("M4 6h5m4 0h7M4 12h11m4 0h1M4 18h2m4 0h10M9 3v6m6 0v6M6 15v6"),
    STRIP(
        "M6.5 2.75h11a2.5 2.5 0 0 1 2.5 2.5v13.5a2.5 2.5 0 0 1-2.5 2.5h-11a2.5 2.5 0 0 1-2.5-2.5v-13.5a2.5 2.5 0 0 1 2.5-2.5Z",
        "M4 8h16M4 16h16",
    ),
    AUTO("M8 3H5a2 2 0 0 0-2 2v3m13-5h3a2 2 0 0 1 2 2v3M3 16v3a2 2 0 0 0 2 2h3m8 0h3a2 2 0 0 0 2-2v-3"),
    SINGLE(
        "M7.8 2.75h8.4a1.8 1.8 0 0 1 1.8 1.8v14.9a1.8 1.8 0 0 1-1.8 1.8H7.8a1.8 1.8 0 0 1-1.8-1.8V4.55a1.8 1.8 0 0 1 1.8-1.8Z",
    ),
    DOUBLE(
        "M4.3 3.75h15.4a1.8 1.8 0 0 1 1.8 1.8v12.9a1.8 1.8 0 0 1-1.8 1.8H4.3a1.8 1.8 0 0 1-1.8-1.8V5.55a1.8 1.8 0 0 1 1.8-1.8Z",
        "M12 3.75v16.5",
    ),
    RIGHT("M5 12h14m-6-6 6 6-6 6"),
    LEFT("M19 12H5m6-6-6 6 6 6"),
    CONTINUOUS(
        "M5 2h14a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z",
        "M12 6v12M9 9l3-3 3 3M9 15l3 3 3-3",
    ),
    PAGED("M4 2h16M6 6h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2ZM4 22h16"),
    ;

    val vector: ImageVector by lazy {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.65f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }
}

@Composable
internal fun ReaderModeIcon(icon: ModeSettingsIcon, modifier: Modifier, tint: Color = LocalContentColor.current) {
    Icon(icon.vector, contentDescription = null, modifier = modifier, tint = tint)
}
