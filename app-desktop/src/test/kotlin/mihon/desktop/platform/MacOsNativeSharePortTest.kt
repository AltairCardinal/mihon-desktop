package mihon.desktop.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class MacOsNativeSharePortTest {
    @TempDir
    lateinit var directory: File

    @EnabledOnOs(OS.MAC)
    @Test
    fun `production JXA delegate reports shared once and exits naturally`() {
        val probeScript = MAC_OS_NATIVE_SHARE_SCRIPT.replace(
            "console.log('MIHON_SHARE:READY');",
            """
                console.log('MIHON_SHARE:READY');
                sharingDelegate.sharingServiceDidShareItems(null, items);
            """.trimIndent(),
        )
        lateinit var process: Process
        val port = MacOsNativeSharePort(
            processLauncher = MacOsShareProcessLauncher { command ->
                ProcessBuilder(command).redirectErrorStream(true).start().also { process = it }
            },
            launchTimeoutMillis = 5_000,
            terminalTimeoutMillis = 5_000,
            script = probeScript,
        )

        val opened = port.share(DesktopNativeShareContent.Text("no-side-effect terminal probe"))
            as DesktopNativeShareOutcome.Opened
        val terminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
        opened.session.onTerminal(terminals::add)

        await { terminals.isNotEmpty() }
        await { !process.isAlive }
        assertEquals(0, process.exitValue())
        assertEquals(listOf(DesktopNativeShareTerminal.Shared), terminals)
    }

    @Test
    fun `platform factory selects mac native port and explicit unavailable ports elsewhere`() {
        val macPort = MacOsNativeSharePort({ ControlledProcess() })

        assertSame(macPort, defaultDesktopNativeSharePort("Mac OS X") { macPort })
        assertSame(UnavailableDesktopNativeSharePort, defaultDesktopNativeSharePort("Windows 11") { macPort })
        assertSame(UnavailableDesktopNativeSharePort, defaultDesktopNativeSharePort("Linux") { macPort })
    }

    @Test
    fun `ready opens picker and shared completes the session exactly once`() {
        val process = ControlledProcess()
        val port = MacOsNativeSharePort({ process }, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 1_000)

        process.emit("MIHON_SHARE:READY")
        val opened = port.share(DesktopNativeShareContent.Text("hello")) as DesktopNativeShareOutcome.Opened
        val terminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
        opened.session.onTerminal(terminals::add)

        assertTrue(terminals.isEmpty())
        process.emit("MIHON_SHARE:SHARED")
        process.emit("MIHON_SHARE:CANCELLED")
        await { terminals.isNotEmpty() }
        assertEquals(listOf(DesktopNativeShareTerminal.Shared), terminals)
        await { process.destroyed }
    }

    @Test
    fun `close fails an opened exchange once destroys its process and rejects new shares`() {
        val process = ControlledProcess().also { it.emit("MIHON_SHARE:READY") }
        val port = MacOsNativeSharePort({ process }, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 60_000)
        val opened = port.share(DesktopNativeShareContent.Text("hello")) as DesktopNativeShareOutcome.Opened
        val terminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
        opened.session.onTerminal(terminals::add)

        assertEquals(1, port.activeExchangeCount)
        port.close()
        port.close()

        await { process.destroyed }
        assertEquals(0, port.activeExchangeCount)
        assertEquals(listOf(DesktopNativeShareTerminal.Failed), terminals)
        assertEquals(
            DesktopNativeShareOutcome.Failed,
            port.share(DesktopNativeShareContent.Text("after close")),
        )
    }

    @Test
    fun `multi session terminal and concurrent close clean only their own exchanges`() {
        val first = ControlledProcess().also { it.emit("MIHON_SHARE:READY") }
        val second = ControlledProcess().also { it.emit("MIHON_SHARE:READY") }
        val processes = ArrayDeque(listOf(first, second))
        val port = MacOsNativeSharePort({ processes.removeFirst() }, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 60_000)
        val firstTerminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
        val secondTerminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
        (port.share(DesktopNativeShareContent.Text("first")) as DesktopNativeShareOutcome.Opened)
            .session.onTerminal(firstTerminals::add)
        (port.share(DesktopNativeShareContent.Text("second")) as DesktopNativeShareOutcome.Opened)
            .session.onTerminal(secondTerminals::add)

        first.emit("MIHON_SHARE:SHARED")
        await { firstTerminals.isNotEmpty() && port.activeExchangeCount == 1 }
        assertFalse(second.destroyed)

        val executor = Executors.newFixedThreadPool(3)
        try {
            val closes = List(3) { executor.submit<Throwable?> { runCatching(port::close).exceptionOrNull() } }
            assertTrue(closes.map { it.get(2, TimeUnit.SECONDS) }.all { it == null })
        } finally {
            executor.shutdownNow()
        }
        await { secondTerminals.isNotEmpty() }
        assertEquals(listOf(DesktopNativeShareTerminal.Shared), firstTerminals)
        assertEquals(listOf(DesktopNativeShareTerminal.Failed), secondTerminals)
        assertEquals(0, port.activeExchangeCount)
        assertTrue(first.destroyed)
        assertTrue(second.destroyed)
    }

    @Test
    fun `terminal timeout and close race emits one terminal and cleanup failure remains retryable`() {
        repeat(3) {
            val process = ControlledProcess().also { it.emit("MIHON_SHARE:READY") }
            val port = MacOsNativeSharePort({ process }, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 10)
            val terminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
            (port.share(DesktopNativeShareContent.Text("race")) as DesktopNativeShareOutcome.Opened)
                .session.onTerminal(terminals::add)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val terminal = executor.submit { process.emit("MIHON_SHARE:CANCELLED") }
                val close = executor.submit { port.close() }
                terminal.get(2, TimeUnit.SECONDS)
                close.get(2, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
            await { terminals.isNotEmpty() }
            assertEquals(1, terminals.size)
            assertEquals(0, port.activeExchangeCount)
        }

        val retrying = ControlledProcess(destroyFailuresRemaining = 1).also { it.emit("MIHON_SHARE:READY") }
        val retryingPort = MacOsNativeSharePort({ retrying }, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 60_000)
        val terminals = CopyOnWriteArrayList<DesktopNativeShareTerminal>()
        (retryingPort.share(DesktopNativeShareContent.Text("retry")) as DesktopNativeShareOutcome.Opened)
            .session.onTerminal(terminals::add)

        assertThrows(IllegalStateException::class.java, retryingPort::close)
        assertEquals(1, retryingPort.activeExchangeCount)
        retryingPort.close()

        assertEquals(0, retryingPort.activeExchangeCount)
        assertEquals(listOf(DesktopNativeShareTerminal.Failed), terminals)
    }

    @Test
    fun `cancel failure and malformed protocol produce terminal outcomes`() {
        listOf(
            "MIHON_SHARE:CANCELLED" to DesktopNativeShareTerminal.Cancelled,
            "MIHON_SHARE:FAILED" to DesktopNativeShareTerminal.Failed,
            "SURPRISE" to DesktopNativeShareTerminal.Failed,
        ).forEach { (line, expected) ->
            val process = ControlledProcess()
            val port = MacOsNativeSharePort({ process }, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 1_000)
            process.emit("MIHON_SHARE:READY")
            val opened = port.share(DesktopNativeShareContent.Text("hello")) as DesktopNativeShareOutcome.Opened
            val terminal = CountDownTerminal(opened.session)
            process.emit(line)
            assertEquals(expected, terminal.await())
            await { process.destroyed }
        }
    }

    @Test
    fun `out of order eof reader failure and timeouts fail and clean up`() {
        val cases = listOf(
            ControlledProcess().also { it.emit("MIHON_SHARE:SHARED") },
            ControlledProcess().also { it.closeOutput() },
            ControlledProcess(FailingInputStream()),
        )
        cases.forEach { process ->
            val result = MacOsNativeSharePort({ process }, launchTimeoutMillis = 100, terminalTimeoutMillis = 100)
                .share(DesktopNativeShareContent.Text("hello"))
            assertEquals(DesktopNativeShareOutcome.Failed, result)
            await { process.destroyed }
        }

        val launchTimeout = ControlledProcess(ignoreDestroy = true)
        assertEquals(
            DesktopNativeShareOutcome.Failed,
            MacOsNativeSharePort({ launchTimeout }, launchTimeoutMillis = 20, terminalTimeoutMillis = 100)
                .share(DesktopNativeShareContent.Text("hello")),
        )
        await { launchTimeout.forciblyDestroyed }

        val terminalTimeout = ControlledProcess()
        terminalTimeout.emit("MIHON_SHARE:READY")
        val opened = MacOsNativeSharePort({ terminalTimeout }, launchTimeoutMillis = 100, terminalTimeoutMillis = 20)
            .share(DesktopNativeShareContent.Text("hello")) as DesktopNativeShareOutcome.Opened
        assertEquals(DesktopNativeShareTerminal.Failed, CountDownTerminal(opened.session).await())
        await { terminalTimeout.destroyed }
    }

    @Test
    fun `ready then eof or repeated ready fails the opened session and cleans up`() {
        listOf<(ControlledProcess) -> Unit>(
            { it.closeOutput() },
            { it.emit("MIHON_SHARE:READY") },
        ).forEach { endProtocol ->
            val process = ControlledProcess()
            process.emit("MIHON_SHARE:READY")
            val opened = MacOsNativeSharePort({ process }, launchTimeoutMillis = 100, terminalTimeoutMillis = 1_000)
                .share(DesktopNativeShareContent.Text("hello")) as DesktopNativeShareOutcome.Opened
            val terminal = CountDownTerminal(opened.session)
            endProtocol(process)
            assertEquals(DesktopNativeShareTerminal.Failed, terminal.await())
            await { process.destroyed }
        }
    }

    @Test
    fun `terminal received before callback registration is replayed once`() {
        val process = ControlledProcess()
        process.emit("MIHON_SHARE:READY")
        process.emit("MIHON_SHARE:CANCELLED")
        val opened = MacOsNativeSharePort({ process }, launchTimeoutMillis = 100, terminalTimeoutMillis = 1_000)
            .share(DesktopNativeShareContent.Text("hello")) as DesktopNativeShareOutcome.Opened
        val terminals = mutableListOf<DesktopNativeShareTerminal>()

        opened.session.onTerminal(terminals::add)

        assertEquals(listOf(DesktopNativeShareTerminal.Cancelled), terminals)
        await { process.destroyed }
    }

    @Test
    fun `fixed script keeps text file and message in independent argv`() {
        val commands = mutableListOf<List<String>>()
        fun launch(command: List<String>) = ControlledProcess().also {
            commands += command
            it.emit("MIHON_SHARE:READY")
            it.emit("MIHON_SHARE:CANCELLED")
        }
        val hostileText = "quote ' slash \\ newline\n$(touch nope)"
        val file = File(directory, "page ' $(unsafe).png").apply { writeBytes(byteArrayOf(1)) }
        val message = "Manga ' $(message)\nchapter"
        val port = MacOsNativeSharePort(::launch, launchTimeoutMillis = 1_000, terminalTimeoutMillis = 1_000)

        assertTrue(port.share(DesktopNativeShareContent.Text(hostileText)) is DesktopNativeShareOutcome.Opened)
        assertTrue(
            port.share(DesktopNativeShareContent.LocalFile(file, "image/png", message))
                is DesktopNativeShareOutcome.Opened,
        )

        assertEquals(listOf("text", hostileText), commands[0].takeLast(2))
        assertEquals(listOf("file", file.absolutePath, message), commands[1].takeLast(3))
        assertFalse(commands[0][4].contains(hostileText))
        assertFalse(commands[1][4].contains(file.absolutePath))
        assertTrue(commands[0][4].contains("NSSharingServicePicker"))
        assertTrue(commands[0][4].contains("implementation: (_picker, service)"))
        assertTrue(commands[0][4].contains("implementation: (_service, _items)"))
        assertTrue(commands[0][4].contains("implementation: (_service, _items, _error)"))
        assertFalse(commands[0][4].contains("_self, _cmd"))
        assertFalse(commands[0][4].contains("openLocation"))
    }

    private class CountDownTerminal(session: DesktopNativeShareSession) {
        private val latch = CountDownLatch(1)
        private var value: DesktopNativeShareTerminal? = null

        init {
            session.onTerminal {
                value = it
                latch.countDown()
            }
        }

        fun await(): DesktopNativeShareTerminal {
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            return requireNotNull(value)
        }
    }

    private class ControlledProcess(
        private val suppliedInput: InputStream? = null,
        private val ignoreDestroy: Boolean = false,
        private var destroyFailuresRemaining: Int = 0,
    ) : Process() {
        private val output = PipedOutputStream()
        private val input = suppliedInput ?: PipedInputStream(output)
        @Volatile var destroyed = false
        @Volatile var forciblyDestroyed = false
        @Volatile private var alive = true

        fun emit(line: String) {
            runCatching {
                output.write("$line\n".toByteArray())
                output.flush()
            }
        }

        fun closeOutput() = output.close()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int { while (alive) Thread.onSpinWait(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) error("alive") else 0
        override fun destroy() {
            if (destroyFailuresRemaining > 0) {
                destroyFailuresRemaining--
                throw IllegalStateException("destroy failed")
            }
            destroyed = true
            if (!ignoreDestroy) stop()
        }
        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            stop()
            return this
        }
        override fun isAlive(): Boolean = alive

        private fun stop() {
            alive = false
            runCatching { output.close() }
        }
    }

    private class FailingInputStream : InputStream() {
        override fun read(): Int = error("reader failed")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
