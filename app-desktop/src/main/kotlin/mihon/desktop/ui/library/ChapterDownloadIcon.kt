package mihon.desktop.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Platform vector adapter for Android res/drawable/ic_download_chapter_24dp.xml. */
internal val ChapterDownloadIcon: ImageVector by lazy {
    ImageVector.Builder("ChapterDownload", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            PathParser().parsePathString(
                "M11.99,2C6.47,2 2,6.48 2,12C2,17.52 6.47,22 11.99,22C17.52,22 22,17.52 22,12C22,6.48 17.52,2 11.99,2zM12,4C16.42,4 20,7.58 20,12C20,16.42 16.42,20 12,20C7.58,20 4,16.42 4,12C4,7.58 7.58,4 12,4z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
        addPath(
            PathParser().parsePathString(
                "M18.041,12 L16.976,10.935 12.755,15.149L12.755,5.959L11.245,5.959L11.245,15.149L7.031,10.928 5.959,12l6.041,6.041z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
