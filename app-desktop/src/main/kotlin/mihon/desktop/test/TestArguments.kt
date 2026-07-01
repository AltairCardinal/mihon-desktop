package mihon.desktop.test

/**
 * Parsed command-line arguments for test mode.
 */
data class TestArguments(
    val testMode: Boolean = false,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val jmxPort: Int = DEFAULT_JMX_PORT,
    val headless: Boolean = false,
    val screenshotDir: String = DEFAULT_SCREENSHOT_DIR,
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_JMX_PORT = 9999
        val DEFAULT_SCREENSHOT_DIR: String = TestArtifactPaths.defaultScreenshotDir().toString()

        /**
         * Parse command-line arguments.
         */
        fun parse(args: Array<String>): TestArguments {
            var testMode = false
            var httpPort = DEFAULT_HTTP_PORT
            var jmxPort = DEFAULT_JMX_PORT
            var headless = false
            var screenshotDir = DEFAULT_SCREENSHOT_DIR

            for (arg in args) {
                when {
                    arg == "--test-mode" -> testMode = true
                    arg.startsWith("--test-http-port=") -> {
                        httpPort = arg.substringAfter("=").toIntOrNull() ?: DEFAULT_HTTP_PORT
                    }
                    arg.startsWith("--test-jmx-port=") -> {
                        jmxPort = arg.substringAfter("=").toIntOrNull() ?: DEFAULT_JMX_PORT
                    }
                    arg == "--headless" -> headless = true
                    arg.startsWith("--screenshot-dir=") -> {
                        screenshotDir = arg.substringAfter("=")
                    }
                }
            }

            return TestArguments(
                testMode = testMode,
                httpPort = httpPort,
                jmxPort = jmxPort,
                headless = headless,
                screenshotDir = screenshotDir,
            )
        }
    }
}
