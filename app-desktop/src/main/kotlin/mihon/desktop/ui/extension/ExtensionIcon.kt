package mihon.desktop.ui.extension

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
internal fun ExtensionIcon(
    iconUrl: String,
    loadIcon: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val iconBytes = produceState<ByteArray?>(initialValue = null, key1 = iconUrl) {
        value = loadIcon(iconUrl)
    }.value

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(),
        )
        if (iconBytes != null) {
            AsyncImage(
                model = iconBytes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
