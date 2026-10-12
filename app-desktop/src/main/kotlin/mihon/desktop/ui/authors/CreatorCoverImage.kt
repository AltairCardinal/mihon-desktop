package mihon.desktop.ui.authors

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale

/** Android's drawable resources cannot load on JVM; retain its cover_error layer geometry and colors. */
@Composable
internal fun CreatorCoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    rounded: Boolean = true,
) {
    val color = Color(0x1F888888)
    val brokenImage = rememberVectorPainter(Icons.Default.BrokenImage)
    val error = remember(brokenImage) {
        object : Painter() {
            override val intrinsicSize = Size(120f, 120f)
            override fun DrawScope.onDraw() {
                drawRect(color)
                val iconSize = Size(size.width / 5f, size.height / 5f)
                translate(size.width * 0.4f, size.height * 0.4f) {
                    with(brokenImage) { draw(iconSize, colorFilter = ColorFilter.tint(color)) }
                }
            }
        }
    }
    coil3.compose.AsyncImage(
        model = model,
        contentDescription = contentDescription,
        placeholder = ColorPainter(color),
        error = error,
        fallback = error,
        contentScale = contentScale,
        modifier = if (rounded) modifier.clip(MaterialTheme.shapes.extraSmall) else modifier,
    )
}
