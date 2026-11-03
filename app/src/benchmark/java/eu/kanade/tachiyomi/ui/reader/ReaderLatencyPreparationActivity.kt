package eu.kanade.tachiyomi.ui.reader

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Synthetic fixture import only; absent from formal Release. Never opens the reader. */
class ReaderLatencyPreparationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arguments = intent.extras ?: Bundle()
        Thread({ prepare(arguments) }, "reader-fixture-preparation").start()
    }

    private fun prepare(arguments: Bundle) {
        val result = JSONObject()
        try {
            runBlocking {
                val context = this@ReaderLatencyPreparationActivity
                require(packageName == "app.mihon.desktop.fork.benchmark")
                val root = requireNotNull(context.getExternalFilesDir("reader-open-real")).apply { mkdirs() }
                val original = File(root, "volume.cbz")
                val expectedHash = "b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677"
                val manifest = ReaderLatencyArchive.verify(original, expectedHash, 187)
                val mode = arguments.getString("probe", "off")
                require(mode in listOf("off", "on"))
                val label = arguments.getString("label", "run")
                require(label.matches(Regex("[A-Za-z0-9_-]{1,60}")))
                val storage = File(context.filesDir, "reader-open-storage").apply { mkdirs() }
                // Bounded fixture setup readiness, outside any measured normal-UI reader open.
                val preferences = withTimeout(10000) {
                    while (true) {
                        val resolved = runCatching { Injekt.get<StoragePreferences>() }.getOrNull()
                        if (resolved != null) return@withTimeout resolved
                        delay(50)
                    }
                    error("unreachable")
                }
                preferences.baseStorageDirectory().set(Uri.fromFile(storage).toString())
                Injekt.get<BasePreferences>().shownOnboardingFlow().set(true)
                Injekt.get<ReaderPreferences>().defaultReadingMode().set(ReadingMode.LEFT_TO_RIGHT.flagValue)
                Injekt.get<ReaderPreferences>().landscapeZoom().set(false)
                Injekt.get<ReaderPreferences>().showReadingMode().set(false)
                val sources = Injekt.get<SourceManager>()
                sources.isInitialized.first { it }
                val manga = Injekt.get<MangaRepository>().insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 610004,
                            url = "/real-cbz-vol04",
                            title = "Real CBZ 04",
                            favorite = true,
                            initialized = true,
                        ),
                    ),
                ).single()
                val chapters = Injekt.get<ChapterRepository>()
                val chapter = chapters.getChapterByMangaId(manga.id, false).firstOrNull()
                    ?: chapters.addAll(
                        listOf(
                            Chapter.create().copy(
                                mangaId = manga.id,
                                url = "/real-volume-04",
                                name = "Volume 04",
                                chapterNumber = 4.0,
                            ),
                        ),
                    ).single()
                val storageManager = Injekt.get<StorageManager>()
                withTimeout(5000) {
                    while (storageManager.getDownloadsDirectory()?.uri?.toString()?.contains(storage.name) !=
                        true
                    ) {
                        delay(20)
                    }
                }
                val provider = Injekt.get<DownloadProvider>()
                val mangaDirectory = provider.getMangaDir(manga.title, sources.getOrStub(manga.source)).getOrThrow()
                val archiveName = provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + ".cbz"
                val destination = requireNotNull(mangaDirectory.createFile(archiveName))
                context.contentResolver.openOutputStream(destination.uri, "wt")!!.use { output ->
                    original.inputStream().use { it.copyTo(output) }
                }
                val copied = File(requireNotNull(destination.uri.path))
                val installed = ReaderLatencyArchive.verify(copied, expectedHash, 187)
                require(installed.imageNames == manifest.imageNames)
                File(root, "config.json").writeText(
                    JSONObject().put(
                        "enabled",
                        mode == "on",
                    ).put("mangaId", manga.id).put("chapterId", chapter.id).put("label", label).toString(2),
                    Charsets.UTF_8,
                )
                result.put("status", "PREPARED_ONLY").put("probe", mode).put("label", label)
                    .put("mangaId", manga.id).put("chapterId", chapter.id).put("archiveUri", destination.uri.toString())
                    .put("archiveSha256", installed.sha256).put("archiveBytes", installed.bytes)
                    .put("imageNames", JSONArray(installed.imageNames)).put("imageCount", installed.imageNames.size)
            }
        } catch (error: Throwable) {
            result.put("status", "ERROR").put("error", android.util.Log.getStackTraceString(error))
        }
        val output = File(getExternalFilesDir("reader-open-real"), "preparation.json")
        output.writeText(result.toString(2), Charsets.UTF_8)
        runOnUiThread { finish() }
    }
}
