package mihon.desktop.platform

import mihon.domain.platform.RejectionReason
import java.io.File

sealed interface DesktopExternalActionTarget {
    data class GlobalSearch(val query: String) : DesktopExternalActionTarget
    data class Manga(val mangaId: Long) : DesktopExternalActionTarget
    data class Chapter(val mangaId: Long, val chapterId: Long) : DesktopExternalActionTarget
    data class Backup(val file: File) : DesktopExternalActionTarget
    data class ExtensionRepo(val url: String) : DesktopExternalActionTarget
    data class Rejected(
        val reason: Rejection,
        val parserReason: RejectionReason? = null,
    ) : DesktopExternalActionTarget

    enum class Rejection { NoAction, ParserRejected, SourceResolutionFailed, InvalidBackupPath }
}
