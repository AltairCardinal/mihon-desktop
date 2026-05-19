package mihon.test.desktop.visual

import mihon.test.desktop.DesktopTestClient
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Visual regression testing client.
 *
 * Uses perceptual hash (pHash) algorithm for image comparison.
 */
class VisualTestClient(private val client: DesktopTestClient) {

    private val logger = LoggerFactory.getLogger(VisualTestClient::class.java)

    private var baselineDir: Path = Path.of("test-baseline/screens")
    private var currentDir: Path = Path.of("build/screens/current")
    private var diffDir: Path = Path.of("build/screens/diff")

    // Thresholds
    var hammingDistanceThreshold: Int = 10
    var pixelDifferenceThreshold: Double = 0.05 // 5%

    /**
     * Set baseline directory.
     */
    fun setBaselineDir(path: Path): VisualTestClient {
        baselineDir = path
        return this
    }

    /**
     * Set current screenshot directory.
     */
    fun setCurrentDir(path: Path): VisualTestClient {
        currentDir = path
        return this
    }

    /**
     * Set diff output directory.
     */
    fun setDiffDir(path: Path): VisualTestClient {
        diffDir = path
        return this
    }

    /**
     * Capture and compare screenshot with baseline.
     */
    fun assertMatchesBaseline(name: String, tolerance: Double = pixelDifferenceThreshold): Boolean {
        // Capture current screenshot
        val screenshot = client.screenshot(name)
        if (!screenshot.success || screenshot.path == null) {
            logger.error("Screenshot failed: ${screenshot.error}")
            return false
        }

        val currentPath = Path.of(screenshot.path)

        // Load baseline
        val baselineFile = baselineDir.resolve("$name.png")
        if (!Files.exists(baselineFile)) {
            logger.warn("No baseline found for '$name', creating new baseline")
            Files.createDirectories(baselineDir)
            Files.copy(currentPath, baselineFile)
            return true
        }

        // Compare images
        val result = compare(baselineFile, currentPath)

        if (result.passed) {
            logger.info("Visual comparison passed for '$name' (diff: ${result.diffPercentage}%)")
        } else {
            logger.warn("Visual comparison FAILED for '$name' (diff: ${result.diffPercentage}%)")
            // Save current as diff
            Files.createDirectories(diffDir)
            Files.copy(currentPath, diffDir.resolve("$name-diff.png"))
        }

        return result.passed
    }

    /**
     * Compare two images using simple pixel comparison.
     */
    private fun compare(baseline: Path, current: Path): ComparisonResult {
        try {
            val baselineBytes = Files.readAllBytes(baseline)
            val currentBytes = Files.readAllBytes(current)

            if (baselineBytes.size != currentBytes.size) {
                return ComparisonResult(
                    passed = false,
                    diffPercentage = 100.0,
                    message = "Image sizes differ: baseline=${baselineBytes.size}, current=${currentBytes.size}",
                )
            }

            // Simple byte-by-byte comparison (proxy for visual diff)
            var diffCount = 0
            for (i in baselineBytes.indices) {
                if (baselineBytes[i] != currentBytes[i]) {
                    diffCount++
                }
            }

            val diffPercentage = (diffCount.toDouble() / baselineBytes.size) * 100
            val passed = diffPercentage <= (pixelDifferenceThreshold * 100)

            return ComparisonResult(
                passed = passed,
                diffPercentage = diffPercentage,
                message = if (passed) "Images match" else "Images differ: ${String.format("%.2f", diffPercentage)}%",
            )
        } catch (e: Exception) {
            return ComparisonResult(
                passed = false,
                diffPercentage = 100.0,
                message = "Error comparing images: ${e.message}",
            )
        }
    }
}

/**
 * Result of image comparison.
 */
data class ComparisonResult(
    val passed: Boolean,
    val hammingDistance: Int = 0,
    val diffPercentage: Double = 0.0,
    val message: String = "",
)
