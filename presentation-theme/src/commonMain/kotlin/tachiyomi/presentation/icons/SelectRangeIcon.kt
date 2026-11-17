package tachiyomi.presentation.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Shared 24dp range-selection icon with hollow square endpoints and a Z-shaped connection. */
val selectRangeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SelectRange",
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
            pathData = addPathNodes("M9 5H20V7L7 17H15V19H4V17L17 7H9Z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
