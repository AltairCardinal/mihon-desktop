package tachiyomi.presentation.widget

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.ImageProvider
import androidx.glance.unit.ColorProvider
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.updates.interactor.GetUpdates
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class WidgetPrivacyProductionWiringTest {

    @Test
    fun `base widget default constructor wires injected privacy consumer`() = runTest {
        patchInjekt()
        val getUpdates = mockk<GetUpdates> { every { subscribe(any(), any()) } returns flowOf(emptyList()) }
        val preferences = SecurityPreferences(InMemoryPreferenceStore())
        Injekt.addSingleton<Application>(mockk(relaxed = true))
        Injekt.addSingleton(getUpdates)
        Injekt.addSingleton(preferences)
        val widget = TestUpdatesWidget()
        val consumer = BaseUpdatesGridGlanceWidget::class.java.getDeclaredField("privacyConsumer").also {
            it.isAccessible =
                true
        }.get(widget) as WidgetPrivacyConsumer
        assertTrue(consumer.subscribe(0).first() is WidgetPrivacyData.Content)
        verify { getUpdates.subscribe(read = false, after = 0) }
    }

    @Test
    fun `locked production data source never queries updates`() = runTest {
        val getUpdates = mockk<GetUpdates>()
        val data = WidgetPrivacyDataSource(getUpdates, MutableStateFlow(true)).subscribe(0).first()
        assertTrue(data is WidgetPrivacyData.Locked)
        verify(exactly = 0) { getUpdates.subscribe(any(), any()) }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `base and manager consumers replace content when lock state changes`() = runTest {
        val lockState = MutableStateFlow(true)
        val getUpdates = mockk<GetUpdates> {
            every { subscribe(any(), any()) } returns flowOf(emptyList())
        }
        val consumer = WidgetPrivacyConsumer(WidgetPrivacyDataSource(getUpdates, lockState))
        val manager = WidgetManager(consumer)
        val display = async { consumer.subscribe(0).take(3).toList() }
        val refreshes = async { manager.refreshes(0).take(3).toList() }

        runCurrent()
        verify(exactly = 0) { getUpdates.subscribe(any(), any()) }
        lockState.value = false
        runCurrent()
        lockState.value = true

        assertEquals(listOf(true, false, true), display.await().map { it is WidgetPrivacyData.Locked })
        assertEquals(listOf(true, false, true), refreshes.await().map { it.locked })
        verify(exactly = 2) { getUpdates.subscribe(read = false, after = 0) }
    }
}

private class TestUpdatesWidget() : BaseUpdatesGridGlanceWidget() {
    override val foreground = ColorProvider(Color.Black)
    override val background = mockk<ImageProvider>()
    override val topPadding = 0.dp
    override val bottomPadding = 0.dp
}
