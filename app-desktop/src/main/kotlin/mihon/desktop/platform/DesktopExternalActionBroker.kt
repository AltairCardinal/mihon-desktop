package mihon.desktop.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.locks.LockSupport

class DesktopExternalActionBroker(
    private val stateFile: File,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val closeServer: (ServerSocket) -> Unit = ServerSocket::close,
) : AutoCloseable {
    private val brokerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + brokerJob)
    private val deliveryLock = Any()
    private val pending = ArrayDeque<String>()
    private var consumer: ((String) -> Unit)? = null
    private var lockFile: RandomAccessFile? = null
    private var ownerLock: FileLock? = null
    private var server: ServerSocket? = null
    private var endpoint: Endpoint? = null
    private var cleanupOwnerId: String? = null
    @Volatile private var closeStarted = false
    private var terminal = false

    @Synchronized
    fun startOrForward(raw: String?): StartResult = try {
        startOrForwardLocked(raw)
    } catch (_: StateAccessException) {
        close()
        StartResult.Failed(Failure.StateAccessFailed)
    }

    private fun startOrForwardLocked(raw: String?): StartResult {
        if (closeStarted) return StartResult.Failed(Failure.InvalidState)
        if (raw != null && raw.length > MAX_PAYLOAD_CHARS) return StartResult.Failed(Failure.MessageTooLarge)
        endpoint?.let {
            raw?.let(::deliver)
            return StartResult.Owner(it)
        }
        repeat(STATE_READ_ATTEMPTS) { attempt ->
            acquireOwnerLock()?.let { lock ->
                ownerLock = lock
                return startOwner()
            }
            val owner = readState()
            if (owner != null) {
                when (val result = forward(owner, raw)) {
                    ForwardResult.Acknowledged -> return StartResult.Forwarded
                    is ForwardResult.Rejected -> {
                        if (result.failure != Failure.ConnectionFailed) {
                            return StartResult.Failed(result.failure)
                        }
                    }
                }
            }
            if (attempt + 1 < STATE_READ_ATTEMPTS) LockSupport.parkNanos(STATE_READ_RETRY_NANOS)
        }
        return StartResult.Failed(Failure.InvalidState)
    }

    fun setActionConsumer(actionConsumer: (String) -> Unit) {
        synchronized(deliveryLock) {
            consumer = actionConsumer
            while (pending.isNotEmpty()) actionConsumer(pending.removeFirst())
        }
    }

    fun forward(owner: Endpoint, raw: String?): ForwardResult {
        if (raw != null && raw.length > MAX_PAYLOAD_CHARS) return ForwardResult.Rejected(Failure.MessageTooLarge)
        if (owner.host != LOOPBACK_HOST) return ForwardResult.Rejected(Failure.InvalidState)
        val request = Request(
            version = PROTOCOL_VERSION,
            requestId = UUID.randomUUID().toString(),
            token = owner.token,
            payload = raw,
        )
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(IPV4_LOOPBACK, owner.port), IO_TIMEOUT_MILLIS)
                socket.soTimeout = IO_TIMEOUT_MILLIS
                writeFrame(DataOutputStream(socket.getOutputStream()), Json.encodeToString(request))
                val ack = Json.decodeFromString<Ack>(readFrame(DataInputStream(socket.getInputStream())))
                when {
                    ack.version != PROTOCOL_VERSION || ack.requestId != request.requestId ->
                        ForwardResult.Rejected(Failure.InvalidAcknowledgement)
                    ack.status == AckStatus.Accepted -> ForwardResult.Acknowledged
                    ack.status == AckStatus.InvalidToken -> ForwardResult.Rejected(Failure.InvalidToken)
                    else -> ForwardResult.Rejected(Failure.ProtocolRejected)
                }
            }
        } catch (_: FrameTooLargeException) {
            ForwardResult.Rejected(Failure.MessageTooLarge)
        } catch (_: Exception) {
            ForwardResult.Rejected(Failure.ConnectionFailed)
        }
    }

    @Synchronized
    override fun close() {
        if (terminal) return
        closeStarted = true
        endpoint = null
        server?.let { ownerServer ->
            closeServer(ownerServer)
            server = null
        }
        cleanupOwnerId?.let { ownerId ->
            if (readStateForClose()?.ownerId == ownerId) Files.delete(stateFile.toPath())
            cleanupOwnerId = null
        }
        ownerLock?.release()
        ownerLock = null
        lockFile?.close()
        lockFile = null
        brokerJob.cancel()
        terminal = true
    }

    private fun acquireOwnerLock(): FileLock? {
        var candidate: RandomAccessFile? = null
        return try {
            stateFile.parentFile?.mkdirs()
            candidate = RandomAccessFile(File(stateFile.path + ".lock"), "rw")
            val lock = try {
                candidate.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                candidate.close()
            } else {
                lockFile = candidate
            }
            lock
        } catch (failure: Exception) {
            runCatching { candidate?.close() }
            throw StateAccessException(failure)
        }
    }

    private fun startOwner(): StartResult {
        return try {
            val ownerServer = ServerSocket().apply {
                bind(InetSocketAddress(IPV4_LOOPBACK, 0))
            }
            val ownerEndpoint = Endpoint(
                version = PROTOCOL_VERSION,
                host = LOOPBACK_HOST,
                port = ownerServer.localPort,
                token = secureToken(),
                ownerId = UUID.randomUUID().toString(),
            )
            server = ownerServer
            endpoint = ownerEndpoint
            cleanupOwnerId = ownerEndpoint.ownerId
            writeState(ownerEndpoint)
            scope.launch(Dispatchers.IO) { acceptLoop(ownerServer, ownerEndpoint) }
            StartResult.Owner(ownerEndpoint)
        } catch (_: Exception) {
            close()
            StartResult.Failed(Failure.OwnerStartFailed)
        }
    }

    private fun acceptLoop(ownerServer: ServerSocket, owner: Endpoint) {
        while (!ownerServer.isClosed) {
            try {
                ownerServer.accept().use { handle(it, owner) }
            } catch (_: Exception) {
                if (!ownerServer.isClosed) continue
            }
        }
    }

    private fun handle(socket: Socket, owner: Endpoint) {
        socket.soTimeout = IO_TIMEOUT_MILLIS
        val request = try {
            Json.decodeFromString<Request>(readFrame(DataInputStream(socket.getInputStream())))
        } catch (_: Exception) {
            return
        }
        val status = synchronized(this) {
            when {
                closeStarted -> AckStatus.Rejected
                request.version != PROTOCOL_VERSION -> AckStatus.Rejected
                request.token != owner.token -> AckStatus.InvalidToken
                request.payload != null && request.payload.length > MAX_PAYLOAD_CHARS -> AckStatus.Rejected
                else -> {
                    request.payload?.let(::deliver)
                    AckStatus.Accepted
                }
            }
        }
        runCatching {
            writeFrame(
                DataOutputStream(socket.getOutputStream()),
                Json.encodeToString(Ack(PROTOCOL_VERSION, request.requestId, status)),
            )
        }
    }

    private fun deliver(raw: String) {
        synchronized(deliveryLock) {
            consumer?.invoke(raw) ?: pending.addLast(raw)
        }
    }

    private fun writeState(owner: Endpoint) {
        val temp = File(stateFile.parentFile, "${stateFile.name}.${owner.ownerId}.tmp")
        try {
            temp.writeText(Json.encodeToString(owner))
            tightenPermissions(temp)
            try {
                Files.move(
                    temp.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            tightenPermissions(stateFile)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun readState(): Endpoint? = runCatching {
        Json.decodeFromString<Endpoint>(stateFile.readText()).takeIf { it.version == PROTOCOL_VERSION }
    }.getOrNull()

    private fun readStateForClose(): Endpoint? {
        if (!stateFile.exists()) return null
        return try {
            Json.decodeFromString<Endpoint>(stateFile.readText()).takeIf { it.version == PROTOCOL_VERSION }
        } catch (failure: Exception) {
            throw StateAccessException(failure)
        }
    }

    sealed interface StartResult {
        data class Owner(val endpoint: Endpoint) : StartResult
        data object Forwarded : StartResult
        data class Failed(val failure: Failure) : StartResult
    }

    sealed interface ForwardResult {
        data object Acknowledged : ForwardResult
        data class Rejected(val failure: Failure) : ForwardResult
    }

    enum class Failure {
        InvalidState,
        MessageTooLarge,
        InvalidToken,
        InvalidAcknowledgement,
        ProtocolRejected,
        ConnectionFailed,
        OwnerStartFailed,
        StateAccessFailed,
    }

    @Serializable
    data class Endpoint(val version: Int, val host: String, val port: Int, val token: String, val ownerId: String)

    companion object {
        const val MAX_PAYLOAD_CHARS = 16 * 1024

        const val PROTOCOL_VERSION = 1
        const val MAX_FRAME_BYTES = 64 * 1024
        const val IO_TIMEOUT_MILLIS = 2_000
        const val STATE_READ_ATTEMPTS = 20
        const val STATE_READ_RETRY_NANOS = 5_000_000L
        const val LOOPBACK_HOST = "127.0.0.1"
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByName(LOOPBACK_HOST)
        val random = SecureRandom()

        fun secureToken(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        fun writeFrame(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_FRAME_BYTES) throw FrameTooLargeException()
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        fun readFrame(input: DataInputStream): String {
            val length = input.readInt()
            if (length !in 1..MAX_FRAME_BYTES) throw FrameTooLargeException()
            return input.readNBytes(length).also {
                if (it.size != length) throw IllegalArgumentException("Incomplete frame")
            }.toString(Charsets.UTF_8)
        }

        fun tightenPermissions(file: File) {
            runCatching {
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setExecutable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }
    }
}

@Serializable
private data class Request(val version: Int, val requestId: String, val token: String, val payload: String?)

@Serializable
private data class Ack(val version: Int, val requestId: String, val status: AckStatus)

@Serializable
private enum class AckStatus { Accepted, InvalidToken, Rejected }

private class FrameTooLargeException : IllegalArgumentException()

private class StateAccessException(cause: Throwable) : IllegalStateException(cause)
