package eu.kanade.presentation.more.settings.screen.about

import com.mikepenz.aboutlibraries.entity.License
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import mihon.domain.license.service.LicenseNoticePolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenSourceLicensesConsumerBehaviorTest {

    @AfterEach
    fun tearDown() = unmockkObject(LicenseNoticePolicy)

    @Test
    fun `real Android license candidates are delegated to shared policy in order`() {
        mockkObject(LicenseNoticePolicy)
        every { LicenseNoticePolicy.selectLicense(any()) } answers { callOriginal() }
        val candidates = listOf(license("First license"), license("Second license"))

        assertEquals("First license", selectAndroidLicenseContent(candidates))
        verify(exactly = 1) {
            LicenseNoticePolicy.selectLicense(listOf("First license", "Second license"))
        }
    }

    @Test
    fun `blank first Android license does not fall through to later content`() {
        mockkObject(LicenseNoticePolicy)
        every { LicenseNoticePolicy.selectLicense(any()) } answers { callOriginal() }
        val candidates = listOf(license("  "), license("Second license"))

        assertNull(selectAndroidLicenseContent(candidates))
        verify(exactly = 1) {
            LicenseNoticePolicy.selectLicense(listOf("  ", "Second license"))
        }
    }

    private fun license(content: String) =
        License(
            name = "License",
            url = null,
            year = null,
            spdxId = null,
            licenseContent = content,
            hash = "",
        )
}
