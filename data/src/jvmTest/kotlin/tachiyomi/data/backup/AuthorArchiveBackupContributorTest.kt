package tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorBinding
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorDiscovery
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorLanguageDecision
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorSourceWork
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorWatch
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

class AuthorArchiveBackupContributorTest {

    @Test
    fun `round trip converges same-name identities and preserves redirect and natural-key bindings`() {
        runBlocking {
            val source = fixture()
            source.seedManga(1L, 10L, "/one")
            source.seedManga(2L, 10L, "/two")
            source.seedArchive()

            val exported = checkNotNull(source.contributor.createSection())
            val legacy = exported.copy(version = 4, creators = exported.creators.map { it.copy(names = emptyList()) })
            val section = BackupCodec.decode(
                BackupAuthorArchiveSection.serializer(),
                BackupCodec.encode(BackupAuthorArchiveSection.serializer(), legacy),
            )

            val target = fixture()
            target.seedManga(101L, 10L, "/one")
            target.seedManga(102L, 10L, "/two")
            target.contributor.restoreSection(checkNotNull(section))
            target.contributor.restoreSection(checkNotNull(section))

            target.long("SELECT COUNT(*) FROM author_archive_creators") shouldBe 3L
            target.long("SELECT COUNT(*) FROM author_archive_creators WHERE normalized_name = 'same'") shouldBe 3L
            target.long("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 1L
            target.long("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 5L
            target.long("SELECT COUNT(*) FROM author_archive_aliases") shouldBe 3L
            target.long("SELECT COUNT(*) FROM author_archive_source_works") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_source_work_creators") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_manga_links") shouldBe 2L
            target.long(
                "SELECT COUNT(*) FROM author_archive_watches WHERE enabled = 1 AND period_millis = 60000",
            ) shouldBe
                1L
            target.long("SELECT COUNT(*) FROM author_archive_watch_sources") shouldBe 2L
            target.long(
                "SELECT COUNT(*) FROM author_archive_watch_sources WHERE baseline_state = 'NEEDS_BASELINE'",
            ) shouldBe
                2L
            target.long("SELECT COUNT(*) FROM author_archive_watch_languages WHERE language_tag = 'ja'") shouldBe 1L
            target.long(
                "SELECT COUNT(*) FROM author_archive_discoveries " +
                    "WHERE read_state = 'SEEN' AND review_disposition = 'IGNORED'",
            ) shouldBe 1L
            target.long("SELECT COUNT(*) FROM author_archive_notification_outbox") shouldBe 0L
            target.long("SELECT COUNT(*) FROM author_archive_canonical_works") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_work_decisions WHERE actor = 'RESTORE'") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_canonical_versions") shouldBe 1L
            target.long("SELECT COUNT(*) FROM author_archive_language_assertions WHERE actor = 'RESTORE'") shouldBe 2L
            target.string(
                "SELECT language_tag FROM author_archive_language_assertions " +
                    "WHERE subject_key = 'source:10:/one' AND withdrawn = 0",
            ) shouldBe "zh-hant"
            target.long(
                "SELECT withdrawn FROM author_archive_language_assertions WHERE subject_key = 'source:10:/two'",
            ) shouldBe 1L
            target.long(
                "SELECT COUNT(*) FROM author_archive_watch_result_policies " +
                    "WHERE include_probable = 1 AND notify_probable = 1",
            ) shouldBe 1L
            target.string(
                "SELECT TARGET.portable_key FROM author_archive_creators SOURCE " +
                    "JOIN author_archive_creators TARGET ON TARGET._id = SOURCE.merged_into_creator_id " +
                    "WHERE SOURCE.portable_key = 'creator-old'",
            ) shouldBe "creator-a"
            target.long(
                "SELECT manga_id FROM author_archive_manga_links ML " +
                    "JOIN author_archive_creators C ON C._id = ML.creator_id WHERE C.portable_key = 'creator-a'",
            ) shouldBe 101L
            target.long(
                "SELECT manga_id FROM author_archive_manga_links ML " +
                    "JOIN author_archive_creators C ON C._id = ML.creator_id WHERE C.portable_key = 'creator-a' " +
                    "ORDER BY manga_id DESC LIMIT 1",
            ) shouldBe 102L
        }
    }

    @Test
    fun `invalid redirect cycle is rejected before transaction writes`() {
        runBlocking {
            val target = fixture()
            val invalid = BackupAuthorArchiveSection(
                creators = listOf(
                    BackupCreatorIdentity(
                        portableKey = "a",
                        displayName = "A",
                        normalizedName = "a",
                        status = "MERGED",
                        mergedIntoPortableKey = "b",
                    ),
                    BackupCreatorIdentity(
                        portableKey = "b",
                        displayName = "B",
                        normalizedName = "b",
                        status = "MERGED",
                        mergedIntoPortableKey = "a",
                    ),
                ),
            )

            shouldThrow<IllegalArgumentException> { target.contributor.restoreSection(invalid) }

            target.long("SELECT COUNT(*) FROM author_archive_creators") shouldBe 0L
        }
    }

    @Test
    fun `portable backup reattaches trimmed and blank urls across different local manga ids`() {
        runBlocking {
            val source = fixture()
            source.seedManga(10L, 42L, " /trimmed ", title = "Trimmed", author = "Portable Author")
            source.seedManga(20L, 42L, "", title = "Blank URL", author = "Portable Author")
            source.seedPortableUrlArchive()

            val section = checkNotNull(source.contributor.createSection())

            section.sourceWorks.map { it.stableSourceUrl }.toSet().let { urls ->
                ("/trimmed" in urls) shouldBe true
                urls.single { it != "/trimmed" }.startsWith("blank-manga:") shouldBe true
            }
            val target = fixture()
            target.seedManga(110L, 42L, "  /trimmed  ", title = "Trimmed", author = "Portable Author")
            target.seedManga(220L, 42L, "", title = "Blank URL", author = "Portable Author")
            target.contributor.restoreSection(section)

            target.long("SELECT COUNT(*) FROM author_archive_manga_links") shouldBe 2L
            target.long("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 110") shouldBe 1L
            target.long("SELECT COUNT(*) FROM author_archive_manga_links WHERE manga_id = 220") shouldBe 1L
        }
    }

    @Test
    fun `exact names and chosen primary survive backup without normalized alias loss`() = runBlocking<Unit> {
        val source = fixture()
        source.contributor.restoreSection(
            BackupAuthorArchiveSection(
                version = 4,
                creators = listOf(
                    BackupCreatorIdentity("root", "One", "one"),
                ),
            ),
        )
        source.driver.execute(
            null,
            "INSERT INTO author_archive_identity_names(name_text, creator_id, origin, " +
                "created_at, last_modified_at) " +
                "SELECT 'ONE', _id, 'MANUAL', 1, 1 FROM author_archive_creators " +
                "WHERE portable_key = 'root'",
            0,
        )
        val backup = checkNotNull(source.contributor.createSection())
        val target = fixture()
        target.contributor.restoreSection(backup)
        target.contributor.restoreSection(backup)
        target.long("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 2L
        target.string("SELECT display_name FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe "One"
    }

    @Test
    fun `existing valid primary survives restoring same portable key`() = runBlocking<Unit> {
        val target = fixture()
        target.contributor.restoreSection(
            BackupAuthorArchiveSection(
                version = 4,
                creators = listOf(
                    BackupCreatorIdentity("root", "Local", "local"),
                ),
            ),
        )
        target.contributor.restoreSection(
            BackupAuthorArchiveSection(
                version = 4,
                creators = listOf(
                    BackupCreatorIdentity("root", "Backup", "backup"),
                ),
            ),
        )
        target.string("SELECT display_name FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe "Local"
        target.long("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 2L
    }

    @kotlinx.serialization.Serializable
    private data class LegacyArchive(
        @kotlinx.serialization.protobuf.ProtoNumber(1) val version: Int = 4,
        @kotlinx.serialization.protobuf.ProtoNumber(2) val creators: List<BackupCreatorIdentity>,
    )

    @Test
    fun `omitted legacy wire version decodes as legacy and restores`() = runBlocking<Unit> {
        val encoded = BackupCodec.encode(
            LegacyArchive.serializer(),
            LegacyArchive(
                creators = listOf(
                    BackupCreatorIdentity("legacy", "Legacy", "legacy"),
                ),
            ),
        )
        val decoded = BackupCodec.decode(BackupAuthorArchiveSection.serializer(), encoded)
        decoded.version shouldBe 4
        val target = fixture()
        target.contributor.restoreSection(decoded)
        target.string("SELECT name_text FROM author_archive_identity_names") shouldBe "Legacy"
    }

    @Test
    fun `real identity commands round trip complete graph and old keys through codec`() = runBlocking<Unit> {
        val source = fixture()
        val target = fixture()
        tachiyomi.data.creator.verifyCreatorIdentityBackup(source.handler, target.handler) { exporter, importer ->
            val section = checkNotNull(exporter.createSection())
            val decoded = BackupCodec.decode(
                BackupAuthorArchiveSection.serializer(),
                BackupCodec.encode(BackupAuthorArchiveSection.serializer(), section),
            )
            importer.restoreSection(decoded)
            decoded
        }
    }

    @Test
    fun `legacy exact-name convergence unions author and artist bindings`() = runBlocking<Unit> {
        val target = fixture()
        val section = BackupAuthorArchiveSection(
            version = 4,
            creators = listOf(
                BackupCreatorIdentity("a", "Same", "same"),
                BackupCreatorIdentity("b", "Same", "same"),
            ),
            sourceWorks = listOf(
                BackupAuthorSourceWork(
                    42,
                    "/book",
                    "Book",
                    bindings = listOf(
                        BackupAuthorBinding("a", "AUTHOR", 0, "AUTOMATIC", "VERIFIED", "Same", 1.0, "fixture"),
                        BackupAuthorBinding("b", "ARTIST", 1, "AUTOMATIC", "VERIFIED", "Same", 1.0, "fixture"),
                    ),
                ),
            ),
        )
        target.contributor.restoreSection(section)
        target.string("SELECT role FROM author_archive_source_work_creators") shouldBe "BOTH"
    }

    @Test
    fun `v5 malformed graphs reject before any archive write`() = runBlocking<Unit> {
        val name = BackupCreatorName("One", "MANUAL")
        val root = BackupCreatorIdentity("one", "One", "one", names = listOf(name))
        val base = BackupAuthorArchiveSection(version = 5, creators = listOf(root))
        val cycleStart = root.copy(status = "MERGED", mergedIntoPortableKey = "two", names = emptyList())
        val cycleEnd = root.copy(
            portableKey = "two",
            status = "MERGED",
            mergedIntoPortableKey = "one",
            names = emptyList(),
        )
        val cases = listOf(
            base.copy(version = 999),
            base.copy(creators = listOf(root.copy(names = emptyList()))),
            base.copy(creators = listOf(root, root.copy(portableKey = "other"))),
            base.copy(creators = listOf(root.copy(status = "MERGED", mergedIntoPortableKey = "missing"))),
            base.copy(creators = listOf(cycleStart, cycleEnd)),
        )
        for (invalid in cases) {
            val target = fixture()
            shouldThrow<IllegalArgumentException> { target.contributor.restoreSection(invalid) }
            target.long("SELECT COUNT(*) FROM author_archive_creators") shouldBe 0L
            target.long("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 0L
        }
    }

    @Test
    fun `merged old key keeps local primary and adds exact names`() = runBlocking<Unit> {
        val target = fixture()
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(target.handler)
        val old = repository.upsertCreator("Old")
        val current = repository.upsertCreator("Current")
        repository.mergeCreatorIdentities(old.id, current.id)
        val oldKey = target.string("SELECT portable_key FROM author_archive_creators WHERE display_name = 'Old'")
        val incoming = BackupAuthorArchiveSection(
            version = 5,
            creators = listOf(
                BackupCreatorIdentity(
                    oldKey,
                    "Backup",
                    "backup",
                    names = listOf(BackupCreatorName("Backup", "MANUAL")),
                ),
            ),
        )
        target.contributor.restoreSection(incoming)
        repository.getIdentitySnapshot(old.id).displayName shouldBe "Current"
        repository.getIdentitySnapshot(old.id).names.toSet() shouldBe setOf("Old", "Current", "Backup")
        target.contributor.restoreSection(incoming)
        target.long("SELECT COUNT(*) FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe 1L
    }

    @Test
    fun `transitive names preserve local primary regardless of order`() = runBlocking<Unit> {
        for (reverse in listOf(false, true)) {
            val target = fixture()
            target.contributor.restoreSection(
                BackupAuthorArchiveSection(
                    version = 4,
                    creators = listOf(
                        BackupCreatorIdentity("local-a", "Local A", "local a"),
                        BackupCreatorIdentity("local-b", "Local B", "local b"),
                    ),
                ),
            )
            val names = listOf("Backup", "Local A", "Local B").let { if (reverse) it.reversed() else it }
            target.contributor.restoreSection(
                BackupAuthorArchiveSection(
                    version = 5,
                    creators = listOf(
                        BackupCreatorIdentity(
                            "incoming",
                            "Backup",
                            "backup",
                            names = names.map {
                                BackupCreatorName(it, "MANUAL")
                            },
                        ),
                    ),
                ),
            )
            target.string("SELECT display_name FROM author_archive_creators WHERE status = 'ACTIVE'") shouldBe "Local A"
            target.long("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 3L
        }
    }

    @Test
    fun `creator language decision resolves restored old key`() = runBlocking<Unit> {
        val target = fixture()
        val backup = BackupAuthorArchiveSection(
            version = 4,
            creators = listOf(
                BackupCreatorIdentity("root", "Root", "root"),
                BackupCreatorIdentity("old", "Old", "old", status = "MERGED", mergedIntoPortableKey = "root"),
            ),
            languageDecisions = listOf(
                BackupAuthorLanguageDecision(
                    "CREATOR",
                    "creator:old",
                    "ORIGINAL",
                    "ja",
                    false,
                    1,
                ),
            ),
        )
        target.contributor.restoreSection(backup)
        target.string(
            "SELECT subject_key FROM author_archive_language_assertions WHERE actor = 'RESTORE'",
        ) shouldBe "creator:root"
    }

    @Test
    fun `redirect order preserves same local primary after repeated restore`() = runBlocking<Unit> {
        for (reverse in listOf(false, true)) {
            val target = fixture()
            var sequence = 0
            val repository = tachiyomi.data.creator.CreatorRepositoryImpl(
                target.handler,
                portableKeyFactory = { if (sequence++ == 0) "local-a" else "local-b" },
            )
            repository.upsertCreator("Local A")
            repository.upsertCreator("Local B")
            val redirected = listOf(
                BackupCreatorIdentity(
                    "local-a",
                    "Backup A",
                    "backup a",
                    status = "MERGED",
                    mergedIntoPortableKey = "remote",
                ),
                BackupCreatorIdentity(
                    "local-b",
                    "Backup B",
                    "backup b",
                    status = "MERGED",
                    mergedIntoPortableKey = "remote",
                ),
            ).let { if (reverse) it.reversed() else it }
            val backup = BackupAuthorArchiveSection(
                version = 5,
                creators = redirected + BackupCreatorIdentity(
                    "remote",
                    "Remote",
                    "remote",
                    names = listOf("Remote", "Backup A", "Backup B").map {
                        BackupCreatorName(it, "MANUAL")
                    },
                ),
            )
            repeat(2) {
                target.contributor.restoreSection(backup)
                target.string(
                    "SELECT display_name FROM author_archive_creators WHERE status = 'ACTIVE'",
                ) shouldBe "Local A"
                target.long("SELECT COUNT(*) FROM author_archive_identity_names") shouldBe 5L
            }
        }
    }

    @Test
    fun `restored discovery collisions retain pending state independent of order`() = runBlocking<Unit> {
        for (reverse in listOf(false, true)) {
            val target = fixture()
            val discoveries = listOf(
                BackupAuthorDiscovery(
                    "root", 42, "/book", "NEW_SOURCE_VERSION", "root reason", 2, "UNSEEN", "PENDING", 10,
                ),
                BackupAuthorDiscovery("old", 42, "/book", "NEW_WORK_CANDIDATE", "old reason", 7, "SEEN", "ACCEPTED", 5),
            ).let { if (reverse) it.reversed() else it }
            val backup = BackupAuthorArchiveSection(
                version = 4,
                creators = listOf(
                    BackupCreatorIdentity("root", "Root", "root"),
                    BackupCreatorIdentity("old", "Old", "old", status = "MERGED", mergedIntoPortableKey = "root"),
                ),
                sourceWorks = listOf(BackupAuthorSourceWork(42, "/book", "Book")),
                watches = listOf("root", "old").map { BackupAuthorWatch(it, true, 60_000, listOf(42)) },
                discoveries = discoveries,
            )
            repeat(2) {
                target.contributor.restoreSection(backup)
                target.string("SELECT read_state FROM author_archive_discoveries") shouldBe "UNSEEN"
                target.string("SELECT review_disposition FROM author_archive_discoveries") shouldBe "PENDING"
                target.string("SELECT kind FROM author_archive_discoveries") shouldBe "NEW_WORK_CANDIDATE"
                target.long("SELECT baseline_generation FROM author_archive_discoveries") shouldBe 7L
                target.long("SELECT first_discovered_at FROM author_archive_discoveries") shouldBe 5L
                target.string("SELECT reason FROM author_archive_discoveries").split("\n").toSet() shouldBe
                    setOf("root reason", "old reason")
            }
        }
    }

    private fun fixture(): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = tachiyomi.data.Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
            ),
        )
        val handler = JvmDatabaseHandler(database, driver)
        val repository = tachiyomi.data.creator.CreatorRepositoryImpl(handler)
        return Fixture(
            driver,
            handler,
            SqlDelightAuthorArchiveBackupContributor(
                handler,
                clock = { 500L },
                awaitIdentityReady = repository::awaitIdentityReady,
            ),
        )
    }

    private data class Fixture(
        val driver: JdbcSqliteDriver,
        val handler: JvmDatabaseHandler,
        val contributor: AuthorArchiveBackupContributor,
    ) {
        fun seedManga(
            id: Long,
            source: Long,
            url: String,
            title: String = "Manga $id",
            author: String? = null,
        ) {
            driver.execute(
                null,
                "INSERT INTO mangas(_id, source, url, title, author, status, favorite, initialized, viewer, " +
                    "chapter_flags, cover_last_modified, date_added, last_modified_at, version) " +
                    "VALUES (?, ?, ?, ?, ?, 0, 1, 0, 0, 0, 0, 0, 1, 1)",
                5,
            ) {
                bindLong(0, id)
                bindLong(1, source)
                bindString(2, url)
                bindString(3, title)
                bindString(4, author)
            }
        }

        fun seedArchive() {
            driver.execute(
                null,
                "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, sort_name, " +
                    "status, needs_review, created_at, last_modified_at) VALUES " +
                    "(1, 'creator-a', 'Same A', 'same', 'Same A', 'ACTIVE', 0, 1, 1), " +
                    "(2, 'creator-b', 'Same B', 'same', 'Same B', 'ACTIVE', 0, 1, 1), " +
                    "(3, 'creator-old', 'Same Old', 'same', 'Same Old', 'ACTIVE', 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "UPDATE author_archive_creators SET status = 'MERGED', merged_into_creator_id = 1 WHERE _id = 3",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                    "confidence, is_manual, created_at, last_modified_at) VALUES " +
                    "(1, 'Same', 'same', 'USER', 'manual a', 1, 1, 1, 1), " +
                    "(2, 'Same', 'same', 'USER', 'manual b', 1, 1, 1, 1), " +
                    "(3, 'Old Same', 'old same', 'USER', 'merge alias', 1, 1, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(_id, source_id, stable_source_url, manga_id, title, " +
                    "normalized_title, first_seen_at, last_seen_at) VALUES " +
                    "(1, 10, '/one', 1, 'One', 'one', 1, 1), " +
                    "(2, 10, '/two', 2, 'Two', 'two', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_work_creators(source_work_id, creator_id, role, creator_order, " +
                    "origin, verification, confidence, evidence, created_at, last_modified_at) VALUES " +
                    "(1, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'split a', 1, 1), " +
                    "(2, 2, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'split b', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_canonical_works(_id, portable_key, primary_title, normalized_title, " +
                    "status, created_at, last_modified_at) VALUES " +
                    "(1, 'work-one', 'One', 'one', 'ACTIVE', 1, 1), " +
                    "(2, 'work-two', 'Two', 'two', 'ACTIVE', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_work_decisions(source_work_id, work_id, state, actor, explicit, score, " +
                    "evidence, decided_at, idempotency_key) VALUES " +
                    "(1, 1, 'CONFIRMED', 'USER', 1, 1, 'same work', 2, 'backup-confirmed'), " +
                    "(2, 2, 'REJECTED', 'USER', 1, 0.8, 'keep separate', 3, 'backup-rejected')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_language_assertions(subject_type, subject_key, dimension, language_tag, " +
                    "confidence, evidence_kind, evidence_payload, actor, withdrawn, asserted_at, " +
                    "idempotency_key) VALUES " +
                    "('SOURCE_WORK', 'source:10:/one', 'READING', 'zh-hant', 1, 'MANUAL', " +
                    "'override', 'USER', 0, 2, 'language-one'), " +
                    "('SOURCE_WORK', 'source:10:/two', 'READING', 'und', 1, 'MANUAL', " +
                    "'undo', 'USER', 1, 3, 'language-two-undo')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watches(" +
                    "_id, creator_id, enabled, period_millis, created_at, last_modified_at" +
                    ") " +
                    "VALUES (1, 1, 1, 60000, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watch_sources(watch_id, source_id, baseline_state, baseline_generation, " +
                    "next_due_at, created_at, last_modified_at) VALUES " +
                    "(1, 10, 'BASELINED', 4, 100, 1, 1), (1, 20, 'BASELINED', 4, 100, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watch_result_policies(_id, watch_id, include_probable, include_unknown, " +
                    "notify_probable, notify_unknown, created_at, last_modified_at) " +
                    "VALUES (1, 1, 1, 0, 1, 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_watch_languages(policy_id, language_tag) VALUES (1, 'ja')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_discoveries(" +
                    "_id, watch_id, source_work_id, kind, reason, baseline_generation, read_state, " +
                    "review_disposition, first_discovered_at, last_modified_at" +
                    ") VALUES (1, 1, 1, 'NEW_WORK_CANDIDATE', 'verified', 4, 'SEEN', 'IGNORED', 10, 11)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_notification_outbox(" +
                    "discovery_id, channel, idempotency_key, attempt_count, state, created_at" +
                    ") VALUES (1, 'DESKTOP', 'do-not-restore', 0, 'PENDING', 10)",
                0,
            )
        }

        fun seedPortableUrlArchive() {
            driver.execute(
                null,
                "INSERT INTO author_archive_creators(_id, portable_key, display_name, normalized_name, sort_name, " +
                    "status, needs_review, created_at, last_modified_at) VALUES " +
                    "(1, 'portable-author', 'Portable Author', 'portable author', 'Portable Author', " +
                    "'ACTIVE', 0, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_aliases(creator_id, raw_alias, normalized_alias, source, evidence, " +
                    "confidence, is_manual, created_at, last_modified_at) VALUES " +
                    "(1, 'Portable Author', 'portable author', 'USER', 'portable alias', 1, 1, 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_works(_id, source_id, stable_source_url, manga_id, title, " +
                    "normalized_title, author_text, first_seen_at, last_seen_at) VALUES " +
                    "(1, 42, '/trimmed', 10, 'Trimmed', 'trimmed', 'Portable Author', 1, 1), " +
                    "(2, 42, 'legacy-manga:20', 20, 'Blank URL', 'blank url', 'Portable Author', 1, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO author_archive_source_work_creators(source_work_id, creator_id, role, creator_order, " +
                    "origin, verification, confidence, evidence, created_at, last_modified_at) VALUES " +
                    "(1, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'trimmed binding', 1, 1), " +
                    "(2, 1, 'AUTHOR', 0, 'USER', 'VERIFIED', 1, 'blank binding', 1, 1)",
                0,
            )
        }

        fun long(sql: String): Long = driver.executeQuery(
            null,
            sql,
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
            },
            0,
        ).value

        fun string(sql: String): String = driver.executeQuery(
            null,
            sql,
            { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getString(0)!! else "")
            },
            0,
        ).value
    }
}
