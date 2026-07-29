package mihon.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private const val DESKTOP_APP_ICON_RESOURCE = "/icons/mihon-desktop.png"

internal fun loadDesktopAppIcon(): ImageBitmap {
    val encoded = requireNotNull(
        DesktopAppIconResource::class.java.getResourceAsStream(DESKTOP_APP_ICON_RESOURCE),
    ) {
        "Desktop application icon resource is missing: $DESKTOP_APP_ICON_RESOURCE"
    }.use { it.readBytes() }

    return Image.makeFromEncoded(encoded).toComposeImageBitmap()
}

private object DesktopAppIconResource
