package mihon.data.sync

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.data.sync.runtime.SyncPanelAction
import mihon.data.sync.runtime.SyncPanelController
import mihon.data.sync.runtime.SyncRuntime
import mihon.data.sync.runtime.SyncSetupStep
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Timeout(30)
abstract class SyncPanelLifecycleContract {
    protected abstract fun open(path: Path): SyncRuntimeStorageContract.Storage

    protected fun storage(
        driver: SqlDriver,
        handler: (Database, SqlDriver, CoroutineDispatcher) -> DatabaseHandler,
    ): SyncRuntimeStorageContract.Storage {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val closed = AtomicBoolean()
        val managedDriver = object : SqlDriver by driver {
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                runBlocking { withContext(dispatcher) { driver.close() } }
                dispatcher.close()
            }
        }
        val database = runBlocking { withContext(dispatcher) { database(managedDriver) } }
        return SyncRuntimeStorageContract.Storage(managedDriver, handler(database, managedDriver, dispatcher))
    }

    private fun database(driver: SqlDriver): Database {
        Database.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        return Database(
            driver,
            History.Adapter(DateColumnAdapter),
            Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
        )
    }

    @Test
    fun `stop joins real database listeners while leaving caller work alive`() = runBlocking {
        withStorage { storage, observer ->
            storage.connect("local", SyncRepository("fixture-owner", "private-sync", "mihon-sync-v1"))
            val callerJob = SupervisorJob()
            val callerScope = CoroutineScope(Dispatchers.Default + callerJob)
            val client = OkHttpClient()
            val runtime = SyncRuntime(
                storage.handler,
                storage.bootstrap,
                storage.creators,
                storage.creators,
                { true },
                MemorySyncSecureStore(),
                InMemoryPreferenceStore(),
                client,
            )
            val panel = SyncPanelController(runtime, storage.handler, callerScope)
            val releaseCaller = CompletableDeferred<Unit>()
            val callerEntered = CompletableDeferred<Unit>()
            val callerWork = callerScope.async {
                callerEntered.complete(Unit)
                releaseCaller.await()
                "caller completed"
            }
            try {
                withTimeout(5_000) {
                    observer.active.first { it == 4 }
                    callerEntered.await()
                    panel.awaitIdle()
                }

                panel.stop()

                assertEquals(
                    0,
                    observer.active.value,
                    "stop must join the active-space and three inbox query collectors",
                )
                assertTrue(callerJob.isActive)
                assertTrue(callerWork.isActive)
                assertEquals(listOf(callerWork), callerJob.children.toList())
                val emissions = observer.emissions.get()
                storage.close()
                runtime.preferences.deviceName.set("after database close")
                releaseCaller.complete(Unit)
                assertEquals("caller completed", withTimeout(5_000) { callerWork.await() })
                assertEquals(emissions, observer.emissions.get())
            } finally {
                panel.stop()
                callerJob.cancelAndJoin()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun `runtime stop joins default panel listeners without cancelling accepted exchange`() = runBlocking {
        withStorage { storage, observer ->
            SyncOnboardingFixture(storage).use { fixture ->
                fixture.existing("")
                fixture.authorize()
                fixture.begin()
                try {
                    withTimeout(5_000) {
                        fixture.panel.state.first { it.setupStep == SyncSetupStep.COMPLETE }
                        // Completed setup also observes its selected persistent run.
                        observer.active.first { it == 5 }
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    val state = fixture.panel.state.value
                    throw AssertionError(
                        "setup did not complete: step=${state.setupStep}, busy=${state.setupBusy}, " +
                            "problem=${state.setupProblem}, run=${state.run?.state}, listeners=${observer.active.value}",
                        error,
                    )
                }
                storage.favorite("/accepted-after-setup")
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val original = fixture.git.server.dispatcher
                fixture.git.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS)) { "exchange gate was not released" }
                        return original.dispatch(request)
                    }
                }
                val exchange = async(Dispatchers.Default) {
                    fixture.runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                }
                try {
                    withTimeout(5_000) { entered.await() }
                    assertTrue(fixture.runtime.coordinator.activity.value.running)

                    fixture.runtime.stopPanel()

                    assertEquals(
                        0,
                        observer.active.value,
                        "runtime getter must own and stop all real database subscriptions",
                    )
                    assertTrue(exchange.isActive)
                    assertFalse(exchange.isCancelled)
                    release.countDown()
                    val result = withTimeout(10_000) { exchange.await() }
                    assertEquals(SyncRunStatus.SUCCESS, result.status)
                    assertTrue(result.uploaded > 0)
                    val emissions = observer.emissions.get()
                    storage.close()
                    fixture.runtime.preferences.deviceName.set("after runtime database close")
                    assertEquals(0, observer.active.value)
                    assertEquals(emissions, observer.emissions.get())
                } finally {
                    release.countDown()
                    exchange.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun withStorage(
        block: suspend (SyncRuntimeStorageContract.Storage, ObservedDatabaseHandler) -> Unit,
    ) {
        val directory = Files.createTempDirectory("mihon-panel-lifecycle-")
        val path = directory.resolve("library.db")
        val original = open(path)
        val observer = ObservedDatabaseHandler(original.handler)
        val storage = SyncRuntimeStorageContract.Storage(original.driver, observer)
        try {
            block(storage, observer)
        } finally {
            // Failed red assertions must not leak their real collectors into the next test.
            observer.cancelCollectors()
            storage.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private class ObservedDatabaseHandler(private val delegate: DatabaseHandler) : DatabaseHandler by delegate {
        val active = MutableStateFlow(0)
        val emissions = AtomicInteger()
        private val collectors = ConcurrentHashMap.newKeySet<Job>()

        override fun <T : Any> subscribeToList(block: Database.() -> Query<T>) =
            observe(delegate.subscribeToList(block))

        override fun <T : Any> subscribeToOne(block: Database.() -> Query<T>) = observe(delegate.subscribeToOne(block))

        override fun <T : Any> subscribeToOneOrNull(block: Database.() -> Query<T>) =
            observe(delegate.subscribeToOneOrNull(block))

        private fun <T> observe(upstream: Flow<T>): Flow<T> = flow {
            val job = requireNotNull(currentCoroutineContext()[Job])
            collectors.add(job)
            active.update { it + 1 }
            try {
                emitAll(upstream.onEach { emissions.incrementAndGet() })
            } finally {
                active.update { it - 1 }
                collectors.remove(job)
            }
        }

        suspend fun cancelCollectors() {
            collectors.toList().forEach { it.cancelAndJoin() }
        }
    }
}
