package mihon.history.fixture

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import mihon.data.sync.journal.SyncLocalJournal
import mihon.domain.sync.transport.SyncRepository
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** Cross-APK ABI red gate before rebuilding the non-debuggable formal target. */
class HistoryFormalInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun formalHistoryFixtureAbiIsCallable() = runBlocking {
        verifyTarget()
        // The 43 formal R8 artifact inlined this real production entry point.
        // This call must remain an actual external invocation, never reflective SQL or a copied journal.
        SyncLocalJournal(Injekt.get<DatabaseHandler>()).connect(
            "history-android-formal-hp02",
            1,
            SyncRepository("history-fixture", "offline", "mihon-sync"),
            "android-receiver",
            1,
        )
    }

    private fun verifyTarget() {
        val args = InstrumentationRegistry.getArguments()
        val device = UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "mihon-history-hp02-api36")
        check(context.packageName == "app.mihon.desktop.fork")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.longVersionCode == checkNotNull(args.getString("expectedVersionCode")).toLong())
        val expectedHash = checkNotNull(args.getString("expectedApkSha256"))
        check(expectedHash.matches(Regex("[0-9a-f]{64}")))
        val actual = File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
            digest.digest().hex()
        }
        check(actual == expectedHash) { "Formal target hash mismatch" }
        check(
            checkNotNull(info.signingInfo).apkContentsSigners.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
            } == listOf("bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3"),
        )
        // Read-only safety guard only. All fixture mutations use the production journal/inbox/projector.
        SQLiteDatabase.openDatabase(context.getDatabasePath("tachiyomi.db").path, null, SQLiteDatabase.OPEN_READONLY)
            .use { database ->
                database.rawQuery("SELECT space_id FROM sync_spaces WHERE active = 1", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        check(cursor.getString(0) == "history-android-formal-hp02") { "Existing sync space rejected" }
                    }
                }
            }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
