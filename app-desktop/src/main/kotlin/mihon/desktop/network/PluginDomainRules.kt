package mihon.desktop.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PluginDomainExportFormat {
    PLAIN,
    MIHOMO_DOMAIN,
    MIHOMO_SUFFIX,
    SING_BOX,
    XRAY,
}

fun normalizePluginHost(value: String): String? {
    val candidate = value.trim()
    if (candidate.isEmpty()) return null
    val url = candidate.toHttpUrlOrNull()
        ?: "https://$candidate".toHttpUrlOrNull()
        ?: return null
    return url.host.lowercase().removeSuffix(".").takeIf(String::isNotBlank)
}

fun exportPluginDomains(
    domains: Collection<String>,
    format: PluginDomainExportFormat,
    target: String = "PROXY",
): String {
    val normalized = domains.mapNotNull(::normalizePluginHost).distinct().sorted()
    return when (format) {
        PluginDomainExportFormat.PLAIN -> normalized.joinToString("\n")
        PluginDomainExportFormat.MIHOMO_DOMAIN -> normalized.joinToString("\n") { "- DOMAIN,$it,$target" }
        PluginDomainExportFormat.MIHOMO_SUFFIX -> normalized.joinToString("\n") { "- DOMAIN-SUFFIX,$it,$target" }
        PluginDomainExportFormat.XRAY -> normalized.joinToString("\n") { "domain:$it" }
        PluginDomainExportFormat.SING_BOX -> buildString {
            appendLine("{")
            appendLine("  \"domain\": [")
            normalized.forEachIndexed { index, host ->
                append("    \"").append(jsonEscape(host)).append('"')
                if (index != normalized.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"action\": \"route\",")
            append("  \"outbound\": \"").append(jsonEscape(target)).appendLine("\"")
            append('}')
        }
    }
}

private fun jsonEscape(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
