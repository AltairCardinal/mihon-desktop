package tachiyomi.presentation.widget

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.updates.interactor.GetUpdates

class WidgetPrivacyProductionWiringTest {

    @Test
    fun `locked production data source never queries updates`() = runTest {
        val getUpdates = mockk<GetUpdates>()
        val data = WidgetPrivacyDataSource(getUpdates, MutableStateFlow(true)).subscribe(0).first()
        assertTrue(data is WidgetPrivacyData.Locked)
        verify(exactly = 0) { getUpdates.subscribe(any(), any()) }
    }

    @Test
    fun `unlocking production data source queries updates`() = runTest {
        val getUpdates = mockk<GetUpdates> {
            every { subscribe(any(), any()) } returns flowOf(emptyList())
        }
        val dataSource = WidgetPrivacyDataSource(getUpdates, MutableStateFlow(false))
        assertTrue(dataSource.subscribe(0).first() is WidgetPrivacyData.Content)
        verify(exactly = 1) { getUpdates.subscribe(read = false, after = 0) }
    }

    @Test
    fun `widget refresh identity includes lock state`() {
        val lockedIdentity = WidgetPrivacyData.Locked.refreshIdentity()
        val unlockedIdentity = WidgetPrivacyData.Content(emptyList()).refreshIdentity()
        assertNotEquals(lockedIdentity, unlockedIdentity)
        assertTrue(lockedIdentity.locked)
        assertFalse(unlockedIdentity.locked)
    }
}
