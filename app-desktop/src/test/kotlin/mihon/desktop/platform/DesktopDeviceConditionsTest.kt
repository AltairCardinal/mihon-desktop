package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopDeviceConditionsTest {
    @Test
    fun `only selected conditions block and unknown never means satisfied`() {
        for (condition in DeviceCondition.entries) {
            for (state in DeviceConditionState.entries) {
                val snapshot = when (condition) {
                    DeviceCondition.WIFI -> DeviceConditionsSnapshot(wifi = state)
                    DeviceCondition.UNMETERED -> DeviceConditionsSnapshot(unmetered = state)
                    DeviceCondition.EXTERNAL_POWER -> DeviceConditionsSnapshot(externalPower = state)
                }
                assertEquals(emptySet<DeviceCondition>(), snapshot.blocking(emptySet()))
                val expected = if (state == DeviceConditionState.SATISFIED) emptySet() else setOf(condition)
                assertEquals(expected, snapshot.blocking(setOf(condition)), "$condition / $state")
            }
        }
    }
}
