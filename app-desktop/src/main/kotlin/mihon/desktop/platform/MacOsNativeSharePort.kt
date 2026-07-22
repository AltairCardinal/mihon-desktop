package mihon.desktop.platform

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun defaultDesktopNativeSharePort(
    osName: String = System.getProperty("os.name"),
    macOsFactory: () -> DesktopNativeSharePort = { MacOsNativeSharePort() },
): DesktopNativeSharePort =
    if (osName.lowercase(Locale.ROOT).contains("mac")) {
        macOsFactory()
    } else {
        UnavailableDesktopNativeSharePort
    }

internal object UnavailableDesktopNativeSharePort : DesktopNativeSharePort {
    override fun share(content: DesktopNativeShareContent) = DesktopNativeShareOutcome.Unavailable
}

internal fun interface MacOsShareProcessLauncher {
    fun start(command: List<String>): Process
}

internal class MacOsNativeSharePort(
    private val processLauncher: MacOsShareProcessLauncher = MacOsShareProcessLauncher { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
    private val launchTimeoutMillis: Long = 5_000,
    private val terminalTimeoutMillis: Long = 120_000,
    private val script: String = MAC_OS_NATIVE_SHARE_SCRIPT,
) : DesktopNativeSharePort {
    private val stateLock = Any()
    private val activeExchanges = mutableSetOf<ShareExchange>()
    private var closeStarted = false

    internal val activeExchangeCount: Int
        get() = synchronized(stateLock) { activeExchanges.size }

    override fun share(content: DesktopNativeShareContent): DesktopNativeShareOutcome {
        val exchange = synchronized(stateLock) {
            if (closeStarted) return DesktopNativeShareOutcome.Failed
            val process = runCatching { processLauncher.start(buildCommand(content)) }
                .getOrElse { return DesktopNativeShareOutcome.Failed }
            ShareExchange(process, terminalTimeoutMillis, ::exchangeCleaned).also {
                activeExchanges += it
                Worker.register(this)
            }
        }
        if (runCatching { Worker.executor.execute(exchange::readProtocol) }.isFailure) {
            runCatching { exchange.failLaunch() }
            return DesktopNativeShareOutcome.Failed
        }
        return try {
            if (exchange.opened.get(launchTimeoutMillis, TimeUnit.MILLISECONDS)) {
                DesktopNativeShareOutcome.Opened(exchange.session)
            } else {
                DesktopNativeShareOutcome.Failed
            }
        } catch (_: TimeoutException) {
            runCatching { exchange.failLaunch() }
            DesktopNativeShareOutcome.Failed
        } catch (_: Exception) {
            runCatching { exchange.failLaunch() }
            DesktopNativeShareOutcome.Failed
        }
    }

    override fun close() {
        val snapshot = synchronized(stateLock) {
            closeStarted = true
            activeExchanges.toList()
        }
        var primary: Throwable? = null
        snapshot.forEach { exchange ->
            try {
                exchange.close()
            } catch (failure: Throwable) {
                val first = primary
                if (first == null) primary = failure else if (failure !== first) first.addSuppressed(failure)
            }
        }
        primary?.let { throw it }
    }

    private fun exchangeCleaned(exchange: ShareExchange) {
        synchronized(stateLock) {
            activeExchanges.remove(exchange)
            if (activeExchanges.isEmpty()) Worker.unregister(this)
        }
    }

    private fun buildCommand(content: DesktopNativeShareContent): List<String> = when (content) {
        is DesktopNativeShareContent.Text -> listOf(
            "/usr/bin/osascript", "-l", "JavaScript", "-e", script, "text", content.text,
        )
        is DesktopNativeShareContent.LocalFile -> listOf(
            "/usr/bin/osascript",
            "-l",
            "JavaScript",
            "-e",
            script,
            "file",
            content.file.absolutePath,
            content.message.orEmpty(),
        )
    }

    private class ShareExchange(
        private val process: Process,
        private val terminalTimeoutMillis: Long,
        private val onCleaned: (ShareExchange) -> Unit,
    ) : AutoCloseable {
        val opened = CompletableFuture<Boolean>()
        val session = ShareSession()
        private val lock = Any()
        private var ready = false
        private var finished = false
        private var cleanupComplete = false
        private var timeout: ScheduledFuture<*>? = null

        fun readProtocol() {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (handle(line.trim())) return
                    }
                }
                failCurrentState()
            } catch (_: Exception) {
                failCurrentState()
            }
        }

        private fun handle(line: String): Boolean = synchronized(lock) {
            if (finished) return true
            when {
                !ready && line == "MIHON_SHARE:READY" -> {
                    ready = true
                    opened.complete(true)
                    timeout = Worker.scheduler.schedule(
                        { runCatching { finish(DesktopNativeShareTerminal.Failed) } },
                        terminalTimeoutMillis,
                        TimeUnit.MILLISECONDS,
                    )
                    false
                }
                ready && line == "MIHON_SHARE:SHARED" -> finish(DesktopNativeShareTerminal.Shared)
                ready && line == "MIHON_SHARE:CANCELLED" -> finish(DesktopNativeShareTerminal.Cancelled)
                ready && line == "MIHON_SHARE:FAILED" -> finish(DesktopNativeShareTerminal.Failed)
                else -> fail()
            }
        }

        fun failLaunch() = fail()

        private fun failCurrentState() = fail()

        private fun fail(): Boolean = finish(DesktopNativeShareTerminal.Failed)

        private fun finish(terminal: DesktopNativeShareTerminal): Boolean {
            var deliverTerminal = false
            synchronized(lock) {
                if (!finished) {
                    finished = true
                    timeout?.cancel(false)
                    timeout = null
                    if (ready) {
                        deliverTerminal = true
                    } else {
                        opened.complete(false)
                    }
                }
            }
            if (deliverTerminal) session.complete(terminal)
            cleanup()
            return true
        }

        override fun close() {
            finish(DesktopNativeShareTerminal.Failed)
        }

        @Synchronized
        private fun cleanup() {
            if (cleanupComplete) return
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroy()
            }
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            check(process.waitFor(250, TimeUnit.MILLISECONDS)) { "Native share process did not terminate" }
            onCleaned(this)
            cleanupComplete = true
        }
    }

    private class ShareSession : DesktopNativeShareSession {
        private val lock = Any()
        private var callback: ((DesktopNativeShareTerminal) -> Unit)? = null
        private var terminal: DesktopNativeShareTerminal? = null
        private var delivered = false

        override fun onTerminal(callback: (DesktopNativeShareTerminal) -> Unit) {
            val ready = synchronized(lock) {
                check(this.callback == null) { "Terminal callback already registered" }
                this.callback = callback
                terminal?.takeIf { !delivered }?.also { delivered = true }
            }
            if (ready != null) runCatching { callback(ready) }
        }

        fun complete(terminal: DesktopNativeShareTerminal) {
            val target = synchronized(lock) {
                if (this.terminal != null) return
                this.terminal = terminal
                callback?.takeIf { !delivered }?.also { delivered = true }
            }
            if (target != null) runCatching { target(terminal) }
        }
    }

    private object Worker {
        private val activePorts = ConcurrentHashMap.newKeySet<MacOsNativeSharePort>()

        private fun factory(role: String) = java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable, "mihon-macos-share-$role").apply { isDaemon = true }
        }

        val executor = Executors.newCachedThreadPool(factory("io"))
        val scheduler = Executors.newSingleThreadScheduledExecutor(factory("timeout"))

        fun register(port: MacOsNativeSharePort) {
            activePorts += port
        }

        fun unregister(port: MacOsNativeSharePort) {
            activePorts -= port
        }

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                activePorts.toList().forEach { runCatching { it.close() } }
                executor.shutdownNow()
                scheduler.shutdownNow()
            })
        }
    }
}

internal val MAC_OS_NATIVE_SHARE_SCRIPT = """
            ObjC.import('AppKit');
            ObjC.import('Foundation');
            const args = $.NSProcessInfo.processInfo.arguments.js.slice(5);
            const kind = ObjC.unwrap(args[0]);
            const payload = ObjC.unwrap(args[1]);
            const message = args.length > 2 ? ObjC.unwrap(args[2]) : '';
            const app = $.NSApplication.sharedApplication;
            app.setActivationPolicy($.NSApplicationActivationPolicyAccessory);
            let done = false;
            let window;
            function finish(value) {
              if (done) return;
              done = true;
              console.log('MIHON_SHARE:' + value);
              if (window) window.close;
              app.terminate(null);
            }
            ObjC.registerSubclass({
              name: 'MihonSharingDelegate',
              protocols: ['NSSharingServiceDelegate'],
              methods: {
                'sharingService:didShareItems:': {
                  types: ['void', ['id', 'id']],
                  implementation: (_service, _items) => finish('SHARED')
                },
                'sharingService:didFailToShareItems:error:': {
                  types: ['void', ['id', 'id', 'id']],
                  implementation: (_service, _items, _error) => finish('FAILED')
                }
              }
            });
            const sharingDelegate = $.MihonSharingDelegate.alloc.init;
            ObjC.registerSubclass({
              name: 'MihonPickerDelegate',
              protocols: ['NSSharingServicePickerDelegate'],
              methods: {
                'sharingServicePicker:didChooseSharingService:': {
                  types: ['void', ['id', 'id']],
                  implementation: (_picker, service) => { if (!service) finish('CANCELLED'); }
                },
                'sharingServicePicker:delegateForSharingService:': {
                  types: ['id', ['id', 'id']],
                  implementation: (_picker, _service) => sharingDelegate
                }
              }
            });
            const pickerDelegate = $.MihonPickerDelegate.alloc.init;
            const items = kind === 'file'
              ? [$.NSURL.fileURLWithPath($(payload))].concat(message ? [$(message)] : [])
              : [$(payload)];
            window = $.NSWindow.alloc.initWithContentRectStyleMaskBackingDefer(
              $.NSMakeRect(0, 0, 2, 2), $.NSWindowStyleMaskBorderless, $.NSBackingStoreBuffered, false
            );
            window.center;
            window.makeKeyAndOrderFront(null);
            const picker = $.NSSharingServicePicker.alloc.initWithItems(items);
            picker.delegate = pickerDelegate;
            picker.showRelativeToRectOfViewPreferredEdge($.NSMakeRect(0, 0, 2, 2), window.contentView, $.NSMinYEdge);
            app.activateIgnoringOtherApps(true);
            console.log('MIHON_SHARE:READY');
            app.run;
    """.trimIndent()
