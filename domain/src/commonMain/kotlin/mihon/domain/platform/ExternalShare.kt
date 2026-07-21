package mihon.domain.platform

sealed interface SharePayload {
    data class Text(val text: String) : SharePayload
    data class Stream(val uri: String, val mimeType: String, val message: String? = null) : SharePayload
}

object ExternalShare {
    fun fromUri(uri: String, mimeType: String = "image/*", message: String? = null): SharePayload? = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> SharePayload.Text(uri)
        uri.startsWith("content://") -> SharePayload.Stream(uri, mimeType, message)
        else -> null
    }
}
