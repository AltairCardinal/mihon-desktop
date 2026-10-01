package mihon.desktop.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Platform vector adapter for Android res/drawable/ic_done_prev_24dp.xml. */
internal val ChapterDonePreviousIcon: ImageVector by lazy {
    ImageVector.Builder("ChapterDonePrevious", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString("M9,16.2L4.8,12l-1.4,1.4L9,19 21,7l-1.4,-1.4L9,16.2z").toNodes(),
            fill = SolidColor(Color.Black),
        )
        addPath(
            PathParser().parsePathString("M22,18l-3,0l0,-4l-2,0l0,4l-3,0l4,4z").toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
