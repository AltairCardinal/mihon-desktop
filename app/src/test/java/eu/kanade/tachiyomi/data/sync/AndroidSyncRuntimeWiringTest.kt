package eu.kanade.tachiyomi.data.sync

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.DomainModule
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.crash.GlobalExceptionHandler
import eu.kanade.tachiyomi.data.library.CreatorDiscoveryJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.runtime.SyncCoordinator
import mihon.domain.sync.runtime.SyncPreferences
import mihon.domain.sync.runtime.SyncRunPort
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidSyncRuntimeWiringTest {
    private lateinit var previous: InjektScope
    private lateinit var context: Application
    private lateinit var preferences: PreferenceStore
    private lateinit var manager: WorkManager
    private val executor = Executors.newFixedThreadPool(2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setup() {
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        context = RuntimeEnvironment.getApplication()
        val shared = context.getSharedPreferences("sync-runtime-test", 0)
        shared.edit().clear().commit()
        preferences = AndroidPreferenceStore(context, shared)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(executor).build(),
        )
        manager = WorkManager.getInstance(context)
    }

    @After
    fun teardown() {
        scope.cancel()
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        executor.shutdownNow()
        Injekt = previous
    }

    @Test
    fun `real application onCreate invokes the asynchronous sync entry`() {
        mockkConstructor(AppModule::class, PreferenceModule::class, DomainModule::class)
        mockkObject(GlobalExceptionHandler.Companion, Notifications, CreatorDiscoveryJob.Companion)
        mockkStatic(Application::class)
        try {
            every {
                with(anyConstructed<AppModule>()) { any<InjektRegistrar>().registerInjectables() }
            } returns Unit
            every {
                with(anyConstructed<PreferenceModule>()) { any<InjektRegistrar>().registerInjectables() }
            } returns Unit
            every {
                with(anyConstructed<DomainModule>()) { any<InjektRegistrar>().registerInjectables() }
            } returns Unit
            every { GlobalExceptionHandler.initialize(any(), any()) } returns Unit
            every { Notifications.createChannels(any()) } returns Unit
            every { Application.getProcessName() } returns context.packageName
            val sentinel = IllegalStateException("End of isolated startup fixture")
            every { CreatorDiscoveryJob.schedule(any()) } throws sentinel
            val app = spyk(App())
            ReflectionHelpers.callInstanceMethod<Unit>(
                app,
                "attachBaseContext",
                ClassParameter.from(Context::class.java, context),
            )
            var started = false
            every { app.startSync(any()) } answers { started = true }
            val result = runCatching { app.onCreate() }
            assertSame(sentinel, result.exceptionOrNull())
            assertTrue("App.onCreate must invoke the separately verified asynchronous entry", started)
        } finally {
            unmockkStatic(Application::class)
            unmockkObject(GlobalExceptionHandler.Companion, Notifications, CreatorDiscoveryJob.Companion)
            unmockkConstructor(AppModule::class, PreferenceModule::class, DomainModule::class)
        }
    }

    @Test
    fun `DomainModule and production worker share the same unconfigured runtime`() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver)
            val database = Database(
                driver,
                History.Adapter(DateColumnAdapter),
                Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
            val networkCalls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor {
                networkCalls.incrementAndGet()
                error("Unconfigured synchronization must not use the network")
            }.build()
            val network = mockk<NetworkHelper> { every { this@mockk.client } returns client }
            Injekt.addSingleton(context)
            Injekt.addSingleton<DatabaseHandler>(AndroidDatabaseHandler(database, driver))
            Injekt.addSingleton<PreferenceStore>(preferences)
            Injekt.addSingleton(network)
            val sources = mockk<SourceManager>()
            every { sources.get(any()) } returns null
            Injekt.addSingleton(sources)
            Injekt.importModule(DomainModule())
            val runtime = Injekt.get<SyncRuntime>()
            assertSame(runtime, Injekt.get<SyncRuntime>())
            assertTrue(Injekt.get<SyncSecureStore>() is AndroidSyncSecureStore)
            val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(1L, runtime.coordinator.activity.value.completion)
            assertEquals(SyncRunStatus.SKIPPED, runtime.coordinator.activity.value.result?.status)
            assertEquals(0, networkCalls.get())
        }
    }

    @Test
    fun `period changes update one connected job and disabling cancels it`() = runBlocking {
        val runtime = runtime { SyncRunResult(SyncRunStatus.SKIPPED) }
        runtime.preferences.startup.set(false)
        AndroidSyncScheduler(context, runtime).start(scope)
        val first = awaitWork { it.state == WorkInfo.State.ENQUEUED }
        var spec = spec(first)
        assertEquals(TimeUnit.MINUTES.toMillis(60), property(spec, "intervalDuration"))
        assertEquals(
            NetworkType.CONNECTED,
            (property(spec, "constraints") as androidx.work.Constraints).requiredNetworkType,
        )
        val generation = property(spec, "generation")
        runtime.preferences.lastAttempt.set(900)
        delay(200)
        assertEquals(generation, property(spec(first), "generation"))
        runtime.preferences.setInterval(15)
        withTimeout(5000) {
            while (property(spec(first), "intervalDuration") != TimeUnit.MINUTES.toMillis(15)) delay(20)
        }
        assertEquals(first.id, manager.getWorkInfosForUniqueWork(AndroidSyncScheduler.WORK_NAME).get().single().id)
        runtime.preferences.setInterval(0)
        assertEquals(WorkInfo.State.CANCELLED, awaitWork { it.state == WorkInfo.State.CANCELLED }.state)
    }

    @Test
    fun `application startup returns before synchronization and obeys the startup option`() = runBlocking {
        val entered = CompletableDeferred<SyncTrigger>()
        val runtime = runtime {
            entered.complete(it)
            awaitCancellation()
        }
        runtime.preferences.setInterval(0)
        Injekt.addSingleton(AndroidSyncScheduler(context, runtime))
        App().startSync(scope)
        assertEquals(SyncTrigger.STARTUP, withTimeout(5000) { entered.await() })
        assertTrue(runtime.coordinator.activity.value.running)
        scope.cancel()
        val disabledScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val calls = AtomicInteger()
            val disabled = runtime {
                calls.incrementAndGet()
                SyncRunResult(SyncRunStatus.SKIPPED)
            }
            disabled.preferences.startup.set(false)
            AndroidSyncScheduler(context, disabled).start(disabledScope)
            delay(200)
            assertEquals(0, calls.get())
        } finally {
            disabledScope.cancel()
        }
    }

    @Test
    fun `cancelled startup leaves the periodic preference subscription active`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime {
            entered.complete(Unit)
            awaitCancellation()
        }
        runtime.preferences.setInterval(0)
        AndroidSyncScheduler(context, runtime).start(scope)
        withTimeout(5000) { entered.await() }
        runtime.coordinator.cancelAndJoin()
        runtime.preferences.setInterval(15)
        val info = awaitWork { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(TimeUnit.MINUTES.toMillis(15), property(spec(info), "intervalDuration"))
        runtime.preferences.setInterval(0)
        awaitWork { it.state == WorkInfo.State.CANCELLED }
        Unit
    }

    @Test
    fun `worker only retries transient network failure within a bounded budget`() = runBlocking {
        var currentProblem = SyncRunProblem.NETWORK
        runtime { SyncRunResult(SyncRunStatus.FAILED, problem = currentProblem) }
        for (problem in SyncRunProblem.entries) {
            currentProblem = problem
            val result = TestListenableWorkerBuilder<SyncWorker>(context)
                .setRunAttemptCount(0)
                .build()
                .doWork()
            assertEquals(
                if (problem ==
                    SyncRunProblem.NETWORK
                ) {
                    ListenableWorker.Result.retry()
                } else {
                    ListenableWorker.Result.failure()
                },
                result,
            )
        }
        currentProblem = SyncRunProblem.NETWORK
        val exhausted = TestListenableWorkerBuilder<SyncWorker>(context).setRunAttemptCount(3).build()
        assertEquals(ListenableWorker.Result.failure(), exhausted.doWork())
    }

    @Test
    fun `stopping the production worker cancels the coordinator exchange`() = runBlocking {
        val entered = CompletableDeferred<SyncTrigger>()
        val cancelled = CompletableDeferred<Unit>()
        val runtime = runtime {
            entered.complete(it)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        manager.enqueueUniqueWork(AndroidSyncScheduler.WORK_NAME, ExistingWorkPolicy.KEEP, request)
            .result.get(5, TimeUnit.SECONDS)
        assertEquals(SyncTrigger.PERIODIC, withTimeout(5000) { entered.await() })
        assertEquals(WorkInfo.State.RUNNING, manager.getWorkInfoById(request.id).get()!!.state)
        manager.cancelUniqueWork(AndroidSyncScheduler.WORK_NAME).result.get(5, TimeUnit.SECONDS)
        withTimeout(5000) { cancelled.await() }
        withTimeout(5000) { while (runtime.coordinator.activity.value.running) delay(10) }
        assertFalse(runtime.coordinator.activity.value.running)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(request.id).get()!!.state)
    }

    private fun runtime(port: SyncRunPort): SyncRuntime {
        val coordinator = SyncCoordinator(port)
        val prefs = SyncPreferences(preferences)
        return mockk<SyncRuntime> {
            every { this@mockk.coordinator } returns coordinator
            every { this@mockk.preferences } returns prefs
        }.also { Injekt.addSingleton(it) }
    }

    private suspend fun awaitWork(predicate: (WorkInfo) -> Boolean): WorkInfo = withTimeout(5000) {
        var result: WorkInfo? = null
        while (result == null) {
            result = manager.getWorkInfosForUniqueWork(AndroidSyncScheduler.WORK_NAME).get().firstOrNull(predicate)
            if (result == null) delay(20)
        }
        result
    }

    private fun spec(info: WorkInfo): Any {
        val database = manager.javaClass.getMethod("getWorkDatabase").invoke(manager)
        val dao = database.javaClass.methods.single { it.name == "workSpecDao" }.invoke(database)
        return dao.javaClass.methods.single { it.name == "getWorkSpec" && it.parameterCount == 1 }
            .invoke(dao, info.id.toString())!!
    }

    private fun property(value: Any, name: String): Any {
        val accessor = "get${name.replaceFirstChar(Char::uppercaseChar)}"
        return value.javaClass.methods.firstOrNull { it.name == accessor && it.parameterCount == 0 }?.invoke(value)
            ?: value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    }
}
