package mihon.data.sync.runtime

import java.io.File

/** A cache report may be shared; the nested session salt cache must never be exported. */
object SyncDiagnosticFiles {
    fun exportFile(path: String, directory: String?): File? = try {
        val parent = directory?.let { File(it).canonicalFile }
        val file = File(path).canonicalFile
        file.takeIf {
            parent != null && it.parentFile == parent && it.isFile && it.length() <= 256 * 1024 &&
                it.name.matches(
                    Regex("sync-diagnostic-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json"),
                )
        }
    } catch (_: Exception) {
        null
    }
}
