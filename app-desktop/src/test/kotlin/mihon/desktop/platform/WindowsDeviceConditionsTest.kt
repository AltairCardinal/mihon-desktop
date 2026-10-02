package mihon.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WindowsDeviceConditionsTest {
    @Test
    fun `UP tunnel with unknown media is retained as routing ambiguity in actual MIB row bytes`() {
        val physical = com.sun.jna.platform.win32.IPHlpAPI.MIB_IF_ROW2().apply {
            InterfaceGuid = com.sun.jna.platform.win32.Guid.GUID(GUID)
            Type = 71
            OperStatus = 1
            MediaConnectState = 1
            InterfaceAndOperStatusFlags = 1
            write()
        }
        val tunnel = com.sun.jna.platform.win32.IPHlpAPI.MIB_IF_ROW2().apply {
            InterfaceGuid = com.sun.jna.platform.win32.Guid.GUID(OTHER)
            Type = 131
            OperStatus = 1
            MediaConnectState = 0
            write()
        }
        val offset = InterfaceTableLayout().rowOffset()
        com.sun.jna.Memory(offset + physical.size() * 2).use { table ->
            table.clear()
            table.setInt(0, 2)
            table.write(offset, physical.pointer.getByteArray(0, physical.size()), 0, physical.size())
            table.write(offset + physical.size(), tunnel.pointer.getByteArray(0, tunnel.size()), 0, tunnel.size())
            val decoded = readWindowsActiveConnections(table)
            assertEquals(2, decoded.size, "An active unknown-media VPN cannot disappear from route ambiguity")
            val calls = Calls().apply { connections = decoded }
            assertEquals(DeviceConditionState.UNKNOWN, WindowsDeviceConditions(calls).query().wifi)
        }
    }

    @Test
    fun `single virtual ethernet is not evidence of one physical Internet route`() {
        val calls = Calls().apply { connections = listOf(WindowsNetworkConnection(GUID, 6, physical = false)) }
        val result = WindowsDeviceConditions(calls).query()
        assertEquals(DeviceConditionState.UNKNOWN, result.wifi)
        assertEquals(DeviceConditionState.UNKNOWN, result.unmetered)
    }

    @Test
    fun `WLAN uses enumerated GUID and releases buffers and handle on query rejection`() {
        val api = WlanFixture()
        try {
            val calls = WindowsNativeDeviceCalls(wlan = { api })
            assertEquals(1, calls.wlanState(GUID))
            assertEquals(GUID, api.queriedGuid)
            assertEquals(6, api.opcode)
            assertEquals(2, api.freed.size)
            assertEquals(1, api.closed)
            api.freed.clear()
            api.queryError = 5
            assertEquals(null, calls.wlanState(GUID))
            assertEquals(2, api.freed.size)
            assertEquals(2, api.closed)
            api.freed.clear()
            assertEquals(null, calls.wlanState(OTHER))
            assertEquals(1, api.freed.size)
            assertEquals(3, api.closed)
            api.freed.clear()
            api.enumError = 5
            assertEquals(null, calls.wlanState(GUID))
            assertEquals(1, api.freed.size)
            assertEquals(4, api.closed)
        } finally {
            api.interfaces.close()
            api.state.close()
        }
    }

    @Test
    fun `unsupported platform never loads Windows DLL or applies retained Windows options`() {
        val port = createDesktopDeviceConditions(windows = false) { error("Windows DLL was loaded") }
        assertEquals(emptySet<DeviceCondition>(), port.supported)
        assertEquals(DeviceConditionsSnapshot(), port.query())
    }

    @Test
    fun `actual Windows native query returns bounded states without changing system configuration`() {
        val port = createDesktopDeviceConditions()
        val result = port.query()
        assertEquals(
            if (com.sun.jna.Platform.isWindows()) DeviceCondition.entries.toSet() else emptySet(),
            port.supported,
        )
        println("RI16 actual native conditions: $result; capabilities=${port.supported}")
        if (com.sun.jna.Platform.isWindows()) {
            val native = WindowsNativeDeviceCalls()
            val active = runCatching { native.activeConnections() }
            val cost = runCatching { native.internetCost() }
            println(
                "RI16 native interfaces: success=${active.isSuccess}, available=${active.getOrNull() != null}, " +
                    "types=${active.getOrNull()?.map { Triple(it.type, it.physical, it.mediaConnected) }}; " +
                    "costSuccess=${cost.isSuccess}, cost=${cost.getOrNull()}",
            )
            org.junit.jupiter.api.Assertions.assertTrue(active.isSuccess)
            org.junit.jupiter.api.Assertions.assertTrue(cost.isSuccess)
        }
    }

    @Test
    fun `native power bytes do not equate no battery with external power`() {
        for (ac in listOf(0, 1, 255)) {
            val calls = WindowsNativeDeviceCalls(powerStatus = { bytes ->
                bytes.setByte(0, ac.toByte())
                bytes.setByte(1, 128.toByte())
                true
            })
            assertEquals(ac, calls.acLineStatus())
        }
        assertEquals(null, WindowsNativeDeviceCalls(powerStatus = { false }).acLineStatus())
    }

    @Test
    fun `wifi queries the single active native GUID and cost unknown is not unrestricted`() {
        val calls = Calls()
        val port = WindowsDeviceConditions(calls)
        val result = port.query()
        assertEquals(DeviceConditionState.SATISFIED, result.wifi)
        assertEquals(listOf(GUID), calls.queried)
        assertEquals(DeviceConditionState.SATISFIED, result.unmetered)
        for (cost in listOf(0, 2, 4, 0x10000, 0x20000, 0x40000, 0x80000, 1 or 0x40000)) {
            calls.cost = cost
            val expected = if (cost == 0) DeviceConditionState.UNKNOWN else DeviceConditionState.UNSATISFIED
            assertEquals(expected, port.query().unmetered, "Native cost flags $cost")
        }
        for (cost in listOf(null, 0x100, 0x200)) {
            calls.cost = cost
            assertEquals(DeviceConditionState.UNKNOWN, port.query().unmetered)
        }
    }

    @Test
    fun `multiple active connections and tunnels never claim any wifi as actual routing`() {
        val calls = Calls()
        calls.connections = listOf(WindowsNetworkConnection(GUID, 71), WindowsNetworkConnection(OTHER, 6))
        val port = WindowsDeviceConditions(calls)
        assertEquals(DeviceConditionState.UNKNOWN, port.query().wifi)
        assertEquals(DeviceConditionState.UNKNOWN, port.query().unmetered)
        assertEquals(emptyList<String>(), calls.queried)
        calls.connections = listOf(WindowsNetworkConnection(GUID, 131))
        assertEquals(DeviceConditionState.UNKNOWN, port.query().wifi)
        assertEquals(DeviceConditionState.UNKNOWN, port.query().unmetered)
    }

    @Test
    fun `AC status is authoritative and failure or 255 remains unknown`() {
        val calls = Calls()
        val port = WindowsDeviceConditions(calls)
        for ((ac, expected) in listOf(
            0 to DeviceConditionState.UNSATISFIED,
            1 to DeviceConditionState.SATISFIED,
            255 to DeviceConditionState.UNKNOWN,
            null to DeviceConditionState.UNKNOWN,
        )) {
            calls.ac = ac
            assertEquals(expected, port.query().externalPower)
        }
    }

    private class Calls : WindowsDeviceCalls {
        var connections: List<WindowsNetworkConnection>? = listOf(WindowsNetworkConnection(GUID, 71))
        var cost: Int? = 1
        var ac: Int? = 1
        val queried = mutableListOf<String>()
        override fun activeConnections() = connections
        override fun wlanState(guid: String): Int {
            queried += guid
            return 1
        }
        override fun internetCost() = cost
        override fun acLineStatus() = ac
    }

    private class WlanFixture : WindowsWlanApi {
        val interfaces = com.sun.jna.Memory(540)
        val state = com.sun.jna.Memory(4)
        val client = com.sun.jna.Pointer(123)
        var queryError = 0
        var enumError = 0
        var queriedGuid: String? = null
        var opcode = 0
        var closed = 0
        val freed = mutableListOf<com.sun.jna.Pointer>()
        init {
            interfaces.clear()
            interfaces.setInt(0, 1)
            val guid = com.sun.jna.platform.win32.Guid.GUID(GUID)
            guid.write()
            interfaces.write(8, guid.pointer.getByteArray(0, 16), 0, 16)
            state.setInt(0, 1)
        }
        override fun WlanOpenHandle(
            version: Int,
            reserved: com.sun.jna.Pointer?,
            negotiated: com.sun.jna.ptr.IntByReference,
            client: com.sun.jna.ptr.PointerByReference,
        ): Int {
            client.value = this.client
            return 0
        }
        override fun WlanCloseHandle(
            client: com.sun.jna.Pointer,
            reserved: com.sun.jna.Pointer?,
        ): Int {
            closed++
            return 0
        }
        override fun WlanEnumInterfaces(
            client: com.sun.jna.Pointer,
            reserved: com.sun.jna.Pointer?,
            interfaces: com.sun.jna.ptr.PointerByReference,
        ): Int {
            interfaces.value = this.interfaces
            return enumError
        }
        override fun WlanQueryInterface(
            client: com.sun.jna.Pointer,
            guid: com.sun.jna.platform.win32.Guid.GUID,
            opcode: Int,
            reserved: com.sun.jna.Pointer?,
            size: com.sun.jna.ptr.IntByReference,
            data: com.sun.jna.ptr.PointerByReference,
            type: com.sun.jna.ptr.IntByReference?,
        ): Int {
            queriedGuid = guid.toGuidString()
            this.opcode = opcode
            size.value = 4
            data.value = state
            return queryError
        }
        override fun WlanFreeMemory(memory: com.sun.jna.Pointer) {
            freed += memory
        }
    }

    companion object {
        const val GUID = "{00112233-4455-6677-8899-AABBCCDDEEFF}"
        const val OTHER = "{11223344-5566-7788-99AA-BBCCDDEEFF00}"
    }
}
