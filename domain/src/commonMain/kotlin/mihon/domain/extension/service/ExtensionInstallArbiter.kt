package mihon.domain.extension.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.extension.model.ExtensionArtifact

/** An application-owned reservation, retained until installation cleanup has completed. */
class ExtensionInstallLease internal constructor(
    val transactionId: Long,
    val artifact: ExtensionArtifact,
    internal val eligibility: () -> ExtensionInstallInvalidation?,
)

abstract class ExtensionPackageLease internal constructor(val transactionId: Long, val packageName: String)
class ExtensionRemovalLease internal constructor(
    transactionId: Long,
    packageName: String,
) : ExtensionPackageLease(transactionId, packageName)
class ExtensionSystemWindowLease internal constructor(
    transactionId: Long,
    packageName: String,
) : ExtensionPackageLease(transactionId, packageName)

enum class ExtensionInstallInvalidation {
    PRESENT,
    INELIGIBLE,
    IGNORED,
    CATALOG_CHANGED,
    INVENTORY_UNKNOWN,
    INSTALLER_CHANGED,
}

enum class ExtensionInstallStop { CANCELLABLE, COMMITTING, INACTIVE }

class ExtensionInstallInvalidated(val reason: ExtensionInstallInvalidation) : RuntimeException(reason.name)

class ExtensionInstallBusy(packageName: String) :
    IllegalStateException("Extension $packageName is already queued or installing")

data class ExtensionInstallReservationState(
    val transactionId: Long,
    val artifact: ExtensionArtifact?,
    val progress: ExtensionInstallState? = null,
)

class ExtensionInstallArbiter {
    private val mutableReservations = MutableStateFlow(emptyMap<String, ExtensionInstallReservationState>())
    val reservations = mutableReservations.asStateFlow()
    private val lock = Any()
    private val active = mutableMapOf<String, Reservation>()
    private var nextId = 0L

    fun reserveSystemWindow(packageName: String): ExtensionSystemWindowLease? =
        reservePackage(packageName, ::ExtensionSystemWindowLease)

    fun releaseSystemWindow(lease: ExtensionSystemWindowLease): Boolean = releasePackage(lease)

    fun reserveRemoval(packageName: String): ExtensionRemovalLease? = reservePackage(
        packageName,
        ::ExtensionRemovalLease,
    )

    fun releaseRemoval(lease: ExtensionRemovalLease): Boolean = releasePackage(lease)

    private fun <T : ExtensionPackageLease> reservePackage(
        packageName: String,
        create: (Long, String) -> T,
    ): T? = synchronized(lock) {
        if (packageName in active) return@synchronized null
        create(++nextId, packageName).also { lease ->
            active[packageName] = Reservation(null, lease)
            mutableReservations.value = mutableReservations.value +
                (packageName to ExtensionInstallReservationState(lease.transactionId, null))
        }
    }

    private fun releasePackage(lease: ExtensionPackageLease): Boolean = synchronized(lock) {
        if (active[lease.packageName]?.packageLease !== lease) return@synchronized false
        active.remove(lease.packageName)
        mutableReservations.value = mutableReservations.value - lease.packageName
        true
    }

    fun progress(lease: ExtensionInstallLease, state: ExtensionInstallState) = synchronized(lock) {
        if (active[lease.artifact.packageName]?.lease !== lease) return@synchronized
        mutableReservations.value = mutableReservations.value + (
            lease.artifact.packageName to ExtensionInstallReservationState(lease.transactionId, lease.artifact, state)
            )
    }

    /** Reserve deletion atomically, but perform filesystem/platform work outside the arbiter lock. */
    fun <T> withRemoval(packageName: String, remove: () -> T): T? {
        val lease = reserveRemoval(packageName) ?: return null
        return try {
            remove()
        } finally {
            releaseRemoval(lease)
        }
    }

    fun reserve(
        artifact: ExtensionArtifact,
        eligibility: () -> ExtensionInstallInvalidation? = { null },
    ): ExtensionInstallLease? = synchronized(lock) {
        if (artifact.packageName in active) return@synchronized null
        ExtensionInstallLease(++nextId, artifact.copy(sources = artifact.sources.toList()), eligibility).also {
            active[artifact.packageName] = Reservation(it)
            mutableReservations.value =
                mutableReservations.value +
                (artifact.packageName to ExtensionInstallReservationState(it.transactionId, it.artifact))
        }
    }

    fun owns(lease: ExtensionInstallLease, artifact: ExtensionArtifact): Boolean = synchronized(lock) {
        active[artifact.packageName]?.lease === lease && lease.artifact == artifact
    }

    fun activate(lease: ExtensionInstallLease, artifact: ExtensionArtifact): Boolean = synchronized(lock) {
        val reservation = active[artifact.packageName]
        if (reservation?.lease !== lease || lease.artifact != artifact || reservation.started || reservation.stopped) {
            return@synchronized false
        }
        reservation.started = true
        true
    }

    /** Eligibility is a synchronous snapshot read; stop and irreversible commit share this lock. */
    fun enterCommit(lease: ExtensionInstallLease) = synchronized(lock) {
        val reservation = checkNotNull(active[lease.artifact.packageName])
        check(reservation.lease === lease && reservation.started && !reservation.committing)
        if (reservation.stopped) throw CancellationException("Installation stopped before commit")
        lease.eligibility()?.let { throw ExtensionInstallInvalidated(it) }
        reservation.committing = true
    }

    fun stop(lease: ExtensionInstallLease): ExtensionInstallStop = synchronized(lock) {
        val reservation = active[lease.artifact.packageName]
        when {
            reservation?.lease !== lease -> ExtensionInstallStop.INACTIVE
            reservation.committing -> ExtensionInstallStop.COMMITTING
            else -> {
                reservation.stopped = true
                ExtensionInstallStop.CANCELLABLE
            }
        }
    }

    fun release(lease: ExtensionInstallLease): Boolean = synchronized(lock) {
        if (active[lease.artifact.packageName]?.lease !== lease) return@synchronized false
        active.remove(lease.artifact.packageName)
        mutableReservations.value = mutableReservations.value - lease.artifact.packageName
        true
    }

    fun isBusy(packageName: String): Boolean = synchronized(lock) { packageName in active }

    private class Reservation(val lease: ExtensionInstallLease?, val packageLease: ExtensionPackageLease? = null) {
        var started = false
        var stopped = false
        var committing = false
    }
}
