package eu.kanade.tachiyomi.extension.installer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.annotation.CallSuper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import uy.kohesive.injekt.injectLazy
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Base implementation class for extension installer. To be used inside a foreground [Service].
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class Installer(private val service: Service) {

    private val extensionManager: ExtensionManager by injectLazy()

    private var waitingInstall = AtomicReference<Entry?>(null)
    private val queue = Collections.synchronizedList(mutableListOf<Entry>())
    private val canceledTransactions = ConcurrentHashMap<String, Long>()
    private val completedTransactions = ConcurrentHashMap<String, Long>()

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID) ?: return
            cancelQueue(transactionId)
        }
    }

    /**
     * Installer readiness. If false, queue check will not run.
     *
     * @see checkQueue
     */
    abstract var ready: Boolean

    /**
     * Add an item to install queue.
     *
     * @param transactionId UUID shared by the install request and platform callback.
     * @param uri Uri of APK to install
     */
    fun addToQueue(transactionId: String, uri: Uri) {
        pruneTombstones()
        var cancellation: CancellationRequest? = null
        synchronized(pendingCancellations) {
            prunePendingCancellations()
            cancellation = pendingCancellations.remove(transactionId)
            if (cancellation != null ||
                canceledTransactions.containsKey(transactionId) ||
                completedTransactions.containsKey(transactionId)
            ) {
                return@synchronized
            }
            queue.add(Entry(transactionId, uri))
        }
        cancellation?.let {
            checkQueue()
            it.acknowledgement.complete(Unit)
            return
        }
        checkQueue()
    }

    /**
     * Proceeds to install the APK of this entry inside this method. Call [continueQueue]
     * when the install process for this entry is finished to continue the queue.
     *
     * @param entry The [Entry] of item to process
     * @see continueQueue
     */
    @CallSuper
    open fun processEntry(entry: Entry) {
        extensionManager.setInstalling(entry.transactionId)
    }

    /**
     * Called before queue continues. Override this to handle when the removed entry is
     * currently being processed.
     *
     * @return true if this entry can be removed from queue.
     */
    open fun cancelEntry(entry: Entry): Boolean {
        return true
    }

    /**
     * Tells the queue to continue processing the next entry and updates the install step
     * of the completed entry ([waitingInstall]) to [ExtensionManager].
     *
     * @param resultStep new install step for the processed entry.
     * @see waitingInstall
     */
    fun continueQueue(resultStep: InstallStep) {
        waitingInstall.load()?.let { continueQueue(it.transactionId, resultStep) }
    }

    protected fun continueQueue(transactionId: String, resultStep: InstallStep) {
        val completedEntry = waitingInstall.load() ?: return
        if (completedEntry.transactionId != transactionId ||
            !waitingInstall.compareAndSet(completedEntry, null) ||
            completedTransactions.putIfAbsent(transactionId, System.nanoTime()) != null
        ) {
            return
        }
        extensionManager.updateInstallStep(transactionId, resultStep)
        checkQueue()
    }

    /**
     * Checks the queue. The provided service will be stopped if the queue is empty.
     * Will not be run when not ready.
     *
     * @see ready
     */
    fun checkQueue() {
        if (!ready) {
            return
        }
        if (queue.isEmpty()) {
            service.stopSelf()
            return
        }
        val nextEntry = queue.first()
        if (waitingInstall.compareAndSet(null, nextEntry)) {
            queue.removeAt(0)
            processEntry(nextEntry)
        }
    }

    /**
     * Call this method when the provided service is destroyed.
     */
    @CallSuper
    open fun onDestroy() {
        LocalBroadcastManager.getInstance(service).unregisterReceiver(cancelReceiver)
        queue.forEach { completeQueued(it, InstallStep.Error) }
        queue.clear()
        waitingInstall.exchange(null)?.let { completeQueued(it, InstallStep.Error) }
    }

    protected fun getActiveEntry(): Entry? = waitingInstall.load()

    protected open fun onCancellationCleanup() = Unit

    /**
     * Cancels queue for the provided transaction ID if it exists.
     *
     * @param transactionId UUID shared by the install request and platform callback.
     */
    private fun cancelQueue(transactionId: String) {
        pruneTombstones()
        canceledTransactions[transactionId] = System.nanoTime()
        val waitingInstall = this.waitingInstall.load()
        val toCancel = queue.find { it.transactionId == transactionId }
            ?: waitingInstall?.takeIf { it.transactionId == transactionId }
            ?: run {
                completeCancellation(transactionId)
                return
            }
        if (cancelEntry(toCancel)) {
            queue.remove(toCancel)
            if (waitingInstall == toCancel && this.waitingInstall.compareAndSet(toCancel, null)) {
                // Currently processing removed entry, continue queue
                if (queue.isEmpty()) {
                    onCancellationCleanup()
                }
                checkQueue()
            }
            completeQueued(toCancel, InstallStep.Idle)
            completeCancellation(transactionId)
        }
    }

    private fun completeQueued(entry: Entry, step: InstallStep) {
        if (completedTransactions.putIfAbsent(entry.transactionId, System.nanoTime()) == null) {
            extensionManager.updateInstallStep(entry.transactionId, step)
        }
    }

    private fun pruneTombstones() {
        val cutoff = System.nanoTime() - TOMBSTONE_TTL_NANOS
        canceledTransactions.entries.removeIf { it.value < cutoff }
        completedTransactions.entries.removeIf { it.value < cutoff }
    }

    private fun completeCancellation(transactionId: String) {
        pendingCancellations.remove(transactionId)?.acknowledgement?.complete(Unit)
    }

    /**
     * Install item to queue.
     *
     * @param transactionId UUID shared by the install request and platform callback.
     * @param uri Uri of APK to install
     */
    data class Entry(val transactionId: String, val downloadId: Long, val uri: Uri) {
        constructor(transactionId: String, uri: Uri) : this(
            transactionId,
            transactionDownloadId(transactionId),
            uri,
        )
    }

    init {
        val filter = IntentFilter(ACTION_CANCEL_QUEUE)
        LocalBroadcastManager.getInstance(service).registerReceiver(cancelReceiver, filter)
    }

    companion object {
        private const val ACTION_CANCEL_QUEUE = "Installer.action.CANCEL_QUEUE"
        private const val EXTRA_TRANSACTION_ID = "Installer.extra.TRANSACTION_ID"
        private const val TOMBSTONE_TTL_NANOS = 5L * 60L * 1_000_000_000L
        private val pendingCancellations = ConcurrentHashMap<String, CancellationRequest>()

        /**
         * Attempts to cancel the installation entry for the provided transaction ID.
         *
         * @param transactionId UUID shared by the install request and platform callback.
         */
        fun cancelInstallQueue(context: Context, transactionId: String): Deferred<Unit> {
            val request = synchronized(pendingCancellations) {
                prunePendingCancellations()
                pendingCancellations.getOrPut(transactionId) {
                    CancellationRequest(System.nanoTime(), CompletableDeferred())
                }
            }
            val intent = Intent(ACTION_CANCEL_QUEUE)
            intent.putExtra(EXTRA_TRANSACTION_ID, transactionId)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            return request.acknowledgement
        }

        private fun prunePendingCancellations() {
            val cutoff = System.nanoTime() - TOMBSTONE_TTL_NANOS
            pendingCancellations.entries.removeIf { (_, request) ->
                (request.createdAtNanos < cutoff).also { expired ->
                    if (expired) request.acknowledgement.complete(Unit)
                }
            }
        }

        private data class CancellationRequest(
            val createdAtNanos: Long,
            val acknowledgement: CompletableDeferred<Unit>,
        )
    }
}

private fun transactionDownloadId(transactionId: String): Long =
    runCatching { UUID.fromString(transactionId).mostSignificantBits and Long.MAX_VALUE }
        .getOrElse { transactionId.hashCode().toLong() and Long.MAX_VALUE }
