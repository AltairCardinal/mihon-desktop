package tachiyomi.core.common.storage

import java.io.File

class DesktopStorageFolderProvider(
    private val baseDir: File = File(System.getProperty("user.home"), "Mihon"),
) : FolderProvider {

    override fun directory(): File = baseDir

    override fun path(): String = baseDir.absolutePath
}
