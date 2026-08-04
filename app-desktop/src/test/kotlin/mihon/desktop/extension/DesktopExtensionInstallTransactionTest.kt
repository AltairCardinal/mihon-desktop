package mihon.desktop.extension

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionSourceDescriptor
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionInstallFailure
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class DesktopExtensionInstallTransactionTest {
    @Test
    fun `native JAR install wires authenticated Page ABI adaptation before runtime load`(
        @TempDir directory: Path,
    ) = runBlocking {
        val bytes = sourceJarWithPageClient(FixtureNewSource::class.java)

        val result = install(bytes, directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, result)
        val installed = directory.resolve("$PACKAGE.jar").toFile()
        java.util.jar.JarFile(installed).use { jar ->
            val descriptor = jar.getInputStream(jar.getJarEntry("PageClient.class")).use(::pageConstructorDescriptor)
            assertEquals(PAGE_OBJECT_DEFAULT_DESCRIPTOR, descriptor)
        }
    }

    @Test
    fun `same package concurrent installs share one application transaction`(@TempDir directory: Path) = runBlocking {
        val bytes = sourceJar(FixtureNewSource::class.java)
        MockWebServer().use { server ->
            server.start()
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder()
                        .headersDelay(250, TimeUnit.MILLISECONDS)
                        .body(Buffer().write(bytes))
                        .build(),
                )
            }
            val api = api()
            val manager = manager(api, directory.toFile())
            val extension = available(server, FixtureNewSource.ID, null)
            try {
                val results = withTimeout(5_000) {
                    coroutineScope {
                        listOf(
                            async { api.installExtension(extension, manager) },
                            async { api.installExtension(extension, manager) },
                        ).map { it.await() }
                    }
                }

                assertEquals(1, server.requestCount)
                assertTrue(results.all { it is DesktopExtensionApi.InstallResult.Success })
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun `different package completions cannot overwrite a newer installed snapshot`(
        @TempDir directory: Path,
    ) = runBlocking {
        val firstPackage = "mihon.desktop"
        val secondPackage = PACKAGE
        val expectedPackages = setOf(firstPackage, secondPackage)
        val bytes = sourceJar(FixtureNewSource::class.java)
        val loader = SnapshotRaceLoader(directory.toFile(), firstPackage)
        val manager = transactionManager(
            loader = loader,
            artifactProvider = { _, destination -> destination.writeBytes(bytes) },
        )
        val firstInstall = async(Dispatchers.IO) {
            manager.installExtension(artifact(FixtureNewSource.ID).copy(packageName = firstPackage))
        }

        try {
            loader.staleSnapshotEntered.awaitLatch()
            val secondInstall = async(Dispatchers.IO) {
                manager.installExtension(artifact(FixtureNewSource.ID).copy(packageName = secondPackage))
            }

            assertInstanceOf(ExtensionInstallState.Installed::class.java, withTimeout(5_000) { secondInstall.await() })
            assertEquals(expectedPackages, manager.installedExtensions.value.map { it.pkgName }.toSet())

            loader.releaseStaleSnapshot()
            assertInstanceOf(ExtensionInstallState.Installed::class.java, withTimeout(5_000) { firstInstall.await() })
            assertEquals(expectedPackages, manager.installedExtensions.value.map { it.pkgName }.toSet())
        } finally {
            loader.releaseStaleSnapshot()
            runCatching { withTimeout(5_000) { firstInstall.await() } }
            manager.close()
        }
    }

    @Test
    fun `invalid package path cannot create artifacts outside extension directory`(@TempDir directory: Path) = runBlocking {
        val extensions = directory.resolve("extensions").toFile().also(File::mkdirs)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("broken").build())

            val api = api()
            val manager = manager(api, extensions)
            val result = try {
                api.installExtension(available(server, null, null, "../escape"), manager)
            } finally {
                manager.close()
            }

            assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
            assertEquals(listOf("extensions"), directory.toFile().listFiles().orEmpty().map { it.name }.sorted())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `jar with classes but no Source provider is rejected`(@TempDir directory: Path) = runBlocking {
        val bytes = classOnlyJar(DesktopExtensionInstallTransactionTest::class.java)

        val result = install(bytes, directory)

        val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        assertInstanceOf(mihon.domain.error.AppError.MalformedData::class.java, error.error)
        assertFalse(directory.resolve("$PACKAGE.jar").toFile().exists())
    }

    @Test
    fun `provider outside declared package is rejected after real runtime load`(@TempDir directory: Path) = runBlocking {
        val declaredPackage = "$PACKAGE.expected"
        val bytes = zip(
            declaredPackage.replace('.', '/') + "/Marker.class" to byteArrayOf(0),
            SERVICE to FixtureNewSource::class.java.name.toByteArray(),
        )

        val result = install(bytes, directory, FixtureNewSource.ID, packageName = declaredPackage)

        val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        assertInstanceOf(mihon.domain.error.AppError.MalformedData::class.java, error.error)
        assertFalse(directory.resolve("$declaredPackage.jar").toFile().exists())
    }

    @Test
    fun `http server failure keeps typed AppError`(@TempDir directory: Path) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).build())

            val api = api()
            val manager = manager(api, directory.toFile())
            val result = try {
                api.installExtension(available(server, FixtureNewSource.ID, null), manager)
            } finally {
                manager.close()
            }

            val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
            val serverError = assertInstanceOf(mihon.domain.error.AppError.Server::class.java, error.error)
            assertEquals(503, serverError.statusCode)
        }
    }

    @Test
    fun `all traversal absolute and drive package forms are rejected before download`(@TempDir directory: Path) = runBlocking {
        val invalid = listOf("../escape", "..\\escape", "/escape", "\\escape", "C:\\escape", "C:/escape")
        MockWebServer().use { server ->
            server.start()
            repeat(invalid.size) { server.enqueue(MockResponse.Builder().body("unused").build()) }
            val api = api()
            val manager = manager(api, directory.toFile())
            try {
                invalid.forEach { packageName ->
                    val result = api.installExtension(available(server, null, null, packageName), manager)
                    val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
                    assertInstanceOf(AppError.MalformedData::class.java, error.error)
                }
                assertEquals(0, server.requestCount)
                assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun `candidate provider load failure closes loader and leaves full directory snapshot unchanged`(@TempDir directory: Path) = runBlocking {
        val marker = directory.resolve("keep/nested.txt").toFile().also { it.parentFile.mkdirs(); it.writeText("keep") }
        val before = directorySnapshot(directory)
        val brokenProvider = zip(
            PACKAGE.replace('.', '/') + "/Marker.class" to byteArrayOf(0),
            SERVICE to "$PACKAGE.DoesNotExist".toByteArray(),
        )

        val result = install(brokenProvider, directory)

        val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        assertInstanceOf(AppError.MalformedData::class.java, error.error)
        assertEquals(before, directorySnapshot(directory))
        assertTrue(marker.delete())
    }

    @Test
    fun `metadata commit failure restores both snapshots and next transaction succeeds`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val fileSystem = FailOnceFileSystem(
            replaceFailure = { destination, occurrence -> destination.name == "$PACKAGE.meta.json" && occurrence == 1 },
        )
        MockWebServer().use { server ->
            server.start()
            val bytes = sourceJar(FixtureNewSource::class.java)
            repeat(2) { server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build()) }
            val api = api()
            val manager = manager(api, directory.toFile(), fileSystem = fileSystem)
            try {
                val first = api.installExtension(available(server, FixtureNewSource.ID, null), manager)
                val firstError = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, first)
                assertInstanceOf(AppError.Storage::class.java, firstError.error)
                snapshot.assertUnchanged()
                assertNotNull(manager.getSource(FixtureOldSource.ID))

                val second = api.installExtension(available(server, FixtureNewSource.ID, null), manager)
                assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, second)
                assertNotNull(manager.getSource(FixtureNewSource.ID))
                assertNull(manager.getSource(FixtureOldSource.ID))
                assertNoTransactionFiles(directory)
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun `fresh install rollback delete failure is surfaced as Storage`(@TempDir directory: Path) = runBlocking {
        val api = api()
        val loader = FailCountLoader(directory.toFile())
        val fileSystem = FailOnceFileSystem(
            deleteFailure = { destination, occurrence -> destination.name == "$PACKAGE.jar" && occurrence == 1 },
        )
        val manager = manager(api, directory.toFile(), loader, fileSystem)
        loader.failReloads = 1
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(sourceJar(FixtureNewSource::class.java))).build())

            val result = try {
                api.installExtension(available(server, FixtureNewSource.ID, null), manager)
            } finally {
                manager.close()
            }

            val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
            assertInstanceOf(AppError.Storage::class.java, error.error)
            assertTrue(directory.resolve("$PACKAGE.jar").toFile().isFile)
            assertNoTransactionFiles(directory)
        }
    }

    @Test
    fun `cancelled and rollback restore failures keep exact error taxonomy`(@TempDir directory: Path) = runBlocking {
        val api = api()
        val cancelledManager = transactionManager(
            loader = DesktopExtensionLoader(directory.resolve("cancelled").toFile()),
            artifactProvider = { _, _ -> throw ExtensionInstallFailure(AppError.Cancelled) },
        )
        try {
            val cancelled = api.installExtension(availableWithoutServer("cancelled.extension"), cancelledManager)
            val cancelledError = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, cancelled)
            assertEquals(AppError.Cancelled, cancelledError.error)
        } finally {
            cancelledManager.close()
        }

        val partialDirectory = directory.resolve("partial")
        val snapshot = installedSnapshot(partialDirectory)
        val loader = FailCountLoader(partialDirectory.toFile())
        val partialManager = transactionManager(
            loader = loader,
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
        ).also { it.loadAll() }
        loader.failReloads = 2
        try {
            val extension = availableWithoutServer(PACKAGE).copy(
                sources = listOf(DesktopAvailableSource(FixtureNewSource.ID, "en", "Fixture", "https://example.com")),
            )
            val result = api.installExtension(extension, partialManager)
            val failed = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
            val partial = assertInstanceOf(AppError.PartialFailure::class.java, failed.error)
            assertEquals(2, partial.failures.size)
            assertTrue(failed.message.startsWith("Extension installation partially failed:"))
            assertTrue(failed.message.contains("injected runtime reload failure"))
            snapshot.assertUnchanged()
        } finally {
            partialManager.close()
        }
    }

    @Test
    fun `cleanup failure after candidate reload releases new runtime before restoring old snapshot`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val fileSystem = CleanupFailureFileSystem()
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
            fileSystem = fileSystem,
        ).also { it.loadAll() }
        fileSystem.manager = manager

        try {
            val terminal = manager.installExtension(artifact(FixtureNewSource.ID))
            val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            assertInstanceOf(AppError.Storage::class.java, failure.error)
            snapshot.assertUnchanged()
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertNull(manager.getSource(FixtureNewSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `public reload waits for full install lifecycle window`(@TempDir directory: Path) = runBlocking {
        assertPublicOperationWaits(directory.resolve("reload")) { manager, _ -> manager.reloadAll() }
    }

    @Test
    fun `public remove waits for full install lifecycle window`(@TempDir directory: Path) = runBlocking {
        assertPublicOperationWaits(directory.resolve("remove")) { manager, extension ->
            manager.removeExtensionWithMeta(extension)
        }
    }

    @Test
    fun `public close waits for full install lifecycle window`(@TempDir directory: Path) = runBlocking {
        assertPublicOperationWaits(directory.resolve("close")) { manager, _ -> manager.close() }
    }

    @Test
    fun `remove waits from installed snapshot through failed install rollback`(@TempDir directory: Path) = runBlocking {
        installedSnapshot(directory)
        val loader = FailOnceLoader(directory.toFile())
        val manager = transactionManager(
            loader = loader,
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(BlockingValidationSource::class.java)) },
        ).also { it.loadAll() }
        val installed = manager.getInstalledExtensions().single()
        BlockingValidationSource.blockNextValidation()
        loader.failNextReload = true
        val install = async(Dispatchers.IO) { manager.installExtension(artifact(BlockingValidationSource.ID)) }
        BlockingValidationSource.validationEntered.awaitLatch()
        val remove = async(Dispatchers.IO) { manager.removeExtensionWithMeta(installed) }

        try {
            delay(200)
            val removeCrossedSnapshotWindow = remove.isCompleted
            BlockingValidationSource.allowValidation.countDown()
            val terminal = withTimeout(2_000) { install.await() }
            val removed = withTimeout(2_000) { remove.await() }

            assertFalse(removeCrossedSnapshotWindow, "remove crossed the installed snapshot rollback window")
            assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            assertTrue(removed)
            assertFalse(directory.resolve("$PACKAGE.jar").toFile().exists())
            assertFalse(directory.resolve("$PACKAGE.meta.json").toFile().exists())
            assertNull(manager.getSource(FixtureOldSource.ID))
            assertNull(manager.getSource(BlockingValidationSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            BlockingValidationSource.allowValidation.countDown()
            manager.close()
        }
    }

    @Test
    fun `validation failure after install window releases public lifecycle`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> destination.writeBytes(classOnlyJar(DesktopExtensionInstallTransactionTest::class.java)) },
        ).also { it.loadAll() }

        try {
            val terminal = manager.installExtension(artifact(FixtureNewSource.ID))
            assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            withTimeout(2_000) { withContext(Dispatchers.IO) { manager.reloadAll() } }
            snapshot.assertUnchanged()
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `install after close is Cancelled without artifacts`(@TempDir directory: Path) = runBlocking {
        val downloads = AtomicInteger()
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination ->
                downloads.incrementAndGet()
                destination.writeBytes(sourceJar(FixtureNewSource::class.java))
            },
        )
        manager.close()

        val terminal = withTimeout(2_000) { manager.installExtension(artifact(FixtureNewSource.ID)) }

        val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
        assertEquals(AppError.Cancelled, failure.error)
        assertEquals(0, downloads.get())
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `load after close is rejected without reviving runtime`(@TempDir directory: Path) {
        val snapshot = installedSnapshot(directory)
        val manager = DesktopExtensionManager(loader = DesktopExtensionLoader(directory.toFile())).also { it.loadAll() }
        manager.close()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { manager.loadAll() }

        assertNull(manager.getSource(FixtureOldSource.ID))
        snapshot.assertUnchanged()
    }

    @Test
    fun `reload after close is rejected without reviving runtime`(@TempDir directory: Path) {
        val snapshot = installedSnapshot(directory)
        val manager = DesktopExtensionManager(loader = DesktopExtensionLoader(directory.toFile())).also { it.loadAll() }
        manager.close()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { manager.reloadAll() }

        assertNull(manager.getSource(FixtureOldSource.ID))
        snapshot.assertUnchanged()
    }

    @Test
    fun `remove after close is rejected without changing artifacts`(@TempDir directory: Path) {
        val snapshot = installedSnapshot(directory)
        val manager = DesktopExtensionManager(loader = DesktopExtensionLoader(directory.toFile())).also { it.loadAll() }
        val installed = manager.getInstalledExtensions().single()
        manager.close()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            manager.removeExtensionWithMeta(installed)
        }

        assertNull(manager.getSource(FixtureOldSource.ID))
        snapshot.assertUnchanged()
    }

    @Test
    fun `public operation queued before close is rejected after install window releases`(@TempDir directory: Path) = runBlocking {
        installedSnapshot(directory)
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(BlockingValidationSource::class.java)) },
        ).also { it.loadAll() }
        BlockingValidationSource.blockNextValidation()
        val install = async(Dispatchers.IO) { manager.installExtension(artifact(BlockingValidationSource.ID)) }
        BlockingValidationSource.validationEntered.awaitLatch()
        val reload = async(Dispatchers.IO) { runCatching { manager.reloadAll() }.exceptionOrNull() }
        delay(100)
        val reloadCrossedInstallWindow = reload.isCompleted
        val close = async(Dispatchers.IO) { manager.close() }

        try {
            BlockingValidationSource.allowValidation.countDown()
            val reloadFailure = withTimeout(2_000) { reload.await() }
            withTimeout(2_000) { close.await() }
            withTimeout(2_000) { install.join() }

            assertFalse(reloadCrossedInstallWindow, "queued reload crossed active install window")
            assertInstanceOf(IllegalStateException::class.java, reloadFailure)
            assertNull(manager.getSource(FixtureOldSource.ID))
            assertNull(manager.getSource(BlockingValidationSource.ID))
        } finally {
            BlockingValidationSource.allowValidation.countDown()
            runCatching { manager.close() }
        }
    }

    @Test
    fun `partial cleanup cannot consume rollback material`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
            fileSystem = PartiallyDeletingCleanupFileSystem(),
        ).also { it.loadAll() }

        try {
            val terminal = manager.installExtension(artifact(FixtureNewSource.ID))
            assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            snapshot.assertUnchanged()
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `recovery archive delete failure is reported then removed by rollback cleanup`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val fileSystem = FailOnceFileSystem(
            deleteFailure = { destination, occurrence ->
                destination.name.startsWith(".recovery-") && occurrence == 1
            },
        )
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
            fileSystem = fileSystem,
        ).also { it.loadAll() }

        try {
            val terminal = manager.installExtension(artifact(FixtureNewSource.ID))
            val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            assertInstanceOf(AppError.Storage::class.java, failure.error)
            snapshot.assertUnchanged()
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertNull(manager.getSource(FixtureNewSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `transaction residue assertion detects recovery archive`(@TempDir directory: Path) {
        directory.resolve(".recovery-orphan.bin").toFile().writeText("orphan")

        org.junit.jupiter.api.Assertions.assertThrows(AssertionError::class.java) {
            assertNoTransactionFiles(directory)
        }
    }

    @Test
    fun `prepare failure and cleanup failure return Network plus Storage partial failure`(@TempDir directory: Path) = runBlocking {
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination ->
                destination.writeText("partial")
                throw ExtensionInstallFailure(AppError.Network(IOException("network interrupted")))
            },
            fileSystem = AlwaysFailingCleanupFileSystem(),
        )

        val terminal = try {
            manager.installExtension(artifact(FixtureNewSource.ID))
        } finally {
            manager.close()
        }

        val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
        val partial = assertInstanceOf(AppError.PartialFailure::class.java, failure.error)
        assertTrue(partial.failures.any { it is AppError.Network })
        assertTrue(partial.failures.any { it is AppError.Storage })
    }

    @Test
    fun `real cancellation during prepare removes partial transaction`(@TempDir directory: Path) = runBlocking {
        val providerStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination ->
                destination.writeText("partial")
                providerStarted.complete(Unit)
                awaitCancellation()
            },
        )
        val install = async { manager.installExtension(artifact(FixtureNewSource.ID)) }
        providerStarted.await()

        install.cancel()
        install.join()
        waitUntil { directory.toFile().listFiles().orEmpty().none { it.name.startsWith(".install-") } }

        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
        manager.close()
    }

    @Test
    fun `trust confirmation resumes exactly the pending request`(@TempDir directory: Path) = runBlocking {
        installedSnapshot(directory)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(sourceJar(FixtureNewSource::class.java))).build())
            val api = api()
            val manager = manager(api, directory.toFile()).also { it.loadAll() }
            val extension = available(server, FixtureNewSource.ID, null).copy(repoFingerprint = "new-key")
            try {
                val discarded = assertInstanceOf(
                    DesktopExtensionInstallStart.TrustRequired::class.java,
                    api.beginInstall(extension, manager),
                )
                assertTrue(api.discardTrust(discarded.requestId))
                assertFalse(api.discardTrust(discarded.requestId))
                assertNull(api.confirmTrust(discarded.requestId, manager))
                val pending = assertInstanceOf(
                    DesktopExtensionInstallStart.TrustRequired::class.java,
                    api.beginInstall(extension, manager),
                )
                assertNull(api.confirmTrust("unrelated", manager))
                val states = checkNotNull(api.confirmTrust(pending.requestId, manager)).toList()
                val terminal = states.last()

                val installedTerminal = assertInstanceOf(ExtensionInstallState.Installed::class.java, terminal)
                assertEquals(
                    listOf(
                        ExtensionInstallState.Preparing::class,
                        ExtensionInstallState.Validating::class,
                        ExtensionInstallState.Committing::class,
                        ExtensionInstallState.Reloading::class,
                        ExtensionInstallState.Installed::class,
                    ),
                    states.map { it::class },
                )
                assertSame(pending.request.incomingArtifact, installedTerminal.artifact)
                assertNull(api.confirmTrust(pending.requestId, manager))
                assertEquals(1, server.requestCount)
                val installed = manager.installedExtensions.value.single()
                assertEquals(listOf(extension.name, extension.lang, extension.isNsfw), listOf(installed.name, installed.language, installed.isNsfw))
                assertTrue(manager.removeExtensionWithMeta(installed))
                assertTrue(manager.installedExtensions.value.isEmpty())
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun `failed uninstall restores runtime and leaves authoritative state unchanged`(@TempDir directory: Path) {
        installedSnapshot(directory)
        val manager = DesktopExtensionManager(DesktopExtensionLoader(directory.toFile())).also { it.loadAll() }
        val before = manager.installedExtensions.value.single()
        val undeletable = before.copy(
            jarFile = object : File(before.jarFile.path) {
                override fun delete() = false
            },
        )
        try {
            assertFalse(manager.removeExtension(undeletable))
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertEquals(before.pkgName, manager.installedExtensions.value.single().pkgName)
            assertFalse(manager.removeExtensionWithMeta(undeletable))
            assertTrue(directory.resolve("$PACKAGE.meta.json").toFile().isFile)
            assertNotNull(manager.getSource(FixtureOldSource.ID))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `public close waits for cancelled prepare cleanup`(@TempDir directory: Path) = runBlocking {
        val providerStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fileSystem = BlockingCleanupFileSystem()
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination ->
                destination.writeText("partial")
                providerStarted.complete(Unit)
                awaitCancellation()
            },
            fileSystem = fileSystem,
        )
        val install = async { manager.installExtension(artifact(FixtureNewSource.ID)) }
        providerStarted.await()
        val close = async(Dispatchers.IO) { manager.close() }
        fileSystem.cleanupEntered.awaitLatch()

        try {
            delay(200)
            assertFalse(close.isCompleted, "close returned before cancelled prepare cleanup completed")
        } finally {
            fileSystem.allowCleanup.countDown()
            withTimeout(2_000) { close.await() }
            withTimeout(2_000) { install.join() }
        }

        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `real cancellation during reload restores old runtime and files`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val loader = BlockingReloadLoader(directory.toFile())
        val manager = transactionManager(
            loader = loader,
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
        ).also { it.loadAll() }
        val installedBefore = manager.installedExtensions.value
        loader.blockNextReload = true
        val install = async { manager.installExtensionStates(artifact(FixtureNewSource.ID)).last() }
        loader.reloadEntered.awaitLatch()

        install.cancel()
        loader.allowReload.countDown()
        install.join()
        waitUntil { manager.getSource(FixtureOldSource.ID) != null }

        snapshot.assertUnchanged()
        assertNotNull(manager.getSource(FixtureOldSource.ID))
        assertNull(manager.getSource(FixtureNewSource.ID))
        assertEquals(
            installedBefore.map { it.copy(sources = emptyList()) to it.sources.map { source -> source.id } },
            manager.installedExtensions.value.map { it.copy(sources = emptyList()) to it.sources.map { source -> source.id } },
        )
        assertNoTransactionFiles(directory)
        manager.close()
    }

    @Test
    fun `real cancellation during cleanup restores old runtime and files`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val fileSystem = BlockingCleanupFileSystem()
        val installFlight = CompletableDeferred<Job>()
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination ->
                installFlight.complete(requireNotNull(currentCoroutineContext()[Job]))
                destination.writeBytes(sourceJar(FixtureNewSource::class.java))
            },
            fileSystem = fileSystem,
        ).also { it.loadAll() }
        val install = async { manager.installExtension(artifact(FixtureNewSource.ID)) }
        fileSystem.cleanupEntered.awaitLatch()

        install.cancel()
        yield()
        withTimeout(2_000) {
            while (!installFlight.await().isCancelled) {
                yield()
            }
        }
        fileSystem.allowCleanup.countDown()
        install.join()
        waitUntil { manager.getSource(FixtureOldSource.ID) != null }

        snapshot.assertUnchanged()
        assertNotNull(manager.getSource(FixtureOldSource.ID))
        assertNull(manager.getSource(FixtureNewSource.ID))
        assertNoTransactionFiles(directory)
        manager.close()
    }

    @Test
    fun `local write failure maps to Storage`(@TempDir directory: Path) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("artifact").build())
            val api = api()
            val destination = directory.resolve("destination-directory").toFile().also(File::mkdirs)

            val failure = org.junit.jupiter.api.Assertions.assertThrows(ExtensionInstallFailure::class.java) {
                runBlocking { api.downloadArtifact(artifact(FixtureNewSource.ID).copy(downloadUrl = server.url("/artifact").toString()), destination) }
            }

            assertInstanceOf(AppError.Storage::class.java, failure.error)
            Unit
        }
    }

    @Test
    fun `installed hash read failure maps to Storage`(@TempDir directory: Path) = runBlocking {
        directory.resolve("$PACKAGE.jar").toFile().mkdirs()
        val api = api()
        val manager = manager(api, directory.toFile())
        try {
            val result = api.installExtension(availableWithoutServer(PACKAGE), manager)
            val failure = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
            assertInstanceOf(AppError.Storage::class.java, failure.error)
        } finally {
            manager.close()
        }
        Unit
    }

    @Test
    fun `http 404 maps to Server with status code`(@TempDir directory: Path) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).build())
            val api = api()
            val manager = manager(api, directory.toFile())
            try {
                val result = api.installExtension(available(server, FixtureNewSource.ID, null), manager)
                val failure = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
                val error = assertInstanceOf(AppError.Server::class.java, failure.error)
                assertEquals(404, error.statusCode)
            } finally {
                manager.close()
            }
        }
    }

    @Test
    fun `runtime rejection maps to MalformedData`(@TempDir directory: Path) = runBlocking {
        installedSnapshot(directory)
        val loader = EmptyOnceReloadLoader(directory.toFile())
        val manager = transactionManager(
            loader = loader,
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
        ).also { it.loadAll() }
        loader.emptyNextReload = true

        val terminal = try {
            manager.installExtension(artifact(FixtureNewSource.ID))
        } finally {
            manager.close()
        }

        val failure = assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
        assertInstanceOf(AppError.MalformedData::class.java, failure.error)
        Unit
    }

    @Test
    fun `jvm jar installs through production api loader and manager`(@TempDir directory: Path) = runBlocking {
        val bytes = sourceJar(FixtureNewSource::class.java)

        val result = install(bytes, directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Success::class.java, result)
        val installed = directory.resolve("$PACKAGE.jar").toFile()
        assertArrayEquals(bytes, installed.readBytes())
        val loaded = DesktopExtensionLoader(directory.toFile()).loadPackage(PACKAGE)
        try {
            assertEquals(FixtureNewSource.ID, loaded.single().source.id)
        } finally {
            loaded.map { it.classLoader }.distinct().forEach { (it as? AutoCloseable)?.close() }
        }
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `dex apk without declared provider is rejected`(@TempDir directory: Path) = runBlocking {
        val apk = zip("classes.dex" to Base64.getDecoder().decode(MINIMAL_DEX_BASE64))

        val result = install(apk, directory)

        val error = assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        assertInstanceOf(mihon.domain.error.AppError.MalformedData::class.java, error.error)
        assertFalse(directory.resolve("$PACKAGE.jar").toFile().exists())
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `corrupt zip preserves installed jar and sidecar`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install("broken zip".toByteArray(), directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `wrong package jar leaves installed artifact and metadata unchanged`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install(fakeJar("wrong.package.NewSource"), directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `dex conversion failure preserves installed jar and removes temporary files`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install(zip("classes.dex" to byteArrayOf(1, 2, 3)), directory, FixtureNewSource.ID)

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `declared digest mismatch preserves installed jar and sidecar`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)

        val result = install(sourceJar(FixtureNewSource::class.java), directory, FixtureNewSource.ID, "0000")

        assertInstanceOf(DesktopExtensionApi.InstallResult.Error::class.java, result)
        snapshot.assertUnchanged()
        assertNoTransactionFiles(directory)
    }

    @Test
    fun `reload failure restores old artifact metadata and runtime`(@TempDir directory: Path) = runBlocking {
        val snapshot = installedSnapshot(directory)
        val loader = FailOnceLoader(directory.toFile())
        val manager = transactionManager(
            loader = loader,
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
        ).also { it.loadAll() }
        loader.failNextReload = true

        val terminal = manager.installExtension(artifact(FixtureNewSource.ID))

        try {
            assertInstanceOf(ExtensionInstallState.Failed::class.java, terminal)
            snapshot.assertUnchanged()
            assertNotNull(manager.getSource(FixtureOldSource.ID))
            assertNull(manager.getSource(FixtureNewSource.ID))
            assertNoTransactionFiles(directory)
        } finally {
            manager.close()
        }
    }

    private suspend fun assertPublicOperationWaits(
        directory: Path,
        operation: (DesktopExtensionManager, InstalledExtension) -> Unit,
    ) = coroutineScope {
        val snapshot = installedSnapshot(directory)
        val fileSystem = BlockingFirstReplaceFileSystem()
        val manager = transactionManager(
            loader = DesktopExtensionLoader(directory.toFile()),
            artifactProvider = { _, destination -> destination.writeBytes(sourceJar(FixtureNewSource::class.java)) },
            fileSystem = fileSystem,
        ).also { it.loadAll() }
        val installed = manager.getInstalledExtensions().single()
        val install = async(Dispatchers.IO) { manager.installExtension(artifact(FixtureNewSource.ID)) }
        fileSystem.replaceEntered.awaitLatch()
        val publicOperation = async(Dispatchers.IO) { operation(manager, installed) }
        try {
            delay(200)
            assertFalse(publicOperation.isCompleted, "public lifecycle operation crossed active install window")
        } finally {
            fileSystem.allowReplace.countDown()
            runCatching { withTimeout(2_000) { install.await() } }
            runCatching { withTimeout(2_000) { publicOperation.await() } }
            manager.close()
        }
    }

    private suspend fun CountDownLatch.awaitLatch() {
        withContext(Dispatchers.IO) {
            check(await(2, TimeUnit.SECONDS)) { "timed out waiting for injected lifecycle phase" }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(25)
        }
    }

    private fun api() = DesktopExtensionApi(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        extensionRepoRepository = FakeExtensionRepoRepository(),
    )

    private fun manager(
        api: DesktopExtensionApi,
        directory: File,
        loader: DesktopExtensionLoader = DesktopExtensionLoader(directory),
        fileSystem: DesktopExtensionFileSystem = DefaultDesktopExtensionFileSystem,
    ) = transactionManager(
        loader = loader,
        artifactProvider = api::downloadArtifact,
        fileSystem = fileSystem,
    ).also { it.loadAll() }

    private fun transactionManager(
        loader: DesktopExtensionLoader,
        artifactProvider: DesktopArtifactProvider,
        fileSystem: DesktopExtensionFileSystem = DefaultDesktopExtensionFileSystem,
    ) = DesktopExtensionManager(
        loader = loader,
        artifactProvider = artifactProvider,
        fileSystem = fileSystem,
        artifactAuthenticator = DesktopArtifactAuthenticator { _, _, _ -> },
    )

    private suspend fun install(
        bytes: ByteArray,
        directory: Path,
        sourceId: Long? = null,
        digest: String? = null,
        packageName: String = PACKAGE,
    ): DesktopExtensionApi.InstallResult = MockWebServer().use { server ->
        server.start()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
        val api = api()
        val manager = manager(api, directory.toFile())
        try {
            api.installExtension(available(server, sourceId, digest, packageName), manager)
        } finally {
            manager.close()
        }
    }

    private fun available(
        server: MockWebServer,
        sourceId: Long?,
        digest: String?,
        packageName: String = PACKAGE,
    ) = DesktopAvailableExtension(
        name = "Expected",
        pkgName = packageName,
        versionName = "1.4.2",
        versionCode = 2,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        jarUrl = server.url("/extension").toString(),
        iconUrl = "",
        repoUrl = "https://repo.example",
        repoName = "Repository",
        repoFingerprint = "fingerprint",
        declaredSha256 = digest,
        sources = sourceId?.let { listOf(DesktopAvailableSource(it, "en", "Fixture", "https://example.com")) }.orEmpty(),
    )

    private fun availableWithoutServer(packageName: String) = DesktopAvailableExtension(
        name = "Expected",
        pkgName = packageName,
        versionName = "1.4.2",
        versionCode = 2,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        jarUrl = "https://repo.example/extension.jar",
        iconUrl = "",
        repoUrl = "https://repo.example",
        repoName = "Repository",
        repoFingerprint = "fingerprint",
    )

    private fun artifact(sourceId: Long) = ExtensionArtifact(
        name = "Expected",
        packageName = PACKAGE,
        versionName = "1.4.2",
        versionCode = 2,
        language = "en",
        isNsfw = false,
        sources = listOf(ExtensionSourceDescriptor(sourceId, "en", "Fixture", "https://example.com")),
        repository = RepositoryIdentity("https://repo.example", "Repository", "fingerprint"),
        downloadUrl = "https://repo.example/extension.jar",
        iconUrl = "",
        declaredSha256 = null,
    )

    private fun installedSnapshot(directory: Path): Snapshot {
        directory.toFile().mkdirs()
        val jar = directory.resolve("$PACKAGE.jar").toFile().also { it.writeBytes(sourceJar(FixtureOldSource::class.java)) }
        writeExtensionMeta(
            jar,
            ExtensionMeta(
                pkgName = PACKAGE,
                versionCode = 1,
                versionName = "1.4.1",
                repoUrl = "https://repo.example",
                repoName = "Repository",
                repoFingerprint = "fingerprint",
                artifactSha256 = jar.readBytes().sha256(),
            ),
        )
        return Snapshot(jar, jar.readBytes(), sidecar(jar).readBytes())
    }

    private fun sourceJar(source: Class<out Source>): ByteArray {
        val path = source.name.replace('.', '/') + ".class"
        val classBytes = checkNotNull(source.classLoader.getResourceAsStream(path)).use { it.readBytes() }
        return zip(path to classBytes, SERVICE to source.name.toByteArray())
    }

    private fun sourceJarWithPageClient(source: Class<out Source>): ByteArray {
        val path = source.name.replace('.', '/') + ".class"
        val classBytes = checkNotNull(source.classLoader.getResourceAsStream(path)).use { it.readBytes() }
        return zip(
            path to classBytes,
            SERVICE to source.name.toByteArray(),
            "PageClient.class" to pageConstructorCaller(),
        )
    }

    private fun pageConstructorCaller(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "PageClient", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "create", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.ACONST_NULL)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                PAGE_OWNER,
                "<init>",
                PAGE_URI_DEFAULT_DESCRIPTOR,
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(6, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun pageConstructorDescriptor(input: java.io.InputStream): String {
        var found = ""
        ClassReader(input).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String?,
                        name: String?,
                        methodDescriptor: String?,
                        isInterface: Boolean,
                    ) {
                        if (owner == PAGE_OWNER && name == "<init>") found = methodDescriptor.orEmpty()
                    }
                }
            },
            0,
        )
        return found
    }

    private fun classOnlyJar(type: Class<*>): ByteArray {
        val path = type.name.replace('.', '/') + ".class"
        val classBytes = checkNotNull(type.classLoader.getResourceAsStream(path)).use { it.readBytes() }
        return zip(path to classBytes)
    }

    private fun fakeJar(provider: String) = zip(
        provider.replace('.', '/') + ".class" to byteArrayOf(0),
        SERVICE to provider.toByteArray(),
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun sidecar(jar: File) = File(jar.parentFile, "${jar.nameWithoutExtension}.meta.json")

    private fun assertNoTransactionFiles(directory: Path) {
        assertTrue(
            directory.toFile().walkTopDown().none {
                ".tmp" in it.name ||
                    ".backup" in it.name ||
                    it.name.startsWith(".install-") ||
                    it.name.startsWith(".recovery-")
            },
        )
    }

    private fun directorySnapshot(directory: Path): Map<String, String> =
        directory.toFile().walkTopDown()
            .filter(File::isFile)
            .associate {
                it.relativeTo(directory.toFile()).invariantSeparatorsPath to Base64.getEncoder().encodeToString(it.readBytes())
            }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private data class Snapshot(val jar: File, val jarBytes: ByteArray, val metaBytes: ByteArray) {
        fun assertUnchanged() {
            assertArrayEquals(jarBytes, jar.readBytes())
            assertArrayEquals(metaBytes, File(jar.parentFile, "${jar.nameWithoutExtension}.meta.json").readBytes())
        }
    }

    private class FailOnceLoader(directory: File) : DesktopExtensionLoader(directory) {
        var failNextReload = false

        override fun loadPackage(packageName: String): List<LoadedExtension> {
            if (failNextReload) {
                failNextReload = false
                error("fake reload failure")
            }
            return super.loadPackage(packageName)
        }
    }

    private class SnapshotRaceLoader(
        directory: File,
        private val stalePackage: String,
    ) : DesktopExtensionLoader(directory) {
        val staleSnapshotEntered = CountDownLatch(1)
        private val releaseSnapshot = CountDownLatch(1)
        private val blockFirstSnapshot = AtomicBoolean(true)

        override fun loadPackage(packageName: String): List<LoadedExtension> {
            val artifact = extensionArtifactFile(extensionsDirectory, packageName, "jar")
            val runtimeArtifact = if (packageName == stalePackage) {
                SnapshotBlockingFile(
                    artifact.absolutePath,
                    blockFirstSnapshot,
                    staleSnapshotEntered,
                    releaseSnapshot,
                )
            } else {
                artifact
            }
            return listOf(
                LoadedExtension(
                    source = FixtureNewSource(),
                    jarFile = runtimeArtifact,
                    classLoader = FixtureNewSource::class.java.classLoader,
                ),
            )
        }

        fun releaseStaleSnapshot() {
            releaseSnapshot.countDown()
        }
    }

    private class SnapshotBlockingFile(
        path: String,
        private val blockFirstSnapshot: AtomicBoolean,
        private val snapshotEntered: CountDownLatch,
        private val releaseSnapshot: CountDownLatch,
    ) : File(path) {
        override fun hashCode(): Int {
            if (blockFirstSnapshot.compareAndSet(true, false)) {
                snapshotEntered.countDown()
                check(releaseSnapshot.await(5, TimeUnit.SECONDS)) { "timed out releasing stale installed snapshot" }
            }
            return super.hashCode()
        }
    }

    private class CleanupFailureFileSystem : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        lateinit var manager: DesktopExtensionManager
        private var cleanupCalls = 0
        private var jarReplaceCalls = 0

        override fun replaceFromSnapshot(snapshot: File, destination: File) {
            if (destination.name == "$PACKAGE.jar" && ++jarReplaceCalls > 1 && manager.getSource(FixtureNewSource.ID) != null) {
                throw IOException("new runtime still owns destination during rollback")
            }
            DefaultDesktopExtensionFileSystem.replaceFromSnapshot(snapshot, destination)
        }

        override fun deleteTree(directory: File) {
            if (++cleanupCalls == 1) throw IOException("injected cleanup failure after runtime reload")
            DefaultDesktopExtensionFileSystem.deleteTree(directory)
        }
    }

    private class BlockingFirstReplaceFileSystem : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        val replaceEntered = CountDownLatch(1)
        val allowReplace = CountDownLatch(1)
        private var replaceCalls = 0

        override fun replaceFromSnapshot(snapshot: File, destination: File) {
            if (++replaceCalls == 1) {
                replaceEntered.countDown()
                check(allowReplace.await(2, TimeUnit.SECONDS)) { "timed out releasing replace" }
            }
            DefaultDesktopExtensionFileSystem.replaceFromSnapshot(snapshot, destination)
        }
    }

    private class PartiallyDeletingCleanupFileSystem : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        private var cleanupCalls = 0

        override fun deleteTree(directory: File) {
            if (++cleanupCalls == 1) {
                val victim = directory.walkTopDown().firstOrNull {
                    it.isFile && (it.name.contains("meta.snapshot") || it.name.contains("recovery"))
                } ?: directory.walkTopDown().first { it.isFile }
                check(victim.delete()) { "unable to inject partial cleanup" }
                throw IOException("injected failure after partial cleanup")
            }
            DefaultDesktopExtensionFileSystem.deleteTree(directory)
        }
    }

    private class AlwaysFailingCleanupFileSystem : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        override fun deleteTree(directory: File) {
            throw IOException("injected cleanup failure")
        }
    }

    private class BlockingCleanupFileSystem : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        private var cleanupCalls = 0

        override fun deleteTree(directory: File) {
            if (++cleanupCalls == 1) {
                cleanupEntered.countDown()
                check(allowCleanup.await(2, TimeUnit.SECONDS)) { "timed out releasing cleanup" }
            }
            DefaultDesktopExtensionFileSystem.deleteTree(directory)
        }
    }

    private class BlockingReloadLoader(directory: File) : DesktopExtensionLoader(directory) {
        val reloadEntered = CountDownLatch(1)
        val allowReload = CountDownLatch(1)
        var blockNextReload = false

        override fun loadPackage(packageName: String): List<LoadedExtension> {
            if (blockNextReload) {
                blockNextReload = false
                reloadEntered.countDown()
                check(allowReload.await(2, TimeUnit.SECONDS)) { "timed out releasing reload" }
            }
            return super.loadPackage(packageName)
        }
    }

    private class EmptyOnceReloadLoader(directory: File) : DesktopExtensionLoader(directory) {
        var emptyNextReload = false

        override fun loadPackage(packageName: String): List<LoadedExtension> {
            if (emptyNextReload) {
                emptyNextReload = false
                return emptyList()
            }
            return super.loadPackage(packageName)
        }
    }

    private class FailCountLoader(directory: File) : DesktopExtensionLoader(directory) {
        var failReloads = 0

        override fun loadPackage(packageName: String): List<LoadedExtension> {
            if (failReloads > 0) {
                failReloads--
                error("injected runtime reload failure")
            }
            return super.loadPackage(packageName)
        }
    }

    private class FailOnceFileSystem(
        private val replaceFailure: (File, Int) -> Boolean = { _, _ -> false },
        private val deleteFailure: (File, Int) -> Boolean = { _, _ -> false },
    ) : DesktopExtensionFileSystem by DefaultDesktopExtensionFileSystem {
        private val replaceCounts = mutableMapOf<String, Int>()
        private val deleteCounts = mutableMapOf<String, Int>()

        override fun replaceFromSnapshot(snapshot: File, destination: File) {
            val occurrence = replaceCounts.merge(destination.absolutePath, 1, Int::plus) ?: 1
            if (replaceFailure(destination, occurrence)) throw IOException("injected replace failure for ${destination.name}")
            DefaultDesktopExtensionFileSystem.replaceFromSnapshot(snapshot, destination)
        }

        override fun delete(file: File) {
            val occurrence = deleteCounts.merge(file.absolutePath, 1, Int::plus) ?: 1
            if (deleteFailure(file, occurrence)) throw IOException("injected delete failure for ${file.name}")
            DefaultDesktopExtensionFileSystem.delete(file)
        }
    }

    private companion object {
        const val PAGE_OWNER = "eu/kanade/tachiyomi/source/model/Page"
        const val PAGE_URI_DEFAULT_DESCRIPTOR =
            "(ILjava/lang/String;Ljava/lang/String;Landroid/net/Uri;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
        const val PAGE_OBJECT_DEFAULT_DESCRIPTOR =
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/Object;ILkotlin/jvm/internal/DefaultConstructorMarker;)V"
        const val PACKAGE = "mihon.desktop.extension"
        const val SERVICE = "META-INF/services/eu.kanade.tachiyomi.source.Source"
        const val MINIMAL_DEX_BASE64 =
            "ZGV4CjAzNQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAcAAAAHhWNBIAAAAAAAAAAHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAcAAAAAEAAAAAAAAAAQAAAAAAAAA="
    }
}

class FixtureOldSource : Source {
    override val id = ID
    override val name = "Old"
    override val lang = "en"
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

    companion object { const val ID = 7001L }
}

class FixtureNewSource : Source {
    override val id = ID
    override val name = "New"
    override val lang = "en"
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

    companion object { const val ID = 7002L }
}

class BlockingValidationSource : Source {
    init {
        if (blockNext.compareAndSet(true, false)) {
            validationEntered.countDown()
            check(allowValidation.await(2, TimeUnit.SECONDS)) { "timed out releasing candidate validation" }
        }
    }

    override val id = ID
    override val name = "Blocking validation"
    override val lang = "en"
    override suspend fun getMangaDetails(manga: SManga) = manga
    override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
    override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()

    companion object {
        const val ID = 7003L
        private val blockNext = AtomicBoolean()

        @Volatile
        var validationEntered = CountDownLatch(0)
            private set

        @Volatile
        var allowValidation = CountDownLatch(0)
            private set

        fun blockNextValidation() {
            validationEntered = CountDownLatch(1)
            allowValidation = CountDownLatch(1)
            blockNext.set(true)
        }
    }
}
