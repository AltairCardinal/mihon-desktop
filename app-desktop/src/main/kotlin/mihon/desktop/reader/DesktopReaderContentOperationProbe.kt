package mihon.desktop.reader

enum class DesktopReaderContentOperationKind {
    DIRECTORY_SIGNATURE,
    ARCHIVE_SIGNATURE,
    ARCHIVE_METADATA_READ,
    ARCHIVE_PAGE_COPY,
}

data class DesktopReaderContentOperation(
    val kind: DesktopReaderContentOperationKind,
    val pageIndex: Int? = null,
    val itemIdentity: String? = null,
)

fun interface DesktopReaderContentOperationProbe {
    val enabled: Boolean get() = true

    fun record(operation: DesktopReaderContentOperation)

    companion object {
        val None = object : DesktopReaderContentOperationProbe {
            override val enabled = false
            override fun record(operation: DesktopReaderContentOperation) = Unit
        }
    }
}

internal inline fun DesktopReaderContentOperationProbe.record(
    kind: DesktopReaderContentOperationKind,
    pageIndex: Int? = null,
    itemIdentity: () -> String? = { null },
) {
    if (!enabled) return
    record(DesktopReaderContentOperation(kind, pageIndex, itemIdentity()))
}
