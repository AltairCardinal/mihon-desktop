package mihon.desktop.di

import app.cash.sqldelight.db.SqlDriver
import mihon.desktop.platform.DesktopNetworkHelper
import mihon.desktop.platform.DesktopPlatformPaths
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService
import mihon.desktop.domain.SaveSourceMangaForDetails
import tachiyomi.domain.source.service.SourceMangaSearchService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Stage 23.0 — DI layer split contract tests.
 *
 * These are compile-time contract tests: if any of the five layer-init
 * functions doesn't exist with the expected signature, this file will
 * not compile and the build fails.
 *
 * Acceptance criteria from ROADMAP §23:
 * - Each sub-function < 80 lines
 * - Total entry function < 20 lines
 * - Tests can call initDataLayer() + initDomainLayer() without initialising
 *   the network, extension, or UI layers
 */
class DILayerSplitContractTest {

    /**
     * Compile-time contract: all five layer-init functions must exist with the
     * correct signatures. If any is missing or renamed, this test fails to compile.
     */
    @Test
    fun `all layer-init functions have correct signatures`() {
        // These :: references are resolved at compile time.
        // The test body will never execute if compilation fails.
        val configFn: (File) -> DesktopPreferenceStore = ::initConfigLayer
        val networkFn: (DesktopPlatformPaths, DesktopPreferenceStore) -> DesktopNetworkHelper = ::initNetworkLayer
        val dataFn: (File) -> DatabaseHandler = ::initDataLayer
        val extFn: (DesktopPlatformPaths, DesktopNetworkHelper, DatabaseHandler) -> Unit = ::initExtensionLayer
        val domainFn: (DatabaseHandler) -> Unit = ::initDomainLayer
        val uiFn: (DesktopPlatformPaths, DesktopPreferenceStore, DesktopNetworkHelper, DatabaseHandler) -> Unit =
            ::initUILayer

        assertNotNull(configFn)
        assertNotNull(networkFn)
        assertNotNull(dataFn)
        assertNotNull(extFn)
        assertNotNull(domainFn)
        assertNotNull(uiFn)
    }

    /**
     * Acceptance criterion: data + domain layers can be initialised without
     * network, extension, or UI layers (useful for unit tests that only need
     * DB-backed use cases).
     */
    @Test
    fun `initDataLayer and initDomainLayer are callable without network or UI layers`(
        @TempDir tempDir: File,
    ) {
        val handler = initDataLayer(tempDir)
        assertNotNull(handler)
        initDomainLayer(handler)
        // No exception means domain use cases were registered without touching network/UI
    }

    @Test
    fun `manga detail category dependencies resolve after data and domain init`(
        @TempDir tempDir: File,
    ) {
        val handler = initDataLayer(tempDir)
        initDomainLayer(handler)

        assertNotNull(Injekt.get<CategoryRepository>())
        assertNotNull(Injekt.get<SetMangaCategories>())
        assertNotNull(Injekt.get<CreatorRepository>())
        assertNotNull(Injekt.get<CreatorDiscoveryService>())
        assertNotNull(Injekt.get<SourceMangaSearchService>())
        assertNotNull(Injekt.get<SaveSourceMangaForDetails>())
    }

    /**
     * Acceptance criterion: total entry function is ≤ 20 lines.
     * Verified by reading the source; this test documents the invariant.
     */
    @Test
    fun `initDesktopDI entry point still exists`() {
        // Just verify it's callable without arguments — real call would touch disk/network
        val fn: () -> Unit = ::initDesktopDI
        assertNotNull(fn)
    }
}
