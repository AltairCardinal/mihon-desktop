package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/** Windows only. No library is loaded by the unsupported platform factory. */
class WindowsNativeDeviceCalls(
    private val wlan: () -> WindowsWlanApi = { com.sun.jna.Native.load("wlanapi", WindowsWlanApi::class.java) },
    private val powerStatus: (Pointer) -> Boolean = {
        NativeLibrary.getInstance("kernel32").getFunction("GetSystemPowerStatus", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(it)) != 0
    },
) : WindowsDeviceCalls {
    override fun activeConnections(): List<WindowsNetworkConnection>? {
        val library = NativeLibrary.getInstance("iphlpapi")
        val table = PointerByReference()
        val getTable = library.getFunction("GetIfTable2", Function.ALT_CONVENTION)
        if (getTable.invokeInt(arrayOf(table)) != 0) return null
        val pointer = table.value ?: return null
        try {
            return readWindowsActiveConnections(pointer)
        } finally {
            library.getFunction("FreeMibTable", Function.ALT_CONVENTION).invokeVoid(arrayOf(pointer))
        }
    }

    override fun wlanState(guid: String): Int? {
        val api = wlan()
        val handle = PointerByReference()
        if (api.WlanOpenHandle(2, null, IntByReference(), handle) != 0) return null
        val client = handle.value ?: return null
        try {
            val interfaces = PointerByReference()
            val code = api.WlanEnumInterfaces(client, null, interfaces)
            val list = interfaces.value
            try {
                if (code != 0 || list == null) return null
                val count = list.getInt(0)
                check(count in 0..4096)
                // WLAN_INTERFACE_INFO = GUID + WCHAR[256] + WLAN_INTERFACE_STATE.
                val matches = (0 until count).filter { index ->
                    Guid.GUID(list.share(8L + index * 532L)).toGuidString().equals(guid, ignoreCase = true)
                }
                if (matches.size != 1) return null
                val nativeGuid = Guid.GUID(list.share(8L + matches.single() * 532L))
                val result = PointerByReference()
                val size = IntByReference()
                val queried = api.WlanQueryInterface(client, nativeGuid, 6, null, size, result, null)
                try {
                    if (queried != 0 || size.value < 4) return null
                    return result.value?.getInt(0)
                } finally {
                    result.value?.let(api::WlanFreeMemory)
                }
            } finally {
                list?.let(api::WlanFreeMemory)
            }
        } finally {
            api.WlanCloseHandle(client, null)
        }
    }

    override fun internetCost(): Int? {
        val ole = Ole32.INSTANCE
        val initialized = ole.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).toInt()
        if (initialized < 0 && initialized != -2147417850) return null // RPC_E_CHANGED_MODE: existing apartment.
        try {
            val result = PointerByReference()
            val created = ole.CoCreateInstance(
                Guid.GUID("{DCB00C01-570F-4A9B-8D69-199FDBA5723B}"),
                null,
                1,
                Guid.GUID("{DCB00008-570F-4A9B-8D69-199FDBA5723B}"),
                result,
            )
            if (created.toInt() < 0 || result.value == null) return null
            val manager = NetworkCostManager(result.value)
            try {
                val cost = IntByReference()
                return if (manager.getCost(cost).toInt() >= 0) cost.value else null
            } finally {
                manager.Release()
            }
        } finally {
            if (initialized >= 0) ole.CoUninitialize()
        }
    }

    override fun acLineStatus(): Int? = Memory(12).use { status ->
        // BatteryFlag=128 is deliberately not used to infer power.
        if (!powerStatus(status)) null else status.getByte(0).toInt() and 0xFF
    }

    private class NetworkCostManager(pointer: Pointer) : Unknown(pointer) {
        fun getCost(value: IntByReference): WinNT.HRESULT =
            _invokeNativeObject(3, arrayOf(pointer, value, null), WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

@Structure.FieldOrder("count", "rows")
class InterfaceTableLayout : Structure() {
    @JvmField var count = 0

    @JvmField var rows: Array<IPHlpAPI.MIB_IF_ROW2> = arrayOf(IPHlpAPI.MIB_IF_ROW2())
    fun rowOffset(): Long = fieldOffset("rows").toLong()
}

// Native entry point names must match the Windows DLL exports.
@Suppress("ktlint:standard:function-naming")
interface WindowsWlanApi : StdCallLibrary {
    fun WlanOpenHandle(version: Int, reserved: Pointer?, negotiated: IntByReference, client: PointerByReference): Int
    fun WlanCloseHandle(client: Pointer, reserved: Pointer?): Int
    fun WlanEnumInterfaces(client: Pointer, reserved: Pointer?, interfaces: PointerByReference): Int
    fun WlanQueryInterface(
        client: Pointer,
        guid: Guid.GUID,
        opcode: Int,
        reserved: Pointer?,
        size: IntByReference,
        data: PointerByReference,
        type: IntByReference?,
    ): Int
    fun WlanFreeMemory(memory: Pointer)
}

internal fun readWindowsActiveConnections(pointer: Pointer): List<WindowsNetworkConnection> {
    val count = pointer.getInt(0)
    check(count in 0..4096)
    val row = IPHlpAPI.MIB_IF_ROW2()
    val size = row.size()
    return buildList {
        repeat(count) { index ->
            val offset = InterfaceTableLayout().rowOffset() + index.toLong() * size
            row.pointer.write(0, pointer.getByteArray(offset, size), 0, size)
            row.read()
            if (row.Type != 24 && row.OperStatus == 1 && row.MediaConnectState != 2) {
                add(
                    WindowsNetworkConnection(
                        row.InterfaceGuid.toGuidString(),
                        row.Type,
                        physical = row.InterfaceAndOperStatusFlags.toInt() and 1 != 0,
                        mediaConnected = row.MediaConnectState == 1,
                    ),
                )
            }
        }
    }
}
