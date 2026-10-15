package mihon.desktop.easteregg

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File

internal const val PIG_EASTER_EGG_RESOURCE = "/easter-egg/pig.png"
internal const val PIG_EASTER_EGG_SVG_RESOURCE = "/easter-egg/pig.svg"
internal const val PIG2_EASTER_EGG_RESOURCE = "/easter-egg/pig2.png"
internal const val SNOUT_PARTICLE_RESOURCE = "/easter-egg/snout_particle.png"
internal const val GOLD_STAR_RESOURCE = "/easter-egg/gold_star.png"
internal const val PIG_PRIMARY_FILE = "d:/temp/pig.png"
internal const val PIG_PRIMARY_SVG_FILE = "d:/temp/pig.svg"
internal const val PIG_SECONDARY_FILE = "d:/temp/pig2.png"
internal const val SNOUT_PRIMARY_FILE = "d:/temp/snout_particle.png"
internal const val GOLD_STAR_PRIMARY_FILE = "d:/temp/gold_star.png"

internal fun loadPigEasterEggImage(): ImageBitmap {
    val svgFile = File(PIG_PRIMARY_SVG_FILE)
    val svgBytes = if (svgFile.exists() && svgFile.isFile) {
        svgFile.readBytes()
    } else {
        PigEasterEggImageResource::class.java.getResourceAsStream(PIG_EASTER_EGG_SVG_RESOURCE)?.use { it.readBytes() }
    }
    if (svgBytes != null) {
        try {
            return renderSvg(svgBytes, targetWidth = 1814, targetHeight = 1560)
        } catch (_: Throwable) {
            // fallback to PNG if SVG rendering fails
        }
    }
    return loadPigBitmap(PIG_PRIMARY_FILE, PIG_EASTER_EGG_RESOURCE)
}

internal fun loadPig2EasterEggImage(): ImageBitmap = loadPigBitmap(PIG_SECONDARY_FILE, PIG2_EASTER_EGG_RESOURCE)

internal fun loadSnoutParticleImage(): ImageBitmap = loadPigBitmap(SNOUT_PRIMARY_FILE, SNOUT_PARTICLE_RESOURCE)

internal fun loadGoldStarImage(): ImageBitmap = loadPigBitmap(GOLD_STAR_PRIMARY_FILE, GOLD_STAR_RESOURCE)

internal fun parseSvgDimensions(svgText: String): Pair<Float, Float> {
    val viewBoxMatch = Regex("""viewBox=["']\s*([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)["']""").find(svgText)
    if (viewBoxMatch != null) {
        val w = viewBoxMatch.groupValues[3].toFloatOrNull()
        val h = viewBoxMatch.groupValues[4].toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) return Pair(w, h)
    }
    val widthMatch = Regex("""width=["']([0-9.]+)["']""").find(svgText)?.groupValues?.get(1)?.toFloatOrNull()
    val heightMatch = Regex("""height=["']([0-9.]+)["']""").find(svgText)?.groupValues?.get(1)?.toFloatOrNull()
    if (widthMatch != null && heightMatch != null && widthMatch > 0f && heightMatch > 0f) {
        return Pair(widthMatch, heightMatch)
    }
    return Pair(246f, 216f)
}

internal fun renderSvg(bytes: ByteArray, targetWidth: Int = 2048, targetHeight: Int = 1800): ImageBitmap {
    val svgText = bytes.decodeToString()
    val (svgW, svgH) = parseSvgDimensions(svgText)
    val dom = SVGDOM(Data.makeFromBytes(bytes))
    dom.setContainerSize(targetWidth.toFloat(), targetHeight.toFloat())
    val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
    val canvas = surface.canvas
    val scale = minOf(targetWidth.toFloat() / svgW, targetHeight.toFloat() / svgH)
    val dx = (targetWidth - svgW * scale) / 2f
    val dy = (targetHeight - svgH * scale) / 2f
    canvas.translate(dx, dy)
    canvas.scale(scale, scale)
    dom.render(canvas)
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

internal fun loadPigBitmap(filePath: String, resourcePath: String): ImageBitmap {
    val file = File(filePath)
    val bytes = if (file.exists() && file.isFile) {
        file.readBytes()
    } else {
        requireNotNull(
            PigEasterEggImageResource::class.java.getResourceAsStream(resourcePath),
        ) {
            "Pig easter egg image resource is missing: $resourcePath"
        }.use { it.readBytes() }
    }

    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

private object PigEasterEggImageResource
