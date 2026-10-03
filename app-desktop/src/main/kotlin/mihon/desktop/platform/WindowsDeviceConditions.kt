package mihon.desktop.platform

data class WindowsNetworkConnection(
    val guid: String,
    val type: Int,
    val physical: Boolean = true,
    val mediaConnected: Boolean = true,
)

interface WindowsDeviceCalls {
    fun activeConnections(): List<WindowsNetworkConnection>?
    fun wlanState(guid: String): Int?
    fun internetCost(): Int?
    fun acLineStatus(): Int?
}

class WindowsDeviceConditions(private val native: WindowsDeviceCalls) : DesktopDeviceConditions {
    override val supported = DeviceCondition.entries.toSet()
    override fun query(): DeviceConditionsSnapshot {
        val connections = runCatching { native.activeConnections() }.getOrNull()
        val active = connections?.singleOrNull()?.takeIf {
            it.physical && it.mediaConnected && (it.type == 6 || it.type == 71)
        }
        val wifi = when {
            active == null -> DeviceConditionState.UNKNOWN
            active.type == 6 -> DeviceConditionState.UNSATISFIED
            else -> when (runCatching { native.wlanState(active.guid) }.getOrNull()) {
                1 -> DeviceConditionState.SATISFIED
                4 -> DeviceConditionState.UNSATISFIED
                else -> DeviceConditionState.UNKNOWN
            }
        }
        // NULL GetCost is machine-wide Internet cost. Only a single physical active
        // connection can make that relevant here; competing or tunneled routes are unknown.
        val cost = if (active != null) runCatching { native.internetCost() }.getOrNull() else null
        val unmetered = when {
            cost == null || cost == 0 -> DeviceConditionState.UNKNOWN
            cost and 0xF0006 != 0 -> DeviceConditionState.UNSATISFIED
            cost == 1 -> DeviceConditionState.SATISFIED
            else -> DeviceConditionState.UNKNOWN
        }
        val power = when (runCatching { native.acLineStatus() }.getOrNull()) {
            0 -> DeviceConditionState.UNSATISFIED
            1 -> DeviceConditionState.SATISFIED
            else -> DeviceConditionState.UNKNOWN
        }
        return DeviceConditionsSnapshot(wifi, unmetered, power)
    }
}
