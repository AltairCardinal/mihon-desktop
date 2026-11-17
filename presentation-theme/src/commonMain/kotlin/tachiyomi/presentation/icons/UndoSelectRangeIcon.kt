package tachiyomi.presentation.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Equal boundary gaps, line-width square dashes, and perpendicular pentagonal corner cuts. */
val undoSelectRangeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "UndoSelectRange",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes("M4 4H8V8H4ZM16 16H20V20H16Z"),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
        )
        addPath(
            pathData = addPathNodes(
                "M10.497717 5L12.497717 5L12.497717 7L10.497717 7Z" +
                "M13.995434 5L15.995434 5L15.995434 7L13.995434 7Z" +
                "M17.493151 5L20 5L20 7L18.425065 8.211488L17.493151 7Z" +
                "M16.122697 7.674848L14.672883 8.79009L15.788125 10.239904L17.237939 9.124663Z" +
                "M13.485757 9.703264L12.035942 10.818506L13.151184 12.26832L14.600998 11.153078Z" +
                "M10.848816 11.73168L9.399002 12.846922L10.514243 14.296736L11.964058 13.181494Z" +
                "M8.211875 13.760096L6.762061 14.875337L7.877303 16.325152L9.327117 15.20991Z" +
                "M6.506849 19L4 19L4 17L5.574935 15.788512L6.506849 17Z" +
                "M10.004566 19L8.004566 19L8.004566 17L10.004566 17Z" +
                "M13.502283 19L11.502283 19L11.502283 17L13.502283 17Z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
