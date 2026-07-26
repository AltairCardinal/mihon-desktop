package eu.kanade.tachiyomi.data.library

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.task.BackgroundTaskLifecycle
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskLifecycleDecision
import mihon.domain.task.TaskLifecycleEvent
import mihon.domain.task.TaskLifecycleOutcome
import mihon.domain.task.TaskLifecycleRejection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LibraryUpdateJobSharedLifecycleIntegrationTest {
    private lateinit var context: Application
    private lateinit var workerExecutor: ExecutorService
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        workerExecutor = Executors.newFixedThreadPool(2)
        BlockingWorkerGate.reset()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(workerExecutor)
                .setWorkerFactory(LibraryUpdateTestWorkerFactory())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        Injekt.addSingleton(libraryPreferences())
    }

    @After
    fun tearDown() {
        BlockingWorkerGate.release.countDown()
        runCatching { workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS) }
        WorkManagerTestInitHelper.closeWorkDatabase()
        workerExecutor.shutdownNow()
        unmockkObject(BackgroundTaskLifecycle)
        unmockkStatic("eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt")
    }

    @Test
    fun `setupTask executes shared registration and preserves original periodic request`() {
        val events = recordSharedEvents()

        LibraryUpdateJob.setupTask(context, prefInterval = 12)

        val info = workManager.getWorkInfosForUniqueWork(WORK_NAME_AUTO).get().single()
        val spec = workSpec(info)
        assertTrue(events.single() is TaskLifecycleEvent.Register)
        val registeredTask = (events.single() as TaskLifecycleEvent.Register).task
        assertEquals(
            setOf(TaskConstraint.UnmeteredNetwork, TaskConstraint.Charging),
            registeredTask.constraints,
        )
        assertTrue(WORK_NAME_AUTO in info.tags)
        assertTrue(TAG in info.tags)
        assertEquals(TimeUnit.HOURS.toMillis(12), spec.longProperty("intervalDuration"))
        assertEquals(TimeUnit.MINUTES.toMillis(10), spec.longProperty("flexDuration"))
        val constraints = spec.property("constraints")
        val requiredNetworkType = constraints.property("requiredNetworkType")
        assertTrue(requiredNetworkType in setOf(NetworkType.UNMETERED, NetworkType.NOT_REQUIRED))
        if (requiredNetworkType == NetworkType.NOT_REQUIRED) {
            assertTrue(constraints.propertyOrNull("requiredNetworkRequest") != null)
        }
        assertTrue(constraints.booleanProperty("requiresCharging"))
        assertTrue(constraints.booleanProperty("requiresBatteryNotLow"))
        assertEquals(BackoffPolicy.LINEAR, spec.property("backoffPolicy"))
        assertEquals(TimeUnit.MINUTES.toMillis(10), spec.longProperty("backoffDelayDuration"))
    }

    @Test
    fun `rejected shared registration prevents periodic enqueue`() {
        rejectSharedEvent<TaskLifecycleEvent.Register>()

        LibraryUpdateJob.setupTask(context, prefInterval = 12)

        assertTrue(workManager.getWorkInfosForUniqueWork(WORK_NAME_AUTO).get().isEmpty())
    }

    @Test
    fun `startNow uses shared register and start decisions to control enqueue and return value`() {
        val events = recordSharedEvents()

        assertTrue(LibraryUpdateJob.startNow(context))

        assertTrue(events.any { it is TaskLifecycleEvent.Register })
        assertTrue(events.any { it is TaskLifecycleEvent.Start })
        assertEquals(1, workManager.getWorkInfosForUniqueWork(WORK_NAME_MANUAL).get().size)

        workManager.cancelUniqueWork(WORK_NAME_MANUAL).result.get()
        events.clear()
        rejectSharedEvent<TaskLifecycleEvent.Start>()

        assertFalse(LibraryUpdateJob.startNow(context))
        assertTrue(workManager.getWorkInfosForUniqueWork(WORK_NAME_MANUAL).get().all { it.state.isFinished })
    }

    @Test
    fun `rejected shared manual registration prevents enqueue`() {
        rejectSharedEvent<TaskLifecycleEvent.Register>()

        assertFalse(LibraryUpdateJob.startNow(context))
        assertTrue(workManager.getWorkInfosForUniqueWork(WORK_NAME_MANUAL).get().isEmpty())
    }

    @Test
    fun `manual unique work keeps the first queued occurrence`() {
        val events = recordSharedEvents()
        assertTrue(LibraryUpdateJob.startNow(context))
        val first = workManager.getWorkInfosForUniqueWork(WORK_NAME_MANUAL).get().single().id

        assertTrue(LibraryUpdateJob.startNow(context))
        val second = workManager.getWorkInfosForUniqueWork(WORK_NAME_MANUAL).get()
            .single { !it.state.isFinished }
            .id

        assertEquals(first, second)
        assertEquals(
            setOf(WORK_NAME_MANUAL),
            events.filterIsInstance<TaskLifecycleEvent.Register>().map { it.task.idempotencyKey }.toSet(),
        )
    }

    @Test
    fun `running occurrence wins over pending work and prevents another manual start`() {
        val running = enqueueBlockingWork(tags = setOf(TAG))
        val pending = OneTimeWorkRequestBuilder<RetryWorker>()
            .addTag(TAG)
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        workManager.enqueue(pending).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfoById(pending.id).get()!!.state)

        assertFalse(LibraryUpdateJob.startNow(context))
        assertEquals(WorkInfo.State.RUNNING, workManager.getWorkInfoById(running.id).get()!!.state)
        assertTrue(workManager.getWorkInfosForUniqueWork(WORK_NAME_MANUAL).get().isEmpty())
    }

    @Test
    fun `rejected shared completion changes the real worker result`() = runBlocking {
        registerWorkerDependencies()
        mockkStatic("eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt")
        coEvery { any<CoroutineWorker>().setForegroundSafely() } returns Unit
        rejectSharedEvent<TaskLifecycleEvent.Complete>()
        val worker = TestListenableWorkerBuilder<LibraryUpdateJob>(context)
            .setTags(listOf(WORK_NAME_MANUAL))
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure()::class, result::class)
        assertNotEquals(ListenableWorker.Result.success()::class, result::class)
    }

    @Test
    fun `rejected shared cancel leaves running work untouched`() {
        val running = enqueueBlockingWork(tags = setOf(TAG))
        rejectSharedEvent<TaskLifecycleEvent.Cancel>()

        LibraryUpdateJob.stop(context)

        assertEquals(WorkInfo.State.RUNNING, workManager.getWorkInfoById(running.id).get()!!.state)
    }

    @Test
    fun `stop applies shared cancel and restores cancelled automatic work`() {
        val events = recordSharedEvents()
        val running = enqueueBlockingWork(tags = setOf(TAG, WORK_NAME_AUTO), uniqueName = WORK_NAME_AUTO)

        LibraryUpdateJob.stop(context)

        assertTrue(events.any { it is TaskLifecycleEvent.Cancel })
        val automatic = awaitAutomaticRestored(running)
        assertTrue(workManager.getWorkInfoById(running.id).get()?.state != WorkInfo.State.RUNNING)
        assertTrue(automatic.any { !it.state.isFinished && WORK_NAME_AUTO in it.tags })
    }

    private fun enqueueBlockingWork(
        tags: Set<String>,
        uniqueName: String? = null,
    ): WorkInfo {
        val request: WorkRequest = if (uniqueName == null) {
            OneTimeWorkRequestBuilder<BlockingWorker>()
                .apply { tags.forEach(::addTag) }
                .build()
        } else {
            PeriodicWorkRequestBuilder<BlockingWorker>(15, TimeUnit.MINUTES)
                .apply { tags.forEach(::addTag) }
                .build()
        }
        if (uniqueName == null) {
            workManager.enqueue(request).result.get()
        } else {
            workManager.enqueueUniquePeriodicWork(
                uniqueName,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request as androidx.work.PeriodicWorkRequest,
            ).result.get()
        }
        assertTrue(BlockingWorkerGate.entered.await(10, TimeUnit.SECONDS))
        return workManager.getWorkInfoById(request.id).get()!!.also {
            assertEquals(WorkInfo.State.RUNNING, it.state)
        }
    }

    private fun awaitAutomaticRestored(running: WorkInfo): List<WorkInfo> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            val automatic = workManager.getWorkInfosForUniqueWork(WORK_NAME_AUTO).get()
            if (
                workManager.getWorkInfoById(running.id).get()?.state != WorkInfo.State.RUNNING &&
                automatic.any { !it.state.isFinished && WORK_NAME_AUTO in it.tags }
            ) {
                return automatic
            }
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        return workManager.getWorkInfosForUniqueWork(WORK_NAME_AUTO).get()
    }

    private fun recordSharedEvents(): MutableList<TaskLifecycleEvent> {
        val events = mutableListOf<TaskLifecycleEvent>()
        mockkObject(BackgroundTaskLifecycle)
        every { BackgroundTaskLifecycle.reduce(any(), any()) } answers {
            events += secondArg<TaskLifecycleEvent>()
            callOriginal()
        }
        return events
    }

    private inline fun <reified E : TaskLifecycleEvent> rejectSharedEvent() {
        mockkObject(BackgroundTaskLifecycle)
        every { BackgroundTaskLifecycle.reduce(any(), any()) } answers {
            if (secondArg<TaskLifecycleEvent>() is E) {
                TaskLifecycleDecision(
                    TaskLifecycleOutcome.Rejected,
                    firstArg(),
                    TaskLifecycleRejection.InvalidTransition,
                )
            } else {
                callOriginal()
            }
        }
    }

    private fun workSpec(info: WorkInfo): Any {
        val database = workManager.javaClass.getMethod("getWorkDatabase").invoke(workManager)
        val dao = database.javaClass.methods.single { it.name == "workSpecDao" }.invoke(database)
        return dao.javaClass.methods
            .single { it.name == "getWorkSpec" && it.parameterCount == 1 }
            .invoke(dao, info.id.toString())!!
    }

    private fun Any.property(name: String): Any {
        val accessor = "get${name.replaceFirstChar(Char::uppercaseChar)}"
        javaClass.methods.firstOrNull { it.name == accessor && it.parameterCount == 0 }
            ?.let { return it.invoke(this)!! }
        return javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this)!!
    }

    private fun Any.propertyOrNull(name: String): Any? = runCatching { property(name) }.getOrNull()

    private fun Any.longProperty(name: String) = property(name) as Long

    private fun Any.booleanProperty(name: String): Boolean {
        val capitalized = name.replaceFirstChar(Char::uppercaseChar)
        val method = javaClass.methods.single {
            it.parameterCount == 0 && it.name in setOf(name, "is$capitalized", "get$capitalized")
        }
        return method.invoke(this) as Boolean
    }

    private fun libraryPreferences(): LibraryPreferences {
        val interval = mockk<Preference<Int>> {
            every { get() } returns 12
        }
        val restrictions = mockk<Preference<Set<String>>> {
            every { get() } returns setOf(
                DEVICE_ONLY_ON_WIFI,
                DEVICE_NETWORK_NOT_METERED,
                DEVICE_CHARGING,
            )
        }
        return mockk {
            every { autoUpdateInterval() } returns interval
            every { autoUpdateDeviceRestrictions() } returns restrictions
            every { lastUpdatedTimestamp() } returns mockk(relaxed = true)
            every { updateCategories() } returns stringSetPreference()
            every { updateCategoriesExclude() } returns stringSetPreference()
            every { autoUpdateMangaRestrictions() } returns stringSetPreference()
        }
    }

    private fun registerWorkerDependencies() {
        Injekt.addSingleton<SourceManager>(EmptySourceManager())
        Injekt.addSingleton(mockk<SecurityPreferences>(relaxed = true))
        Injekt.addSingleton(mockk<DownloadManager>(relaxed = true))
        Injekt.addSingleton(mockk<CoverCache>(relaxed = true))
        Injekt.addSingleton(
            mockk<GetLibraryManga> {
                coEvery { await() } returns emptyList()
            },
        )
        Injekt.addSingleton(mockk<GetManga>(relaxed = true))
        Injekt.addSingleton(mockk<UpdateManga>(relaxed = true))
        Injekt.addSingleton(mockk<SyncChaptersWithSource>(relaxed = true))
        Injekt.addSingleton(
            mockk<FetchInterval> {
                every { getWindow(any()) } returns (0L to Long.MAX_VALUE)
            },
        )
        Injekt.addSingleton(mockk<FilterChaptersForDownload>(relaxed = true))
        Injekt.addSingleton(
            mockk<CreatorDiscoveryService> {
                coEvery { discoverDueWatches(any()) } returns mockk(relaxed = true)
            },
        )
    }

    private fun stringSetPreference(): Preference<Set<String>> = mockk {
        every { get() } returns emptySet()
    }

    private companion object {
        const val TAG = "LibraryUpdate"
        const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        const val WORK_NAME_MANUAL = "LibraryUpdate-manual"
    }
}

private class EmptySourceManager : SourceManager {
    override val isInitialized = MutableStateFlow(true)
    override val catalogueSources = flowOf(emptyList<CatalogueSource>())

    override fun get(sourceKey: Long): Source? = null

    override fun getOrStub(sourceKey: Long): Source = error("No sources in worker fixture")

    override fun getOnlineSources(): List<HttpSource> = emptyList()

    override fun getCatalogueSources(): List<CatalogueSource> = emptyList()

    override fun getStubSources(): List<StubSource> = emptyList()
}

private class LibraryUpdateTestWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        LibraryUpdateJob::class.java.name -> RetryWorker(appContext, workerParameters)
        BlockingWorker::class.java.name -> BlockingWorker(appContext, workerParameters)
        else -> null
    }
}

private class RetryWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result = Result.retry()
}

private class BlockingWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        BlockingWorkerGate.entered.countDown()
        BlockingWorkerGate.release.await(30, TimeUnit.SECONDS)
        return Result.success()
    }
}

private object BlockingWorkerGate {
    lateinit var entered: CountDownLatch
    lateinit var release: CountDownLatch

    fun reset() {
        entered = CountDownLatch(1)
        release = CountDownLatch(1)
    }
}
