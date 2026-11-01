package tachiyomi.core.common.feedback

data class SafeCrashEntry(val atMillis: Long, val content: String)

object SafeCrashReportCodec {
    const val HEADER = "MIHON SAFE CRASH REPORT v1\n"
    const val FOOTER = "END MIHON CRASH REPORT v1\n"
    fun format(error: Throwable, atMillis: Long = System.currentTimeMillis()): String =
        HEADER + safeCrashReport(error, atMillis) + FOOTER

    fun read(content: String): List<SafeCrashEntry> {
        require(content.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Crash preview too large" }
        if (content.isEmpty()) return emptyList()
        val normalized = content.replace("\r\n", "\n")
        val blocks = normalized.split(HEADER)
        require(blocks.size >= 2 && blocks.first().isEmpty()) { "Unknown crash format" }
        return blocks.drop(1).map { block ->
            require(block.endsWith(FOOTER)) { "Incomplete crash report" }
            val body = block.removeSuffix(FOOTER)
            require(body.endsWith('\n')) { "Incomplete crash body" }
            val lines = body.removeSuffix("\n").lines()
            val timestamp = lines.first().removePrefix("Crash time (UTC millis): ")
            require(lines.first() != timestamp && timestamp.matches(Regex("-?[0-9]{1,19}"))) {
                "Invalid crash timestamp"
            }
            val time = requireNotNull(timestamp.toLongOrNull()) { "Invalid crash timestamp" }
            lines.drop(1).forEach { line ->
                if (line.startsWith("  at ")) {
                    val frame = line.removePrefix("  at ")
                    val separator = frame.lastIndexOf(':')
                    val identifier = if (separator >= 0) frame.substring(0, separator) else frame
                    val method = identifier.lastIndexOf('.')
                    require(
                        method > 0 && safeCrashIdentifier(identifier.substring(0, method)) &&
                            safeCrashIdentifier(identifier.substring(method + 1)),
                    ) { "Unsafe crash frame" }
                    if (separator >=
                        0
                    ) {
                        require(frame.substring(separator + 1).matches(Regex("[0-9]{1,10}"))) {
                            "Invalid crash line"
                        }
                    }
                } else {
                    require(safeCrashIdentifier(line)) { "Unsafe crash type" }
                }
            }
            SafeCrashEntry(time, HEADER + body + FOOTER)
        }
    }
}
