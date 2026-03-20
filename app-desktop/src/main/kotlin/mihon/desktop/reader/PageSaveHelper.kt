package mihon.desktop.reader

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Utility object for page-save actions in the reader context menu.
 *
 * Android reference: presentation/reader/ReaderPageActionsDialog.kt
 * Android saves via MediaStore or Downloads folder; desktop saves to ~/Pictures/Mihon.
 */
object PageSaveHelper {

    /** Characters that are not safe in a filename on any major OS. */
    private val UNSAFE_CHARS = Regex("""[/\\:*?"<>|]""")

    /**
     * Builds a filename for a saved page image.
     * Format: `{mangaTitle} - {chapterTitle} - p{pageIndex+1}.png`
     *
     * Path-unsafe characters are replaced with underscores.
     */
    fun buildSaveFileName(mangaTitle: String, chapterTitle: String, pageIndex: Int): String {
        val safeManga = mangaTitle.replace(UNSAFE_CHARS, "_")
        val safeChapter = chapterTitle.replace(UNSAFE_CHARS, "_")
        return "$safeManga - $safeChapter - p${pageIndex + 1}.png"
    }

    /**
     * Returns the default directory where pages are saved: `~/Pictures/Mihon/`.
     * Creates the directory if it does not exist.
     */
    fun defaultSaveDirectory(): File {
        val pictures = System.getProperty("user.home") + File.separator + "Pictures" + File.separator + "Mihon"
        return File(pictures).also { it.mkdirs() }
    }

    /**
     * Writes [image] as PNG to [destination].
     * Overwrites the file if it already exists.
     */
    fun saveImageToFile(image: BufferedImage, destination: File) {
        ImageIO.write(image, "png", destination)
    }

    /**
     * Loads a page image from [url] using [ImageIO] (same approach as [EdgePixelMatcher]).
     * Returns null if loading fails.
     */
    fun loadImage(url: String): BufferedImage? = try {
        ImageIO.read(java.net.URL(url))
    } catch (_: Exception) {
        null
    }
}
