package mihon.desktop.extension

/**
 * Categorizes why an extension failed to load.
 *
 * Distinguishes test-infrastructure issues (DI not initialized) from real
 * compatibility gaps (missing Android stub), so the two types of failures
 * can be tracked and fixed separately.
 */
enum class ExtensionFailureCategory {

    /** Extension loaded and a Source was instantiated successfully. */
    LOAD_OK,

    /**
     * A class from android.* or androidx.* is missing from the stub layer.
     * Fix: add a desktop stub for the missing class.
     */
    STUB_MISSING,

    /**
     * Injekt bindings weren't initialized, or an injectLazy call resolved to
     * an unbound type.  This is a test-infrastructure issue, not a real compat gap.
     */
    DI_FAILURE,

    /** Any other Throwable — requires manual investigation. */
    LOAD_ERROR,
    ;

    companion object {

        /** Classifies a [Throwable] (or null for success) into a [ExtensionFailureCategory]. */
        fun from(error: Throwable?): ExtensionFailureCategory {
            if (error == null) return LOAD_OK

            // Walk the cause chain to find the most informative failure
            val chain = generateSequence(error) { it.cause }.toList()

            for (t in chain) {
                // Missing Android/AndroidX stub
                if (t is NoClassDefFoundError) {
                    val name = t.message?.replace('.', '/') ?: ""
                    if (name.startsWith("android/") || name.startsWith("androidx/")) {
                        return STUB_MISSING
                    }
                }

                // Injekt binding failure — covers both Kohesive and our custom fork
                if (t is NoSuchMethodError) {
                    val msg = t.message ?: ""
                    if ("injekt" in msg.lowercase() || "injectLazy" in msg || "InjektMain" in msg) {
                        return DI_FAILURE
                    }
                }
                val msg = (t.message ?: "").lowercase()
                if ("binding" in msg && ("find" in msg || "can't" in msg || "cannot" in msg)) {
                    return DI_FAILURE
                }
                if ("injekt" in msg || "inject" in msg && "binding" in msg) {
                    return DI_FAILURE
                }
            }

            return LOAD_ERROR
        }
    }
}

/**
 * Accumulates per-JAR load results and computes aggregate statistics.
 *
 * Usage:
 * ```
 * val report = CompatibilityReport()
 * for (jar in jars) {
 *     val (category, error) = tryLoadExtension(jar)
 *     report.record(jar.name, category, error)
 * }
 * println(report.summary())
 * ```
 */
class CompatibilityReport {

    data class Entry(
        val jarName: String,
        val category: ExtensionFailureCategory,
        val error: Throwable?,
    )

    private val entries = mutableListOf<Entry>()

    fun record(jarName: String, category: ExtensionFailureCategory, error: Throwable?) {
        entries.add(Entry(jarName, category, error))
    }

    val total: Int get() = entries.size
    val okCount: Int get() = entries.count { it.category == ExtensionFailureCategory.LOAD_OK }
    val stubMissingCount: Int get() = entries.count { it.category == ExtensionFailureCategory.STUB_MISSING }
    val diFailureCount: Int get() = entries.count { it.category == ExtensionFailureCategory.DI_FAILURE }
    val loadErrorCount: Int get() = entries.count { it.category == ExtensionFailureCategory.LOAD_ERROR }

    val successRate: Double
        get() = if (total == 0) 0.0 else okCount.toDouble() / total * 100.0

    /** One-line summary suitable for test output or CI logs. */
    fun summary(): String =
        "Extensions: $total total | $okCount OK (${successRate.toInt()}%) | " +
            "$stubMissingCount stub-missing | $diFailureCount DI-failure | $loadErrorCount other"

    /** Full report with per-JAR details for failing entries. */
    fun fullReport(): String = buildString {
        appendLine(summary())
        val failures = entries.filter { it.category != ExtensionFailureCategory.LOAD_OK }
        if (failures.isEmpty()) {
            appendLine("All extensions loaded successfully.")
        } else {
            appendLine("\nFailures:")
            failures.forEach { e ->
                appendLine("  [${e.category}] ${e.jarName}: ${e.error?.message ?: e.error?.javaClass?.simpleName}")
            }
        }
        // Missing stubs summary
        val missingClasses = entries
            .filter { it.category == ExtensionFailureCategory.STUB_MISSING }
            .mapNotNull { (it.error as? NoClassDefFoundError)?.message }
            .distinct()
            .sorted()
        if (missingClasses.isNotEmpty()) {
            appendLine("\nMissing stubs (${missingClasses.size} unique):")
            missingClasses.forEach { appendLine("  $it") }
        }
    }
}
