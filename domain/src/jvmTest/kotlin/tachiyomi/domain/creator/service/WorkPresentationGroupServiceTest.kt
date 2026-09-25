package tachiyomi.domain.creator.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.CanonicalWorkArchiveGroup
import tachiyomi.domain.creator.model.CreatorWorkArchive
import tachiyomi.domain.creator.model.DecisionActor
import tachiyomi.domain.creator.model.LanguageCertainty
import tachiyomi.domain.creator.model.LanguageDimension
import tachiyomi.domain.creator.model.LanguageEvidenceKind
import tachiyomi.domain.creator.model.LanguageProjectionContract
import tachiyomi.domain.creator.model.SourceDateQualityStatus
import tachiyomi.domain.creator.model.SourceWorkArchiveVersion
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.model.WorkDecisionContract
import tachiyomi.domain.creator.model.WorkDecisionProjection
import tachiyomi.domain.creator.model.WorkDecisionState

class WorkPresentationGroupServiceTest {

    @Test
    fun `retained earliest date stays visible under review and follows current membership`() {
        val retained = version(
            10L,
            "/retained",
            "《同作》",
            publishedDateAt = 100L,
            publishedDateQuality = SourceDateQualityStatus.SUSPECT,
        )
        val current = version(20L, "/current", "同作", publishedDateAt = 200L)
        val archive = CreatorWorkArchive(emptyList(), listOf(retained, current), emptyList())
        val group = WorkPresentationGroupService.project(archive).single()
        group.publishedDateAt shouldBe 100L
        group.publishedDateQuality shouldBe SourceDateQualityStatus.SUSPECT

        val excluded = WorkPresentationGroupService.project(archive, setOf(retained.naturalKey))
        excluded.single { current in it.members }.publishedDateAt shouldBe 200L
        excluded.single { current in it.members }.publishedDateQuality shouldBe SourceDateQualityStatus.TRUSTED
        excluded.single { retained in it.members }.publishedDateAt shouldBe 100L
    }

    @Test
    fun `groups same authors unconfirmed script and typography variants into one stable projection`() {
        val traditional = version(
            sourceId = 10L,
            url = "/traditional",
            title = "《詭譎屋：外傳》",
            inLibrary = true,
            firstSeenAt = 100L,
            publishedDateAt = 300L,
        )
        val simplified = version(
            sourceId = 20L,
            url = "/simplified",
            title = "诡谲屋 外传",
            unread = true,
            firstSeenAt = 120L,
            latestChapterAt = 600L,
        )
        val archive = CreatorWorkArchive(
            works = emptyList(),
            pending = listOf(simplified, traditional),
            rejected = emptyList(),
        )

        val firstProjection = WorkPresentationGroupService.project(archive)
        val secondProjection = WorkPresentationGroupService.project(archive.copy(pending = archive.pending.reversed()))

        firstProjection shouldHaveSize 1
        secondProjection shouldHaveSize 1
        val group = firstProjection.single()
        group shouldBe secondProjection.single()
        group.groupKey shouldBe "title:诡谲屋外传"
        group.title shouldBe traditional.title
        group.members.map(SourceWorkArchiveVersion::naturalKey) shouldContainExactly listOf(
            traditional.naturalKey,
            simplified.naturalKey,
        )
        group.sourceCount shouldBe 2
        group.inLibrary shouldBe true
        group.unread shouldBe true
        group.firstSeenAt shouldBe 100L
        group.representative.naturalKey shouldBe traditional.naturalKey
        group.publishedDateAt shouldBe 300L
        group.latestChapterAt shouldBe null

        WorkPresentationGroupService.project(
            CreatorWorkArchive(
                works = emptyList(),
                pending = listOf(
                    version(30L, "/phrase-traditional", "情有獨鍾"),
                    version(40L, "/phrase-simplified", "情有独钟"),
                ),
                rejected = emptyList(),
            ),
        ).single().groupKey shouldBe "title:情有独钟"
    }

    @Test
    fun `keeps case numbers semantic text and identical originals outside presentation groups`() {
        val variants = listOf(
            version(1L, "/upper", "Series 2"),
            version(2L, "/lower", "series 2"),
            version(3L, "/volume-one", "詭譎屋 外傳 1"),
            version(4L, "/volume-two", "诡谲屋 外传 2"),
            version(5L, "/same-a", "Same Title"),
            version(6L, "/same-b", "Same Title"),
            version(7L, "/context-a", "乾坤"),
            version(8L, "/context-b", "干坤"),
        )

        val groups = WorkPresentationGroupService.project(
            CreatorWorkArchive(emptyList(), variants, emptyList()),
        )

        groups shouldHaveSize variants.size
        groups.flatMap { it.members } shouldContainExactly variants.sortedBy { it.naturalKey.sourceId }
    }

    @Test
    fun `keeps confirmed canonical groups apart and attaches only a uniquely matched pending version`() {
        val canonical = canonicalGroup(100L, "canonical-a", "詭譎屋", 10L, "/confirmed-a")
        val unrelatedCanonical = canonicalGroup(200L, "canonical-b", "Other Work", 20L, "/confirmed-b")
        val pending = version(30L, "/pending", "诡谲屋")

        val groups = WorkPresentationGroupService.project(
            CreatorWorkArchive(
                works = listOf(canonical, unrelatedCanonical),
                pending = listOf(pending),
                rejected = emptyList(),
            ),
        )

        groups.map { it.canonicalWorkId } shouldContainExactly listOf(100L, 200L)
        groups.first {
            it.canonicalWorkId == 100L
        }.members.map(SourceWorkArchiveVersion::naturalKey) shouldContainExactly
            listOf(
                SourceWorkNaturalKey(10L, "/confirmed-a"),
                pending.naturalKey,
            )
        groups.first { it.canonicalWorkId == 100L }.title shouldBe "詭譎屋"
        groups.first { it.canonicalWorkId == 100L }.groupKey shouldBe "canonical:canonical-a"
    }

    @Test
    fun `does not attach ambiguous pending version or merge equivalent canonical groups`() {
        val firstCanonical = canonicalGroup(100L, "canonical-a", "詭譎屋", 10L, "/confirmed-a")
        val secondCanonical = canonicalGroup(200L, "canonical-b", "诡谲屋", 20L, "/confirmed-b")
        val ambiguous = version(30L, "/ambiguous", "詭譎屋")

        val groups = WorkPresentationGroupService.project(
            CreatorWorkArchive(
                works = listOf(firstCanonical, secondCanonical),
                pending = listOf(ambiguous),
                rejected = emptyList(),
            ),
        )

        groups.map { it.canonicalWorkId } shouldContainExactly listOf(100L, 200L, null)
        groups.single { it.canonicalWorkId == null }.members shouldContainExactly listOf(ambiguous)
        groups.filter { it.canonicalWorkId != null }.forEach { it.sourceCount shouldBe 1 }
    }

    @Test
    fun `excludes rejected versions and isolates a user-excluded member without changing archive facts`() {
        val includedTraditional = version(10L, "/traditional", "詭譎屋")
        val includedSimplified = version(20L, "/simplified", "诡谲屋")
        val excluded = version(30L, "/excluded", "詭譎屋", decisionState = WorkDecisionState.SUGGESTED)
        val rejected = version(40L, "/rejected", "诡谲屋", decisionState = WorkDecisionState.REJECTED)
        val archive = CreatorWorkArchive(
            works = emptyList(),
            pending = listOf(includedTraditional, includedSimplified, excluded, rejected),
            rejected = listOf(rejected),
        )

        val groups = WorkPresentationGroupService.project(
            archive,
            excludedNaturalKeys = setOf(excluded.naturalKey),
        )

        groups shouldHaveSize 2
        groups.first { it.sourceCount == 2 }.members.map(SourceWorkArchiveVersion::naturalKey) shouldContainExactly
            listOf(
                includedTraditional.naturalKey,
                includedSimplified.naturalKey,
            )
        groups.single { it.members.singleOrNull()?.naturalKey == excluded.naturalKey }.sourceCount shouldBe 1
        groups.flatMap { it.members }.none { it.naturalKey == rejected.naturalKey } shouldBe true
        archive shouldBe CreatorWorkArchive(
            works = emptyList(),
            pending = listOf(includedTraditional, includedSimplified, excluded, rejected),
            rejected = listOf(rejected),
        )
    }

    @Test
    fun `isolates an excluded canonical member without changing its canonical archive fact`() {
        val first = version(10L, "/canonical-first", "詭譎屋")
        val second = version(20L, "/canonical-second", "诡谲屋")
        val canonical = canonicalGroup(100L, "canonical-a", "詭譎屋", 10L, "/canonical-first")
            .copy(versions = listOf(first, second))

        val groups = WorkPresentationGroupService.project(
            CreatorWorkArchive(works = listOf(canonical), pending = emptyList(), rejected = emptyList()),
            excludedNaturalKeys = setOf(second.naturalKey),
        )

        groups.single { it.canonicalWorkId == 100L }.members shouldContainExactly listOf(first)
        groups.single { it.members.singleOrNull()?.naturalKey == second.naturalKey }.groupKey shouldBe
            "source:20:/canonical-second"
        canonical.versions shouldContainExactly listOf(first, second)
    }

    @Test
    fun `uses a prior member title when a stable group key receives a new equivalent source`() {
        val traditional = version(10L, "/traditional", "詭譎屋")
        val simplified = version(20L, "/simplified", "诡谲屋")
        val groupKey = "title:诡谲屋"

        val group = WorkPresentationGroupService.project(
            CreatorWorkArchive(emptyList(), listOf(traditional, simplified), emptyList()),
            previousTitles = mapOf(groupKey to traditional.title),
        ).single()

        group.groupKey shouldBe groupKey
        group.title shouldBe traditional.title
    }

    @Test
    fun `display title follows requested application script without changing source facts`() {
        val traditional = version(10L, "/traditional", "詭譎屋")
        val simplified = version(20L, "/simplified", "诡谲屋")
        val archive = CreatorWorkArchive(emptyList(), listOf(traditional, simplified), emptyList())

        WorkPresentationGroupService.project(
            archive,
            preferredDisplayScript = WorkTitleNormalizer.DisplayScript.SIMPLIFIED,
        ).single().title shouldBe simplified.title
        WorkPresentationGroupService.project(
            archive,
            preferredDisplayScript = WorkTitleNormalizer.DisplayScript.TRADITIONAL,
        ).single().title shouldBe traditional.title
        archive.pending shouldContainExactly listOf(traditional, simplified)
    }

    @Test
    fun `single source pending version is still a presentation group`() {
        val only = version(20L, "/only", "诡谲屋")
        val group = WorkPresentationGroupService.project(
            CreatorWorkArchive(emptyList(), listOf(only), emptyList()),
        ).single()

        group.members shouldContainExactly listOf(only)
        group.groupKey shouldBe "source:20:/only"
    }

    @Test
    fun `non equivalent confirmed title remains authoritative over source title`() {
        val canonical = canonicalGroup(100L, "confirmed", "用户确认的作品名", 10L, "/confirmed")
            .copy(versions = listOf(version(10L, "/confirmed", "不同的来源译名")))

        val group = WorkPresentationGroupService.project(
            CreatorWorkArchive(listOf(canonical), emptyList(), emptyList()),
            preferredDisplayScript = WorkTitleNormalizer.DisplayScript.SIMPLIFIED,
        ).single()

        group.title shouldBe "用户确认的作品名"
    }

    @Test
    fun `canonical alias only chooses source titles equivalent to confirmed title`() {
        val confirmed = version(20L, "/confirmed", "用户确认的作品名")
        val unrelated = version(10L, "/unrelated", "不同的来源译名")
        val canonical = canonicalGroup(100L, "confirmed", "用户确认的作品名", 20L, "/confirmed")
            .copy(versions = listOf(unrelated, confirmed))

        WorkPresentationGroupService.project(
            CreatorWorkArchive(listOf(canonical), emptyList(), emptyList()),
        ).single().title shouldBe "用户确认的作品名"
    }

    @Test
    fun `unread groups sort before seen groups by first discovery independent of bucket type`() {
        val oldCanonical = canonicalGroup(100L, "old", "Old", 10L, "/old")
        val earlier = version(20L, "/earlier", "Earlier", unread = true, unreadFirstDiscoveredAt = 100L)
        val later = version(30L, "/later", "Later", unread = true, unreadFirstDiscoveredAt = 300L)
        val archive = CreatorWorkArchive(listOf(oldCanonical), listOf(earlier, later), emptyList())

        WorkPresentationGroupService.project(archive).map { it.title } shouldContainExactly
            listOf("Later", "Earlier", "Old")
    }

    @Test
    fun `unread source groups sort by first discovery rather than source id`() {
        val earlier = version(10L, "/earlier", "Earlier", unread = true, unreadFirstDiscoveredAt = 100L)
        val later = version(20L, "/later", "Later", unread = true, unreadFirstDiscoveredAt = 300L)

        WorkPresentationGroupService.project(
            CreatorWorkArchive(emptyList(), listOf(earlier, later), emptyList()),
        ).map { it.title } shouldContainExactly listOf("Later", "Earlier")
    }

    private fun canonicalGroup(
        workId: Long,
        portableKey: String,
        title: String,
        sourceId: Long,
        url: String,
    ): CanonicalWorkArchiveGroup = CanonicalWorkArchiveGroup(
        workId = workId,
        portableKey = portableKey,
        title = title,
        versions = listOf(version(sourceId, url, title)),
    )

    private fun version(
        sourceId: Long,
        url: String,
        title: String,
        inLibrary: Boolean = false,
        unread: Boolean = false,
        unreadFirstDiscoveredAt: Long? = null,
        firstSeenAt: Long = 1L,
        publishedDateAt: Long? = null,
        publishedDateQuality: SourceDateQualityStatus = if (publishedDateAt != null) {
            SourceDateQualityStatus.TRUSTED
        } else {
            SourceDateQualityStatus.UNKNOWN
        },
        latestChapterAt: Long? = null,
        decisionState: WorkDecisionState? = null,
    ): SourceWorkArchiveVersion = SourceWorkArchiveVersion(
        sourceWorkId = sourceId,
        naturalKey = SourceWorkNaturalKey(sourceId, url),
        mangaId = sourceId,
        title = title,
        readingLanguage = LanguageProjectionContract(
            dimension = LanguageDimension.READING,
            tag = "zh-Hans",
            certainty = LanguageCertainty.CONFIRMED,
            evidenceKind = LanguageEvidenceKind.SOURCE_FILTER_OR_TAG,
        ),
        chapterCount = 12L,
        inLibrary = inLibrary,
        detailsFetchedAt = 10L,
        lastSeenAt = 20L,
        decision = decisionState?.let { state ->
            WorkDecisionProjection(
                workId = 99L,
                workPortableKey = "decision-$sourceId",
                workTitle = title,
                decision = WorkDecisionContract(state, DecisionActor.USER, explicit = true),
                score = null,
                evidence = "fixture",
                decidedAt = 30L,
            )
        },
        firstSeenAt = firstSeenAt,
        publishedDateAt = publishedDateAt,
        publishedDateQuality = publishedDateQuality,
        latestChapterAt = latestChapterAt,
        unread = unread,
        unreadFirstDiscoveredAt = unreadFirstDiscoveredAt,
    )
}
