package mihon.desktop.test

import java.io.File

/**
 * Parsed command-line arguments for test mode.
 */
data class TestArguments(
    val testMode: Boolean = false,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val jmxPort: Int = DEFAULT_JMX_PORT,
    val headless: Boolean = false,
    val platformAcceptanceToken: String? = null,
    val testProfile: String? = null,
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
            var testProfile: String? = null

            for (arg in args) {
                when {
                    arg == "--test-profile" -> require(false) { "Use --test-profile=<absolute-directory>" }
                    arg.startsWith("--test-profile=") -> {
                        require(testProfile == null) { "Only one test profile may be selected" }
                        testProfile = arg.substringAfter("=")
                        require(testProfile.isNotBlank() && File(testProfile).isAbsolute) {
                            "Test profile requires an absolute directory"
                        }
                    }
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

            require(testProfile == null || testMode) { "--test-profile requires --test-mode" }
            return TestArguments(
                testMode = testMode,
                httpPort = httpPort,
                jmxPort = jmxPort,
                headless = headless,
                platformAcceptanceToken = platformAcceptanceToken,
                testProfile = testProfile,
            )
        }
    }
}
