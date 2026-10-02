package mihon.desktop.platform

enum class DeviceCondition(val preferenceKey: String) {
    WIFI("wifi"),
    UNMETERED("network_not_metered"),
    EXTERNAL_POWER("ac"),
}

enum class DeviceConditionState { SATISFIED, UNSATISFIED, UNKNOWN }

data class DeviceConditionsSnapshot(
    val wifi: DeviceConditionState = DeviceConditionState.UNKNOWN,
    val unmetered: DeviceConditionState = DeviceConditionState.UNKNOWN,
    val externalPower: DeviceConditionState = DeviceConditionState.UNKNOWN,
) {
    fun state(condition: DeviceCondition): DeviceConditionState = when (condition) {
        DeviceCondition.WIFI -> wifi
        DeviceCondition.UNMETERED -> unmetered
        DeviceCondition.EXTERNAL_POWER -> externalPower
    }

    fun blocking(selected: Set<DeviceCondition>): Set<DeviceCondition> =
        selected.filterTo(mutableSetOf()) { state(it) != DeviceConditionState.SATISFIED }
}

interface DesktopDeviceConditions {
    val supported: Set<DeviceCondition>
    fun query(): DeviceConditionsSnapshot
}

fun createDesktopDeviceConditions(
    windows: Boolean = com.sun.jna.Platform.isWindows(),
    native: () -> WindowsDeviceCalls = { WindowsNativeDeviceCalls() },
): DesktopDeviceConditions = if (windows) {
    WindowsDeviceConditions(native())
} else {
    object : DesktopDeviceConditions {
        override val supported = emptySet<DeviceCondition>()
        override fun query() = DeviceConditionsSnapshot()
    }
}
