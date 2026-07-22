package mihon.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopExternalActionBrokerTest {
    @Test
    fun `secondary forwards raw action with matching acknowledgement`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val received = mutableListOf<String>()
        val owner = DesktopExternalActionBroker(stateFile)
        val ownerResult = assertInstanceOf(
            DesktopExternalActionBroker.StartResult.Owner::class.java,
            owner.startOrForward(null),
        )
        owner.setActionConsumer(received::add)
        val secondary = DesktopExternalActionBroker(stateFile)

        assertEquals(
            DesktopExternalActionBroker.StartResult.Forwarded,
            secondary.startOrForward("tachiyomi://manga?url=raw%2Fvalue"),
        )
        withTimeout(2_000) {
            while (received.isEmpty()) yield()
        }

        assertEquals(listOf("tachiyomi://manga?url=raw%2Fvalue"), received)
        assertEquals("127.0.0.1", ownerResult.endpoint.host)
        assertTrue(ownerResult.endpoint.port > 0)
        secondary.close()
        owner.close()
    }

    @Test
    fun `owner rejects counterfeit token and oversized payload`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val received = mutableListOf<String>()
        val owner = DesktopExternalActionBroker(stateFile)
        val endpoint = assertInstanceOf(
            DesktopExternalActionBroker.StartResult.Owner::class.java,
            owner.startOrForward(null),
        ).endpoint
        owner.setActionConsumer(received::add)
        val secondary = DesktopExternalActionBroker(stateFile)

        assertEquals(
            DesktopExternalActionBroker.ForwardResult.Rejected(DesktopExternalActionBroker.Failure.InvalidToken),
            secondary.forward(endpoint.copy(token = "counterfeit"), "secret"),
        )
        assertEquals(
            DesktopExternalActionBroker.StartResult.Failed(DesktopExternalActionBroker.Failure.MessageTooLarge),
            secondary.startOrForward("x".repeat(DesktopExternalActionBroker.MAX_PAYLOAD_CHARS + 1)),
        )
        assertTrue(received.isEmpty())

        secondary.close()
        owner.close()
        assertFalse(stateFile.exists())
    }

    @Test
    fun `concurrent election yields one owner and all other launches are acknowledged`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val brokers = List(8) { DesktopExternalActionBroker(stateFile) }

        val results = brokers.map { broker ->
            async(Dispatchers.IO) { broker.startOrForward(null) }
        }.awaitAll()

        assertEquals(1, results.count { it is DesktopExternalActionBroker.StartResult.Owner })
        assertEquals(7, results.count { it == DesktopExternalActionBroker.StartResult.Forwarded })
        brokers.forEach(DesktopExternalActionBroker::close)
    }

    @Test
    fun `owner repeated start and concurrent forwarding serialize consumer and deliver each once`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        val received = mutableListOf<String>()
        val activeConsumers = AtomicInteger()
        val maxConcurrentConsumers = AtomicInteger()
        owner.setActionConsumer { raw ->
            val active = activeConsumers.incrementAndGet()
            maxConcurrentConsumers.updateAndGet { maxOf(it, active) }
            received += raw
            activeConsumers.decrementAndGet()
        }

        assertTrue(owner.startOrForward("owner-raw") is DesktopExternalActionBroker.StartResult.Owner)
        val results = (0 until 12).map { index ->
            async(Dispatchers.IO) {
                DesktopExternalActionBroker(stateFile).use { secondary ->
                    secondary.startOrForward("action-$index")
                }
            }
        }.awaitAll()
        withTimeout(2_000) {
            while (received.size < 13) yield()
        }

        assertTrue(results.all { it == DesktopExternalActionBroker.StartResult.Forwarded })
        assertEquals("owner-raw", received.first())
        assertEquals((0 until 12).map { "action-$it" }.toSet(), received.drop(1).toSet())
        assertEquals(13, received.distinct().size)
        assertEquals(1, maxConcurrentConsumers.get())
        owner.close()
    }

    @Test
    fun `sequential secondary actions retain FIFO order`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        owner.startOrForward(null)
        val received = mutableListOf<String>()
        owner.setActionConsumer(received::add)

        val expected = (0 until 12).map { "action-$it" }
        expected.forEach { raw ->
            DesktopExternalActionBroker(stateFile).use { secondary ->
                assertEquals(DesktopExternalActionBroker.StartResult.Forwarded, secondary.startOrForward(raw))
            }
        }
        withTimeout(2_000) {
            while (received.size < expected.size) yield()
        }

        assertEquals(expected, received)
        owner.close()
    }

    @Test
    fun `malformed frame is rejected without stopping owner`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        val endpoint = (owner.startOrForward(null) as DesktopExternalActionBroker.StartResult.Owner).endpoint
        val received = mutableListOf<String>()
        owner.setActionConsumer(received::add)

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", endpoint.port))
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(-1)
                flush()
            }
        }
        sendRawFrame(endpoint.port, 4, "{bad".toByteArray())
        sendRawFrame(endpoint.port, DesktopExternalActionBroker.MAX_FRAME_BYTES + 1, byteArrayOf())
        val secondary = DesktopExternalActionBroker(stateFile)
        assertEquals(DesktopExternalActionBroker.StartResult.Forwarded, secondary.startOrForward("after-malformed"))
        withTimeout(2_000) {
            while (received.isEmpty()) yield()
        }

        assertEquals(listOf("after-malformed"), received)
        secondary.close()
        owner.close()
    }

    @Test
    fun `secondary rejects acknowledgement with mismatched request id`(@TempDir tempDir: File) = runTest {
        val fakeOwner = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val server = async(Dispatchers.IO) {
            fakeOwner.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val requestLength = input.readInt()
                input.readNBytes(requestLength)
                val ack = """{"version":1,"requestId":"wrong","status":"Accepted"}""".toByteArray()
                DataOutputStream(socket.getOutputStream()).apply {
                    writeInt(ack.size)
                    write(ack)
                    flush()
                }
            }
        }
        val secondary = DesktopExternalActionBroker(File(tempDir, "unused.json"))
        val result = secondary.forward(
            DesktopExternalActionBroker.Endpoint(
                version = 1,
                host = "127.0.0.1",
                port = fakeOwner.localPort,
                token = "token",
                ownerId = "owner",
            ),
            "raw",
        )

        assertEquals(
            DesktopExternalActionBroker.ForwardResult.Rejected(
                DesktopExternalActionBroker.Failure.InvalidAcknowledgement,
            ),
            result,
        )
        server.await()
        fakeOwner.close()
        secondary.close()
    }

    @Test
    fun `stale endpoint failure retries election after owner lock becomes available`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "instance.json")
        val previous = DesktopExternalActionBroker(stateFile)
        val previousEndpoint = (previous.startOrForward(null) as DesktopExternalActionBroker.StartResult.Owner).endpoint
        val staleState = stateFile.readText()
        previous.close()
        val fakeOwner = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        stateFile.writeText(
            staleState.replace(
                "\"port\":${previousEndpoint.port}",
                "\"port\":${fakeOwner.localPort}",
            ),
        )
        val lockFile = RandomAccessFile(File(stateFile.path + ".lock"), "rw")
        val heldLock = lockFile.channel.lock()
        val accepted = async(Dispatchers.IO) { fakeOwner.accept().use { } }
        val newOwner = DesktopExternalActionBroker(stateFile)
        val takeover = async(Dispatchers.IO) { newOwner.startOrForward(null) }

        accepted.await()
        heldLock.release()
        lockFile.close()
        fakeOwner.close()

        assertTrue(takeover.await() is DesktopExternalActionBroker.StartResult.Owner)
        newOwner.close()
    }

    @Test
    fun `stale crash state is replaced and old owner cannot delete replacement`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "instance.json")
        val oldOwner = DesktopExternalActionBroker(stateFile)
        val oldEndpoint = (oldOwner.startOrForward(null) as DesktopExternalActionBroker.StartResult.Owner).endpoint
        val replacementOwnerId = UUID.randomUUID().toString()
        val staleState = stateFile.readText().replace(oldEndpoint.ownerId, replacementOwnerId)
        stateFile.writeText(staleState)
        oldOwner.close()
        assertTrue(stateFile.exists())

        val newOwner = DesktopExternalActionBroker(stateFile)
        val newEndpoint = (newOwner.startOrForward(null) as DesktopExternalActionBroker.StartResult.Owner).endpoint

        assertTrue(stateFile.exists())
        assertTrue(newEndpoint.ownerId != oldEndpoint.ownerId)
        newOwner.close()
        assertFalse(stateFile.exists())
    }

    @Test
    fun `state permissions are owner only when POSIX permissions are available`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "instance.json")
        val owner = DesktopExternalActionBroker(stateFile)
        owner.startOrForward(null)

        if (Files.getFileStore(stateFile.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(stateFile.toPath()),
            )
        }
        owner.close()
    }

    @Test
    fun `repeated start and close leaves no owner state`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "instance.json")

        repeat(20) {
            val owner = DesktopExternalActionBroker(stateFile)
            assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
            owner.close()
            owner.close()
            assertFalse(stateFile.exists())
        }
    }

    @Test
    fun `server close failure retains owner state until concurrent retry commits once`(@TempDir tempDir: File) = runTest {
        val stateFile = File(tempDir, "retry.json")
        val failure = IllegalStateException("server close")
        val retryEntered = CountDownLatch(2)
        val releaseRetry = CountDownLatch(1)
        var closeCalls = 0
        val owner = DesktopExternalActionBroker(stateFile, closeServer = { server ->
            closeCalls++
            if (closeCalls == 1) throw failure
            retryEntered.countDown(); releaseRetry.await(); server.close()
        })
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)

        assertSame(failure, runCatching(owner::close).exceptionOrNull())
        assertTrue(stateFile.exists())
        assertEquals(DesktopExternalActionBroker.StartResult.Failed(DesktopExternalActionBroker.Failure.InvalidState), owner.startOrForward(null))
        DesktopExternalActionBroker(stateFile).use { secondary ->
            assertEquals(
                DesktopExternalActionBroker.StartResult.Failed(DesktopExternalActionBroker.Failure.ProtocolRejected),
                secondary.startOrForward("closing"),
            )
        }
        val retries = List(2) { async(Dispatchers.IO) { runCatching(owner::close).exceptionOrNull() } }
        retryEntered.await(1, TimeUnit.SECONDS); releaseRetry.countDown()
        assertTrue(retries.awaitAll().all { it == null })
        assertFalse(stateFile.exists())
        owner.close()
        assertEquals(2, closeCalls)
        assertEquals(DesktopExternalActionBroker.StartResult.Failed(DesktopExternalActionBroker.Failure.InvalidState), owner.startOrForward(null))

        val successor = DesktopExternalActionBroker(stateFile)
        assertTrue(successor.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        successor.close()
    }

    @Test
    fun `malformed owner state blocks terminal cleanup until exact retry`(@TempDir tempDir: File) {
        val stateFile = File(tempDir, "malformed-close.json")
        val owner = DesktopExternalActionBroker(stateFile)
        assertTrue(owner.startOrForward(null) is DesktopExternalActionBroker.StartResult.Owner)
        val validState = stateFile.readText()
        stateFile.writeText("{bad")

        assertTrue(runCatching(owner::close).exceptionOrNull() is IllegalStateException)
        assertTrue(stateFile.exists())
        assertEquals(DesktopExternalActionBroker.StartResult.Failed(DesktopExternalActionBroker.Failure.InvalidState), owner.startOrForward(null))
        stateFile.writeText(validState)
        owner.close()
        assertFalse(stateFile.exists())
    }

    private fun sendRawFrame(port: Int, length: Int, bytes: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port))
            DataOutputStream(socket.getOutputStream()).apply {
                writeInt(length)
                write(bytes)
                flush()
            }
        }
    }
}
