package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.reader.CropBorderScanner
import mihon.desktop.reader.WebtoonSidePadding
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Vertical-scroll (webtoon) viewer.  All pages are stacked in a [LazyColumn]
 * and the user scrolls continuously through the chapter — no page swipes.
 *
 * @param pageUrls    Ordered list of page image URLs for the chapter.
 * @param cropBorders When true, white borders are trimmed from each page.
 * @param sidePadding Amount of horizontal padding on each side.
 */
@Composable
internal fun WebtoonViewer(
    pageUrls: List<String>,
    cropBorders: Boolean = false,
    sidePadding: WebtoonSidePadding = WebtoonSidePadding.NONE,
) {
    val paddingFraction = sidePadding.ratio

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(pageUrls) { url ->
            val itemModifier = if (paddingFraction > 0f) {
                Modifier.fillMaxWidth(1f - 2f * paddingFraction)
            } else {
                Modifier.fillMaxWidth()
            }
            WebtoonPageItem(url = url, cropBorders = cropBorders, modifier = itemModifier)
        }
    }
}

/**
 * A single item in the webtoon LazyColumn.
 * Shows a loading indicator while the image is loading,
 * and handles optional border cropping.
 */
@Composable
private fun WebtoonPageItem(
    url: String,
    cropBorders: Boolean,
    modifier: Modifier = Modifier,
) {
    var croppedBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url, cropBorders) {
        if (cropBorders) {
            croppedBitmap = withContext(Dispatchers.IO) {
                webtoonLoadAndCrop(url)
            }
        } else {
            croppedBitmap = null
        }
    }

    val cropped = croppedBitmap
    if (cropped != null) {
        Image(
            bitmap = cropped,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.FillWidth,
        )
    } else {
        val painter = rememberAsyncImagePainter(url)
        val painterState by painter.state.collectAsState()

        when (painterState) {
            is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                // Show a loading indicator with a reasonable aspect ratio placeholder
                Box(
                    modifier = modifier.aspectRatio(2f / 3f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            else -> {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

private fun webtoonLoadAndCrop(url: String): ImageBitmap? = try {
    val awtImage: BufferedImage = ImageIO.read(java.net.URL(url)) ?: return null
    val rect = CropBorderScanner().detectCropRect(awtImage)
    if (rect.top == 0 && rect.left == 0 &&
        rect.bottom == awtImage.height && rect.right == awtImage.width
    ) {
        return null
    }
    awtImage.getSubimage(rect.left, rect.top, rect.width, rect.height).toComposeImageBitmap()
} catch (_: Exception) {
    null
}
