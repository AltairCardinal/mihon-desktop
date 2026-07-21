package mihon.desktop.privacy

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.BOOL
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import java.awt.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.desktop.platform.OperatingSystem
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.security.SecureScreenPolicy

internal const val WDA_NONE = 0x0
internal const val WDA_MONITOR = 0x1
internal const val WDA_EXCLUDEFROMCAPTURE = 0x11

sealed interface DesktopWindowPrivacyResult {
    data object Supported : DesktopWindowPrivacyResult
    data class Limited(val reasonSlug: String) : DesktopWindowPrivacyResult
    data class Unsupported(val reasonSlug: String) : DesktopWindowPrivacyResult
    data class Failed(val error: DesktopWindowPrivacyError) : DesktopWindowPrivacyResult
}

data class DesktopWindowPrivacyError(
    val reasonSlug: String,
    val nativeCode: Int? = null,
)

data class NativeAffinityCall(val succeeded: Boolean, val nativeErrorCode: Int? = null) {
    companion object {
        fun success() = NativeAffinityCall(true)
        fun failed(nativeErrorCode: Int?) = NativeAffinityCall(false, nativeErrorCode)
    }
}

data class NativeAffinityQuery(
    val succeeded: Boolean,
    val affinity: Int? = null,
    val nativeErrorCode: Int? = null,
) {
    companion object {
        fun success(affinity: Int) = NativeAffinityQuery(true, affinity)
        fun failed(nativeErrorCode: Int?) = NativeAffinityQuery(false, nativeErrorCode = nativeErrorCode)
    }
}

interface DesktopWindowPrivacyBridge {
    val unsupportedReasonSlug: String?
    fun windowHandle(window: Window?): Long?
    fun setAffinity(handle: Long, affinity: Int): NativeAffinityCall
    fun queryAffinity(handle: Long): NativeAffinityQuery
    fun detached(handle: Long) = Unit
}

class DesktopWindowPrivacy(
    private val bridge: DesktopWindowPrivacyBridge = defaultWindowPrivacyBridge(),
) {
    private var handle: Long? = null

    @Synchronized
    fun attach(window: Window?): DesktopWindowPrivacyResult {
        unsupported()?.let { return it }
        val resolved = try {
            bridge.windowHandle(window)
        } catch (_: Throwable) {
            null
        } ?: return failed("window_not_ready")
        handle = resolved
        return DesktopWindowPrivacyResult.Supported
    }

    @Synchronized
    fun apply(protected: Boolean): DesktopWindowPrivacyResult {
        unsupported()?.let { return it }
        val currentHandle = handle ?: return failed("window_not_ready")
        val target = if (protected) WDA_EXCLUDEFROMCAPTURE else WDA_NONE
        val set = runCatching { bridge.setAffinity(currentHandle, target) }
            .getOrElse { NativeAffinityCall.failed(null) }
        if (!set.succeeded) {
            if (protected) return applyMonitorFallback(currentHandle)
            bestEffortClear(currentHandle)
            return failed("windows_set_affinity_failed", set.nativeErrorCode)
        }
        val query = runCatching { bridge.queryAffinity(currentHandle) }
            .getOrElse { NativeAffinityQuery.failed(null) }
        if (!query.succeeded) {
            bestEffortClear(currentHandle)
            return failed("windows_query_affinity_failed", query.nativeErrorCode)
        }
        return if (protected) {
            protectedResult(query.affinity, currentHandle)
        } else {
            clearedResult(query.affinity, currentHandle)
        }
    }

    @Synchronized
    fun clear(): DesktopWindowPrivacyResult = apply(protected = false)

    @Synchronized
    fun query(): DesktopWindowPrivacyResult {
        unsupported()?.let { return it }
        val currentHandle = handle ?: return failed("window_not_ready")
        val query = runCatching { bridge.queryAffinity(currentHandle) }
            .getOrElse { NativeAffinityQuery.failed(null) }
        if (!query.succeeded) return failed("windows_query_affinity_failed", query.nativeErrorCode)
        return when (query.affinity) {
            WDA_NONE, WDA_EXCLUDEFROMCAPTURE -> DesktopWindowPrivacyResult.Supported
            WDA_MONITOR -> DesktopWindowPrivacyResult.Limited("windows_monitor_affinity_only")
            else -> failed("windows_affinity_verification_failed")
        }
    }

    @Synchronized
    fun detach() {
        handle?.let(bridge::detached)
        handle = null
    }

    private fun protectedResult(affinity: Int?, currentHandle: Long) = when (affinity) {
        WDA_EXCLUDEFROMCAPTURE -> DesktopWindowPrivacyResult.Supported
        else -> {
            bestEffortClear(currentHandle)
            failed("windows_affinity_verification_failed")
        }
    }

    private fun applyMonitorFallback(currentHandle: Long): DesktopWindowPrivacyResult {
        val set = runCatching { bridge.setAffinity(currentHandle, WDA_MONITOR) }
            .getOrElse { NativeAffinityCall.failed(null) }
        if (!set.succeeded) {
            bestEffortClear(currentHandle)
            return failed("windows_monitor_set_affinity_failed", set.nativeErrorCode)
        }
        val query = runCatching { bridge.queryAffinity(currentHandle) }
            .getOrElse { NativeAffinityQuery.failed(null) }
        if (!query.succeeded) {
            bestEffortClear(currentHandle)
            return failed("windows_monitor_query_affinity_failed", query.nativeErrorCode)
        }
        if (query.affinity == WDA_MONITOR) {
            return DesktopWindowPrivacyResult.Limited("windows_monitor_affinity_only")
        }
        bestEffortClear(currentHandle)
        return failed("windows_monitor_affinity_verification_failed")
    }

    private fun clearedResult(affinity: Int?, currentHandle: Long) =
        if (affinity == WDA_NONE) {
            DesktopWindowPrivacyResult.Supported
        } else {
            bestEffortClear(currentHandle)
            failed("windows_clear_affinity_failed")
        }

    private fun bestEffortClear(currentHandle: Long) {
        runCatching { bridge.setAffinity(currentHandle, WDA_NONE) }
    }

    private fun unsupported() = bridge.unsupportedReasonSlug?.let(DesktopWindowPrivacyResult::Unsupported)

    private fun failed(reasonSlug: String, nativeCode: Int? = null) =
        DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError(reasonSlug, nativeCode))
}

data class DesktopWindowPrivacyState(
    val mode: SecurityPreferences.SecureScreenMode,
    val result: DesktopWindowPrivacyResult,
    val shouldProtect: Boolean,
    val appliedProtected: Boolean?,
)

class DesktopWindowPrivacyController(
    internal val securityPreferences: SecurityPreferences,
    internal val appPreferences: DesktopAppPreferences,
    internal val windowPrivacy: DesktopWindowPrivacy,
) {
    private var lastAppliedProtection: Boolean? = null
    private val initialMode = securityPreferences.secureScreen().get()
    private val initialShouldProtect = SecureScreenPolicy.isProtected(initialMode, appPreferences.incognitoMode.get())
    private val mutableState = MutableStateFlow(
        DesktopWindowPrivacyState(
            mode = initialMode,
            result = windowPrivacy.query(),
            shouldProtect = initialShouldProtect,
            appliedProtected = null,
        ),
    )
    val state = mutableState.asStateFlow()

    @Synchronized
    fun attach(window: Window?): DesktopWindowPrivacyResult {
        lastAppliedProtection = null
        val mode = securityPreferences.secureScreen().get()
        val shouldProtect = SecureScreenPolicy.isProtected(mode, appPreferences.incognitoMode.get())
        return publish(mode, windowPrivacy.attach(window), shouldProtect, appliedProtected = null)
    }

    @Synchronized
    fun applyPolicy(
        mode: SecurityPreferences.SecureScreenMode,
        incognito: Boolean,
    ): DesktopWindowPrivacyResult = applyProtection(mode, SecureScreenPolicy.isProtected(mode, incognito))

    @Synchronized
    internal fun applyProtection(
        mode: SecurityPreferences.SecureScreenMode,
        shouldProtect: Boolean,
    ): DesktopWindowPrivacyResult {
        val previous = mutableState.value.result
        if (lastAppliedProtection == shouldProtect && previous.isApplied()) {
            return publish(mode, previous, shouldProtect, appliedProtected = shouldProtect)
        }
        val result = windowPrivacy.apply(shouldProtect)
        lastAppliedProtection = shouldProtect.takeIf { result.isApplied() }
        return publish(
            mode = mode,
            result = result,
            shouldProtect = shouldProtect,
            appliedProtected = shouldProtect.takeIf { result.isApplied() },
        )
    }

    @Synchronized
    fun changeMode(mode: SecurityPreferences.SecureScreenMode): DesktopWindowPrivacyResult {
        val preference = securityPreferences.secureScreen()
        val oldMode = preference.get()
        val incognito = appPreferences.incognitoMode.get()
        val result = applyPolicy(mode, incognito)
        if (!result.isApplied()) {
            return publish(
                mode = oldMode,
                result = result,
                shouldProtect = SecureScreenPolicy.isProtected(oldMode, incognito),
                appliedProtected = null,
            )
        }
        return try {
            preference.set(mode)
            publish(
                mode = mode,
                result = result,
                shouldProtect = SecureScreenPolicy.isProtected(mode, incognito),
                appliedProtected = SecureScreenPolicy.isProtected(mode, incognito),
            )
        } catch (_: RuntimeException) {
            lastAppliedProtection = null
            val rollback = applyPolicy(oldMode, incognito)
            val oldShouldProtect = SecureScreenPolicy.isProtected(oldMode, incognito)
            publish(
                mode = oldMode,
                result = failedResult("secure_screen_preference_write_failed"),
                shouldProtect = oldShouldProtect,
                appliedProtected = oldShouldProtect.takeIf { rollback.isApplied() },
            )
        }
    }

    @Synchronized
    fun clearAndDetach() {
        val mode = securityPreferences.secureScreen().get()
        val result = windowPrivacy.clear()
        publish(
            mode = mode,
            result = result,
            shouldProtect = SecureScreenPolicy.isProtected(mode, appPreferences.incognitoMode.get()),
            appliedProtected = false.takeIf { result.isApplied() },
        )
        windowPrivacy.detach()
        lastAppliedProtection = null
    }

    private fun publish(
        mode: SecurityPreferences.SecureScreenMode,
        result: DesktopWindowPrivacyResult,
        shouldProtect: Boolean,
        appliedProtected: Boolean?,
    ): DesktopWindowPrivacyResult {
        mutableState.value = DesktopWindowPrivacyState(mode, result, shouldProtect, appliedProtected)
        return result
    }
}

private fun DesktopWindowPrivacyResult.isApplied() =
    this is DesktopWindowPrivacyResult.Supported || this is DesktopWindowPrivacyResult.Limited

private fun failedResult(reasonSlug: String) =
    DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError(reasonSlug))

private class UnsupportedWindowPrivacyBridge(
    override val unsupportedReasonSlug: String,
) : DesktopWindowPrivacyBridge {
    override fun windowHandle(window: Window?): Long? = null
    override fun setAffinity(handle: Long, affinity: Int) = NativeAffinityCall.failed(null)
    override fun queryAffinity(handle: Long) = NativeAffinityQuery.failed(null)
}

private class WindowsWindowPrivacyBridge : DesktopWindowPrivacyBridge {
    override val unsupportedReasonSlug: String? = null

    override fun windowHandle(window: Window?): Long? {
        if (window == null || !window.isDisplayable) return null
        return Native.getComponentPointer(window)?.let(Pointer::nativeValue)?.takeIf { it != 0L }
    }

    override fun setAffinity(handle: Long, affinity: Int): NativeAffinityCall = try {
        if (WindowAffinityApi.instance.SetWindowDisplayAffinity(HWND(Pointer(handle)), affinity).booleanValue()) {
            NativeAffinityCall.success()
        } else {
            NativeAffinityCall.failed(Native.getLastError())
        }
    } catch (_: Throwable) {
        NativeAffinityCall.failed(Native.getLastError())
    }

    override fun queryAffinity(handle: Long): NativeAffinityQuery = try {
        val affinity = IntByReference()
        if (WindowAffinityApi.instance.GetWindowDisplayAffinity(HWND(Pointer(handle)), affinity).booleanValue()) {
            NativeAffinityQuery.success(affinity.value)
        } else {
            NativeAffinityQuery.failed(Native.getLastError())
        }
    } catch (_: Throwable) {
        NativeAffinityQuery.failed(Native.getLastError())
    }
}

private interface WindowAffinityApi : StdCallLibrary {
    fun SetWindowDisplayAffinity(window: HWND, affinity: Int): BOOL
    fun GetWindowDisplayAffinity(window: HWND, affinity: IntByReference): BOOL

    companion object {
        val instance: WindowAffinityApi by lazy { Native.load("user32", WindowAffinityApi::class.java) }
    }
}

private fun defaultWindowPrivacyBridge(): DesktopWindowPrivacyBridge = when (OperatingSystem.detect()) {
    OperatingSystem.WINDOWS -> WindowsWindowPrivacyBridge()
    OperatingSystem.MACOS -> UnsupportedWindowPrivacyBridge("macos_capture_affinity_unavailable")
    OperatingSystem.LINUX -> UnsupportedWindowPrivacyBridge("linux_capture_affinity_unavailable")
    OperatingSystem.UNSUPPORTED -> UnsupportedWindowPrivacyBridge("platform_capture_affinity_unavailable")
}
