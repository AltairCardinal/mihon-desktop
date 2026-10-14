package mihon.desktop.easteregg

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal const val PIG_EASTER_EGG_RESOURCE = "/easter-egg/pig.png"

internal fun loadPigEasterEggImage(): ImageBitmap {
    val encoded = requireNotNull(
        PigEasterEggImageResource::class.java.getResourceAsStream(PIG_EASTER_EGG_RESOURCE),
    ) {
        "Pig easter egg image resource is missing: $PIG_EASTER_EGG_RESOURCE"
    }.use { it.readBytes() }

    return Image.makeFromEncoded(encoded).toComposeImageBitmap()
}

private object PigEasterEggImageResource
