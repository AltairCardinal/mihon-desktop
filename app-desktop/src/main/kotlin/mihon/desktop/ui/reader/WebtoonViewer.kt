package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.rememberAsyncImagePainter

/**
 * Vertical-scroll (webtoon) viewer.  All pages are stacked in a [LazyColumn]
 * and the user scrolls continuously through the chapter — no page swipes.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * This composable has zero desktop-specific code.  Move it to a shared Compose
 * Multiplatform module and use it on Android without changes.
 *
 * @param pageUrls Ordered list of page image URLs for the chapter.
 */
@Composable
internal fun WebtoonViewer(pageUrls: List<String>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(pageUrls) { url ->
            Image(
                painter = rememberAsyncImagePainter(url),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
