package mihon.domain.platform

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
sealed interface ExternalActionInput {
    data class Search(val primaryQuery: String?, val fallbackText: String? = null) : ExternalActionInput
    data class SendText(val text: String?) : ExternalActionInput
    data class ViewUri(val value: String?) : ExternalActionInput
}

sealed interface ExternalAction {
    data object NoOp : ExternalAction
    data class Search(val query: String) : ExternalAction
    data class AddRepository(val url: String) : ExternalAction
    data class RestoreBackup(val uri: String) : ExternalAction
    data class Rejected(val reason: RejectionReason) : ExternalAction
}

enum class RejectionReason { MALFORMED_URI, UNSUPPORTED_URI, INVALID_REPOSITORY_URL, INVALID_BACKUP_URI }

/** Fixed-main action semantics plus documented cross-platform input hardening. */
object ExternalActionParser {
    fun resolve(input: ExternalActionInput): ExternalAction = when (input) {
        is ExternalActionInput.Search -> search(input.primaryQuery ?: input.fallbackText)
        is ExternalActionInput.SendText -> search(input.text)
        is ExternalActionInput.ViewUri -> viewUri(input.value)
    }

    private fun search(text: String?) =
        text?.takeIf(String::isNotEmpty)?.let(ExternalAction::Search) ?: ExternalAction.NoOp

    private fun viewUri(value: String?): ExternalAction {
        val uri = value.orEmpty()
        if (uri.isEmpty()) return ExternalAction.NoOp
        return when {
            uri.endsWith(".tachibk") -> ExternalAction.RestoreBackup(uri)
            uri.startsWith("tachiyomi:") -> addRepository(uri)
            hasScheme(uri) -> ExternalAction.Rejected(RejectionReason.UNSUPPORTED_URI)
            else -> ExternalAction.Rejected(RejectionReason.MALFORMED_URI)
        }
    }

    private fun addRepository(uri: String): ExternalAction {
        val prefix = "tachiyomi://add-repo"
        if (!uri.startsWith(prefix)) return ExternalAction.Rejected(RejectionReason.UNSUPPORTED_URI)
        if ('#' in uri) return ExternalAction.Rejected(RejectionReason.MALFORMED_URI)
        val suffix = uri.removePrefix(prefix)
        if (!suffix.startsWith("?")) return ExternalAction.Rejected(RejectionReason.MALFORMED_URI)
        val urls = suffix.removePrefix("?").split('&').mapNotNull { part ->
            part.indexOf('=').takeIf { it >= 0 }?.let { index -> part.substring(0, index) to part.substring(index + 1) }
        }.filter { it.first == "url" }
        if (urls.size != 1) return ExternalAction.Rejected(RejectionReason.INVALID_REPOSITORY_URL)
        val repositoryUrl =
            percentDecode(urls.single().second)
                ?: return ExternalAction.Rejected(RejectionReason.INVALID_REPOSITORY_URL)
        return repositoryUrl.takeIf(::isHttpUrl)?.let(ExternalAction::AddRepository)
            ?: ExternalAction.Rejected(RejectionReason.INVALID_REPOSITORY_URL)
    }

    private fun hasScheme(value: String): Boolean = value.indexOf(':').let { separator ->
        separator > 0 && value.take(separator).all { it.isLetterOrDigit() || it in "+-." }
    }

    private fun isHttpUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty()
    } ?: false

    private fun percentDecode(value: String): String? = runCatching {
        buildString {
            var index = 0
            while (index < value.length) {
                if (value[index] != '%') {
                    append(value[index++])
                } else {
                    val bytes = ArrayList<Byte>()
                    while (index < value.length && value[index] == '%') {
                        require(index + 2 < value.length)
                        bytes += ((value[index + 1].digitToInt(16) shl 4) or value[index + 2].digitToInt(16)).toByte()
                        index += 3
                    }
                    append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
                }
            }
        }
    }.getOrNull()
}
