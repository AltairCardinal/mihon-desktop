package eu.kanade.tachiyomi.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.sync.AndroidSyncScheduler
import eu.kanade.tachiyomi.data.sync.AndroidSyncSecureStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.data.sync.crypto.SyncAeadEngineFactory
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.runtime.SyncRuntime
import mihon.domain.sync.SyncBatch
import mihon.domain.sync.SyncCategory
import mihon.domain.sync.SyncEffect
import mihon.domain.sync.SyncEffectKind
import mihon.domain.sync.SyncEventEnvelope
import mihon.domain.sync.SyncField
import mihon.domain.sync.SyncMutationContext
import mihon.domain.sync.SyncObjectDescriptor
import mihon.domain.sync.SyncObjectKey
import mihon.domain.sync.SyncObjectType
import mihon.domain.sync.SyncOrigin
import mihon.domain.sync.crypto.SyncAeadCiphertext
import mihon.domain.sync.crypto.SyncBatchEncryption
import mihon.domain.sync.crypto.SyncSecret
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncRepository
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.LibraryMembershipResult
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Run with scripts/sync-android-acceptance.init.gradle, first with syncAcceptancePhase=prepare,
 * then force-stop this isolated application and run with syncAcceptancePhase=verify.
 * No GitHub credentials/network are needed. This proves ART/R8, native storage and the real App graph;
 * remote connection/HTTP acceptance and the minimum-API device gate remain separate requirements.
 */
class SyncReleaseAcceptanceInstrumentationTest {
    @Test
    fun optionalPasswordFormatSurvivesReleaseR8AndNativeSecureStore() = runBlocking {
        val password = " 密碼e\u0301🔒 "
        val material = SyncSpaceCrypto.create("art-password-space", 1, password)
        val encoded = SyncSpaceDescriptorCodec.encode(material.descriptor).decodeToString()
        assertFalse(encoded.contains(password))
        val store = Injekt.get<SyncSecureStore>()
        val key = "onboarding-art-${UUID.randomUUID()}"
        assertTrue(store.compareAndSet(key, null, encoded))
        try {
            val restored = SyncSpaceDescriptorCodec.decode(
                requireNotNull(AndroidSyncSecureStore(context).read(key)).encodeToByteArray(),
            ).getOrThrow()
            assertTrue(SyncSpaceCrypto.unlock(restored, "wrong").isFailure)
            assertEquals(material.secret, SyncSpaceCrypto.unlock(restored, password).getOrThrow().secret)
            val plain = SyncSpaceCrypto.create("art-plain-space", 1, "")
            assertEquals("none", plain.descriptor.mode)
            assertNull(plain.secret)
        } finally {
            assertTrue(store.compareAndSet(key, encoded, null))
        }
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val receipt get() = context.getSharedPreferences("sync-art-acceptance", Context.MODE_PRIVATE)
    private val fixtureSecret get() = SyncSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })

    @Before
    fun requireIsolatedReleaseApplication() {
        check(context.packageName == "app.mihon.syncacceptance") { "Refusing to modify another application profile" }
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(context.applicationContext is App)
        assertSame(context.applicationContext, Injekt.get<Application>())
    }

    @Test
    fun productionAeadReadsFrozenVectorAndRejectsWrongKeyAadAndTampering() {
        val engine = SyncAeadEngineFactory.create()
        // Same independent AES-256-GCM vector as SyncCryptoSafetyContractTest on JVM/Android unit targets.
        val frozen = (
            "000000000000000000000000" +
                "cea7403d4d606b6e074ec5d3baf39d18" +
                "d0d1c8a799996bf0265b98b5d48ab919"
            ).decodeHex().toByteArray()
        val secret = SyncSecret.fromBytes(ByteArray(32))
        val encrypted = SyncAeadCiphertext(frozen)
        assertArrayEquals(ByteArray(16), engine.decrypt(secret, encrypted, byteArrayOf()))
        assertThrows(IllegalArgumentException::class.java) {
            engine.decrypt(fixtureSecret, encrypted, byteArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.decrypt(secret, encrypted, byteArrayOf(1))
        }
        frozen[frozen.lastIndex] = (frozen.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            engine.decrypt(secret, SyncAeadCiphertext(frozen), byteArrayOf())
        }

        val objectKey = SyncObjectKey(SyncObjectType.MANGA, SOURCE_ID.toString(), originalUrl = MANGA_URL)
        val event = SyncEventEnvelope(
            1, SPACE, 1, ACTOR, 1, 1, SyncCategory.FAVORITE,
            listOf(SyncEffect("favorite", objectKey, SyncField.FAVORITE, SyncEffectKind.ADD)),
            SyncOrigin.USER, batchId = "art-batch",
        )
        val batch = SyncBatch(
            1,
            SPACE,
            1,
            "art-batch",
            listOf(event),
            objects = listOf(SyncObjectDescriptor(objectKey, "ART 加密互通")),
        )
        val sealed = SyncBatchEncryption.encrypt(
            engine,
            fixtureSecret,
            batch,
            ".mihon-sync/batches/$ACTOR/1/art-batch.json",
            spaceMaterial = null,
        )
        assertEquals(batch, SyncBatchEncryption.decrypt(engine, fixtureSecret, sealed, spaceMaterial = null))
        assertThrows(IllegalArgumentException::class.java) {
            SyncBatchEncryption.decrypt(
                engine,
                fixtureSecret,
                sealed.copy(spaceId = "different-space"),
                spaceMaterial = null,
            )
        }
    }

    @Test
    fun realAppRepositoriesAndKeystoreSurviveAnotherInstrumentationProcess() = runBlocking {
        val runtime = Injekt.get<SyncRuntime>()
        assertSame(runtime, Injekt.get<SyncRuntime>())
        assertNotNull(Injekt.get<AndroidSyncScheduler>())
        val secure = Injekt.get<SyncSecureStore>()
        assertTrue(secure is AndroidSyncSecureStore)
        when (InstrumentationRegistry.getArguments().getString("syncAcceptancePhase")) {
            "prepare" -> prepare(runtime, secure)
            "verify" -> verify(runtime, secure)
            else -> error("Run prepare and verify in separate processes using syncAcceptancePhase")
        }
    }

    private suspend fun prepare(runtime: SyncRuntime, secure: SyncSecureStore) {
        check(!receipt.contains("pid")) { "Verify the previous run or clear only the isolated acceptance profile" }
        check(runtime.connection() == null) { "Acceptance requires an unconfigured isolated profile" }
        assertNull(runtime.credentials.read())
        runtime.preferences.startup.set(false)
        runtime.preferences.setInterval(0)
        runtime.coordinator.cancelAndJoin()

        val material = SyncSpaceCrypto.create(SPACE, 1, FIXTURE_PASSWORD)
        val secret = requireNotNull(material.secret)
        // Frozen v2 secure-storage wire fixture, decoded by the real runtime in both processes.
        // These synthetic identity numbers are local metadata, never GitHub credentials.
        val encoded = buildJsonObject {
            put("version", 2)
            put("accountId", 900000001L)
            put("accountLogin", "fixture-owner")
            put("repositoryId", 900000002L)
            put("owner", "fixture-owner")
            put("repository", "private-sync")
            put("branch", "mihon-sync")
            put(
                "material",
                buildJsonObject {
                    put("descriptor", SyncSpaceDescriptorCodec.encode(material.descriptor).decodeToString())
                    put("keyHex", secret.bytes.toByteString().hex())
                },
            )
            put("actorId", ACTOR)
            put("epoch", 1)
        }.toString()
        assertFalse(encoded.contains(FIXTURE_PASSWORD))
        assertTrue(secure.compareAndSet(SECRET_PURPOSE, null, encoded))
        assertEquals(encoded, AndroidSyncSecureStore(context).read(SECRET_PURPOSE))
        val ciphertext = SyncAeadEngineFactory.create().encrypt(secret, "跨进程恢复".encodeToByteArray(), AAD)

        val mangas = Injekt.get<MangaRepository>()
        val manga = mangas.insertNetworkManga(
            listOf(Manga.create().copy(source = SOURCE_ID, url = MANGA_URL, title = "ART 同步验收")),
        ).single()
        val chapter = Injekt.get<ChapterRepository>().addAll(
            listOf(Chapter.create().copy(mangaId = manga.id, url = CHAPTER_URL, name = "Chapter 1")),
        ).single()
        // Exercise the runtime's actual bootstrap/database wiring, without manufacturing GitHub credentials.
        val initialImport = runtime.baseline.connectAndImport(SPACE, 1, REPOSITORY, ACTOR, 1)
        assertEquals(0L, runtime.baseline.process(initialImport).remaining)
        assertTrue(Injekt.get<UpdateLibraryMembership>().await(manga, true) is LibraryMembershipResult.Success)
        Injekt.get<ChapterRepository>().update(
            ChapterUpdate(chapter.id, read = true, syncContext = SyncMutationContext.User),
        )
        assertJournal(runtime, manga.id, chapter.id)

        val result = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
        assertEquals(SyncRunStatus.FAILED, result.status)
        assertEquals(SyncRunProblem.AUTHORIZATION, result.problem)
        assertEquals(result, runtime.records().last().result)
        // Instrumentation finishes without an Activity lifecycle stop to drain queued apply() writes.
        // Wait for the production preference file before terminating this synthetic host process.
        assertTrue(
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                .edit().commit(),
        )
        assertTrue(
            receipt.edit()
                .putInt("pid", Process.myPid())
                .putLong("process-start", Process.getStartElapsedRealtime())
                .putLong("manga", manga.id)
                .putLong("chapter", chapter.id)
                .putString("ciphertext", ciphertext.bytes.toByteString().hex())
                .commit(),
        )
    }

    private suspend fun verify(runtime: SyncRuntime, secure: SyncSecureStore) {
        check(receipt.contains("pid")) { "The prepare instrumentation run must complete first" }
        assertFalse(
            "Verification must run in another ART process",
            receipt.getInt("pid", -1) == Process.myPid() &&
                receipt.getLong("process-start", -1) == Process.getStartElapsedRealtime(),
        )
        val encoded = requireNotNull(secure.read(SECRET_PURPOSE))
        val material = Json.parseToJsonElement(encoded).jsonObject.getValue("material").jsonObject
        val descriptor = SyncSpaceDescriptorCodec.decode(
            material.getValue("descriptor").jsonPrimitive.content.encodeToByteArray(),
        ).getOrThrow()
        val restored = requireNotNull(SyncSpaceCrypto.unlock(descriptor, FIXTURE_PASSWORD).getOrThrow().secret)
        assertArrayEquals(material.getValue("keyHex").jsonPrimitive.content.decodeHex().toByteArray(), restored.bytes)
        assertFalse(encoded.contains(FIXTURE_PASSWORD))
        val encrypted = SyncAeadCiphertext(
            requireNotNull(receipt.getString("ciphertext", null)).decodeHex().toByteArray(),
        )
        assertArrayEquals(
            "跨进程恢复".encodeToByteArray(),
            SyncAeadEngineFactory.create().decrypt(restored, encrypted, AAD),
        )
        assertJournal(runtime, receipt.getLong("manga", -1), receipt.getLong("chapter", -1))
        assertFalse(runtime.preferences.startup.get())
        assertEquals(0, runtime.preferences.intervalMinutes())
        assertEquals(SyncRunProblem.AUTHORIZATION, runtime.records().last().result.problem)
        // Stale CAS cannot overwrite recovered material, including after process recreation.
        assertFalse(secure.compareAndSet(SECRET_PURPOSE, "stale", "replacement"))
        assertEquals(encoded, secure.read(SECRET_PURPOSE))
        assertTrue(secure.compareAndSet(SECRET_PURPOSE, encoded, null))
        assertNull(secure.read(SECRET_PURPOSE))
        runtime.disconnect()
        assertFalse(requireNotNull(runtime.connection()).enabled)
        assertEquals(2, SyncLocalJournal(Injekt.get()).pendingEvents(SPACE, 1).size)
        assertTrue(receipt.edit().putBoolean("verified", true).commit())
    }

    private suspend fun assertJournal(runtime: SyncRuntime, mangaId: Long, chapterId: Long) {
        val connection = requireNotNull(runtime.connection())
        assertEquals(SPACE, connection.spaceId)
        assertEquals(REPOSITORY, connection.repository)
        assertTrue(connection.enabled)
        assertFalse(connection.unsupportedFormat)
        assertEquals("password", connection.protectionMode)
        assertEquals("fixture-owner", connection.accountLogin)
        assertTrue(Injekt.get<MangaRepository>().getMangaById(mangaId).favorite)
        assertTrue(requireNotNull(Injekt.get<ChapterRepository>().getChapterById(chapterId)).read)
        val handler = Injekt.get<DatabaseHandler>()
        val events = SyncLocalJournal(handler).pendingEvents(SPACE, 1)
        assertEquals(listOf(1L, 2L), events.map { it.seq })
        assertEquals(listOf(SyncCategory.FAVORITE, SyncCategory.READING), events.map { it.category })
        assertTrue(events.all { it.origin == SyncOrigin.USER && it.actorId == ACTOR })
        assertEquals(MANGA_URL, events.first().effects.single().objectKey.originalUrl)
        assertEquals(SOURCE_ID.toString(), events.first().effects.single().objectKey.sourceId)
        assertEquals(SyncEffectKind.MARK_READ, events.last().effects.single().kind)
        assertEquals(CHAPTER_URL, events.last().effects.single().objectKey.originalUrl)
        handler.await {
            assertEquals(3L, sync_journalQueries.getActiveActor().executeAsOne().next_seq)
        }
    }

    @Test
    fun missingAndroidKeystoreWrappingKeyFailsClosedWithoutReplacingCiphertext() = runBlocking {
        val root = File(context.cacheDir, "sync-art-keyloss-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = keystore.aliases().toList().toSet()
        var ownedAlias: String? = null
        try {
            val secure = AndroidSyncSecureStore(isolated)
            assertTrue(secure.compareAndSet("fixture", null, "synthetic-secret"))
            ownedAlias = (keystore.aliases().toList().toSet() - before).single()
            val record = File(root, "sync-secrets").listFiles()!!.single { it.extension == "record" }
            val original = record.readBytes()
            assertEquals(-1, original.toByteString().indexOf("synthetic-secret".encodeToByteArray()))
            keystore.deleteEntry(ownedAlias)
            assertTrue(
                runCatching { AndroidSyncSecureStore(isolated).read("fixture") }.exceptionOrNull()
                    is SyncSecureStoreException,
            )
            assertTrue(
                runCatching { secure.compareAndSet("fixture", null, "replacement") }.exceptionOrNull()
                    is SyncSecureStoreException,
            )
            assertArrayEquals(original, record.readBytes())
            assertFalse(keystore.containsAlias(ownedAlias))
        } finally {
            ownedAlias?.let { keystore.deleteEntry(it) }
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private companion object {
        const val SPACE = "art-acceptance-space"
        const val ACTOR = "art-acceptance-actor"

        // Frozen purpose for generation 1 / art-acceptance-space; changing the storage mapping breaks this fixture.
        const val SECRET_PURPOSE = "space-a7376dcef8037f48464910f19a6c82c22c9254b519b40ca1c18bea28d1ad7444"
        const val FIXTURE_PASSWORD = "art-only-密碼-test"
        const val SOURCE_ID = 9223372036854775806L
        const val MANGA_URL = "/art-acceptance/manga"
        const val CHAPTER_URL = "/art-acceptance/chapter"
        val REPOSITORY = SyncRepository("fixture-owner", "private-sync", "mihon-sync")
        val AAD = "sync-art-persistence-v2".encodeToByteArray()
    }
}
