package mihon.desktop.test.screenshot

import org.slf4j.LoggerFactory
import mihon.desktop.test.TestArtifactPaths
import java.awt.Robot
import java.awt.Toolkit
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * Service for capturing screenshots during test execution.
 * 
 * Uses Java AWT Robot to capture screen content.
 */
object ScreenshotService {
    
    private val logger = LoggerFactory.getLogger(ScreenshotService::class.java)
    
    private var screenshotDir: Path = TestArtifactPaths.defaultScreenshotDir()
    private var enabled = false
    
    /**
     * Initialize the screenshot service.
     */
    fun initialize(dir: String? = null) {
        dir?.let { 
            screenshotDir = Paths.get(it)
        }
        
        try {
            Files.createDirectories(screenshotDir)
            enabled = true
            logger.info("Screenshot service initialized at: $screenshotDir")
        } catch (e: IOException) {
            logger.error("Failed to create screenshot directory", e)
            enabled = false
        }
    }
    
    /**
     * Capture a screenshot and save it to a file.
     * 
     * @param name Name identifier for the screenshot
     * @return Path to the saved screenshot, or null if capture failed
     */
    fun capture(name: String): String? {
        if (!enabled) {
            logger.warn("Screenshot service not enabled")
            return null
        }
        
        return try {
            val robot = Robot()
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val rectangle = java.awt.Rectangle(0, 0, screenSize.width, screenSize.height)
            val image = robot.createScreenCapture(rectangle)
            
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            val filename = "${name}-${timestamp}.png"
            val file = screenshotDir.resolve(filename).toFile()
            
            ImageIO.write(image, "png", file)
            
            logger.info("Screenshot captured: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to capture screenshot", e)
            null
        }
    }
    
    /**
     * Get the screenshot directory path.
     */
    fun getScreenshotDir(): Path = screenshotDir
    
    /**
     * Set the screenshot directory.
     */
    fun setScreenshotDir(dir: String) {
        screenshotDir = Paths.get(dir)
        try {
            Files.createDirectories(screenshotDir)
        } catch (e: IOException) {
            logger.error("Failed to create screenshot directory", e)
        }
    }
    
    /**
     * Check if screenshots are enabled.
     */
    fun isEnabled(): Boolean = enabled
    
    /**
     * Disable the screenshot service.
     */
    fun disable() {
        enabled = false
    }
    
    /**
     * Enable the screenshot service.
     */
    fun enable() {
        if (screenshotDir.toFile().exists() || screenshotDir.toFile().mkdirs()) {
            enabled = true
        }
    }
    
    /**
     * Delete all screenshots in the screenshot directory.
     */
    fun clearScreenshots() {
        try {
            screenshotDir.toFile().listFiles()?.forEach { file ->
                if (file.isFile && file.extension == "png") {
                    file.delete()
                }
            }
            logger.info("Cleared all screenshots")
        } catch (e: Exception) {
            logger.error("Failed to clear screenshots", e)
        }
    }
    
    /**
     * Get list of all screenshots.
     */
    fun getScreenshots(): List<File> {
        return screenshotDir.toFile().listFiles()?.filter {
            it.isFile && it.extension == "png"
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
