@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package mihon.desktop.backup.models

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Root backup container — wire-compatible with the Android .tachibk format.
 *
 * Field numbers match [eu.kanade.tachiyomi.data.backup.models.Backup] exactly
 * so that a backup created on Desktop can be restored on Android and vice-versa.
 */
@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(101) val backupSources: List<BackupSource> = emptyList(),
)

@Serializable
data class BackupManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(14) val viewer: Int = 0,
    @ProtoNumber(16) val chapters: List<BackupChapter> = emptyList(),
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(18) val tracking: List<BackupTracking> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(101) val chapterFlags: Int = 0,
    @ProtoNumber(104) val history: List<BackupHistory> = emptyList(),
    @ProtoNumber(105) val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    @ProtoNumber(106) val lastModifiedAt: Long = 0,
    @ProtoNumber(107) val favoriteModifiedAt: Long? = null,
    @ProtoNumber(108) val excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(109) val version: Long = 0,
    @ProtoNumber(110) val notes: String = "",
    @ProtoNumber(111) val initialized: Boolean = false,
)

@Serializable
data class BackupChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val scanlator: String? = null,
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(5) val bookmark: Boolean = false,
    @ProtoNumber(6) val lastPageRead: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val chapterNumber: Float = 0f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
    @ProtoNumber(11) val lastModifiedAt: Long = 0,
    @ProtoNumber(12) val version: Long = 0,
)

@Serializable
data class BackupCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Long = 0,
    @ProtoNumber(3) val id: Long = 0,
    @ProtoNumber(100) val flags: Long = 0,
)

@Serializable
data class BackupHistory(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val lastRead: Long,
    @ProtoNumber(3) val readDuration: Long = 0,
)

@Serializable
data class BackupTracking(
    @ProtoNumber(1) val syncId: Int,
    @ProtoNumber(2) val libraryId: Long,
    @ProtoNumber(4) val trackingUrl: String = "",
    @ProtoNumber(5) val title: String = "",
    @ProtoNumber(6) val lastChapterRead: Float = 0f,
    @ProtoNumber(7) val totalChapters: Int = 0,
    @ProtoNumber(8) val score: Float = 0f,
    @ProtoNumber(9) val status: Int = 0,
    @ProtoNumber(10) val startedReadingDate: Long = 0,
    @ProtoNumber(11) val finishedReadingDate: Long = 0,
    @ProtoNumber(100) val mediaId: Long = 0,
)

@Serializable
data class BackupSource(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val sourceId: Long,
)
