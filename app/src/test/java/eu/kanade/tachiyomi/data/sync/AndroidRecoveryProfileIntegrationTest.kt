package eu.kanade.tachiyomi.data.sync

import android.app.Application
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.DomainModule
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import mihon.data.sync.security.EncryptedFileSyncSecureStore
import mihon.data.sync.security.SyncRecordCipher
import mihon.domain.sync.security.SyncSecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class AndroidRecoveryProfileIntegrationTest {
    private lateinit var previous: InjektScope
    private lateinit var raw: Application

    @Before
    fun setup() {
        previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        raw = RuntimeEnvironment.getApplication()
    }

    @After
    fun cleanup() {
        Injekt = previous
    }

    @Test
    fun `production application freezes isolated files cache no backup and preference roots before DI`() {
        val originalFiles = raw.filesDir.canonicalFile
        val originalNoBackup = raw.noBackupFilesDir.canonicalFile
        val originalCache = raw.cacheDir.canonicalFile
        val originalDatabase = raw.getDatabasePath("tachiyomi.db").canonicalFile
        val originalPreference = raw.getSharedPreferences("profile-test", 0)
        originalPreference.edit().putString("identity", "original").commit()
        val id = AndroidRecoveryProfile.createAndSelect(raw)
        val app = attachedProductionApp()
        assertNotEquals(originalFiles, app.filesDir.canonicalFile)
        assertNotEquals(originalNoBackup, app.noBackupFilesDir.canonicalFile)
        assertNotEquals(originalCache, app.cacheDir.canonicalFile)
        assertNotEquals(originalDatabase, app.getDatabasePath("tachiyomi.db").canonicalFile)
        assertTrue(app.filesDir.path.contains(id))
        assertSame(app, app.applicationContext)
        assertEquals(null, app.getSharedPreferences("profile-test", 0).getString("identity", null))
        app.getSharedPreferences("profile-test", 0).edit().putString("identity", "new").commit()
        assertEquals("original", originalPreference.getString("identity", null))
        Injekt.importModule(PreferenceModule(app))
        Injekt.get<PreferenceStore>().getString("profile-test-key", "new-default").set("new-scope")
        assertEquals("new-scope", Injekt.get<PreferenceStore>().getString("profile-test-key", "").get())
    }

    @Test
    fun `SQLite profile DI`() {
        val original = raw.getDatabasePath("tachiyomi.db")
        original.parentFile!!.mkdirs()
        JdbcSqliteDriver("jdbc:sqlite:${original.absolutePath}").use {
            Database.Schema.create(it)
            it.execute(null, "PRAGMA user_version = ${Database.Schema.version}", 0)
            it.execute(null, "CREATE TABLE old_profile_marker(value TEXT NOT NULL)", 0)
            it.execute(null, "INSERT INTO old_profile_marker VALUES ('preserved')", 0)
        }
        val originalBytes = original.readBytes()
        AndroidRecoveryProfile.createAndSelect(raw)
        val app = attachedProductionApp()
        var nativeFactoryCalls = 0
        if (!BuildConfig.DEBUG) {
            // Requery's Android-only sqlite3x cannot load in the host JVM. Adapt only its helper
            // creation; the actual release DI factory selection and production callback remain guarded.
            mockkConstructor(RequerySQLiteOpenHelperFactory::class)
            every { anyConstructed<RequerySQLiteOpenHelperFactory>().create(any()) } answers {
                assertTrue(self is RequerySQLiteOpenHelperFactory)
                nativeFactoryCalls++
                FrameworkSQLiteOpenHelperFactory().create(firstArg())
            }
        }
        try {
            Injekt.importModule(PreferenceModule(app))
            Injekt.importModule(AppModule(app))
            Injekt.get<SqlDriver>().use { driver ->
                val count = driver.executeQuery(
                    null,
                    "SELECT count(*) FROM sqlite_master WHERE name = 'old_profile_marker'",
                    mapper = { cursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else -1L)
                    },
                    parameters = 0,
                ).value
                assertEquals(0L, count)
            }
            if (!BuildConfig.DEBUG) {
                assertEquals(1, nativeFactoryCalls)
                verify(exactly = 1) {
                    anyConstructed<RequerySQLiteOpenHelperFactory>().create(
                        match { it.context === app && it.name == "tachiyomi.db" },
                    )
                }
            }
            assertTrue(originalBytes.contentEquals(original.readBytes()))
            assertTrue(app.getDatabasePath("tachiyomi.db").isFile)
        } finally {
            if (!BuildConfig.DEBUG) unmockkConstructor(RequerySQLiteOpenHelperFactory::class)
        }
    }

    @Test
    fun `production secure store application context cannot read original no backup records`() = runBlocking {
        val originalDirectory = File(raw.noBackupFilesDir, "sync-secrets")
        val fixtureCipher = object : SyncRecordCipher {
            override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = ByteArray(32) + plaintext
            override fun decrypt(
                ciphertext: ByteArray,
                aad: ByteArray,
            ): ByteArray = ciphertext.copyOfRange(32, ciphertext.size)
        }
        val originalStore = EncryptedFileSyncSecureStore(originalDirectory, fixtureCipher)
        assertTrue(originalStore.compareAndSet("origin-material", null, "original-kept"))
        val originalFiles = originalDirectory.listFiles()!!.filter { it.extension == "record" }
            .associate { it.name to it.readBytes() }
        AndroidRecoveryProfile.createAndSelect(raw)
        val app = attachedProductionApp()
        Injekt.importModule(PreferenceModule(app))
        Injekt.importModule(AppModule(app))
        Injekt.importModule(DomainModule())
        assertEquals(null, Injekt.get<SyncSecureStore>().read("origin-material"))
        assertEquals("original-kept", originalStore.read("origin-material"))
        originalFiles.forEach { (name, bytes) ->
            assertTrue(bytes.contentEquals(File(originalDirectory, name).readBytes()))
        }
    }

    @Test
    fun `storage root DI`() {
        AndroidRecoveryProfile.createAndSelect(raw)
        val app = attachedProductionApp()
        Injekt.importModule(PreferenceModule(app))
        Injekt.importModule(AppModule(app))
        val storage = Injekt.get<tachiyomi.core.common.storage.AndroidStorageFolderProvider>().directory()
        assertTrue(storage.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
    }

    @Test
    fun `invalid profile routes startup to safety before any database binding`() {
        raw.getSharedPreferences(AndroidRecoveryProfile.CONTROL_PREFERENCES, 0).edit()
            .putString("selected", "invalid-profile").commit()
        val app = attachedProductionApp()
        assertTrue(app.profileStartupIssue)
        app.onCreate()
        val launch = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(eu.kanade.tachiyomi.crash.CrashActivity::class.java.name, launch.component?.className)
        assertTrue(runCatching { Injekt.get<tachiyomi.data.DatabaseHandler>() }.isFailure)
    }

    @Test
    fun `error handler remains raw when selected profile is damaged`() {
        raw.getSharedPreferences(AndroidRecoveryProfile.CONTROL_PREFERENCES, 0).edit()
            .putString("selected", "invalid-profile").commit()
        assertSame(raw, AndroidRecoveryProfile.wrap(raw, Application(), "app:error_handler"))
    }

    @Test
    fun `invalid marker is preserved and cannot enter business startup`() {
        val id = AndroidRecoveryProfile.createAndSelect(raw)
        val marker = File(AndroidRecoveryProfile.root(raw, id), ".mihon-recovery-profile")
        marker.writeText("damaged-marker", Charsets.UTF_8)
        val app = attachedProductionApp()
        assertTrue(app.profileStartupIssue)
        assertEquals("damaged-marker", marker.readText(Charsets.UTF_8))
    }

    @Test
    fun `profile switch stops old process before committing a new selection`() = runBlocking {
        val control = raw.getSharedPreferences(AndroidRecoveryProfile.CONTROL_PREFERENCES, 0)
        val order = mutableListOf<String>()
        assertTrue(
            AndroidRecoverySwitch.createAndRestart(
                raw,
                stopOldProcess = {
                    assertEquals(null, control.getString("selected", null))
                    order += "stop"
                    true
                },
                startMainProcess = {
                    assertTrue(control.getString("selected", null) != null)
                    order += "start"
                },
            ),
        )
        assertEquals(listOf("stop", "start"), order)
        val selected = control.getString("selected", null)
        assertEquals(
            false,
            AndroidRecoverySwitch.createAndRestart(
                raw,
                stopOldProcess = { false },
                startMainProcess = { error("must not start") },
            ),
        )
        assertEquals(selected, control.getString("selected", null))
    }

    @Test
    fun `safe activity opens without business DI`() {
        val activity = org.robolectric.Robolectric.buildActivity(
            eu.kanade.tachiyomi.crash.CrashActivity::class.java,
            android.content.Intent().putExtra("sync-safe-recovery", true),
        ).setup()
        try {
            assertTrue(activity.get().findViewById<android.view.ViewGroup>(android.R.id.content).childCount > 0)
            assertTrue(runCatching { Injekt.get<tachiyomi.data.DatabaseHandler>() }.isFailure)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `new recovery instance keeps the actual preceding instance`() {
        val first = AndroidRecoveryProfile.createAndSelect(raw)
        val database = File(AndroidRecoveryProfile.root(raw, first), "databases/tachiyomi.db")
            .apply {
                parentFile!!.mkdirs()
                writeText("first recovery bytes", Charsets.UTF_8)
            }
        val second = AndroidRecoveryProfile.createAndSelect(raw)
        assertEquals(first, AndroidRecoveryProfile.previousSelection(raw, second))
        assertEquals(
            database.canonicalPath,
            File(AndroidRecoveryProfile.root(raw, second), "origin.txt").readText(Charsets.UTF_8),
        )
        assertEquals("first recovery bytes", database.readText(Charsets.UTF_8))
    }

    @Test
    fun `startup configures isolated WebView before production DI`() {
        AndroidRecoveryProfile.createAndSelect(raw)
        val app = attachedProductionApp()
        val called = mutableListOf<String>()
        val boundary = IllegalStateException("stop before business DI")
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        io.mockk.mockkStatic(android.webkit.WebView::class)
        try {
            io.mockk.every { android.webkit.WebView.setDataDirectorySuffix(any()) } answers {
                called += firstArg<String>()
                throw boundary
            }
            val failure = runCatching { app.onCreate() }.exceptionOrNull()
            assertEquals(listOf(AndroidRecoveryProfile.webViewSuffix(app, app.packageName)), called)
            assertSame(boundary, failure)
            assertTrue(runCatching { Injekt.get<tachiyomi.data.DatabaseHandler>() }.isFailure)
        } finally {
            io.mockk.unmockkStatic(android.webkit.WebView::class)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }

    @Test
    fun `web view storage follows the frozen recovery instance`() {
        AndroidRecoveryProfile.createAndSelect(raw)
        val first = attachedProductionApp()
        val firstSuffix = AndroidRecoveryProfile.webViewSuffix(first, first.packageName)
        assertTrue(firstSuffix != null)
        AndroidRecoveryProfile.createAndSelect(raw)
        val second = attachedProductionApp()
        assertNotEquals(firstSuffix, AndroidRecoveryProfile.webViewSuffix(second, second.packageName))
        assertEquals(firstSuffix, AndroidRecoveryProfile.webViewSuffix(first, first.packageName))
        assertEquals(false, AndroidRecoveryProfile.canUseInternalWebView(first, 27))
        val suffixes = mutableListOf<String>()
        AndroidRecoveryProfile.configureWebView(first, first.packageName, 35, suffixes::add)
        assertEquals(listOf(firstSuffix), suffixes)
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("profile.test").build()
        val originalCookies = eu.kanade.tachiyomi.network.AndroidCookieJar()
        originalCookies.saveFromResponse(
            url,
            listOf(
                okhttp3.Cookie.Builder()
                    .domain("profile.test").name("original").value("preserved").build(),
            ),
        )
        try {
            AndroidRecoveryProfile.configureWebView(first, first.packageName, 27, suffixes::add)
            assertEquals(emptyList<okhttp3.Cookie>(), eu.kanade.tachiyomi.network.AndroidCookieJar().get(url))
            assertEquals("preserved", originalCookies.get(url).single().value)
        } finally {
            eu.kanade.tachiyomi.util.system.WebViewUtil.storageAccessible = true
        }
    }

    @Test
    fun `native recovery dependencies resolve from real platform modules`() {
        AndroidRecoveryProfile.createAndSelect(raw)
        val app = attachedProductionApp()
        Injekt.importModule(PreferenceModule(app))
        Injekt.importModule(AppModule(app))
        Injekt.importModule(DomainModule())
        assertTrue(Injekt.get<eu.kanade.tachiyomi.extension.ExtensionManager>() != null)
        assertTrue(Injekt.get<eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel>() != null)
        assertTrue(Injekt.get<tachiyomi.domain.manga.repository.MangaRepository>() != null)
        assertTrue(Injekt.get<tachiyomi.domain.source.service.SourceManager>() != null)
        assertTrue(Injekt.get<tachiyomi.domain.manga.interactor.NetworkToLocalManga>() != null)
        Injekt.get<mihon.data.sync.runtime.SyncRuntime>()
        assertEquals(
            AndroidRecoveryProfile.origin(app),
            Injekt.get<PreferenceStore>().getString("sync.recovery.external-origin-unverified", "").get(),
        )
    }

    private fun attachedProductionApp(): App = App().also {
        ReflectionHelpers.callInstanceMethod<Unit>(
            it,
            "attach",
            ReflectionHelpers.ClassParameter.from(android.content.Context::class.java, raw.baseContext),
        )
    }
}
