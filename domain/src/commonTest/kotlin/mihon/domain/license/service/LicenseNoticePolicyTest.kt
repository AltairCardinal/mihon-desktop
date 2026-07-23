package mihon.domain.license.service

import mihon.domain.license.model.DependencyNotice
import mihon.domain.license.model.DependencyNoticeMetadata
import mihon.domain.license.model.LicenseNoticeFailureReason
import mihon.domain.license.model.LicenseNoticeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LicenseNoticePolicyTest {

    @Test
    fun `dependencies are sorted deterministically by display name`() {
        val metadata = listOf(
            metadata(name = "zeta"),
            metadata(name = "beta"),
            metadata(name = "Alpha"),
            metadata(name = "Beta"),
        )

        val forward = LicenseNoticePolicy.create(Result.success(metadata)).success()
        val reversed = LicenseNoticePolicy.create(Result.success(metadata.reversed())).success()

        assertEquals(listOf("Alpha", "Beta", "beta", "zeta"), forward.map { it.name })
        assertEquals(forward, reversed)
    }

    @Test
    fun `first license is selected without later entries overwriting it`() {
        val first = "  <p>first license</p>  "

        val notice = LicenseNoticePolicy.create(
            Result.success(
                listOf(
                    metadata(
                        name = "library",
                        licenses = listOf(first, "second license"),
                    ),
                ),
            ),
        ).success().single()

        assertEquals(first, notice.license)
    }

    @Test
    fun `missing license remains absent instead of fabricating content`() {
        val notice = LicenseNoticePolicy.create(
            Result.success(listOf(metadata(name = "library", licenses = emptyList()))),
        ).success().single()

        assertNull(notice.license)
    }

    @Test
    fun `blank first license is normalized to absent and does not select a later license`() {
        val notice = LicenseNoticePolicy.create(
            Result.success(
                listOf(
                    metadata(
                        name = "library",
                        licenses = listOf("", "later license"),
                    ),
                ),
            ),
        ).success().single()

        assertNull(notice.license)
    }

    @Test
    fun `empty and blank websites do not produce an action target`() {
        val notices = LicenseNoticePolicy.create(
            Result.success(
                listOf(
                    metadata(name = "empty", website = ""),
                    metadata(name = "spaces", website = "   "),
                    metadata(name = "tab", website = "\t"),
                ),
            ),
        ).success()

        notices.forEach { assertNull(it.website) }
    }

    @Test
    fun `nonblank website keeps its original value`() {
        val website = " https://example.test/project "
        val notice = LicenseNoticePolicy.create(
            Result.success(listOf(metadata(name = "library", website = website))),
        ).success().single()

        assertEquals(website, notice.website)
    }

    @Test
    fun `empty metadata is a successful empty list`() {
        assertEquals(
            emptyList<DependencyNotice>(),
            LicenseNoticePolicy.create(Result.success(emptyList())).success(),
        )
    }

    @Test
    fun `malformed metadata is an explicit failure instead of an empty list`() {
        val result = LicenseNoticePolicy.create(
            Result.failure(IllegalArgumentException("malformed metadata")),
        )

        assertEquals(
            LicenseNoticeFailureReason.MALFORMED_METADATA,
            assertInstanceOf(LicenseNoticeResult.Failure::class.java, result).reason,
        )
    }

    @Test
    fun `blank dependency name is malformed metadata`() {
        val result = LicenseNoticePolicy.create(
            Result.success(listOf(metadata(name = " "))),
        )

        assertEquals(
            LicenseNoticeFailureReason.MALFORMED_METADATA,
            assertInstanceOf(LicenseNoticeResult.Failure::class.java, result).reason,
        )
    }

    private fun metadata(
        name: String,
        website: String? = null,
        licenses: List<String> = emptyList(),
    ) = DependencyNoticeMetadata(
        name = name,
        website = website,
        licenses = licenses,
    )

    private fun LicenseNoticeResult.success() =
        assertInstanceOf(LicenseNoticeResult.Success::class.java, this).notices
}
