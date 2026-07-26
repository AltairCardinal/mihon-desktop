package eu.kanade.tachiyomi.ui.updates

import android.app.Application
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.objenesis.ObjenesisStd
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class UpdatesScreenModelLibraryUpdateWiringTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(LibraryUpdateJob.Companion)
    }

    @Test
    fun `updateLibrary exposes started and already running results through its production event`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Injekt.addSingleton(mockk<Application>())
        mockkObject(LibraryUpdateJob.Companion)
        every { LibraryUpdateJob.startNow(any(), any()) } returnsMany listOf(true, false)
        val events = Channel<UpdatesScreenModel.Event>(Channel.UNLIMITED)
        val model = ObjenesisStd().newInstance(UpdatesScreenModel::class.java).also {
            UpdatesScreenModel::class.java.getDeclaredField("_events")
                .apply { isAccessible = true }
                .set(it, events)
        }

        assertEquals(true, model.updateLibrary())
        runCurrent()
        assertEquals(UpdatesScreenModel.Event.LibraryUpdateTriggered(true), events.receive())

        assertEquals(false, model.updateLibrary())
        runCurrent()
        assertEquals(UpdatesScreenModel.Event.LibraryUpdateTriggered(false), events.receive())
    }
}
