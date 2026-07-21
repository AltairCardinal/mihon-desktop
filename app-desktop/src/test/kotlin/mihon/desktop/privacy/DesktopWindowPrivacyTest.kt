package mihon.desktop.privacy

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import mihon.desktop.platform.OperatingSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class DesktopWindowPrivacyTest {
    @Test
    fun `unsupported bridge and window not ready return honest structured results`() {
        val unsupported = DesktopWindowPrivacy(FakeBridge(unsupportedReasonSlug = "linux_capture_affinity_unavailable"))
        assertEquals(
            DesktopWindowPrivacyResult.Unsupported("linux_capture_affinity_unavailable"),
            unsupported.attach(null),
        )

        val notReady = DesktopWindowPrivacy(FakeBridge(handle = null))
        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("window_not_ready")),
            notReady.attach(null),
        )
    }

    @Test
    fun `exclude must be queried exactly while monitor affinity is only limited`() {
        val exactBridge = FakeBridge().apply { queries += NativeAffinityQuery.success(WDA_EXCLUDEFROMCAPTURE) }
        val exact = DesktopWindowPrivacy(exactBridge)
        assertEquals(DesktopWindowPrivacyResult.Supported, exact.attach(null))
        assertEquals(DesktopWindowPrivacyResult.Supported, exact.apply(protected = true))
        assertEquals(listOf(WDA_EXCLUDEFROMCAPTURE), exactBridge.setCalls)

        val limitedBridge = FakeBridge().apply {
            setResults += NativeAffinityCall.failed(5)
            setResults += NativeAffinityCall.success()
            queries += NativeAffinityQuery.success(WDA_MONITOR)
        }
        val limited = DesktopWindowPrivacy(limitedBridge)
        limited.attach(null)
        assertEquals(
            DesktopWindowPrivacyResult.Limited("windows_monitor_affinity_only"),
            limited.apply(protected = true),
        )
        assertEquals(listOf(WDA_EXCLUDEFROMCAPTURE, WDA_MONITOR), limitedBridge.setCalls)

        val mismatchedBridge = FakeBridge().apply { queries += NativeAffinityQuery.success(WDA_MONITOR) }
        val mismatched = DesktopWindowPrivacy(mismatchedBridge)
        mismatched.attach(null)
        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("windows_affinity_verification_failed")),
            mismatched.apply(protected = true),
        )
        assertEquals(listOf(WDA_EXCLUDEFROMCAPTURE, WDA_NONE), mismatchedBridge.setCalls)
    }

    @Test
    fun `monitor fallback failures return failed and best effort clear`() {
        val setFailure = FakeBridge().apply {
            setResults += NativeAffinityCall.failed(5)
            setResults += NativeAffinityCall.failed(50)
        }
        val setAdapter = DesktopWindowPrivacy(setFailure)
        setAdapter.attach(null)
        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("windows_monitor_set_affinity_failed", 50)),
            setAdapter.apply(protected = true),
        )
        assertEquals(listOf(WDA_EXCLUDEFROMCAPTURE, WDA_MONITOR, WDA_NONE), setFailure.setCalls)

        val fallbackQueryFailure = FakeBridge().apply {
            setResults += NativeAffinityCall.failed(5)
            setResults += NativeAffinityCall.success()
            queries += NativeAffinityQuery.failed(87)
        }
        val fallbackQueryAdapter = DesktopWindowPrivacy(fallbackQueryFailure)
        fallbackQueryAdapter.attach(null)
        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("windows_monitor_query_affinity_failed", 87)),
            fallbackQueryAdapter.apply(protected = true),
        )
        assertEquals(listOf(WDA_EXCLUDEFROMCAPTURE, WDA_MONITOR, WDA_NONE), fallbackQueryFailure.setCalls)

        val queryFailure = FakeBridge().apply { queries += NativeAffinityQuery.failed(87) }
        val queryAdapter = DesktopWindowPrivacy(queryFailure)
        queryAdapter.attach(null)
        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("windows_query_affinity_failed", 87)),
            queryAdapter.apply(protected = true),
        )
        assertEquals(listOf(WDA_EXCLUDEFROMCAPTURE, WDA_NONE), queryFailure.setCalls)
    }

    @Test
    fun `clear is queried and detach prevents further native handle use`() {
        val bridge = FakeBridge().apply { queries += NativeAffinityQuery.success(WDA_NONE) }
        val adapter = DesktopWindowPrivacy(bridge)
        adapter.attach(null)

        assertEquals(DesktopWindowPrivacyResult.Supported, adapter.clear())
        assertEquals(listOf(WDA_NONE), bridge.setCalls)
        adapter.detach()
        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("window_not_ready")),
            adapter.apply(protected = true),
        )
        assertEquals(listOf(WDA_NONE), bridge.setCalls)
    }

    @Test
    fun `query never treats an unknown affinity as supported`() {
        val bridge = FakeBridge().apply { queries += NativeAffinityQuery.success(0x7F) }
        val adapter = DesktopWindowPrivacy(bridge)
        adapter.attach(null)

        assertEquals(
            DesktopWindowPrivacyResult.Failed(DesktopWindowPrivacyError("windows_affinity_verification_failed")),
            adapter.query(),
        )
    }

    @Test
    @Tag("integration")
    fun `windows frame applies queries and clears native affinity`() {
        assumeTrue(OperatingSystem.detect() == OperatingSystem.WINDOWS)
        assumeFalse(GraphicsEnvironment.isHeadless())
        val frame = Frame("Mihon privacy integration")
        val adapter = DesktopWindowPrivacy()
        try {
            frame.setSize(320, 240)
            frame.isVisible = true
            assertEquals(DesktopWindowPrivacyResult.Supported, adapter.attach(frame))
            assertTrue(adapter.apply(protected = true) is DesktopWindowPrivacyResult.Supported)
            assertEquals(DesktopWindowPrivacyResult.Supported, adapter.query())
            assertEquals(DesktopWindowPrivacyResult.Supported, adapter.clear())
        } finally {
            runCatching { adapter.clear() }
            adapter.detach()
            frame.dispose()
        }
    }

    private class FakeBridge(
        override val unsupportedReasonSlug: String? = null,
        var handle: Long? = 42L,
    ) : DesktopWindowPrivacyBridge {
        val setCalls = mutableListOf<Int>()
        val setResults = ArrayDeque<NativeAffinityCall>()
        val queries = ArrayDeque<NativeAffinityQuery>()

        override fun windowHandle(window: Window?): Long? = handle

        override fun setAffinity(handle: Long, affinity: Int): NativeAffinityCall {
            setCalls += affinity
            return setResults.removeFirstOrNull() ?: NativeAffinityCall.success()
        }

        override fun queryAffinity(handle: Long): NativeAffinityQuery =
            queries.removeFirstOrNull() ?: NativeAffinityQuery.success(WDA_NONE)
    }
}
