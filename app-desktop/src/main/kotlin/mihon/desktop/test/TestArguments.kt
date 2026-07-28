package mihon.desktop.test

/**
 * Parsed command-line arguments for test mode.
 */
data class TestArguments(
    val testMode: Boolean = false,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val jmxPort: Int = DEFAULT_JMX_PORT,
    val headless: Boolean = false,
    val platformAcceptanceToken: String? = null,
) {
    companion object {
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_JMX_PORT = 9999

        /**
         * Parse command-line arguments.
         */
        fun parse(args: Array<String>): TestArguments {
            var testMode = false
            var httpPort = DEFAULT_HTTP_PORT
            var jmxPort = DEFAULT_JMX_PORT
            var headless = false
            var platformAcceptanceToken: String? = null

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
                    arg.startsWith("--platform-acceptance-token=") -> {
                        platformAcceptanceToken = arg.substringAfter("=")
                    }
                }
            }

            return TestArguments(
                testMode = testMode,
                httpPort = httpPort,
                jmxPort = jmxPort,
                headless = headless,
                platformAcceptanceToken = platformAcceptanceToken,
            )
        }
    }
}
