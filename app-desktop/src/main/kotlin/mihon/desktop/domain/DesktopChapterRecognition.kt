package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga

internal fun SChapter.recognizedChapterNumber(mangaTitle: String): Double {
    return ChapterRecognition.parseChapterNumber(
        mangaTitle = mangaTitle,
        chapterName = name,
        chapterNumber = chapter_number.toDouble(),
    )
}

internal fun SChapter.recognizedChapterNumber(manga: Manga): Double {
    return recognizedChapterNumber(manga.title)
}
